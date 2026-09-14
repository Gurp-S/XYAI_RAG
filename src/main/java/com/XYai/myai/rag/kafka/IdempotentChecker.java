package com.XYai.myai.rag.kafka;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

// Kafka幕等
@Component
public class IdempotentChecker {

    private final StringRedisTemplate stringRedisTemplate;
    private static final String PREFIX = "idempotent:";
    private static final long TTL_HOURS = 24;

    public IdempotentChecker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public Boolean isProcessed(String eventId){
        if(eventId == null) return false;
        return stringRedisTemplate.hasKey(PREFIX + eventId);
    }

    public void markProcessed(String eventId){
        if(eventId == null) return;
        stringRedisTemplate.opsForValue().set(PREFIX + eventId, "1", TTL_HOURS, TimeUnit.HOURS);
    }
}