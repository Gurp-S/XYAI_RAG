package com.XYai.myai.rag.evaluate.impl;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.GoldenCaseMapper;
import com.XYai.myai.mapper.SystemEvaluateMapper;
import com.XYai.myai.mapper.UserEvaluateMapper;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.evaluate.pojo.EvaluateResult;
import com.XYai.myai.rag.evaluate.pojo.GoldenCasePOJO;
import com.XYai.myai.rag.evaluate.pojo.SystemEvaluatePOJO;
import com.XYai.myai.rag.evaluate.pojo.UserEvaluatePOJO;
import com.XYai.myai.rag.evaluate.service.SystemEvaluateService;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.user.LoginUserInfoManager;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
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
    @Resource
    private GoldenCaseMapper goldenCaseMapper;

    public Result<String> userEvaluate(Long conversationId, Long messageId, Integer feedback) {
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

        // badcase 回流：点踩消息自动进入待审黄金集（enabled=0，人工完善后启用）
        if (feedback != null && feedback == 0) {
            addBadcaseToGoldenSet(messageId);
        }
        return Result.success("评价成功");
    }

    private void addBadcaseToGoldenSet(Long messageId) {
        try {
            ChatConversation chat = chatConversationMapper.selectById(messageId);
            if (chat == null || chat.getUserMessage() == null || chat.getUserMessage().isBlank()) {
                return;
            }
            // 去重：同一问题已回流过则跳过
            Long exists = goldenCaseMapper.selectCount(
                    new QueryWrapper<GoldenCasePOJO>()
                            .eq("question", chat.getUserMessage())
                            .eq("source", "BADCASE"));
            if (exists != null && exists > 0) {
                return;
            }
            GoldenCasePOJO goldenCase = GoldenCasePOJO.builder()
                    .question(chat.getUserMessage())
                    .groundTruth(chat.getAssistantMessage()) // 原始回答作为参考，人工修正后启用
                    .source("BADCASE")
                    .enabled(0)
                    .remark("点踩自动回流，请核对标准答案与期望文档后启用")
                    .build();
            goldenCaseMapper.insert(goldenCase);
            log.info("badcase 已回流至待审黄金集 messageId={}", messageId);
        } catch (Exception e) {
            log.warn("badcase 回流失败 messageId={}", messageId, e);
        }
    }

    public Result<String> systemEvaluate(Long conversationId, Long userId, Long chatMessageId) {
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
