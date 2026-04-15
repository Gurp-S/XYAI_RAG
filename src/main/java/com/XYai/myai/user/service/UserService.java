package com.XYai.myai.user.service;

import com.XYai.myai.rag.memory.POJO.ChatConversation;
import com.XYai.myai.rag.memory.POJO.ChatSessionRecord;
import com.XYai.myai.config.Result;
import com.XYai.myai.user.POJO.Group;
import com.XYai.myai.user.POJO.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;
import java.util.Map;

/**
 * 用户相关服务接口，包含登录、登出、会话历史查询等功能。
 */
public interface UserService {

    /**
     * 用户登出处理。
     *
     * @return Result<String> 登出结果
     */
    Result<String> logout(HttpServletRequest request, HttpServletResponse response);

    /**
     * 用户登录认证。
     *
     * @param password 密码
     * @return Result<String> 登录结果
     */
    // now accepts HttpServletResponse so the service implementation can set refresh
    // token cookie
    // 接口使用 UserDTO，因为前端会传递用户相关字段（例如 id/phone/name）
    Result<Map<String, Object>> login(UserDTO userDTO, String password, HttpServletResponse response);

    /**
     * 查询用户会话元信息列表（会话概览）。
     *
     * @param userId 用户 ID
     * @return 包含 ChatSessionRecord 的列表
     */
    Result<List<ChatSessionRecord>> history(Long userId);

    /**
     * 查询指定会话的消息内容（分页/游标方式）。
     *
     * @param conversationId 会话 ID
     * @param cursor         查询游标（时间点），用于分页
     * @return 会话内消息列表
     */
    Result<List<ChatConversation>> conversationHistory(String conversationId, java.time.LocalDateTime cursor);

    void register(UserDTO userDTO);

    Result<String> resetPassword(UserDTO userDTO, String confirmPassword);

    Group getGroup(Long userId);

    Result<String> addFriend(Long userId, Long friendId);

    Result<Map<String, Object>> refreshAccessToken(String refreshToken, HttpServletResponse response);
}