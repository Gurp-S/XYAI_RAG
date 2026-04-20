package com.XYai.myai.rag.etlpipeline.POJO;


import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "pipeline")
public class PipelineProperties {

    Boolean enricherEnable;

    /** 默认分块大小（当无法从输入推断时使用） */
    private int defaultChunkSize = 900;

    /** 默认重叠大小 */
    private int defaultOverlapSize = 120;

    // Lombok @Data generates getters/setters for defaultChunkSize and defaultOverlapSize
}
