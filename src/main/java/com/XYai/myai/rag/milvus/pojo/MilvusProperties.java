package com.XYai.myai.rag.milvus.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "milvus")
public class MilvusProperties {
    private int DeleteCountThreshold;
    private int DeleteCountFileThreshold;
    private int DeleteCountCollectionThreshold;
}
