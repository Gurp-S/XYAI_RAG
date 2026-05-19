package com.XYai.myai.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置。
 * 配置项可通过 admin 面板修改并持久化到 DB，重启后生效。
 */
@Data
@Configuration
@EnableCaching
public class CacheConfig {

    /** 词典缓存 */
    private int dictTime = 30;
    private int dictCount = 100;

    /** 短时缓存 */
    private int shortTime = 5;
    private int shortCount = 1000;

    /** 默认缓存 */
    private int defaultTime = 10;
    private int defaultCount = 500;


    /** 词典缓存 */
    @Bean
    public Cache<String, Object> dictCache() {
        return Caffeine.newBuilder()
                .maximumSize(dictCount)
                .expireAfterWrite(dictTime, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /** 短时缓存 */
    @Bean
    public Cache<String, Object> shortCache() {
        return Caffeine.newBuilder()
                .maximumSize(shortCount)
                .expireAfterWrite(shortTime, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /** 默认缓存 */
    @Bean
    public Cache<String, Object> defaultCache() {
        return Caffeine.newBuilder()
                .maximumSize(defaultCount)
                .expireAfterWrite(defaultTime, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
