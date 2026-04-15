//package com.XYai.myai.RAG.Channel.Search;
//
//import com.XYai.myai.RAG.Channel.POJO.RetrievedChunk;
//import com.XYai.myai.RAG.Channel.POJO.SearchChannel;
//import com.XYai.myai.RAG.Channel.POJO.SearchChannelResult;
//import com.XYai.myai.RAG.Channel.POJO.SearchContext;
//import com.XYai.myai.RAG.mcp.MCPRegistry;
//import com.XYai.myai.RAG.mcp.MCPToolExecutor;
//import com.XYai.myai.RAG.intent.POJO.IntentNode;
//import com.XYai.myai.RAG.intent.POJO.SubQuestionIntent;
//import jakarta.annotation.Resource;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.stereotype.Component;
//
//import java.time.Duration;
//import java.util.*;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.TimeUnit;
//
//@Slf4j
//@Component
//public class MCPToolSearchChannel implements SearchChannel {
//
//    @Resource
//    private MCPRegistry mcpRegistry;
//
//    @Override
//    public String getName() {
//        return "mcp-tool-channel";
//    }
//
//    @Override
//    public int getPriority() {
//        return 5; // 低于 KB 意图检索优先级，根据需要调整
//    }
//
//    @Override
//    public String getType() {
//        return "mcp-tool";
//    }
//
//    @Override
//    public boolean isEnabled(SearchContext context) {
//        if (context == null || context.getKbIntents() == null) return false;
//        for (SubQuestionIntent s : context.getKbIntents()) {
//            if (s != null && s.getSubIntent() != null) {
//                for (IntentNode node : s.getSubIntent()) {
//                    if (node != null && node.getMcpToolId() != null && !node.getMcpToolId().isBlank()) {
//                        return true;
//                    }
//                }
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public SearchChannelResult search(SearchContext context) {
//        List<RetrievedChunk> chunks = new ArrayList<>();
//        List<CompletableFuture<Void>> futures = new ArrayList<>();
//
//        for (SubQuestionIntent s : context.getKbIntents()) {
//            if (s == null || s.getSubIntent() == null) continue;
//            for (IntentNode node : s.getSubIntent()) {
//                String toolId = node.getMcpToolId();
//                if (toolId == null || toolId.isBlank()) continue;
//
//                Optional<MCPToolExecutor<?, ?>> opt = mcpRegistry.get(toolId);
//                if (opt.isEmpty()) {
//                    log.warn("未注册的 MCP 工具 id={}", toolId);
//                    continue;
//                }
//                MCPToolExecutor<Object, Object> executor = (MCPToolExecutor<Object, Object>) opt.get();
//
//                // 构造工具输入。这里简单使用 context.getQuestion()，可按 node.promptTemplate 等构造更复杂输入
//                Object input = context.getQuestion();
//
//                CompletableFuture<Void> future = executor.execute(input)
//                        .orTimeout(executor.descriptor().timeout().toMillis(), TimeUnit.MILLISECONDS)
//                        .handle((resp, ex) -> {
//                            if (ex != null) {
//                                log.error("MCP tool {} 执行失败: {}", toolId, ex.getMessage());
//                                return null;
//                            }
//                            // 将工具响应封装成 RetrievedChunk
//                            RetrievedChunk rc = RetrievedChunk.builder()
//                                    .id(UUID.randomUUID().toString())
//                                    .collectionName("mcp:" + toolId)
//                                    .content(String.valueOf(resp))
//                                    .score(1.0) // 工具结果可赋予高置信度
//                                    .metadata(Map.of("mcpToolId", toolId, "intentNode", node.getName()))
//                                    .build();
//                            synchronized (chunks) {
//                                chunks.add(rc);
//                            }
//                            return null;
//                        });
//                futures.add(future);
//            }
//        }
//
//        // 等待所有工具调用完成（可根据需要改为异步返回）
//        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
//                .join();
//
//        return SearchChannelResult.builder()
//                .channelName(getName())
//                .chunks(chunks)
//                .metadata(Map.of("toolCount", chunks.size()))
//                .build();
//    }
//}
