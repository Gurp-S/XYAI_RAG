package com.XYai.myai.user;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.rag.aop.Annotation.RagTraceRoot;
import com.XYai.myai.rag.memory.POJO.ChatConversation;
import com.XYai.myai.rag.memory.POJO.ChatSessionRecord;
import com.XYai.myai.security.JwtUtil;
import com.XYai.myai.security.POJO.JwtProperties;
import com.XYai.myai.user.POJO.Group;
import com.XYai.myai.user.POJO.User;
import com.XYai.myai.user.POJO.UserDTO;
import com.XYai.myai.user.POJO.UserVO;
import com.XYai.myai.user.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 用户模块 REST 接口控制器
 * 功能范围：用户登录/注册、信息管理、会话历史、好友关系、登出、Token刷新
 *
 * @author XYai My-AI System
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private JwtProperties jwtProperties;

    // ================================ 登录 / 注册 / 登出
    // ================================

    /**
     * 用户登录接口
     * 业务逻辑：校验用户信息 → 生成 accessToken + refreshToken → 返回令牌 + 设置Cookie
     *
     * @param userDTO  登录用户信息（id/账号等）
     * @param password 密码
     * @param response 用于写入 refreshToken HTTP-only Cookie
     * @return 登录结果：包含 accessToken 等信息
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(UserDTO userDTO, String password, HttpServletResponse response) {
        log.info(String.valueOf(userDTO));
        return userService.login(userDTO, password, response);
    }

    /**
     * 用户注册接口
     * 业务逻辑：参数校验 → 账号查重 → 创建用户 → 初始化信息
     *
     * @param userDTO 注册信息
     * @return 注册结果
     */
    @PostMapping("/registry")
    public Result<String> registry(UserDTO userDTO) {
        userService.register(userDTO);
        return Result.success();
    }

    /**
     * 忘记密码 / 重置密码接口
     * 业务逻辑：校验账号、邮箱或手机号 → 更新密码 → 撤销旧 refreshToken
     *
     * @param userDTO         账号与验证信息
     * @param confirmPassword 确认的新密码
     * @return 重置结果
     */
    @PostMapping("/reset-password")
    public Result<String> resetPassword(UserDTO userDTO, String confirmPassword) {
        return userService.resetPassword(userDTO, confirmPassword);
    }

    /**
     * 用户登出接口
     * 业务逻辑：使当前 refreshToken 失效 → 清除登录状态 → 删除Cookie
     *
     * @return 登出成功
     */
    @PostMapping("/logout")
    public Result<String> logout(HttpServletRequest request, HttpServletResponse response) {
        return userService.logout(request, response);
    }

    // ================================ 用户信息 ================================

    /**
     * 获取当前登录用户信息
     * 从线程上下文获取登录用户 → 转为VO返回
     *
     * @return 用户VO信息
     */
    @GetMapping("/Info")
    public Result<UserVO> getUser() {
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(requireLoginUser(), userVO);
        return Result.success(userVO);
    }

    /**
     * 修改当前登录用户信息
     *
     * @param userDTO 要修改的用户信息
     * @return 更新结果
     */
    @PostMapping("/update")
    public Result<String> updateUser(UserDTO userDTO) {
        log.info("更新信息");
        User user = new User();
        BeanUtils.copyProperties(userDTO, user);
        user.setId(requireLoginUser().getId());
        int rows = userMapper.updateById(user);
        return rows > 0 ? Result.success("更新成功") : Result.error(400, "更新失败");
    }

    // ================================ 会话历史 ================================

    /**
     * 获取当前用户的所有会话列表（元数据）
     *
     * @return 会话记录列表
     */
    @GetMapping("/history")
    public Result<List<ChatSessionRecord>> history() {
        return userService.history(requireLoginUser().getId());
    }


    @GetMapping("/history/delete")
    public Result<String> deleteHistory(String conversationId) {
        return userService.deleteHistory(requireLoginUser().getId(), conversationId);
    }

    @GetMapping("/history/updata")
    public Result<String> updateHistory(ChatSessionRecord chatSessionRecord) {
        return userService.updateHistory(chatSessionRecord);
    }

    /**
     * 获取某一个会话的完整聊天记录
     * 使用 AOP 埋点记录 RAG 调用链路
     *
     * @param conversationId 会话ID
     * @return 消息列表
     */
    @RagTraceRoot(name = "历史对话查询", conversationIdArg = "conversationId", taskIdArg = "taskId")
    @GetMapping("/history/conversation")
    public Result<List<ChatConversation>> conversationHistory(String conversationId) {
        LocalDateTime cursor = LocalDateTime.now();
        return userService.conversationHistory(conversationId, cursor);
    }

    // ================================ 好友管理 ================================

    /**
     * 获取当前用户的好友列表
     *
     * @return 好友User列表
     */
    @GetMapping("/friend")
    public Result<List<User>> getFriend() {
        Long userId = requireLoginUser().getId();
        if (userId == null) {
            return Result.success(List.of());
        }

        List<Long> friendIds = userMapper.selectFriendIds(userId);
        if (friendIds == null || friendIds.isEmpty()) {
            return Result.success(List.of());
        }

        List<User> friends = userMapper.selectBatchIds(friendIds);
        return Result.success(friends == null ? List.of() : friends);
    }

    /**
     * 添加好友
     *
     * @param friendId 要添加的好友ID
     * @return 添加结果
     */
    @PostMapping("/friend/add")
    public Result<String> addFriend(@RequestParam("friendId") Long friendId) {
        return userService.addFriend(requireLoginUser().getId(), friendId);
    }

    /**
     * 删除好友
     *
     * @param friendId 要删除的好友ID
     * @return 删除结果
     */
    @PostMapping("/friend/delete")
    public Result<String> deleteFriend(@RequestParam("friendId") Long friendId) {
        Long userId = requireLoginUser().getId();
        if (userId == null || friendId == null) {
            return Result.error(400, "用户ID不能为空");
        }
        if (Objects.equals(userId, friendId)) {
            return Result.error(400, "不能删除自己");
        }
        userMapper.deleteFriendRelation(userId, friendId);
        return Result.success("删除好友成功");
    }

    // ================================ 组 / 权限 ================================

    /**
     * 获取当前用户所属的用户组信息（权限组）
     *
     * @return 组信息
     */
    @GetMapping("/group")
    public Result<Group> getGroup() {
        Group userGroup = userService.getGroup(requireLoginUser().getId());
        return Result.success(userGroup);
    }

    // ================================ 文件分享 ================================

    /**
     * 文件/知识库分享接口
     * TODO 暂未实现：分享文件到其他用户/组
     *
     * @param collectionName 集合名称
     * @param kbId           知识库ID
     * @param fileId         文件ID
     * @return 分享结果
     */
    @PostMapping("/share")
    public Result<String> shareFile(String collectionName, String kbId, String fileId) {
        // TODO 实现文件/知识库分享功能
        return Result.success("TODO：未实现分享功能");
    }

    // ================================ Token 刷新 ================================

    /**
     * 刷新 accessToken
     * 从Cookie中提取 refreshToken → 校验 → 颁发新 accessToken
     *
     * @return 新的令牌信息
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refreshToken(HttpServletRequest request, HttpServletResponse response) {
        Optional<String> maybeRefresh = JwtUtil.resolveRefreshTokenFromCookie(request, jwtProperties);
        if (maybeRefresh.isEmpty()) {
            return Result.error(401, "缺少 refresh token");
        }
        String refreshToken = maybeRefresh.get();
        return userService.refreshAccessToken(refreshToken, response);
    }


    // ================================ 工具方法 ================================

    /**
     * 获取当前登录用户（强制登录）
     * 若未登录，直接抛出 401 异常
     *
     * @return 当前登录用户实体
     */
    private User requireLoginUser() {
        User loginUser = LoginUserInfoManager.get();
        if (loginUser == null || loginUser.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return loginUser;
    }
}