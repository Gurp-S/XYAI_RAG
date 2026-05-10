package com.XYai.myai.rag.mcp;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.mcp.pojo.McpToolDecision;
import com.XYai.myai.rag.mcp.pojo.ToolProcessorResult;
import com.XYai.myai.rag.memory.pojo.LoadSession;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolDecisionManager {

    /**
     * 使用结构化输出专用模型（qwen3.5-122b-a10b, temp=0.1, maxTokens=2000），
     * 确保工具决策能稳定输出合法 JSON，不会因 maxTokens 不足被截断。
     */
    @Resource(name = "structuredOutputModel")
    private ChatModel chatModel;

    @Resource(name = "mcpExecutor")
    private ThreadPoolTaskExecutor mcpExecutor;

    @Resource
    private ToolCallbackProvider allToolsProvider;

    @Resource
    private ArgumentExtractor argumentExtractor;

    private Map<String, ToolCallback> toolCallbackMap = Collections.emptyMap();

    @PostConstruct
    public void init() {
        // 初始化工具回调映射，供后续按名称快速查找
        log.info("[MCP_INIT] ====== 开始初始化MCP工具 ======");
        ToolCallback[] callbacksArray = allToolsProvider.getToolCallbacks();
        if (callbacksArray.length == 0) {
            log.warn("[MCP_INIT] ⚠ 未获取到任何工具! allToolsProvider={}, 检查 @ConditionalOnProperty(tool.enabled) 是否开启",
                    allToolsProvider.getClass().getName());
            toolCallbackMap = Collections.emptyMap();
            return;
        }
        log.info("[MCP_INIT] 获取到 {} 个工具回调", callbacksArray.length);
        for (ToolCallback cb : callbacksArray) {
            if (cb != null) {
                log.info("[MCP_INIT]   工具: name='{}', desc='{}'",
                        cb.getToolDefinition().name(),
                        cb.getToolDefinition().description());
            }
        }
        toolCallbackMap = Arrays.stream(callbacksArray)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("工具名重复:{}", existing.getToolDefinition().name());
                            return existing;
                        }));
        log.info("[MCP_INIT] ====== 缓存完成, 共 {} 个MCP工具, 工具名列表: {} ======",
                toolCallbackMap.size(), toolCallbackMap.keySet());
    }

    @RagTraceNode(name = "MCP工具调用", type = "MCP工具")
    public List<ToolProcessorResult> toolProcessor(String query, LoadSession memorySession) {
        // 先用规则，失败则 LLM
        List<McpToolDecision> toolDecisionList = decideByRules(query);
        if (toolDecisionList.isEmpty()) {
            toolDecisionList = toolDecision(query,memorySession);  // 原有 LLM 决策方法保持不变
        } else {
            log.info("[MCP_PROCESSOR] 规则命中 {} 个工具，跳过 LLM", toolDecisionList.size());
        }

        if (toolDecisionList.isEmpty()) {
            return List.of();
        }

        // 获取 bean
        long t1 = System.currentTimeMillis();

        if (toolDecisionList.isEmpty()) {
            log.info("[MCP_PROCESSOR] 无工具决策，直接返回空列表");
            return List.of();
        }

        // 并行处理
        List<CompletableFuture<ToolProcessorResult>> futures = toolDecisionList.stream()
                .sorted(Comparator.comparingInt(McpToolDecision::getId))
                .map(decision -> CompletableFuture.supplyAsync(
                        () -> executeSingleTool(decision),
                        mcpExecutor))
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
    public List<McpToolDecision> toolDecision(String query , LoadSession memorySession) {
        // 所有tool的名字->描述
        Map<String, String> tools = getTools();
        if (tools.isEmpty()) {
            log.warn("没有可用的MCP工具，跳过工具决策");
            return List.of();
        }
        // prompt的构建
        Prompt prompt = getPrompt(query, tools,memorySession);
        log.info("[MCP_DECISION] >>> 调用结构化模型进行工具决策, 可用工具={}, thread={}",
                tools.keySet(), Thread.currentThread().getName());
        // 模型call
        long t1 = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long cost = System.currentTimeMillis() - t1;
        log.info("[MCP_DECISION] <<< LLM原始响应(前300字), 耗时={}ms:\n{}", cost,
                rawResponse.length() > 300 ? rawResponse.substring(0, 300) + "..." : rawResponse);
        // 清理 LLM 返回内容：去掉 markdown 代码块标记和前后文本
        String cleaned = cleanJsonResponse(rawResponse);
        log.info("[MCP_DECISION] 清理后的JSON: {}", cleaned);
        // 解析json返回
        try {
            List<McpToolDecision> decisions = JSON.parseObject(cleaned, new TypeReference<List<McpToolDecision>>() {
            });
            log.info("[MCP_DECISION] 解析成功, 共 {} 个决策", decisions.size());
            return decisions;
        } catch (Exception e) {
            log.error("[MCP_DECISION] ⚠ 解析LLM返回的工具决策JSON失败! raw(完整)={}, cleaned={}", rawResponse, cleaned, e);
            return List.of();
        }
    }

    /**
     * 从LLM返回的文本中提取纯JSON数组。
     * 处理情况：
     * 1. markdown代码块包裹：```json [...] ``` 或 ``` [...] ```
     * 2. LLM在JSON前后添加了思考文字
     */
    private String cleanJsonResponse(String text) {
        if (text == null || text.isBlank()) {
            return "[]";
        }
        String trimmed = text.trim();
        // 尝试提取 markdown 代码块中的内容
        int jsonStart = trimmed.indexOf('[');
        int jsonEnd = trimmed.lastIndexOf(']');
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }
        // 尝试提取 {} 包裹的对象（不期望但兜底）
        jsonStart = trimmed.indexOf('{');
        jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }
        return "[]";
    }

    private ToolProcessorResult executeSingleTool(McpToolDecision decision) {
        String toolName = decision.getToolName();
        log.info("[MCP_EXEC] >>> 执行单个工具: toolName='{}', thread={}", toolName, Thread.currentThread().getName());
        ToolCallback callback = toolCallbackMap.get(toolName);
        if (callback == null) {
            log.warn("[MCP_EXEC] ⚠ 工具'{}'未注册, 已注册工具: {}", toolName, toolCallbackMap.keySet());
            return ToolProcessorResult.fail(toolName, "工具未注册");
        }
        try {
            // 将参数序列化为 JSON 字符串，由 SpringAI 负责参数绑定
            String inputJson = JSON.toJSONString(decision.getArguments());
            log.info("[MCP_EXEC] 调用工具: {}, inputJson={}", toolName, inputJson);
            long t1 = System.currentTimeMillis();
            String result = callback.call(inputJson);
            log.info("[MCP_EXEC] <<< 工具'{}'执行完成, 耗时={}ms, result预览={}",
                    toolName, System.currentTimeMillis() - t1,
                    result != null && result.length() > 100 ? result.substring(0, 100) + "..." : result);
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
                        cb -> cb.getToolDefinition().description()));
    }

    private Prompt getPrompt(String query, Map<String, String> tools, LoadSession memorySession) {
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
            你是工具调用规划师，根据用户问题及对话历史，从可用工具列表中选择必要的工具(可串联)并按调用顺序输出（id从1开始递增）。
            重要规则：
            1. 只有当问题必须依赖工具才能回答时，才选择该工具。
            2. 如果问题可以直接回答，则不要选择任何工具，输出空列表：%s。
            3. 输出必须是合法的JSON数组，不能带markdown格式，不要添加任何解释文字。

            示例（需要工具）：%s
            示例（不需要工具）：%s
            """, noToolsExample, needToolsExample, noToolsExample);

        // 提取历史对话文本（复用 LoadSession 的方法，外加长度控制）
        String historyContext = buildHistoryContext(memorySession);

        String userMessage = String.format(
                "对话历史：\n%s\n\n当前问题：%s\n可用工具列表（名称 -> 描述）：%s",
                historyContext, query, tools);

        return new Prompt(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage));
    }

    /**
     * 构建历史上下文文本，限制长度，避免 Prompt 爆炸
     */
    private String buildHistoryContext(LoadSession session) {
        if (session == null) {
            return "无历史对话";
        }
        String text = session.getHistoryAsText();
        if (text == null || text.isBlank() || "无".equals(text)) {
            return "无历史对话";
        }
        // 如果历史过长，只保留最后 20 行（约 10 轮对话）
        String[] lines = text.split("\n");
        int maxLines = 20;
        if (lines.length > maxLines) {
            return String.join("\n", Arrays.copyOfRange(lines, lines.length - maxLines, lines.length));
        }
        return text;
    }

    /**
     * 基于规则提取参数并生成工具决策。
     * 遍历所有工具，若工具所需的必填参数能从 query 中提取出来，则视为命中。
     * 返回按提取顺序排列的决策列表。
     */
    private List<McpToolDecision> decideByRules(String query) {
        List<McpToolDecision> decisions = new ArrayList<>();
        int id = 1;

        // 获取所有工具名称（现在注册在 toolCallbackMap 中）
        Set<String> toolNames = toolCallbackMap.keySet();

        for (String toolName : toolNames) {
            // 调用 ArgumentExtractor 尝试提取参数
            Map<String, Object> args = argumentExtractor.extract(toolName, query, Collections.emptyMap());

            // 检查是否满足“规则命中”条件：必填参数均非空
            if (isToolTriggeredByRule(toolName, args)) {
                McpToolDecision decision = McpToolDecision.builder()
                        .id(id++)
                        .toolName(toolName)
                        .arguments(args)
                        .build();
                decisions.add(decision);
                log.debug("[RULE_DECISION] 命中工具: {}，参数: {}", toolName, args);
            }
        }

        if (decisions.isEmpty()) {
            log.info("[RULE_DECISION] 无工具被规则命中");
        }
        return decisions;
    }

    /**
     * 判断某个工具是否被规则成功触发。
     * 定义“必填参数”规则：工具名称已知，这里针对不同工具定义必须非空的参数列表。
     * 如果工具不在白名单中，保守返回 false（避免错误触发）。
     */
    private boolean isToolTriggeredByRule(String toolName, Map<String, Object> args) {
        // 针对你的每个工具定义必填参数集
        Set<String> requiredParams = switch (toolName) {
            case "weather_query"        -> Set.of("city");
            case "geo_code"             -> Set.of("address");
            case "reverse_geo_code"     -> Set.of("location");
            case "keyword_search"       -> Set.of("keywords");
            case "around_search"        -> Set.of("location");
            case "enterPrompt"          -> Set.of("keywords");
            case "direction"            -> Set.of("directionType", "origin", "destination");
            case "calculate_distance"   -> Set.of("startingPoint", "endingPoint");
            case "ipLocation"           -> Set.of("ip");
            case "bilibili_search"      -> Set.of("keyword");
            case "memery_search"        -> Set.of("expr");
            case "file_search"          -> Set.of("file_name");
            case "now_time"             -> Set.of(); // 无参数，任何情况都触发
            default                     -> Set.of(); // 未知工具保守不触发
        };

        // 检查每个必填参数是否都存在且非空字符串
        for (String param : requiredParams) {
            Object value = args.get(param);
            if (value == null || (value instanceof String && ((String) value).isBlank())) {
                return false;
            }
        }
        return true;
    }
}
