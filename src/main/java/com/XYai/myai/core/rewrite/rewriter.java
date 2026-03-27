package com.XYai.myai.core.rewrite;

import com.XYai.myai.core.dto.RewriteResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class rewriter implements QueryReweiterService {

    @Resource
    private ChatModel chatModel;

    @Override
    public RewriteResult callLLMRewriteAndSplit(String userQuestion, String context) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return null;
        }

        // 1. 初始化 Spring AI 的 BeanOutputConverter，指定我们需要的返回类型
        BeanOutputConverter<RewriteResult> outputConverter = new BeanOutputConverter<>(RewriteResult.class);
        // 2. 获取 Spring AI 自动生成的 JSON Schema 要求格式
        Prompt prompt = getPrompt(userQuestion, outputConverter);
        try {
            // 4. 发起请求
            String jsonOutput = chatModel.call(prompt).getResult().getOutput().getText();

            // 5. 使用 Converter 将结果字符串反序列化为 RewriteResult 对象
            return outputConverter.convert(jsonOutput);
        } catch (Exception e) {
            log.error("口语标准化与意图识别失败，用户输入: {}", userQuestion, e);
            // 如果遇到异常，返回一个基于原句的默认结果防止流程中断
            return RewriteResult.builder()
                    .intent("Knowledge_Search") // 兜底为常规查询
                    .rewritten_query(userQuestion.trim())
                    .sub_query(Collections.singletonList(userQuestion.trim()))
                    .build();
        }
    }

    private static Prompt getPrompt(String userQuestion, BeanOutputConverter<RewriteResult> outputConverter) {
        String format = outputConverter.getFormat();

        // 3. 将 format 嵌入到提示词中，告知模型应该返回什么结构
        String promptText = """
                你是一个对话预处理专家。请对用户的输入同时进行【意图识别】和【查询检索重写】。
                
                【意图列表】
                1. Knowledge_Search: 需要检索公司/业务/知识
                2. General_Chat: 普通闲聊、打招呼或无效输入
                【重写与拆分规则】
                1. 如果是知识检索，将其重写为标准书面检索句，去除口语和废话。
                2. 如果用户的问题包含多个意图（如“医保怎么用？怎么报销？”），必须将其拆分为多个子问题数组。
                3. 如果用户的问题非常单一（如“医保怎么用”），就将重写后的单独问题放入子问题数组中，绝对不能返回空！
                4. 如果是闲聊，保持原意。
                
                【处理示例】
                输入：<<< 那个，我想问下医保怎么用啊？然后辞职了公积金怎么提取？ >>>
                返回：{"intent": "Knowledge_Search", "rewritten_query": "医保的使用方法及离职后公积金提取指南", "sub_query": ["医保如何使用", "离职后公积金如何提取"]}
                输入：<<< 医保怎么用 >>>
                返回：{"intent": "Knowledge_Search", "rewritten_query": "医保如何使用", "sub_query": ["医保如何使用"]}
                输入：<<< 嗨，你好呀 >>>
                返回：{"intent": "General_Chat", "rewritten_query": "你好", "sub_query": []}
                
                【输出要求】
                %s
                
                当前用户输入：<<< %s >>>
                """.formatted(format, userQuestion);

        Prompt prompt = new Prompt(List.of(new UserMessage(promptText)));
        return prompt;
    }
}
