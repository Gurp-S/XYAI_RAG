package com.XYai.myai.Service.iml;

import com.XYai.myai.Annotation.RagTraceNode;
import com.XYai.myai.Service.UserService;
import com.XYai.myai.core.dto.*;
import com.XYai.myai.core.memory.MemoryCompressor;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatMemorySummaryMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;
import com.XYai.myai.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    @Resource
    private ChatMemorySummaryMapper chatMemorySummaryMapper;
    @Resource
    private MemoryCompressor memoryCompressor;
    /**
     * 查询对话内容
     * @param conversationId 对话Id
     * @param cursor 游标分页的时间点,使用select *避免sql语句慢的问题
     * @return 返回对话内容
     */
    @RagTraceNode(name = "历史对话查询", type = "conversation")
    public Result<List<ChatSessionRecord>> conversationHistory(String conversationId, LocalDateTime cursor) {
        if (conversationId == null || conversationId.isBlank()) return Result.error(404,"会话无记录");
        //WHERE created_at < ? ORDER BY created_at
        // 游标分页：如果有 cursor，则查创建时间早于 cursor 的记录，避免 select *
        LambdaQueryWrapper<ChatSessionRecord> queryWrapper = new LambdaQueryWrapper<ChatSessionRecord>()
                .select(ChatSessionRecord::getConversationId, 
                        ChatSessionRecord::getUserMessage, 
                        ChatSessionRecord::getAssistantMessage, 
                        ChatSessionRecord::getCreatedAt)
                .eq(ChatSessionRecord::getConversationId, conversationId);
        if (cursor != null) {
            queryWrapper.lt(ChatSessionRecord::getCreatedAt, cursor);
        }
        queryWrapper.orderByAsc(ChatSessionRecord::getCreatedAt);
        List<ChatSessionRecord> records = chatSessionRecordMapper.selectList(queryWrapper);
        // 无会话记录时直接返回空列表
        if (records == null || records.isEmpty()) {
            return Result.success(List.of());
        }
        //设置摘要给ai
        setSummary(conversationId, records);
        return Result.success(records);
    }

    private void setSummary(String conversationId, List<ChatSessionRecord> records) {
        CompletableFuture.runAsync(() -> {
            // 查询当前会话已有摘要（按 conversation_id 查询，不是主键 id）
            ChatMemorySummary chatMemorySummary = chatMemorySummaryMapper.selectOne(
                    new LambdaQueryWrapper<ChatMemorySummary>()
                            .eq(ChatMemorySummary::getConversationId, conversationId)
                            .last("LIMIT 1")
            );
            String existingSummary = chatMemorySummary == null ? "" : chatMemorySummary.getSummaryText();
            // memoryCompressor 需要 List<String>，将对话记录转为标准文本格式并按时间正序输入
            List<String> oldRoundsText = new ArrayList<>();
            for (ChatSessionRecord r: records) {
                oldRoundsText.add("User: " + r.getUserMessage() + "\nAssistant: " + r.getAssistantMessage());
            }
            // 进行旧摘要+保留的会话拼接形成新摘要
            String summarize = memoryCompressor.summarize(existingSummary,oldRoundsText);
            // 写入 Redis 摘要缓存（与服务层 key 规则保持一致）
            String summaryKey = "Chat:Mem:{cid}:recent" + conversationId + "Summary";
            stringRedisTemplate.opsForValue().set(summaryKey, summarize);
            // 写回摘要表（upsert）
            if (chatMemorySummary == null) {
                ChatMemorySummary newSummary = new ChatMemorySummary();
                newSummary.setConversationId(conversationId);
                newSummary.setSummaryText(summarize);
                chatMemorySummaryMapper.insert(newSummary);
            } else {
                //TODO消息队列异步更新
                chatMemorySummaryMapper.update(
                        null,
                        new LambdaUpdateWrapper<ChatMemorySummary>()
                                .eq(ChatMemorySummary::getConversationId, conversationId)
                                .set(ChatMemorySummary::getSummaryText, summarize)
                );
            }
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
        //根据用户id查出对话id
        List<ChatConversation> conversationIds = getConversationId(userId);
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Result.success("登出成功");
        }
        //删除redis中的对话数据 + TODO保存摘要数据
        for (ChatConversation conversation : conversationIds) {
            String conversationId = conversation.getConversationId();
            if (conversationId == null || conversationId.isBlank()) {
                continue;
            }
            String recentKey = "Chat:Mem:{cid}:recent" + conversationId;
            String summaryKey = recentKey + "Summary";
            stringRedisTemplate.delete(recentKey);
            stringRedisTemplate.delete(summaryKey);
        }
        return null;
    }
    /**
     * 登录
     * @param id id
     * @param password 密码
     * @return 成功
     */
    public Result<String> login(Long id, String password) {
        //员工ID和密码为公式内部所给,不需要注册和加密
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
        // Return a simple success message or token if needed.
        return Result.success("登录成功");
    }

    /**
     * 查询用户对话
     * @param userId 用户Id
     * @return 返回对话对象
     */
    public Result<List<ChatConversation>> history(Long userId) {
        //空指针
        if(userId == null || userId < 0) return Result.error(500,"id非法");
        //根据用户ID查询用户会话历史根据时倒序返回会话对象
        //where user_id = {userId} order by updated_at desc
        List<ChatConversation> conversations = getConversationId(userId);
        log.info("查询历史");
        //返回会话对象
        return Result.success(conversations);
    }


    /**
     * 根据用户id查出对话id
     *
     * @param userId 用户id
     * @return 对话列表
     */
    private List<ChatConversation> getConversationId(Long userId) {
        return chatConversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversation>()
                        .select(ChatConversation::getConversationId, ChatConversation::getTitle, ChatConversation::getCreatedAt)
                        .eq(ChatConversation::getUserId, String.valueOf(userId))
                        .orderByDesc(ChatConversation::getCreatedAt)
        );
    }
}
