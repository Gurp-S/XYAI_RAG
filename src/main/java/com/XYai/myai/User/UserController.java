package com.XYai.myai.User;

import com.XYai.myai.Annotation.RagTraceRoot;
import com.XYai.myai.Config.Result;
import com.XYai.myai.Memory.ChatConversation;
import com.XYai.myai.Memory.ChatSessionRecord;
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
     * @param id id
     * @param password 密码
     * @return 成功
     */
    @PostMapping("/login")
    public Result<String> login(Long id, String password){
        //TODO登录拦截器获取用户id
        USERID = id;
        return userService.login(id,password);
    }

    /**
     * 查询用户对话（返回会话列表 metadata）
     * @param userId 用户Id
     * @return 返回对话对象
     */
    @GetMapping("/history")
    public Result<List<ChatSessionRecord>> history(Long userId){
        return userService.history(userId);
    }

    /**
     * 查询对话内容
     * @param conversationId 对话Id
     * @return 返回对话内容（会话内的消息）
     */
    @RagTraceRoot(name="历史对话查询", conversationIdArg = "conversationId", taskIdArg = "taskId")
    @GetMapping("/history/conversation")
    public Result<List<ChatConversation>> conversationHistory(String conversationId){
        LocalDateTime cursor = LocalDateTime.now();
        return userService.conversationHistory(conversationId,cursor);
    }

    /**
     * 用户登出
     * @param userId 用户id
     * @return 成功返回
     */
    @PostMapping("/logout")
    public Result<String> logout(Long userId){
        return userService.logout(userId);
    }
}
