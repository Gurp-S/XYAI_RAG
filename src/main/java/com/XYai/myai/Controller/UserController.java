package com.XYai.myai.Controller;


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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {
    public static Long USERID;
    @Resource
    private UserMapper userMapper;
    @Resource
    private ChatSessionRecordMapper chatSessionRecordMapper;
    @Resource
    private ChatConversationMapper chatConversationMapper;
    @Resource
    private MemoryCompressor memoryCompressor;
    @Resource
    private ChatMemorySummaryMapper chatMemorySummaryMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @PostMapping("/login")
    public Result<String> login(Long id, String password){
        //员工ID和密码为公式内部所给,不需要注册和加密
        if (id == null || password == null) {
            return Result.error(400, "ID或密码不能为空");
        }USERID = id;
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
    @GetMapping("/history")
    public Result<List<ChatConversation>> history(Long userId){
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
     * 查询对话内容
     * @param conversationId 对话Id
     * @return 返回对话内容
     */
    @GetMapping("/history/conversation")
    public Result<List<ChatSessionRecord>> conversationHistory(String conversationId){
        if (conversationId == null || conversationId.isBlank()) return Result.error(404,"会话无记录");
        //根据会话ID查询会话
        //where conversation_id = {conversationId} order by updated_at desc
        List<ChatSessionRecord> records = chatSessionRecordMapper.selectList(
                new LambdaQueryWrapper<ChatSessionRecord>()
                        .eq(ChatSessionRecord::getConversationId, conversationId)
                        .orderByDesc(ChatSessionRecord::getCreatedAt)
        );
        // 无会话记录时直接返回空列表
        if (records == null || records.isEmpty()) {
            return Result.success(List.of());
        }
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
        String summarize = memoryCompressor.summarize(existingSummary, oldRoundsText);
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
            chatMemorySummaryMapper.update(
                    null,
                    new LambdaUpdateWrapper<ChatMemorySummary>()
                            .eq(ChatMemorySummary::getConversationId, conversationId)
                            .set(ChatMemorySummary::getSummaryText, summarize)
            );
        }
        return Result.success(records);
    }

    /**
     * 用户登出
     * @param userId 用户id
     * @return 成功返回
     */
    @PostMapping("/logout")
    public Result<String> logout(Long userId){
        if (userId == null || userId < 0) {
            return Result.error(400, "用户ID非法");
        }
        //根据用户id查出对话id
        List<ChatConversation> conversationIds = getConversationId(userId);
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Result.success("登出成功");
        }
        //删除redis中的对话数据 + 摘要数据
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
        return Result.success("登出成功");
    }

    /**
     * //根据用户id查出对话id
     *
     * @param userId 用户id
     * @return 对话列表
     */
    private List<ChatConversation> getConversationId(Long userId) {
        return chatConversationMapper.selectList(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getUserId, String.valueOf(userId))
                        .orderByDesc(ChatConversation::getConversationId)
        );
    }
}