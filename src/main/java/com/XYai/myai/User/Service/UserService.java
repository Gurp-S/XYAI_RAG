package com.XYai.myai.User.Service;

import com.XYai.myai.RAG.Memory.POJO.ChatConversation;
import com.XYai.myai.RAG.Memory.POJO.ChatSessionRecord;
import com.XYai.myai.Config.Result;
import com.XYai.myai.User.POJO.Group;
import com.XYai.myai.User.POJO.User;

import java.util.List;

/**
 * 用户相关服务接口，包含登录、登出、会话历史查询等功能。
 */
public interface UserService {


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
    Result<List<ChatSessionRecord>> history(Long userId);

    /**
     * 查询指定会话的消息内容（分页/游标方式）。
     *
     * @param conversationId 会话 ID
     * @param cursor 查询游标（时间点），用于分页
     * @return 会话内消息列表
     */
    Result<List<ChatConversation>> conversationHistory(String conversationId, java.time.LocalDateTime cursor);


    Result<String> register(User user,String Verification);


    Group getGroup(Long userId);


    List<User> getFriend(Long userId);


    Result<String> addFriend(Long userId, Long friendId);


    Result<String> deleteFriend(Long userId, Long friendId);

    Result<String> update(User user);
}