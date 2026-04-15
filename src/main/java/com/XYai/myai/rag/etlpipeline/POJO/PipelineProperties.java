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
}
