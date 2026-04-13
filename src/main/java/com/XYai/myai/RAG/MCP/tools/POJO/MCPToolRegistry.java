package com.XYai.myai.RAG.MCP.tools.POJO;

import java.util.Collection;
import java.util.Optional;

/**
 * MCPToolRegistry 注册表接口
 * 定义工具注册与获取的规范及行为。
 */
public interface MCPToolRegistry {
    
    /** 注册一个工具执行器 */
    void register(MCPToolExecutor executor);
    
    /** 根据工具 ID 获取对应执行器 */
    Optional<MCPToolExecutor> getExecutor(String toolId);
    
    /** 返回当前所有注册工具的列表 */
    Collection<MCPTool> getAllTools();
}
