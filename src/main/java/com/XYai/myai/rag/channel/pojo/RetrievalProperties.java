package com.XYai.myai.rag.channel.pojo;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Component
public class RetrievalProperties {
    @Builder.Default
    private Boolean rerankLLM = true;
    @Builder.Default
    private double commonScoreThreshold = 0.6;
    @Builder.Default
    private double highScoreThreshold = 0.85;
    @Builder.Default
    private double lowScoreThreshold = 0.2;
    @Builder.Default
    private double vectorOnlyThreshold = 0.6;
    @Builder.Default
    private int minContentLength = 10;
    @Builder.Default
    private int maxContentLength = 2000;
}
