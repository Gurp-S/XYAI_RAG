package com.XYai.myai.redis;

import java.util.Locale;

/**
 * Centralized Redis key naming and prefixes used across the project.
 */
public final class RedisKeyConfig {

    public static final String PREFIX = "xyai:";

    private RedisKeyConfig() {
    }

    public static String userLoadCollectionsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:collections:%d", userId);
    }

    public static String userUnloadCollectionsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:collections:unloaded:%d", userId);
    }
    //用户下文件:fileId:bitMap(chunkId)
    public static String userFileIdsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:files:%d", userId);
    }
    //集合下文件:fileId:chunkSize
    public static String collectionFileIds(String collectionName) {
        return PREFIX + String.format(Locale.ROOT, "collection:files:%s", collectionName);
    }

    /**
     * 用户-文件位图键（用于按位存储 chunk 权限）
     * 格式: xyai:user:filebits:{userId}:{fileId}
     */
    public static String userFileBitKey(Long userId, String fileId) {
        return PREFIX + String.format(Locale.ROOT, "user:filebits:%d:%s", userId, fileId);
    }

    public static String fileHashKey(String sha256) {
        return PREFIX + "file:hash:" + sha256;
    }

    public static String metricsKey(String metricName) {
        return PREFIX + "metrics:" + metricName;
    }

    // 单个文件最大分片数
    public static final long MAX_CHUNK_PER_FILE = 1000L;

    // BitMap 最大偏移量
    public static final long REDIS_BIT_MAX_OFFSET = (1L << 31) - 1;  // 21亿
}
