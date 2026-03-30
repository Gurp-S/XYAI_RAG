package com.XYai.myai.Service.iml;

import com.XYai.myai.Annotation.RagTraceNode;
import com.XYai.myai.Config.Result;
import com.XYai.myai.Memory.ChatConversation;
import com.XYai.myai.Memory.ChatSessionRecord;
import com.XYai.myai.Service.UserService;
import com.XYai.myai.User.User;
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

@Slf4j
@Service
public class UserServiceIml implements UserService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private ChatSessionRecordMapper chatSessionRecordMapper;
    @Resource
    private ChatConversationMapper chatConversationMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询对话内容（会话内的消息）
     *
     * @param conversationId 对话Id
     * @param cursor         游标分页的时间点
     * @return 返回对话内容（ChatConversation 列表）
     */
    @RagTraceNode(name = "历史对话查询", type = "conversation")
    public Result<List<ChatConversation>> conversationHistory(String conversationId, LocalDateTime cursor) {
        if (conversationId == null || conversationId.isBlank()) return Result.error(404,"会话无记录");
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

        // 使用 ChatConversationMapper 查询消息（之前是错误地用 chatSessionRecordMapper）
        List<ChatConversation> records = chatConversationMapper.selectList(queryWrapper);

        if (records == null || records.isEmpty()) {
            return Result.success(List.of());
        }
        // 可选：异步更新摘要（实现保留）
        setSummary(conversationId, records);
        return Result.success(records);
    }

    private void setSummary(String conversationId, List<ChatConversation> records) {
        CompletableFuture.runAsync(() -> {
            // TODO: 将会话摘要逻辑实现进来（或调用 ConversationMemorySummaryService）
        });
    }

    /**
     * 用户登出
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
            String recentKey = "Chat:Mem:" + conversationId + ":recent";
            String summaryKey = recentKey + ":Summary";
            try {
                stringRedisTemplate.delete(recentKey);
                stringRedisTemplate.delete(summaryKey);
            } catch (Exception e) {
                log.warn("删除 redis key 失败: {}", e.getMessage());
            }
        }
        return Result.success("登出成功");
    }

    /**
     * 登录
     * @param id id
     * @param password 密码
     * @return 成功
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
        if(userId == null || userId < 0) return Result.error(500,"id非法");
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
}
