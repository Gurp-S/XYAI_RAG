package com.XYai.myai.rag.rewrite;

import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于 LLM 的查询重写与拆分服务。
 * 接收原始查询，构造 Prompt 调用 ChatModel，期望模型返回符合 RewriteResult 的 JSON，
 * 并将 JSON 反序列化为 RewriteResult 对象；失败时回退到原始输入。
 */
@Slf4j
@Service
public class Rewriter implements QueryRewriterService {

    @Resource
    private ChatModel chatModel;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 调用大模型对查询进行重写与拆分。
     *
     * @param userMessage 初始的 RewriteResult（可仅包含原始 query）
     * @return 重写并拆分后的 RewriteResult，失败时返回输入的 userMessage
     */
    public RewriteResult callLLMRewriteAndSplit(RewriteResult userMessage, LoadSession load) {
        // 1. 空值安全判断（修复：原代码直接 return null 导致上游报错）
        if (userMessage == null || userMessage.getRewrittenQuery() == null) {
            log.warn("输入的查询内容为空，直接返回原始对象");
            return userMessage;
        }

        String userQuestion = userMessage.getRewrittenQuery();
        String formatJson;
        try {
            // 2. 修复：正确生成 JSON 格式示例给大模型
            formatJson = objectMapper.writeValueAsString(RewriteResult.builder().build());
        } catch (JsonProcessingException e) {
            log.error("生成JSON格式失败", e);
            return userMessage;
        }

        // 3. 构建提示词
        Prompt prompt = getPrompt(userQuestion, formatJson, load);

        try {
            // 4. 调用模型
            String rewrittenMessage = chatModel.call(prompt).getResult().getOutput().getText();
            log.debug("LLM原始返回内容：{}", rewrittenMessage);

            // 5. 空返回判断
            if (rewrittenMessage == null || rewrittenMessage.isBlank()) {
                log.warn("LLM返回空内容，使用原始查询");
                return userMessage;
            }

            // 6. JSON解析
            RewriteResult result = objectMapper.readValue(rewrittenMessage, RewriteResult.class);
            log.info("重写解析成功：{}", result);
            return result;

        } catch (Exception e) {
            log.error("LLM调用/解析失败，用户输入：{}", userQuestion, e);
            return userMessage;
        }
    }

    /**
     * 构建提示词（修复：提示词更清晰、模型更容易返回正确JSON）
     */
    private Prompt getPrompt(String userQuestion, String formatJson, LoadSession load) {
        String context;
        try {
            context = load == null ? "无上下文" : objectMapper.writeValueAsString(load);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("上下文序列化失败", e);
        }

        // 核心优化：提示词更明确，强制返回JSON
        String systemText = """
                你是专业的查询重写与子问题拆分助手。
                严格遵守以下规则：
                1. 将用户口语化查询标准化为正式查询句。
                2. 如果包含多个问题，必须拆分为subQuery数组。
                3. 只返回JSON，不要解释、不要多余文字、不要markdown。
                4. 必须严格按照以下JSON结构返回：
                %s
                """.formatted(formatJson);

        String userText = """
                上下文：%s
                用户问题：%s
                """.formatted(context, userQuestion);

        return new Prompt(List.of(
                new SystemMessage(systemText),
                new UserMessage(userText)
        ));
    }
}