package com.XYai.myai.core.memory;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话摘要压缩器。
 *
 * <p>负责将较早会话压缩成增量摘要，供记忆存储层在压缩历史时调用。</p>
 */
@Service
public class MemoryCompressor {

    @Resource
    private ChatModel chatModel;

    /**
     * 生成增量会话摘要。
     *
     * <p>通常在 `RedisMemoryStore.compactConversation(...)` 中触发，
     * 当历史轮次超过阈值时，把较早的对话压缩为一段摘要，减少后续 prompt token。</p>
     *
     * @param existingSummary 已有摘要（可能为空）；用于做增量摘要，避免丢失历史关键信息
     * @param oldRoundsText 需要压缩的旧对话文本列表（按时间顺序）
     * @return 新的摘要文本；若压缩失败，建议返回 existingSummary 或一个降级摘要
     */
    public String summarize(String existingSummary, List<String> oldRoundsText){
        //拼接前后信息
        String safeExisting = existingSummary == null ? "" : existingSummary.trim();
        List<String> safeRounds = oldRoundsText == null
                ? List.of()
                : oldRoundsText.stream().filter(line -> line != null && !line.isBlank()).toList();
        if (safeRounds.isEmpty()) {
            return safeExisting;
        }
        //使用系统提示词
        String roundsText = String.join("\n", safeRounds);
        String prompt = """
                已有摘要：
                %s
                
                新增旧对话片段：
                %s
                """.formatted(safeExisting.isBlank() ? "(无)" : safeExisting, roundsText);

        if (chatModel == null) {
            return fallbackSummary(safeExisting, safeRounds);
        }
        return getSummary(prompt, safeExisting, safeRounds);
    }

    /**
     * 补充新摘要
     *
     * @param prompt 上下文
     * @param safeExisting 安全的旧摘要
     * @param safeRounds 安全的旧会话
     * @return 返回摘要
     */
    private String getSummary(String prompt, String safeExisting, List<String> safeRounds) {
        //补充新摘要
        try {
            List<Message> messages = List.of(
                    new SystemMessage("""
                            你是会话记忆压缩器。请把输入对话压缩成精简摘要。
                            输出要求：
                            1) 保留事实、约束、用户偏好、未完成任务
                            2) 删除寒暄和重复内容
                            3) 只输出摘要正文，不要额外解释
                            4) 不可丢失用户之前的内容
                            5) 不可超过200字"""
                    ),
                    new UserMessage(prompt)
            );
            ChatResponse response = chatModel.call(new Prompt(messages));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return fallbackSummary(safeExisting, safeRounds);
            }
            String summary = response.getResult().getOutput().getText();
            if (summary == null) {
                return fallbackSummary(safeExisting, safeRounds);
            }
            summary = summary.trim();
            if (summary.isBlank()) {
                return fallbackSummary(safeExisting, safeRounds);
            }
            //TODO记忆过长后存入RAG
            return summary;
        } catch (Exception ex) {
            return fallbackSummary(safeExisting, safeRounds);
        }
    }

    /**
     * 模型调用失败时的降级摘要策略。
     *
     * @param existingSummary 以往会话摘要
     * @param oldRoundsText 旧会话
     * @return 返回降级摘要
     */
    private String fallbackSummary(String existingSummary, List<String> oldRoundsText) {
        String appended = String.join(" ", oldRoundsText);
        if (appended.length() > 600) {
            appended = appended.substring(0, 600) + "...";
        }
        if (existingSummary == null || existingSummary.isBlank()) {
            return appended;
        }
        if (appended.isBlank()) {
            return existingSummary;
        }
        return (existingSummary + "\n补充摘要: " + appended).trim();
    }


}
