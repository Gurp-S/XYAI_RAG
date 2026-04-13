package com.XYai.myai.User.Service;

import com.XYai.myai.RAG.Aop.Annotation.RagTraceNode;
import com.XYai.myai.Config.Result;
import com.XYai.myai.RAG.Memory.POJO.ChatConversation;
import com.XYai.myai.RAG.Memory.POJO.ChatSessionRecord;
import com.XYai.myai.User.POJO.Group;
import com.XYai.myai.User.POJO.User;
import com.XYai.myai.mapper.GroupMapper;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;
import com.XYai.myai.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;

/**
 * 用户服务实现类。
 * 处理用户注册、会话管理以及聊天记录存储的相关业务逻辑。
    /** 获取小组 */
@Slf4j
@Service
public class UserServiceIml implements UserService{
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


    /**
     * 查询对话内容（会话内的消息）
      /** 获取好友 */
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
     * @param userId 用户id
     * @return 成功返回
     */
    public Result<String> logout(Long userId) {
        if (userId == null || userId < 0) {
            return Result.error(400, "用户ID非法");
        }
        // 根据用户id查出对话id
        List<ChatSessionRecord> conversationIds = getConversationId(userId);
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Result.success("登出成功");
        }
        // 删除 redis 中的对话数据 + TODO 保存摘要数据
        for (ChatSessionRecord conversation : conversationIds) {
            String conversationId = conversation.getConversationId();
            if (conversationId == null || conversationId.isBlank()) {
                continue;
            }
            // 统一 key 模式，与其它模块保持一致（示例）
            String conversationKey = "chatMessage:" + conversationId;
            String summaryKey = "summary:" + conversationId;
            try {
                stringRedisTemplate.delete(conversationKey);
                stringRedisTemplate.delete(summaryKey);
            } catch (Exception e) {
                log.warn("删除 redis key 失败: {}", e.getMessage());
            }
        }
        return Result.success("登出成功");
    }

    /**
     * 登录
     */
    public Result<String> login(Long id, String password) {
        if (id == null || password == null) {
            return Result.error(400, "ID或密码不能为空");
        }
        User user = userMapper.selectById(id);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }
        if (!user.getPassword().equals(password)) {
            return Result.error(401, "密码错误");
        }
        return Result.success("登录成功");
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

    public Result<String> register(User user, String Verification) {
        User registerUser = new User();
        registerUser.setId(user.getId());
        registerUser.setPassword(user.getPassword());
        return Result.success("注册成功");
    }

    /**
     * 获取小组
     * @param userId
     * @return
     */
    public Group getGroup(Long userId){
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
     * 获取好友
     * @param userId
     * @return
     */
    public List<User> getFriend(Long userId) {
        if (userId == null) {
            return List.of();
        }

        List<Long> friendIds = userMapper.selectFriendIds(userId);
        if (friendIds == null || friendIds.isEmpty()) {
            return List.of();
        }

        List<User> friends = userMapper.selectBatchIds(friendIds);
        return friends == null ? List.of() : friends;
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
     *
     * @param userId
     * @param friendId
     * @return
     */
    public Result<String> deleteFriend(Long userId, Long friendId) {
        if (userId == null || friendId == null) {
            return Result.error(400, "用户ID不能为空");
        }
        if (Objects.equals(userId, friendId)) {
            return Result.error(400, "不能删除自己");
        }

        userMapper.deleteFriendRelation(userId, friendId);
        return Result.success("删除好友成功");
    }

    /**
     *
     * @param user
     * @return
     */
    public Result<String> update(User user){
        userMapper.update();
        return Result.success();
    }
}