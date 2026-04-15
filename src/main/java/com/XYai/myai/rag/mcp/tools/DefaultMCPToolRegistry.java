package com.XYai.myai.rag.mcp.tools;

import com.XYai.myai.rag.mcp.tools.POJO.MCPTool;
import com.XYai.myai.rag.mcp.tools.POJO.MCPToolExecutor;
import com.XYai.myai.rag.mcp.tools.POJO.MCPToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * DefaultMCPToolRegistry 注册表实现类
 * 核心逻辑：管理内存中所有工具执行器，并提供统一的查询接口。
 */
@Component
@RequiredArgsConstructor
public class DefaultMCPToolRegistry implements MCPToolRegistry, InitializingBean {

    private final ApplicationContext context;

    // 线程安全的映射容器，使用 toolId -> executor
    private final Map<String, MCPToolExecutor> registry = new ConcurrentHashMap<>();

    @Override
    public void afterPropertiesSet() {
        // 自动注入所有已定义的 MCPToolExecutor 子类到注册表
        Map<String, MCPToolExecutor> beans = context.getBeansOfType(MCPToolExecutor.class);
        beans.values().forEach(this::register);
    }

    @Override
    public void register(MCPToolExecutor executor) {
        String toolId = executor.getDefinition().getId();
        registry.put(toolId, executor);
    }

    @Override
    public Optional<MCPToolExecutor> getExecutor(String toolId) {
        return Optional.ofNullable(registry.get(toolId));
    }

    @Override
    public Collection<MCPTool> getAllTools() {
        return registry.values().stream()
                .map(MCPToolExecutor::getDefinition)
                .collect(Collectors.toList());
    }
}
