package com.XYai.myai.rag.evaluate.strategy;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.chat.ModelInvocationService;
import com.XYai.myai.user.LoginUserInfoManager;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 第三层：LLM 评估
 * 使用结构化模型对回答进行多维度评分
 * 完全异步，不影响主流程
 */
@Slf4j
@Component
public class LLMEvaluator {

    @Resource(name = "structuredOutputModel")
    private ChatModel chatModel;
    @Resource
    private ModelInvocationService modelInvocationService;

    private static final String SYSTEM_PROMPT = """
            你是一个RAG系统评测助手。请评估AI助手的回答质量，仅输出JSON。
            评分标准（每项0~1，保留两位小数）：
            - faithfulness: 回答是否忠实于检索内容，不捏造事实
            - relevance: 回答是否与用户问题相关
            - completeness: 回答是否完整覆盖问题需求
            输出格式：{"faithfulness":0.9,"relevance":0.85,"completeness":0.7}
            """;

    public double evaluate(String question, String answer, List<String> retrievedChunks, Long conversationId, Long chatMessageId) {
        if (answer == null || answer.isBlank())
            return 0;

        String retrievedText = retrievedChunks != null && !retrievedChunks.isEmpty()
                ? String.join("\n---\n", retrievedChunks.subList(0, Math.min(retrievedChunks.size(), 5)))
                : "无检索内容";

        String userPrompt = String.format("""
                用户问题：%s
                检索内容：%s
                AI回答：%s
                """, question, retrievedText, answer);

        try {
            long startTime = System.nanoTime();
            String raw = chatModel.call(new Prompt(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(userPrompt))).getResult().getOutput().getText();
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;

            RagTraceContext.setPhase("LLM评估");
            modelInvocationService.saveTokenUseAsync(
                    conversationId,
                    IdUtil.getSnowflakeNextId(),
                    (long) (SYSTEM_PROMPT.length()+userPrompt.length()),
                    (long) raw.length(),
                    LoginUserInfoManager.getUserId(),
                    durationMs,
                            chatModel.getDefaultOptions().getModel()+" > evaluate",
                    "evaluate"
            );


            // 提取 JSON
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start != -1 && end > start) {
                raw = raw.substring(start, end + 1);
                Map<String, Object> result = JSON.parseObject(raw,
                        new TypeReference<Map<String, Object>>() {
                        });
                double faith = toDouble(result.get("faithfulness"));
                double relevance = toDouble(result.get("relevance"));
                double completeness = toDouble(result.get("completeness"));
                // F1-like 综合
                double avg = (faith + relevance + completeness) / 3.0;
                return Math.max(0, Math.min(1, avg));
            }
        } catch (Exception e) {
            log.warn("[LLM_EVAL] 调用失败，使用默认分: {}", e.getMessage());
        }
        return 0.5; // 默认中位分
    }

    private double toDouble(Object v) {
        if (v instanceof Number n)
            return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception ignored) {
            }
        }
        return 0.5;
    }
}
