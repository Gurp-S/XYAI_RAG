package com.XYai.myai.Service;

import com.XYai.myai.Memory.ChatConversation;
import com.XYai.myai.Memory.ChatSessionRecord;
import com.XYai.myai.Config.Result;

import java.util.List;

public interface UserService {

    Result<String> logout(Long userId);

    Result<String> login(Long id, String password);

    Result<java.util.List<ChatSessionRecord>> history(Long userId);

    // 修正为返回会话内容（ChatConversation），以匹配 controller/impl 语义
    Result<java.util.List<ChatConversation>> conversationHistory(String conversationId, java.time.LocalDateTime cursor);

}
