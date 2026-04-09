//package com.XYai.myai.RAG.mcp;
//
//import jakarta.annotation.PostConstruct;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.Optional;
//
//@Component
//public class MCPRegistry {
//
//    private final Map<String, MCPToolExecutor<?, ?>> registry = new ConcurrentHashMap<>();
//
//    private List<MCPToolExecutor<?, ?>> executors = Collections.emptyList();
//
//    @PostConstruct
//    private void init() {
//        for (MCPToolExecutor<?, ?> executor : executors) {
//            try {
//                ToolDescriptor d = executor.descriptor();
//                if (d != null && d.name() != null && !d.name().isBlank()) {
//                    registry.put(d.name(), executor);
//                }
//            } catch (Exception e) {
//                // 忽略某个 executor 的异常，不阻塞其它注册
//            }
//        }
//    }
//
//    public Optional<MCPToolExecutor<?, ?>> get(String name) {
//        return Optional.ofNullable(registry.get(name));
//    }
//
//    public Set<String> availableTools() {
//        return Set.copyOf(registry.keySet());
//    }
//}
