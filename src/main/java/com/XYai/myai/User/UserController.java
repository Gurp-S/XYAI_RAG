package com.XYai.myai.User;

import com.XYai.myai.Aop.Annotation.RagTraceRoot;
import com.XYai.myai.Config.Result;
import com.XYai.myai.RAG.Memory.ChatConversation;
import com.XYai.myai.RAG.Memory.ChatSessionRecord;
import com.XYai.myai.Service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {
    public static Long USERID;

    @Resource
    private UserService userService;

    /**
     * 登录
     * 用户登录接口，会将用户 ID 存入本地静态变量（仅示例）。
     *
     * @param id 用户 ID
     * @param password 密码
     * @return Result<String> 登录结果，通常 msg 为 success 或错误信息
     */
    @PostMapping("/login")
    public Result<String> login(Long id, String password){
        //TODO登录拦截器获取用户id
        USERID = id;
        return userService.login(id,password);
    }

    /**
     * 查询用户对话（返回会话列表 metadata）
     *
     * @param userId 用户 ID
     * @return Result<List<ChatSessionRecord>> 包含用户会话元信息的列表
     */
    @GetMapping("/history")
    public Result<List<ChatSessionRecord>> history(Long userId){
        return userService.history(userId);
    }

    /**
     * 查询对话内容
     *
     * @param conversationId 对话 ID
     * @return Result<List<ChatConversation>> 返回会话中的消息列表
     */
    @RagTraceRoot(name="历史对话查询", conversationIdArg = "conversationId", taskIdArg = "taskId")
    @GetMapping("/history/conversation")
    public Result<List<ChatConversation>> conversationHistory(String conversationId){
        LocalDateTime cursor = LocalDateTime.now();
        return userService.conversationHistory(conversationId,cursor);
    }

    /**
     * 用户登出
     *
     * @param userId 用户 ID
     * @return Result<String> 登出结果
     */
    @PostMapping("/logout")
    public Result<String> logout(Long userId){
        return userService.logout(userId);
    }
}
