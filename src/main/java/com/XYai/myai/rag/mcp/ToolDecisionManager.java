package com.XYai.myai.rag.mcp;


import com.XYai.myai.rag.mcp.POJO.McpToolDecision;
import com.XYai.myai.rag.mcp.POJO.ToolProcessorResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolDecisionManager {

    @Resource
    private ChatModel chatModel;

    @Resource(name = "mcpExecutor")
    private ThreadPoolTaskExecutor mcpExecutor;

    @Resource
    private ToolCallbackProvider allToolsProvider;

    private Map<String, ToolCallback> toolCallbackMap = Collections.emptyMap();

    @PostConstruct
    public void init() {
        // 初始化工具回调映射，供后续按名称快速查找
        ToolCallback[] callbacksArray = allToolsProvider.getToolCallbacks();
        if (callbacksArray.length == 0) {
            log.warn("未获取到任何工具");
            toolCallbackMap = Collections.emptyMap();
            return;
        }
        toolCallbackMap = Arrays.stream(callbacksArray)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                    cb -> cb.getToolDefinition().name() ,
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("工具名重复:{}", existing.getToolDefinition().name());
                            return existing;
                        }
                ));
        log.info("缓存{}个MCP工具", toolCallbackMap.size());
    }


    public List<ToolProcessorResult> toolProcessor(String query) {
        // 获取 bean
        List<McpToolDecision> toolDecisionList = toolDecision(query);
        // 并行处理
        List<CompletableFuture<ToolProcessorResult>> futures = toolDecisionList.stream()
                .sorted(Comparator.comparingInt(McpToolDecision::getId))
                .map(decision -> CompletableFuture.supplyAsync(
                        () -> executeSingleTool(decision),
                        mcpExecutor)
                )
                .toList();
        // 等待执行并返回
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    /**
     * 根据用户的问题使用llm获取mcpTool是否使用和使用顺序
     *
     * @param query 用户问题
     * @return 工具执行的顺序和参数
     */
    public List<McpToolDecision> toolDecision(String query) {
        // 所有tool的名字->描述
        Map<String, String> tools = getTools();
        // prompt的构建
        Prompt prompt = getPrompt(query, tools);
        // 模型call
        String toolDecisionList = chatModel.call(prompt).getResult().getOutput().getText();
        // 解析json返回
        return JSON.parseObject(toolDecisionList, new TypeReference<List<McpToolDecision>>() {});
    }

    private ToolProcessorResult  executeSingleTool(McpToolDecision decision) {
        String toolName = decision.getToolName();
        ToolCallback callback = toolCallbackMap.get(toolName);
        if (callback == null) {
            return ToolProcessorResult.fail(toolName, "工具未注册");
        }
        try {
            // 将参数序列化为 JSON 字符串，由 SpringAI 负责参数绑定
            String inputJson = JSON.toJSONString(decision.getArguments());
            String result = callback.call(inputJson);
            return ToolProcessorResult.success(toolName, result);
        } catch (Exception e) {
            log.error("工具执行失败: {}", toolName, e);
            return ToolProcessorResult.fail(toolName, e.getMessage());
        }
    }


    private Map<String, String> getTools() {
        return toolCallbackMap.values().stream()
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb.getToolDefinition().description()
                ));
    }

    private Prompt getPrompt(String query, Map<String, String> tools) {
        // 需要工具
        String needToolsExample = """
        [
          {"id": 1, "toolName": "geo_code", "arguments": {"address": "北京", "city": "北京"}},
          {"id": 2, "toolName": "weather_query", "arguments": {"city": "110105"}}
        ]
        """;
        // 不需要工具
        String noToolsExample = "[]";
        String systemMessage = String.format("""
        你是工具调用规划师，根据用户问题，从可用工具列表中选择必要的工具并按调用顺序输出（id从1开始递增）。
        重要规则：
        1. 只有当问题必须依赖某个工具才能回答时，才选择该工具。
        2. 如果问题可以直接回答（例如常识、闲聊、已有知识），则不要选择任何工具，输出空列表：%s。
        3. 输出必须是合法的JSON数组，不能带markdown格式，不要添加任何解释文字。
       
        示例（需要工具）：%s
        示例（不需要工具）：%s
        """, noToolsExample, needToolsExample, noToolsExample);
        String userMessage = String.format("问题：%s\n可用工具列表（名称 -> 描述）：%s", query, tools);
        return new Prompt(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage)
        );
    }
}
