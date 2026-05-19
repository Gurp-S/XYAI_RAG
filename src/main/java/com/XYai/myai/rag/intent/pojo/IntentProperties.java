package com.XYai.myai.rag.intent.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Builder
@Data
@NoArgsConstructor
@Component
@AllArgsConstructor
public class IntentProperties {

    @Builder.Default
    private Boolean updateIntentEnabled = true;

    @Builder.Default
    private Boolean intentEnabled = true;

    @Builder.Default
    private Boolean DBEnabled =true;

    @Builder.Default
    private Boolean redisEnabled= true;

    @Builder.Default
    private Boolean vectorEnabled = true;

    @Builder.Default
    private Boolean cacheTreeToRedisEnabled = true;
}