package com.XYai.myai.RAG.MCP.tools.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCPResponse 调用响应
 * 封装工具端执行的最终结果或具体的错误信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPResponse {
    /** 执行状态码 */
    private int code;
    
    /** 错误或结果的描述消息 */
    private String message;
    
    /** 实际的数据输出内容（通常是 JSON 结构） */
    private Object data;
    
    /** 是否执行成功 */
    private boolean success;
    
    /** 建议 LLM 下一步的操作内容（可选） */
    private String suggestion;
}
