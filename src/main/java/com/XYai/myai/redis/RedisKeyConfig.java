package com.XYai.myai.redis;

import java.util.Locale;

/**
 * Centralized Redis key naming and prefixes used across the project.
 */
public final class RedisKeyConfig {

    public static final String PREFIX = "xyai:";
    // 单个文件最大分片数
    public static final long MAX_CHUNK_PER_FILE = 1000L;

    private RedisKeyConfig() {
    }

    public static String userLoadCollectionsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:collections:%d", userId);
    }

    public static String userUnloadCollectionsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:collections:unloaded:%d", userId);
    }

    //集合下文件:fileId:chunkSize
    public static String collectionFileIds(String collectionName) {
        return PREFIX + String.format(Locale.ROOT, "collection:files:%s", collectionName);
    }

    public static String userFileBitKey(Long userId, String fileId) {
        return PREFIX + String.format(Locale.ROOT, "user:filebits:%d:%s", userId, fileId);
    }

    public static String fileHashKey(String sha256) {
        return PREFIX + "file:hash:" + sha256;
    }

    public static String metricsKey(String metricName) {
        return PREFIX + "metrics:" + metricName;
    }


    public static String deletionMonitor() {
        return PREFIX + "deletionMonitor:total";
    }

    public static String deletionMonitorByType(String type) {
        return PREFIX + "deletionMonitor:type:" + type;
    }
}
