package com.XYai.myai.rag.evaluate.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Component
public class EvaluateProperties {
    @Builder.Default
    private Boolean LLMEnable = true;
    @Builder.Default
    private Boolean rerankEnable = true;
    @Builder.Default
    private Boolean ruleEnable = true;
}
