package com.XYai.myai.rag.kafka.event;

import com.XYai.myai.rag.evaluate.pojo.SystemEvaluate;
import com.XYai.myai.rag.milvus.pojo.FileRecord;
import com.XYai.myai.xyAdmin.pojo.TokenUse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEvent {
    private String eventId;
    private String eventType;  // evaluate, use_count
    private FileRecord fileRecord;
    private TokenUse tokenUse;
    private SystemEvaluate systemEvaluate;
}