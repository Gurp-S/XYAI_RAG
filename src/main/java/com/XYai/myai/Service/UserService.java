package com.XYai.myai.Service;

import com.XYai.myai.core.dto.ChatConversation;
import com.XYai.myai.core.dto.ChatSessionRecord;
import com.XYai.myai.core.dto.Result;

import java.util.List;

public interface UserService {

    Result<String> logout(Long userId);

    Result<String> login(Long id, String password);

    Result<List<ChatConversation>> history(Long userId);

    Result<List<ChatSessionRecord>> conversationHistory(String conversationId, java.time.LocalDateTime cursor);

}
