package com.XYai.myai.user.service.Impl;

import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.memory.POJO.ChatConversation;
import com.XYai.myai.rag.memory.POJO.ChatSessionRecord;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.security.JwtUtil;
import com.XYai.myai.security.POJO.JwtProperties;
import com.XYai.myai.security.service.JwtService;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.Group;
import com.XYai.myai.user.POJO.RefreshToken;
import com.XYai.myai.user.POJO.User;
import com.XYai.myai.user.POJO.UserDTO;
import com.XYai.myai.user.service.CustomUserDetailsService;
import com.XYai.myai.user.service.RefreshTokenService;
import com.XYai.myai.user.service.UserService;
import com.XYai.myai.mapper.GroupMapper;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;
import com.XYai.myai.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 用户服务实现类。
 * 处理用户注册、会话管理以及聊天记录存储的相关业务逻辑。
 * /** 获取小组
 */
@Slf4j
@Service
public class UserServiceImpl implements UserService {
    @Resource
    private UserMapper userMapper;
    @Resource
    private GroupMapper groupMapper;
    @Resource
    private ChatSessionRecordMapper chatSessionRecordMapper;
    @Resource
    private ChatConversationMapper chatConversationMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Resource
    private JwtService jwtService;
    @Resource
    private CustomUserDetailsService customUserDetailsService;
    @Resource
    private RefreshTokenService refreshTokenService;
    @Resource
    private JwtProperties jwtProperties;

    /**
     * 查询对话内容（会话内的消息）
     * /** 获取好友
     */
    @RagTraceNode(name = "历史对话查询", type = "conversation")
    public Result<List<ChatConversation>> conversationHistory(String conversationId, LocalDateTime cursor) {
        log.info("查询对话数据");
        if (conversationId == null || conversationId.isBlank()) {
            return Result.error(404, "会话无记录");
        }
        LambdaQueryWrapper<ChatConversation> queryWrapper = new LambdaQueryWrapper<ChatConversation>()
                .select(ChatConversation::getConversationId,
                        ChatConversation::getUserMessage,
                        ChatConversation::getAssistantMessage,
                        ChatConversation::getCreatedAt)
                .eq(ChatConversation::getConversationId, conversationId);
        if (cursor != null) {
            queryWrapper.lt(ChatConversation::getCreatedAt, cursor);
        }
        queryWrapper.orderByAsc(ChatConversation::getCreatedAt);

        // 使用 ChatConversationMapper
        List<ChatConversation> records = chatConversationMapper.selectList(queryWrapper);

        if (records == null || records.isEmpty()) {
            return Result.success(List.of());
        }
        // 可选：异步更新摘要（实现保留）
        setSummary(conversationId);
        return Result.success(records);
    }

    private void setSummary(String conversationId) {
        CompletableFuture.runAsync(() -> {
            // TODO: 将会话摘要逻辑实现进来（或调用 ConversationMemorySummaryService）
            ChatSessionRecord chatSessionRecord = chatSessionRecordMapper.selectById(conversationId);
            if (chatSessionRecord == null) {
                return;
            }
            String summaryKey = "summary:" + conversationId;
            stringRedisTemplate.opsForValue().set(summaryKey, chatSessionRecord.getSummaryText());
        });
    }

    /**
     * 用户登出
     *
     * @return 成功返回
     */
    public Result<String> logout(HttpServletRequest request, HttpServletResponse response) {
        User user = LoginUserInfoManager.get();
        JwtUtil.clearRefreshTokenCookie(response, jwtProperties);
        if (user == null || user.getId() == null) {
            return Result.success("登出成功");
        }

        Long userId = user.getId(); // 或从 SecurityContext 取
        // 1.撤销此用户所有 refresh tokens
        refreshTokenService.revokeAllForUser(userId);
        // 2.cookie 已在前面统一清除
        // 3.TODO把当前 access 的 jti 写入 Redis 黑名单（需要前端把 jti 传回或从 token 解析）

        // String jti = jwtService.getJti(currentAccessToken);
        // redis.opsForValue().set("jwt:blacklist:"+jti, "1", TTL)
        // 4. 设置用户状态
        user.setStatus(false);
        userMapper.updateById(user);
        return Result.success("登出成功");
    }

    /**
     * 登录
     */
    public Result<Map<String, Object>> login(UserDTO userDTO, String password, HttpServletResponse response) {
        // 1. 校验用户存在并比对密码：当前示例要求前端传递 userDTO.id
        if (userDTO == null || userDTO.getId() == null) {
            return Result.error(400, "用户 ID 不能为空");
        }
        Long userId = userDTO.getId();
        User user = userMapper.selectById(userId);
        if (user == null)
            return Result.error(404, "用户不存在");
        String providedPassword = password;
        if (providedPassword == null || providedPassword.isBlank()) {
            providedPassword = userDTO.getPassword();
        }
        if (providedPassword == null || providedPassword.isBlank()) {
            return Result.error(400, "密码不能为空");
        }

        boolean passwordMatches = false;
        try {
            passwordMatches = passwordEncoder.matches(providedPassword, user.getPassword());
        } catch (IllegalArgumentException ignored) {
            passwordMatches = false;
        }
        if (!passwordMatches) {
            passwordMatches = Objects.equals(providedPassword, user.getPassword());
        }
        if (!passwordMatches) {
            return Result.error(400, "密码错误");
        }

        // 2. 生成 jti 和 access token（先通过 CustomUserDetailsService 构造 UserDetails）
        UserDetails ud = customUserDetailsService.loadUserByUsername(String.valueOf(userId));
        String jti = UUID.randomUUID().toString();
        String accessToken = jwtService.generateAccessToken(ud, jti);
        // 3. 生成 refresh token 原文，并在服务端存储其哈希
        String refreshToken = refreshTokenService.generateSecureRandomToken();
        String hashRefreshToken = refreshTokenService.hashTokenSHA256(refreshToken);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(jwtProperties.getRefreshTokenExpSec());
        // 4. 保存 refresh 哈希到 DB（通过 RefreshTokenService）
        refreshTokenService.saveRefreshToken(user.getId(), hashRefreshToken, now, expiresAt);
        // 5. 把 refresh 原文写入 HttpOnly cookie（由 JwtUtil 方法实现）
        JwtUtil.setRefreshTokenCookie(response, jwtProperties, refreshToken,
                (int) jwtProperties.getRefreshTokenExpSec());
        // 6. 返回 access token 给前端（或者仅返回成功，视前端策略）
        Map<String, Object> body = Map.of("accessToken", accessToken, "expiresIn",
                jwtProperties.getAccessTokenExpSec());
        // 7.设置登录状态
        user.setStatus(true);
        userMapper.updateById(user);
        return Result.success(body);
    }

    /**
     * 查询用户对话（会话元数据）
     *
     * @param userId 用户Id
     * @return 返回会话对象
     */
    public Result<List<ChatSessionRecord>> history(Long userId) {
        if (userId == null || userId < 0)
            return Result.error(500, "id非法");
        List<ChatSessionRecord> conversations = getConversationId(userId);
        log.info("查询历史");
        return Result.success(conversations);
    }

    /**
     * 根据用户id查出对话id
     *
     * @param userId 用户id
     * @return 对话列表
     */
    private List<ChatSessionRecord> getConversationId(Long userId) {
        LambdaQueryWrapper<ChatSessionRecord> query = new LambdaQueryWrapper<>();
        // ChatSessionRecord.userId 类型是 String
        query.eq(ChatSessionRecord::getUserId, String.valueOf(userId));
        return chatSessionRecordMapper.selectList(query);
    }

    /**
     * 注册
     * 
     * @param userDTO
     */
    public void register(UserDTO userDTO) {
        User user = new User();
        BeanUtils.copyProperties(userDTO, user);
        user.setPassword(passwordEncoder.encode(userDTO.getPassword()));
        userMapper.insert(user);

        // 注册后初始化用户“分区”缓存：先创建空的可访问集合列表
        Long userId = user.getId() != null ? user.getId() : userDTO.getId();
        if (userId != null) {
            try {
                // userCollectionsKey 在 Milvus ACL 中按 RSet<String> 使用，这里保持同一 Redis 结构
                RSet<String> set = redissonClient.getSet(RedisKeyConfig.userCollectionsKey(userId));
                set.clear();
            } catch (Exception e) {
                log.warn("初始化用户分区缓存失败, userId={}", userId, e);
            }
        }
    }

    @Override
    public Result<String> resetPassword(UserDTO userDTO, String confirmPassword) {
        if (userDTO == null || userDTO.getId() == null) {
            return Result.error(400, "用户 ID 不能为空");
        }

        String newPassword = userDTO.getPassword();
        if (newPassword == null || newPassword.isBlank()) {
            return Result.error(400, "新密码不能为空");
        }
        if (!Objects.equals(newPassword, confirmPassword)) {
            return Result.error(400, "两次输入的新密码不一致");
        }

        User user = userMapper.selectById(userDTO.getId());
        if (user == null) {
            return Result.error(404, "用户不存在");
        }

        String providedEmail = userDTO.getEmail() == null ? "" : userDTO.getEmail().trim();
        String providedPhone = userDTO.getPhone() == null ? "" : userDTO.getPhone().trim();
        if (providedEmail.isBlank() && providedPhone.isBlank()) {
            return Result.error(400, "请提供绑定邮箱或手机号");
        }

        boolean verified = false;
        if (!providedEmail.isBlank() && user.getEmail() != null && !user.getEmail().isBlank()) {
            verified = Objects.equals(user.getEmail().trim(), providedEmail);
        }
        if (!verified && !providedPhone.isBlank() && user.getPhone() != null && !user.getPhone().isBlank()) {
            verified = Objects.equals(user.getPhone().trim(), providedPhone);
        }
        if (!verified) {
            return Result.error(400, "邮箱或手机号不匹配");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setStatus(false);
        userMapper.updateById(user);
        refreshTokenService.revokeAllForUser(user.getId());
        return Result.success("密码重置成功");
    }

    /**
     * 获取小组
     * 
     * @param userId
     * @return
     */
    public Group getGroup(Long userId) {
        if (userId == null) {
            return Group.builder().groupId(null).group(List.of()).build();
        }

        User user = userMapper.selectById(userId);
        if (user == null || user.getGroupId() == null || user.getGroupId().isBlank()) {
            return Group.builder().groupId(null).group(List.of()).build();
        }

        List<User> members = groupMapper.selectMembersByGroupId(user.getGroupId());
        return Group.builder()
                .groupId(user.getGroupId())
                .group(members == null ? List.of() : members)
                .build();
    }

    /**
     *
     * @param userId
     * @param friendId
     * @return
     */
    public Result<String> addFriend(Long userId, Long friendId) {
        if (userId == null || friendId == null) {
            return Result.error(400, "用户ID不能为空");
        }
        if (Objects.equals(userId, friendId)) {
            return Result.error(400, "不能添加自己为好友");
        }

        User user = userMapper.selectById(userId);
        User friend = userMapper.selectById(friendId);
        if (user == null || friend == null) {
            return Result.error(404, "用户不存在");
        }

        userMapper.insertFriendRelation(userId, friendId);
        return Result.success("添加好友成功");
    }

    /**
     * 刷新token
     * 
     * @param refreshToken
     * @param response
     * @return
     */
    public Result<Map<String, Object>> refreshAccessToken(String refreshToken, HttpServletResponse response) {
        String hash = refreshTokenService.hashTokenSHA256(refreshToken);
        Optional<RefreshToken> opt = refreshTokenService.findByTokenHash(hash);
        if (opt.isEmpty()) {
            return Result.error(401, "refresh token 无效");
        }
        RefreshToken rec = opt.get();
        if (rec.getRevoked() != null && rec.getRevoked()) {
            return Result.error(401, "refresh token 已被撤销");
        }
        if (rec.getExpiresAt().isBefore(LocalDateTime.now())) {
            return Result.error(401, "refresh token 已过期");
        }

        // 通过记录得到 userId，加载 UserDetails
        Long userId = rec.getUserId();
        User user = userMapper.selectById(userId);
        if (user == null)
            return Result.error(404, "用户不存在");

        // 生成新的 access token（新的 jti）
        String jti = UUID.randomUUID().toString();
        // 加载 UserDetails 用于构建 token（不要将 domain User 强转为 UserDetails）
        UserDetails ud = customUserDetailsService.loadUserByUsername(String.valueOf(userId));
        String newAccessToken = jwtService.generateAccessToken(ud, jti);

        // 可选但推荐：旋转 refresh token（生成新的 refresh 并撤销旧的）
        String newRefresh = refreshTokenService.generateSecureRandomToken();
        String newHash = refreshTokenService.hashTokenSHA256(newRefresh);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime newExpires = now.plusSeconds(jwtProperties.getRefreshTokenExpSec());
        // 保存 new refresh，撤销旧 refresh
        refreshTokenService.saveRefreshToken(userId, newHash, now, newExpires);
        refreshTokenService.revokeByHash(hash); // 撤销旧 hash（或直接删除）
        // 用 cookie 写入新 refresh 原文
        JwtUtil.setRefreshTokenCookie(response, jwtProperties, newRefresh, (int) jwtProperties.getRefreshTokenExpSec());
        // 返回新的 access token
        Map<String, Object> body = Map.of("accessToken", newAccessToken, "expiresAt",
                jwtProperties.getAccessTokenExpSec());
        return Result.success(body);
    }
}