package com.XYai.myai.user.userChat;

import com.XYai.myai.mapper.UserChatMessageMapper;
import com.XYai.myai.user.userChat.pojo.ChatMessage;
import com.XYai.myai.user.userChat.pojo.UserChatMessageDTO;
import com.XYai.myai.user.userChat.pojo.UserChatMessageEntity;
import com.XYai.myai.user.userChat.pojo.UserChatRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 用户/群聊服务。
 * <p>
 * 消息持久化到 MySQL（user_chat_message 表），同时维护内存队列作为缓存。
 * 支持游标分页加载历史消息与本地文件分享。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserChatService {

    private static final int MAX_MESSAGES_PER_CONVERSATION = 300;
    private static final int MAX_POLLING_MESSAGES = 200;
    private static final String LOCAL_FILE_STORAGE_DIR = "userchat-files";

    private final UserChatMessageMapper messageMapper;

    /** 内存消息队列，作为缓存和兼容旧版消息的暂存区 */
    private final Map<String, Deque<ChatMessage>> conversationStore = new ConcurrentHashMap<>();
    private final AtomicLong messageIdGenerator = new AtomicLong(1L);

    // ===================== 兼容旧接口 =====================

    /**
     * 兼容旧接口：发送消息并通过 SseEmitter 回传 delivery 事件。
     */
    public void chat(UserChatRequest request, SseEmitter emitter) {
        try {
            UserChatSendResult result = send(request);
            String deliveryEvent = "{\"type\":\"delivery\",\"conversationId\":\""
                    + result.conversationId()
                    + "\",\"messageId\":"
                    + result.messageId()
                    + ",\"timestamp\":"
                    + result.timestamp()
                    + "}";
            emitter.send(SseEmitter.event().data(deliveryEvent));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    // ===================== 消息发送 =====================

    /**
     * 发送消息：持久化到 MySQL 并写入内存缓存。
     */
    public UserChatSendResult send(UserChatRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }

        String content = normalizeMessage(request.message());
        String targetType = normalizeTargetType(request.targetType());
        String targetId = normalizeTargetId(request.targetId());

        if (request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发送方用户ID不能为空");
        }

        String conversationId = buildConversationId(request.userId(), targetType, targetId);
        long now = System.currentTimeMillis();

        String messageStatus = request.status() != null ? request.status() : "active";

        // 1. 持久化到 MySQL
        UserChatMessageEntity entity = UserChatMessageEntity.builder()
                .conversationId(conversationId)
                .targetType(targetType)
                .targetId(targetId)
                .senderId(String.valueOf(request.userId()))
                .senderName(resolveSenderName(request))
                .content(content)
                .createdAt(now)
                .status(messageStatus)
                .build();
        try {
            messageMapper.insert(entity);
        } catch (Exception e) {
            log.error("持久化用户聊天消息失败", e);
        }

        long msgId = entity.getId() != null ? entity.getId() : messageIdGenerator.getAndIncrement();

        // 2. 写入内存缓存
        ChatMessage message = new ChatMessage(
                msgId,
                conversationId,
                targetType,
                targetId,
                String.valueOf(request.userId()),
                resolveSenderName(request),
                content,
                now,
                messageStatus
        );
        Deque<ChatMessage> queue = conversationStore.computeIfAbsent(conversationId, key -> new ConcurrentLinkedDeque<>());
        appendMessage(queue, message);

        return new UserChatSendResult(conversationId, msgId, now);
    }

    // ===================== 消息查询 =====================

    /**
     * 查询消息列表——游标分页（前端滚动加载更早消息时使用）。
     *
     * @param cursor 上一页最旧消息的ID（null 表示加载最新消息）
     * @param limit  每页数量（默认20，最大100）
     */
    public List<UserChatMessageDTO> getMessages(Long userId, String targetType, String targetId,
                                                 Long cursor, Integer limit) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户ID不能为空");
        }

        String normalizedType = normalizeTargetType(targetType);
        String normalizedTargetId = normalizeTargetId(targetId);
        String conversationId = buildConversationId(userId, normalizedType, normalizedTargetId);

        int pageSize = (limit == null || limit < 1) ? 20 : Math.min(limit, 100);

        // ---------- 游标分页（加载更早的历史消息） ----------
        if (cursor != null && cursor > 0) {
            LambdaQueryWrapper<UserChatMessageEntity> wrapper = new LambdaQueryWrapper<UserChatMessageEntity>()
                    .eq(UserChatMessageEntity::getConversationId, conversationId)
                    .lt(UserChatMessageEntity::getId, cursor)
                    .orderByDesc(UserChatMessageEntity::getId)
                    .last("LIMIT " + pageSize);
            List<UserChatMessageEntity> records = messageMapper.selectList(wrapper);
            if (records == null) records = Collections.emptyList();
            Collections.reverse(records); // 按 id 升序返回给前端
            return records.stream().map(this::toDTO).collect(Collectors.toList());
        }

        // ---------- 无游标：返回最新消息（轮询 / 初始加载） ----------
        // 先从 MySQL 加载最新消息
        LambdaQueryWrapper<UserChatMessageEntity> wrapper = new LambdaQueryWrapper<UserChatMessageEntity>()
                .eq(UserChatMessageEntity::getConversationId, conversationId)
                .orderByDesc(UserChatMessageEntity::getId)
                .last("LIMIT " + MAX_POLLING_MESSAGES);
        List<UserChatMessageEntity> records = messageMapper.selectList(wrapper);
        if (records == null) records = Collections.emptyList();

        Set<Long> dbIds = records.stream().map(UserChatMessageEntity::getId).collect(Collectors.toSet());

        // 合并内存中尚未落地的消息（旧版纯内存消息、或刚发送尚未刷新的消息）
        Deque<ChatMessage> memoryQueue = conversationStore.get(conversationId);
        List<UserChatMessageDTO> memoryMessages = new ArrayList<>();
        if (memoryQueue != null) {
            for (ChatMessage msg : memoryQueue) {
                if (!dbIds.contains(msg.getId())) {
                    memoryMessages.add(toDTO(msg));
                }
            }
        }

        // 合并、按 id 升序排序
        List<UserChatMessageDTO> result = new ArrayList<>();
        for (UserChatMessageEntity e : records) {
            result.add(toDTO(e));
        }
        result.addAll(memoryMessages);
        result.sort(Comparator.comparingLong(UserChatMessageDTO::getId));

        return result;
    }

    /**
     * 旧版接口（无游标），向前端返回消息用于轮询。
     */
    public List<UserChatMessageDTO> getMessages(Long userId, String targetType, String targetId) {
        return getMessages(userId, targetType, targetId, null, null);
    }

    // ===================== 本地文件分享 =====================

    /**
     * 分享本地文件到聊天。
     *
     * @param fileId 已存储的文件的唯一标识（与磁盘文件名对应）
     */
    public UserChatSendResult shareLocalFile(Long userId, String targetType, String targetId,
                                              String senderName, String originalFileName,
                                              long fileSize, String fileId) throws IOException {
        String convId = buildConversationId(userId, normalizeTargetType(targetType),
                normalizeTargetId(targetId));

        if (fileId == null || fileId.isBlank()) {
            fileId = UUID.randomUUID().toString().replace("-", "");
        }
        String content = String.format(
                "{\"type\":\"local_file_share\",\"fileId\":\"%s\",\"fileName\":\"%s\",\"fileSize\":%d,\"senderId\":\"%s\",\"senderName\":\"%s\"}",
                fileId, escapeJson(originalFileName), fileSize, userId,
                escapeJson(senderName != null ? senderName : "用户-" + userId));

        long now = System.currentTimeMillis();

        // 写入 MySQL
        UserChatMessageEntity entity = UserChatMessageEntity.builder()
                .conversationId(convId)
                .targetType(normalizeTargetType(targetType))
                .targetId(normalizeTargetId(targetId))
                .senderId(String.valueOf(userId))
                .senderName(senderName != null ? senderName : "用户-" + userId)
                .content(content)
                .createdAt(now)
                .status("accepted")
                .build();
        messageMapper.insert(entity);
        long msgId = entity.getId();

        // 写入内存缓存
        ChatMessage message = new ChatMessage(msgId, convId,
                normalizeTargetType(targetType), normalizeTargetId(targetId),
                String.valueOf(userId),
                senderName != null ? senderName : "用户-" + userId,
                content, now, "accepted");
        conversationStore.computeIfAbsent(convId, k -> new ConcurrentLinkedDeque<>()).addLast(message);

        return new UserChatSendResult(convId, msgId, now);
    }

    /** 获取本地文件存储目录（基于 user.dir 的绝对路径） */
    public Path getLocalFileStorageDir() {
        Path dir = Path.of(System.getProperty("user.dir"), LOCAL_FILE_STORAGE_DIR);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("创建本地文件存储目录失败", e);
        }
        return dir;
    }

    /** 根据 fileId 查找已存储的本地文件路径 */
    public Path resolveLocalFilePath(String fileId) {
        Path dir = getLocalFileStorageDir();
        java.io.File[] files = dir.toFile().listFiles((d, name) -> name.startsWith(fileId + "_"));
        if (files != null && files.length > 0) {
            return files[0].toPath();
        }
        return null;
    }

    // ===================== 消息管理 =====================

    /**
     * 更新消息状态（用于文件分享接受/拒绝）。
     */
    public boolean updateMessageStatus(Long messageId, String status) {
        if (messageId == null || status == null) return false;
        try {
            UserChatMessageEntity entity = new UserChatMessageEntity();
            entity.setId(messageId);
            entity.setStatus(status);
            int rows = messageMapper.updateById(entity);
            return rows > 0;
        } catch (Exception e) {
            log.error("更新消息状态失败: messageId={} status={}", messageId, status, e);
            return false;
        }
    }

    /**
     * 删除指定对话中的某条消息（用于拒绝文件分享等场景）。
     */
    public boolean deleteMessage(String conversationId, long messageId) {
        try {
            LambdaQueryWrapper<UserChatMessageEntity> wrapper = new LambdaQueryWrapper<UserChatMessageEntity>()
                    .eq(UserChatMessageEntity::getId, messageId);
            messageMapper.delete(wrapper);
        } catch (Exception e) {
            log.error("从 MySQL 删除消息失败", e);
        }
        Deque<ChatMessage> queue = conversationStore.get(conversationId);
        if (queue == null || queue.isEmpty()) return false;
        return queue.removeIf(msg -> msg.getId() == messageId);
    }

    // ===================== 私有工具方法 =====================

    private String normalizeMessage(String message) {
        if (!StringUtils.hasText(message)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        return message.trim();
    }

    private String normalizeTargetType(String targetType) {
        return "group".equalsIgnoreCase(targetType) ? "group" : "user";
    }

    private String normalizeTargetId(String targetId) {
        if (!StringUtils.hasText(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "聊天对象ID不能为空");
        }
        return targetId.trim();
    }

    private String resolveSenderName(UserChatRequest request) {
        if (StringUtils.hasText(request.senderName())) {
            return request.senderName().trim();
        }
        return "用户-" + request.userId();
    }

    String buildConversationId(Long userId, String targetType, String targetId) {
        if ("group".equals(targetType)) {
            return "group:" + targetId;
        }
        String self = String.valueOf(userId);
        if (self.compareTo(targetId) <= 0) {
            return "user:" + self + ":" + targetId;
        }
        return "user:" + targetId + ":" + self;
    }

    private void appendMessage(Deque<ChatMessage> queue, ChatMessage message) {
        queue.addLast(message);
        while (queue.size() > MAX_MESSAGES_PER_CONVERSATION) {
            queue.pollFirst();
        }
    }

    private UserChatMessageDTO toDTO(UserChatMessageEntity e) {
        return UserChatMessageDTO.builder()
                .id(e.getId())
                .conversationId(e.getConversationId())
                .targetType(e.getTargetType())
                .targetId(e.getTargetId())
                .senderId(e.getSenderId())
                .senderName(e.getSenderName())
                .content(e.getContent())
                .timestamp(e.getCreatedAt())
                .status(e.getStatus())
                .build();
    }

    private UserChatMessageDTO toDTO(ChatMessage m) {
        return UserChatMessageDTO.builder()
                .id(m.getId())
                .conversationId(m.getConversationId())
                .targetType(m.getTargetType())
                .targetId(m.getTargetId())
                .senderId(m.getSenderId())
                .senderName(m.getSenderName())
                .content(m.getContent())
                .timestamp(m.getTimestamp())
                .status(m.getStatus())
                .build();
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    public record UserChatSendResult(String conversationId, long messageId, long timestamp) {
    }
}
