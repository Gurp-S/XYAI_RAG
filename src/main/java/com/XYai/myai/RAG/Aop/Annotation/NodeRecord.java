package com.XYai.myai.RAG.Aop.Annotation;

import lombok.Builder;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@Builder
@TableName("node_record")
public class NodeRecord {
    /** 节点名称（用于可读化展示） */
    private String nodeName;
    
    /** 节点唯一 ID */
    private String nodeId;
    
    /** 所属 traceId */
    private String traceId;
    
    /** 节点类型（如 chat/retriever/aggregate） */
    private String nodeType;
    
    /** 节点耗时（毫秒） */
    private Long costTime;

    /** 节点执行状态 */
    private String status;

    /** 节点异常信息（若有） */
    private String errorMessage;
}
