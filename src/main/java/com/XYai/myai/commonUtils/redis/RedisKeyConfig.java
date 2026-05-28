package com.XYai.myai.commonUtils.redis;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Redis 键配置类
 * 统一管理项目中所有 Redis Key 的前缀、命名规则和生成方法
 * 避免硬编码 Key 导致冲突或难以维护
 */
public final class RedisKeyConfig {

    /**
     * 全局 Redis Key 统一前缀
     * 格式：项目标识:，用于区分不同业务/项目的缓存数据
     */
    public static final String PREFIX = "xyai:";

    /**
     * 单个文件允许的最大分片数量
     * 文件上传/解析时会按分片存储，限制最大分片数防止异常
     */
    public static final long MAX_CHUNK_PER_FILE = 1000L;

    /**
     * 私有构造方法
     * 工具类禁止实例化
     */
    private RedisKeyConfig() {
    }

    private static String fileHashTag(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return "{unknown}";
        }
        if (fileId.startsWith("{") && fileId.endsWith("}")) {
            return fileId;
        }
        return "{" + fileId + "}";
    }

    public static String stripHashTag(String value) {
        if (value == null) return null;
        if (value.startsWith("{") && value.endsWith("}") && value.length() > 2) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 意图树节点 Key
     * 用途：存储意图节点信息，用于意图识别快速判断
     * @param nodeName 节点名称
     * @return 拼接后的 Redis Key
     */
    public static String intentNodeNameKey(String nodeName) {
        return PREFIX + String.format(Locale.ROOT, "intent:tree:node:%s", nodeName);
    }

    /**
     * 意图树叶子节点 Key（无孩子节点）
     * 用途：快速查询所有叶子节点，用于意图匹配
     * @return 拼接后的 Redis Key
     */
    public static String intentNodeLeaveKey() {
        return PREFIX + "intent:tree:leaves:";
    }

    /**
     * 意图树子节点 Key
     * 用途：根据父节点查询其所有子节点
     * @param nodeName 父节点名称
     * @return 拼接后的 Redis Key
     */
    public static String intentNodeChildrenKey(String nodeName) {
        return PREFIX + String.format(Locale.ROOT, "intent:tree:children:%s", nodeName);
    }

    /**
     * 用户已加载的知识库集合 Key
     * 用途：记录用户当前已加载/启用的知识库
     * @param userId 用户ID
     * @return 拼接后的 Redis Key
     */
    public static String userLoadCollectionsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:collections:%d", userId);
    }

    /**
     * 用户未加载的知识库集合 Key
     * 用途：记录用户未加载/禁用的知识库
     * @param userId 用户ID
     * @return 拼接后的 Redis Key
     */
    public static String userUnloadCollectionsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:collections:unloaded:%d", userId);
    }

    /**
     * 知识库下所有文件ID集合 Key
     * 用途：存储某个知识库下的所有文件ID
     * @param collectionName 知识库名称
     * @return 拼接后的 Redis Key
     */
    public static String collectionFileIds(String collectionName) {
        return PREFIX + String.format(Locale.ROOT, "collection:files:%s", collectionName);
    }

    /**
     * 知识库文件分片位图 Key
     * 用途：记录某个知识库下文件的分片状态（BitMap 存储）
     * @param collectionName 知识库名称
     * @param fileId 文件ID
     * @return 拼接后的 Redis Key
     */
    public static String collectionFileChunkBitKey(String collectionName,String fileId) {
        return PREFIX + String.format(Locale.ROOT, "collection:filebits:%s:%s", fileHashTag(fileId), collectionName);
    }

    /**
     * 用户文件分片位图 Key
     * 用途：记录用户拥有/使用的文件分片状态（BitMap 存储）
     * @param userId 用户ID
     * @param fileId 文件ID
     * @return 拼接后的 Redis Key
     */
    public static String userFileBitKey(Long userId, String fileId) {
        return PREFIX + String.format(Locale.ROOT, "user:filebits:%s:%d", fileHashTag(fileId), userId);
    }

    public static String userFileBitKeyPatternByUser(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:filebits:*:%d", userId);
    }

    public static String userFileBitKeyPatternByFileId(String fileId) {
        return PREFIX + String.format(Locale.ROOT, "user:filebits:%s:*", fileHashTag(fileId));
    }

    /**
     * 文件哈希去重 Key
     * 用途：通过文件 SHA256 值判断文件是否已上传，实现文件去重
     * @param sha256 文件哈希值
     * @return 拼接后的 Redis Key
     */
    public static String fileHashKey(String sha256) {
        return PREFIX + "file:hash:" + fileHashTag(sha256);
    }

    /**
     * 系统监控指标 Key
     * 用途：存储系统各类监控指标数据
     * @param metricName 指标名称
     * @return 拼接后的 Redis Key
     */
    public static String metricsKey(String metricName) {
        return PREFIX + "metrics:" + metricName;
    }

    /**
     * 文件分片用户使用计数 Key
     * 用途：统计某个文件分片被多少用户使用
     * @param fileId 文件ID
    * @param chunkId 分片ID
     * @return 拼接后的 Redis Key
     */
    public static String fileChunkUserCountKey(String fileId, int chunkId) {
        return PREFIX + "fileChunk:count:" + fileHashTag(fileId) + ":" + chunkId;
    }

    /**
     * 用户短期对话记录锁 Key
     * 用途：用户对话处理时加锁，防止重复处理/并发冲突
     * @param userId 用户ID
     * @param conversationId 对话ID
     * @return 拼接后的 Redis Key
     */
    public static String userConversationRecord(Long userId,Long conversationId){
        return PREFIX + "chatMessage:" + userId + ":" + conversationId;
    }

    public static String userConversationLock(Long userId,Long conversationId){
        return PREFIX + "chatMessage:" + userId + ":lock:" + conversationId;
    }

    /**
     * 用户长期对话摘要 Key
     * 用途：存储用户历史对话的摘要信息，用于长期记忆
     * @param userId 用户ID
     * @param conversationId 对话ID
     * @return 拼接后的 Redis Key
     */
    public static String userSummaryRecord(Long userId,Long conversationId){
        return PREFIX + "chatMessage:" + userId + ":summary:" + conversationId;
    }

    /**
     * 知识库用户计数 Key
     * 用途：统计某个知识库被多少用户使用
     * @param collection 知识库名称
     * @return 拼接后的 Redis Key
     */
    public static String collectionUserCountKey(String collection) {
        return PREFIX + "collection:users:" + collection;
    }

    /**
     * 每日文件使用计数 Key
     * 用途：统计每天的文件被检索/使用次数，用于管理后台文件使用趋势图
     * @param localDate 日期
     * @return 拼接后的 Redis Key
     */
    public static String dailyFileUseCountKey(LocalDate localDate) {
        return PREFIX + "dashboard:fileUse:" + localDate;
    }
}