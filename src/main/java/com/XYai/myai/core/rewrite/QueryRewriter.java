package com.XYai.myai.core.rewrite;

import com.XYai.myai.core.Channel.SearchChannel;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询重写接口。
 *
 * <p>将用户原始提问转换为更适合检索的标准查询。</p>
 */
@RestController
public class QueryRewriter{

    @Resource
    private QueryReweiterService queryReweiterService;
    @Resource
    private ChatModel chatModel;
    /**
     * 重写查询文本。
     *
     * @param userQuestion 原始查询
     * @param context 辅助上下文（如意图、历史摘要）
     * @return 重写后的查询
     */
    public String rewrite(String userQuestion, String context){
        //TODO如果不是问题会导致ai忽略系统提示词
        // 步骤1：检查是否启用了 LLM 重写
        // 如果没启用，就用简单的规则处理
        // 步骤2：使用 LLM 进行智能重写
        String normalizedQuestion = queryReweiterService.normalize(userQuestion);
        System.out.println(normalizedQuestion);
        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, context);
    }

    private String callLLMRewriteAndSplit(String normalizedQuestion, String userQuestion, String context) {
        // 将所有指令组合成一个没有对话感的工作任务
        String promptText = """
        你现在是一个“智能重写专家”，不要回答问题，仅仅执行重写任务。
        任务：结合上下文(如果有)，将用户输入的内容提取并重写为最适合向量检索的核心关键词语句。只输出改写后的结果，禁止输出任何其他内容。
        
        转换规则：
        1. 简洁、准确、无冗余。提取核心实体和动作，使其适合数据库检索。
        2. 必须完全删除所有寒暄（如“你好”、“请问”）、陈述性废话（如“你是我编写的...”）和语气词。
        3. 绝对不要与用户对话，不要回答问题，不要解释。
        
        【上下文信息】
        %s
        
        请严格按规则，重写以下被 <<< >>> 包裹的用户输入，只输出重写后的结果，不要任何多余文字！如果提取不到有效检索词，请直接返回原语义核心。
        
        <<< %s >>>
        """.formatted(
                context == null || context.isBlank() ? "无" : context.trim(),
                userQuestion.trim()
        );
        Prompt prompt = new Prompt(
            new UserMessage(promptText)
        );
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
}

