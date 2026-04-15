package com.XYai.myai.rag.mcp.tools.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;

/**
 * MCPRequest 调用请求
 * 封装工具ID、用户信息、经参数提取器解析后的参数等。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPRequest {
    /** 目标工具 ID */
    private String toolId;
    
    /** 用户标识 */
    private String userId;
    
    /** 经模型处理后生成的参数 KV 映射 */
    private Map<String, Object> parameters;

    /** 可选：远端 MCP Server 地址（本地执行器可忽略） */
    private String serverUrl;

    /** 完整的会话历史或原始问题上下文（可选） */
    private String context;
}
