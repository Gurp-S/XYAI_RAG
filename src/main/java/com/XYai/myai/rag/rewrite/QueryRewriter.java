package com.XYai.myai.rag.rewrite;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.chat.ModelInvocationService;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.XYai.myai.rag.rewrite.pojo.RewriterProperties;
import com.XYai.myai.user.LoginUserInfoManager;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 查询重写接口。
 *
 * <p>将用户原始提问转换为更适合检索的标准查询。</p>
 */
@Slf4j
@Service
public class QueryRewriter {

    private static final String[] PHRASES_TO_REMOVE = {
            "请问", "麻烦", "帮我", "帮我看下", "替我", "我想问", "我想知道", "我想了解一下",
            "能不能", "可不可以", "可以帮我", "能不能帮我", "帮忙", "给我", "我要", "我想",
            "请问一下", "问一下", "查一下", "帮忙查一下", "麻烦问一下", "你好", "谢谢", "请",
            "有没有", "是否有", "是否"
    };
    private static final Pattern TONE_WORDS = Pattern.compile("[吧吗呢啊呀哦哈嗯嘛欸]");
    private static final Pattern REDUNDANT_PUNCT = Pattern.compile("[\\s～~…—]+");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");
    private static final Pattern PURE_NOISE = Pattern.compile("^[啊吧吗呢呀哦哈嗯嘿呵欸]+$");
    private static final Pattern LAUGHTER_OR_REPEAT = Pattern.compile(
            "^([啊嗯呵哈嘿哦嚯]{2,}|(.)\\2{2,})$"
    );
    @Resource
    private ChatModel chatModel;
    @Resource
    private RewriterProperties rewriterProperties;
    @Resource
    private ModelInvocationService modelInvocationService;

    public static String QueryCleaner(String query) {
        if (query == null || query.isBlank()) return null;

        String cleaned = query.trim();

        // 1. 预先检测：如果原始文本就匹配纯笑声/重复模式，直接判无效
        if (LAUGHTER_OR_REPEAT.matcher(cleaned).matches() ||
                PURE_NOISE.matcher(cleaned).matches()) {
            return null;
        }

        // 2. 移除口语短语（按原逻辑）
        for (String phrase : PHRASES_TO_REMOVE) {
            cleaned = cleaned.replace(phrase, "");
        }

        // 3. 移除句末语气词（单字）
        cleaned = TONE_WORDS.matcher(cleaned).replaceAll("");

        // 4. 清理标点，保留一个空格
        cleaned = REDUNDANT_PUNCT.matcher(cleaned).replaceAll(" ");
        cleaned = MULTI_SPACE.matcher(cleaned).replaceAll(" ").trim();

        // 5. 清洗后再次检测：如果变成空字符串或纯噪音，返回 null
        if (cleaned.isBlank() ||
                LAUGHTER_OR_REPEAT.matcher(cleaned).matches() ||
                PURE_NOISE.matcher(cleaned).matches()) {
            return null;
        }

        return cleaned;
    }

    /**
     * 重写查询文本。
     *
     * @param userQuestion 原始查询
     * @return 重写后的查询
     */
    public RewriteResult rewrite(String userQuestion, Long conversationId, Long chatMessageId) {
        // 简单规则重写
        String cleanUserQuery = QueryCleaner(userQuestion);
        // 判断是否开启llm
        RewriteResult userMessage = RewriteResult.builder().rewrittenQuery(cleanUserQuery).build();
        if (!rewriterProperties.getRewriterEnabled() || userQuestion.length() < 15) return userMessage;
        // 使用 LLM 进行智能重写
        return callLLMRewriteAndSplit(userMessage,conversationId,chatMessageId);
    }

    /**
     * 调用大模型对查询进行重写与拆分。
     *
     * @param userMessage 初始的 RewriteResult（可仅包含原始 query）
     * @return 重写并拆分后的 RewriteResult，失败时返回输入的 userMessage
     */
    public RewriteResult callLLMRewriteAndSplit(RewriteResult userMessage, Long conversationId, Long chatMessageId) {
        // 1. 空值安全判断（修复：原代码直接 return null 导致上游报错）
        if (userMessage == null || userMessage.getRewrittenQuery() == null) {
            log.warn("输入的查询内容为空，直接返回原始对象");
            return userMessage;
        }

        String userQuestion = userMessage.getRewrittenQuery();
        String formatJson;
        // 2. 修复：正确生成 JSON 格式示例给大模型
        formatJson = JSON.toJSONString(RewriteResult.builder().build());

        // 3. 构建提示词
        Prompt prompt = getPrompt(userQuestion, formatJson);

        try {
            // 4. 调用模型
            long startTime = System.nanoTime();
            String rewrittenMessage = chatModel.call(prompt).getResult().getOutput().getText();
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.debug("LLM原始返回内容：{}", rewrittenMessage);

            // 5. 空返回判断
            if (rewrittenMessage == null || rewrittenMessage.isBlank()) {
                log.warn("LLM返回空内容，使用原始查询");
                return userMessage;
            }
            // 5.2 token消耗保存
            RagTraceContext.setPhase("查询重写");
            modelInvocationService.saveTokenUseAsync(
                    conversationId,
                    IdUtil.getSnowflakeNextId(),
                    (long) prompt.toString().length(),
                    (long) rewrittenMessage.length(),
                    LoginUserInfoManager.getUserId(),
                    durationMs,
                    chatModel.getDefaultOptions().getModel(),
                    "queryRewrite"
            );
            // 6. JSON解析
            return JSON.parseObject(rewrittenMessage, RewriteResult.class);

        } catch (Exception e) {
            log.error("LLM调用/解析失败，用户输入：{}", userQuestion, e);
            return userMessage;
        }
    }

    /**
     * 构建提示词（修复：提示词更清晰、模型更容易返回正确JSON）
     */
    private Prompt getPrompt(String userQuestion, String formatJson) {
        // 核心优化：提示词更明确，强制返回JSON
        String systemText = """
                你是查询重写与子问题拆分器
                严格遵守规则：
                将用户口语化查询标准化为正式查询句
                如果包含多个问题，必须拆分为subQuery数组
                只返回标准 JSON 格式
                %s
                """.formatted(formatJson);

//        String userText = """
//                上下文：%s
//                用户问题：%s
//                """.formatted(context, userQuestion);
        String userText = """
                用户问题：%s
                """.formatted(userQuestion);
        return new Prompt(List.of(
                new SystemMessage(systemText),
                new UserMessage(userText)
        ));
    }
}

