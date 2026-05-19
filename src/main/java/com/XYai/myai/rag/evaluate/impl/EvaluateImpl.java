package com.XYai.myai.rag.evaluate.impl;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.SystemEvaluateMapper;
import com.XYai.myai.mapper.UserEvaluateMapper;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.evaluate.pojo.EvaluateResult;
import com.XYai.myai.rag.evaluate.pojo.SystemEvaluatePOJO;
import com.XYai.myai.rag.evaluate.pojo.UserEvaluatePOJO;
import com.XYai.myai.rag.evaluate.service.SystemEvaluateService;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.user.LoginUserInfoManager;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class EvaluateImpl {

    @Resource
    private UserEvaluateMapper userExceptionMapper;
    @Resource
    private ChatConversationMapper chatConversationMapper;
    @Resource
    private SystemEvaluateService systemEvaluateService;
    @Resource
    private SystemEvaluateMapper systemEvaluateMapper;

    public Result<String> userEvaluate(String conversationId, String messageId, Integer feedback) {
        UserEvaluatePOJO record = UserEvaluatePOJO.builder()
                .messageId(messageId)
                .conversationId(conversationId)
                .userId(LoginUserInfoManager.getUserId())
                .feedback(feedback)
                .build();
        chatConversationMapper.update(
                new LambdaUpdateWrapper<ChatConversation>()
                        .eq(ChatConversation::getChatMessageId, messageId)
                        .set(ChatConversation::getFeedback, 1));
        int affected = userExceptionMapper.updateById(record);
        if (affected == 0)
            userExceptionMapper.insert(record);
        return Result.success("评价成功");
    }

    public Result<String> systemEvaluate(String conversationId, Long userId, String chatMessageId) {
        ChatConversation chat = chatConversationMapper.selectById(chatMessageId);
        if (chat == null)
            return Result.error(404, "消息不存在");

        ChatMessage msg = ChatMessage.builder()
                .userMessage(chat.getUserMessage())
                .assistantMessage(chat.getAssistantMessage())
                .userId(userId).build();

        EvaluateResult r = systemEvaluateService.evaluate(msg, null, 0, chat.getUserMessage(), conversationId);

        SystemEvaluatePOJO record = SystemEvaluatePOJO.builder()
                .conversationId(conversationId).chatMessageId(chatMessageId).userId(userId)
                .overallScore(r.getOverallF1()).retrievalScore(r.getRetrievalF1())
                .faithfulnessScore(r.getFaithfulnessF1()).answerRelevanceScore(r.getRelevanceF1())
                .completenessScore(r.getCompletenessF1()).ruleScore(r.getRuleScore())
                .rerankScore(r.getRerankScore()).llmScore(r.getLlmScore())
                .retrievedDocCount(0).latencyMs(0L).modelName("manual").build();
        systemEvaluateMapper.insert(record);

        return Result.success("系统评估完成，综合F1: " + String.format("%.2f", r.getOverallF1()));
    }
}
