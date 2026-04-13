package com.XYai.myai.User;

import com.XYai.myai.RAG.Aop.Annotation.RagTraceRoot;
import com.XYai.myai.Config.Result;
import com.XYai.myai.RAG.Memory.POJO.ChatConversation;
import com.XYai.myai.RAG.Memory.POJO.ChatSessionRecord;
import com.XYai.myai.User.POJO.Group;
import com.XYai.myai.User.POJO.User;
import com.XYai.myai.User.Service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

import java.util.List;

/**
 * 用户管理控制器。
 * 处理用户登录、注册、会话获取及历史聊天记录清理等用户相关的 HTTP 接口。
 */
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
     * @param id       用户 ID
     * @param password 密码
     * @return Result<String> 登录结果，通常 msg 为 success 或错误信息
     */
    @PostMapping("/login")
    public Result<String> login(Long id, String password) {
        // TODO登录拦截器获取用户id
        USERID = id;
        return userService.login(id, password);
    }

    /**
     * 查询用户对话（返回会话列表 metadata）
     *
     * @param userId 用户 ID
     * @return Result<List<ChatSessionRecord>> 包含用户会话元信息的列表
     */
    @GetMapping("/history")
    public Result<List<ChatSessionRecord>> history(Long userId) {
        return userService.history(userId);
    }

    /**
     * 查询对话内容
     *
     * @param conversationId 对话 ID
     * @return Result<List<ChatConversation>> 返回会话中的消息列表
     */
    @RagTraceRoot(name = "历史对话查询", conversationIdArg = "conversationId", taskIdArg = "taskId")
    @GetMapping("/history/conversation")
    public Result<List<ChatConversation>> conversationHistory(String conversationId) {
        LocalDateTime cursor = LocalDateTime.now();
        return userService.conversationHistory(conversationId, cursor);
    }

    /**
     * 用户登出
     *
     * @param userId 用户 ID
     * @return Result<String> 登出结果
     */
    @PostMapping("/logout")
    public Result<String> logout(Long userId) {
        return userService.logout(userId);
    }

    /**
     * 查看组信息
     * @return 用户所属组
     */
    @GetMapping("/group")
    public Result<Group> getGroup(@RequestParam("userId") Long userId){
        Group userGroup = userService.getGroup(userId);
        return Result.success(userGroup);
    }

    /**
     * 查看好友信息
     * @return 用户的好友列表
     */
    @GetMapping("/friend")
    public Result<List<User>> getFriend(@RequestParam("userId") Long userId){
        List<User> userFriend = userService.getFriend(userId);
        return Result.success(userFriend);
    }

    /**
     * 添加好友
     */
    @PostMapping("/friend/add")
    public Result<String> addFriend(@RequestParam("userId") Long userId,
                                    @RequestParam("friendId") Long friendId) {
        return userService.addFriend(userId, friendId);
    }

    /**
     * 删除好友
     */
    @DeleteMapping("/friend/delete")
    public Result<String> deleteFriend(@RequestParam("userId") Long userId,
                                       @RequestParam("friendId") Long friendId) {
        return userService.deleteFriend(userId, friendId);
    }

    @PostMapping("/update")
    public Result<String> updateUser(User user) {

        return userService.update(user);
    }
}
