package com.XYai.myai.rag.milvus;

import com.XYai.myai.redis.RedisKeyConfig;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.redisson.api.RBucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.alibaba.fastjson2.JSON;
import java.time.Instant;

import jakarta.annotation.Resource;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 后台 Reconciler：异步清理 Redis 索引与 Milvus 元数据不一致的项，
 * 并对 Redis 中过期/丢失的 fileId 做轻量级回收。
 *
 * 设计原则：非破坏性、幂等、低频执行。仅清理 Redis 层的垃圾引用，避免误删 Milvus 原始数据。
 */


