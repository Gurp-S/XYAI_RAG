package com.XYai.myai.rag.intent.POJO;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "intent")
public class IntentProperties {
    Boolean updateIntentEnabled;    //是否启用摘要压缩功能，开发环境关闭便于调试历史消息
    Boolean intentEnabled;    //是否启用摘要压缩功能，开发环境关闭便于调试历史消息
    Boolean DBEnabled;    //是否启用摘要压缩功能，开发环境关闭便于调试历史消息
    Boolean redisEnabled;    //是否启用摘要压缩功能，开发环境关闭便于调试历史消息
    Boolean vectorEnabled;    //是否启用摘要压缩功能，开发环境关闭便于调试历史消息
    Boolean cacheTreeToRedisEnabled;
}

