package com.XYai.myai.Aop.Annotation;

import lombok.Builder;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@Builder
@TableName("node_record")
public class NodeRecord {
    //节点的记录实体(包含 traceId, nodeId, nodeName, nodeType, costTime)
    private String nodeName;
    
    private String nodeId;
    
    private String traceId;
    
    private String nodeType;
    
    private Long costTime;

    private String status;

    private String errorMessage;
}
