package com.XYai.myai.rag.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线 Mock 大模型（测试专用）。
 *
 * 通过 rag.mock-llm.enabled=true 启用（默认关闭），启用后 ModelRouterService 的所有
 * 路由调用（普通/快速/流式）都会被本组件拦截，不访问任何真实 LLM API。
 *
 * 用途：在 LLM 额度耗尽/断网环境下验证完整 RAG 链路——检索、Prompt 组装、
 * SSE 流式输出、[n] 引用标注、拒答路径、答案缓存回放等。
 * Mock 答案是确定性的：直接摘录参考文档片段并附引用编号，便于断言验证。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.mock-llm.enabled", havingValue = "true")
public class MockLlmResponder {

    /** 模拟首 token 延迟（ms），让 TTFT 指标有实际意义 */
    private static final long SIMULATED_TTFT_MS = 600;
    /** 模拟流式吐字间隔（ms） */
    private static final long STREAM_INTERVAL_MS = 20;
    /** 每个流式分片的字符数 */
    private static final int STREAM_CHUNK_SIZE = 8;
    /** 摘录每个 chunk 的最大字符数 */
    private static final int SNIPPET_LENGTH = 90;

    private static final Pattern REFERENCE_SECTION = Pattern.compile("参考文档:<<(.*?)>>", Pattern.DOTALL);
    private static final Pattern CHUNK_MARKER = Pattern.compile("\\[(\\d+)]\\s*来源:");

    public String getModelName() {
        return "mock-offline";
    }

    /**
     * 从 finalPrompt 中提取参考文档片段，生成确定性的带引用回答。
     * 无相关参考时返回拒答话术（与 grounded prompt 的拒答要求一致，可测拒答路径）。
     */
    public String answer(String prompt) {
        List<String[]> snippets = extractSnippets(prompt);
        if (snippets.isEmpty()) {
            return "当前知识库中没有找到相关资料。（离线 Mock 模式：本次检索未命中参考文档，此回答用于验证拒答路径）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("根据知识库检索结果：");
        for (String[] s : snippets) {
            sb.append("\n- ").append(s[1]).append(" [").append(s[0]).append("]");
        }
        sb.append("\n以上内容均摘自参考文档（离线 Mock 模式，未调用真实大模型）。");
        return sb.toString();
    }

    /**
     * 提取参考文档区块中的 chunk 摘录，返回 [编号, 摘录文本] 列表（最多 3 条）。
     */
    private List<String[]> extractSnippets(String prompt) {
        List<String[]> result = new ArrayList<>();
        if (prompt == null) {
            return result;
        }
        Matcher section = REFERENCE_SECTION.matcher(prompt);
        if (!section.find()) {
            return result;
        }
        String reference = section.group(1);
        Matcher marker = CHUNK_MARKER.matcher(reference);
        List<int[]> marks = new ArrayList<>();
        while (marker.find() && marks.size() < 3) {
            marks.add(new int[]{Integer.parseInt(marker.group(1)), marker.start(), marker.end()});
        }
        for (int i = 0; i < marks.size(); i++) {
            int[] m = marks.get(i);
            int end = (i + 1 < marks.size()) ? marks.get(i + 1)[1] : reference.length();
            String body = reference.substring(m[2], end).trim();
            // 去掉来源行本身（首行），取正文
            int nl = body.indexOf('\n');
            if (nl >= 0) {
                body = body.substring(nl + 1).trim();
            }
            if (body.length() > SNIPPET_LENGTH) {
                body = body.substring(0, SNIPPET_LENGTH) + "…";
            }
            if (!body.isBlank()) {
                result.add(new String[]{String.valueOf(m[0]), body});
            }
        }
        return result;
    }

    /**
     * 模拟流式输出：先延迟 TTFT，再分片回调，最后 onComplete。
     * 在独立线程执行，不阻塞调用方。
     */
    public void streamAnswer(String prompt, java.util.function.Consumer<String> onChunk,
                             java.util.function.Consumer<Throwable> onError, Runnable onComplete) {
        String full = answer(prompt);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(SIMULATED_TTFT_MS);
                for (int i = 0; i < full.length(); i += STREAM_CHUNK_SIZE) {
                    onChunk.accept(full.substring(i, Math.min(i + STREAM_CHUNK_SIZE, full.length())));
                    Thread.sleep(STREAM_INTERVAL_MS);
                }
                onComplete.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onError.accept(e);
            } catch (Exception e) {
                onError.accept(e);
            }
        }, "mock-llm-stream");
        t.setDaemon(true);
        t.start();
    }
}
