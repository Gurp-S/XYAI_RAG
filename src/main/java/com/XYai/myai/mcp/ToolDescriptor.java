package com.XYai.myai.mcp;

import java.time.Duration;
import java.util.Map;

/**
 * MCP 工具元描述。
 *
 * @param name 工具名
 * @param description 工具说明
 * @param timeout 超时时间
 * @param schema 输入输出结构描述
 */
public record ToolDescriptor(String name, String description, Duration timeout, Map<String, Object> schema) {}

