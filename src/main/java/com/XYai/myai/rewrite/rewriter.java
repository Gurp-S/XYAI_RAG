package com.XYai.myai.rewrite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class rewriter implements QueryReweiterService {

    @Resource
    private ChatModel chatModel;
    @Resource
    private ObjectMapper objectMapper;

    public RewriteResult callLLMRewriteAndSplit(RewriteResult userMessage) {
        String userQuestion = userMessage.getRewrittenQuery();
        if (userQuestion == null) {
            return null;
        }
        BeanOutputConverter<RewriteResult> outputConverter = new BeanOutputConverter<>(RewriteResult.class);
        Prompt prompt = getPrompt(userQuestion, outputConverter);
        System.out.println(prompt);
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
            log.error("口语标准化与意图识别失败，用户输入: {}", userQuestion, e);
            return userMessage;
        }
    }


    private static Prompt getPrompt(String userQuestion, BeanOutputConverter<RewriteResult> outputConverter) {
        //返回JSON格式
        String format = outputConverter.getFormat();
        String systemText = """
                你是查询重写与子问题拆分器，不是问答助手。
                你的唯一任务是输出结构化 JSON，严格遵守给定 schema。
                禁止回答用户问题、禁止补充解释、禁止输出 markdown。
                """;
        // 3. 将 format 嵌入到提示词中，告知模型应该返回什么结构
        String promptText = """
                请对当前输入进行重写与拆分。
                【重写与拆分规则】
                1. 如果是知识检索，将其重写为标准书面检索句，去除口语和废话。
                2. 如果用户的问题包含多个问题，必须拆分为多个子问题数组。
                3. 如果是闲聊，保持原样。
                【输出要求】
                %s

                当前用户输入：<<< %s >>>
                """.formatted(format, userQuestion);
        return new Prompt(List.of(
                new SystemMessage(systemText),
                new UserMessage(promptText)
        ));
    }
}
