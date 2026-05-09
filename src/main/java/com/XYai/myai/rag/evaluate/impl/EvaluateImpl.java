package com.XYai.myai.rag.evaluate.impl;


import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.UserEvaluateMapper;
import com.XYai.myai.rag.evaluate.pojo.UserEvaluatePOJO;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.user.LoginUserInfoManager;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class EvaluateImpl {

    @Resource
    private UserEvaluateMapper userExceptionMapper;

    @Resource
    private ChatConversationMapper chatConversationMapper;

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
                        .set(ChatConversation::getFeedback, 1)
        );
        // upsert：先尝试更新，若不存在则插入
        int affected = userExceptionMapper.updateById(record);
        if (affected == 0) {
            userExceptionMapper.insert(record);
        }
        return Result.success("评价成功");
    }

    public Result<String> systemEvaluate(String conversationId, Long userId, String chatMessageId) {
        return Result.success();


    }

}
