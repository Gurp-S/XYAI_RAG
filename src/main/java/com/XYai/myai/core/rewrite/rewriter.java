package com.XYai.myai.core.rewrite;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
@Slf4j
@Service
public class rewriter implements QueryReweiterService{

    @Resource
    private ChatModel chatModel;

    /**
     * 口语标准化（问题重写）
     * @param userQuestion 用户原始提问
     * @return 适合检索的标准查询语句
     */
    public String normalize(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return "";
        }
        // 把指令和用户输入合并到一个 Prompt 字符串中
        String PromptText = """
        你现在是一个"术语归一化专家"，把口语化词汇转换成标准词汇
        任务：口语标准化 → 只输出改写后的标准问题，禁止输出任何其他内容。
        转换规则：
        1. 口语转标准：比如咋整→怎么办，咋用→怎么使用，啥→什么
        1. 简洁、准确、无冗余。提取核心实体和动作，使其适合数据库检索。
        2. 必须完全删除所有寒暄、重复性废话和语气词。
        3. 绝对不要与用户对话，不要回答问题，不要解释。
        请严格按规则，重写以下被 <<< >>> 包裹的句子。只输出重写后的结果，不要任何多余文字！
        
        <<< %s >>>
        """.formatted(userQuestion.trim());
        Prompt prompt = new Prompt(List.of(
                new UserMessage(PromptText)
        ));
        try {
            return chatModel.call(prompt).getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("口语标准化失败", e);
            return userQuestion.trim(); // 失败则返回原问题
        }
    }
}