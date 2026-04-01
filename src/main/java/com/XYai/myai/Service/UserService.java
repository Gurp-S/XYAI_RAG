package com.XYai.myai.Service;

import com.XYai.myai.RAG.Memory.ChatConversation;
import com.XYai.myai.RAG.Memory.ChatSessionRecord;
import com.XYai.myai.Config.Result;

public interface UserService {

    /**
     * 用户相关服务接口，包含登录、登出、会话历史查询等功能。
     */

    /**
     * 用户登出处理。
     *
     * @param userId 用户 ID
     * @return Result<String> 登出结果
     */
    Result<String> logout(Long userId);

    /**
     * 用户登录认证。
     *
     * @param id 用户 ID
     * @param password 密码
     * @return Result<String> 登录结果
     */
    Result<String> login(Long id, String password);

    /**
     * 查询用户会话元信息列表（会话概览）。
     *
     * @param userId 用户 ID
     * @return 包含 ChatSessionRecord 的列表
     */
    Result<java.util.List<ChatSessionRecord>> history(Long userId);

    /**
     * 查询指定会话的消息内容（分页/游标方式）。
     *
     * @param conversationId 会话 ID
     * @param cursor 查询游标（时间点），用于分页
     * @return 会话内消息列表
     */
    Result<java.util.List<ChatConversation>> conversationHistory(String conversationId, java.time.LocalDateTime cursor);

}
