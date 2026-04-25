package com.XYai.myai.rag.channel.POJO;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;

public interface SearchChannel {


    /**
     * 通道名称（唯一标识，用于日志和配置）
     */
    String getName();

    /**
     * 优先级（数字越小，优先级越高，先执行）
     */
    int getPriority();

    /**
     * 是否启用该通道（根据检索上下文动态判断）
     */
    boolean isEnabled(SearchContext context);


    /**
     * 执行检索逻辑，返回该通道的检索结果
     */
    @CircuitBreaker(name = "searchChannel", fallbackMethod = "searchFallback")
    @TimeLimiter(name = "searchChannel")
    SearchChannelResult search(SearchContext context);

    /**
     * 通道类型（如向量检索、关键词检索、意图定向检索）
     */
    String getType();

    // 降级方法：熔断后直接返回空
    default SearchChannelResult searchFallback(SearchContext context, Exception ex) {
        return null;
    }
}