package com.XYai.myai.redis;

import java.util.Locale;

/**
 * Centralized Redis key naming and prefixes used across the project.
 */
public final class RedisKeyConfig {

    public static final String PREFIX = "xyai:";

    private RedisKeyConfig() {
    }

    public static String userCollectionsKey(Long userId) {
        return PREFIX + String.format(Locale.ROOT, "user:collections:%d", userId);
    }

    public static String fileHashKey(String sha256) {
        return PREFIX + "file:hash:" + sha256;
    }

    public static String milvusModifyLockKey(String collectionName) {
        return PREFIX + "milvus:modify:lock:" + collectionName;
    }

    public static String milvusModifyWaitKey(String collectionName) {
        return PREFIX + "milvus:modify:wait:" + collectionName;
    }

    public static String metricsKey(String metricName) {
        return PREFIX + "metrics:" + metricName;
    }
}
