package com.XYai.myai.RAG.rewrite;

import com.XYai.myai.RAG.Memory.POJO.LoadSession;
import com.XYai.myai.RAG.rewrite.POJO.RewriteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import javax.naming.Context;
import java.util.List;

/**
 * 基于 LLM 的查询重写与拆分服务。
 * 接收原始查询，构造 Prompt 调用 ChatModel，期望模型返回符合 RewriteResult 的 JSON，
 * 并将 JSON 反序列化为 RewriteResult 对象；失败时回退到原始输入。
 */
@Slf4j
@Service
public class rewriter implements QueryReweiterService {

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
        String userQuestion = userMessage.getRewrittenQuery();
        if (userQuestion == null) {
            return null;
        }
        BeanOutputConverter<RewriteResult> outputConverter = new BeanOutputConverter<>(RewriteResult.class);
        Prompt prompt = getPrompt(userQuestion, outputConverter,load);
        try {
            String rewrittenMessage = chatModel.call(prompt).getResult().getOutput().getText();
            // 正常输出用 debug/info，而不是 error
            RewriteResult message = objectMapper.readValue(rewrittenMessage,RewriteResult.class);
            log.debug("LLM raw output for rewrite: {}", rewrittenMessage);
            if (rewrittenMessage == null || rewrittenMessage.isBlank()) {
                log.warn("LLM returned empty output for userQuestion='{}'", userQuestion);
                return userMessage;
            }
            return message;
        } catch (Exception e) {
            // 调用模型或其它环节异常，返回默认结果以保证上游可用性
            log.error("口语标准化失败，用户输入: {}", userQuestion, e);
            return userMessage;
        }
    }


    private Prompt getPrompt(String userQuestion, BeanOutputConverter<RewriteResult> outputConverter, LoadSession load) {
        // 返回 JSON 格式的 Prompt
        String format = outputConverter.getFormat();
        String context;
        try {
            context = objectMapper.writeValueAsString(load);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("上下文转化失败");
        }
        String systemText = """
                你是查询重写与子问题拆分器，不是问答助手。
                你的唯一任务是根据用户上下文,处理用户问题输出结构化 JSON，严格遵守给定 schema。
                规则:
                1. 如果是闲聊，保持原样。
                2. 如果用户的问题包含多个问题，必须拆分为多个子问题数组。
                3. 如果是知识检索，给出适合检索的语句。
                4. 根据语义将人称具体化,例如"我"->用户
                5. 将口语标准化,例如"天气怎么样"->天气查询
                JSON格式:
                %s
                """.formatted(format);
        // 3. 将 format 嵌入到提示词中，告知模型应该返回什么结构
        String promptText = """
                上下文: <<< %s >>>
                当前用户输入：<<< %s >>>
                """.formatted(context==null?"无":context, userQuestion);
        return new Prompt(List.of(
                new SystemMessage(systemText),
                new UserMessage(promptText)
        ));
    }
}
