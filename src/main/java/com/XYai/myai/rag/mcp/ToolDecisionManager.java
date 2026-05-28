package com.XYai.myai.rag.mcp;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.chat.ModelInvocationService;
import com.XYai.myai.rag.mcp.pojo.McpToolDecision;
import com.XYai.myai.rag.mcp.pojo.ToolProcessorResult;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.user.LoginUserInfoManager;
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
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolDecisionManager {

    private static final String systemMessage = """
            你是工具调用规划师，根据用户问题及对话历史，从可用工具列表中选择必要的工具(可串联)并按调用顺序输出（id从1开始递增）。
            重要规则：
            1. 只有当问题必须依赖工具才能回答时，才选择该工具。
            2. 如果问题可以直接回答，则不要选择任何工具，输出空列表：%s。
            3. 输出必须是合法的JSON数组，不能带markdown格式，不要添加任何解释文字。
            
            示例（需要工具）：%s
            示例（不需要工具）：%s
            """;


    /**
     * 工具触发正则映射。
     * 任何一条正则匹配 query 即视为候选工具。
     * 建议移至配置中心实现热更新，此处为静态示例。
     */
    private static final Map<String, List<Pattern>> TOOL_TRIGGER_PATTERNS =
            new HashMap<>() {{
                put("weather_query", compilePatterns(
                        "天气|温度|下雨|几度|热不热|带伞|明天.*天气|外面.*冷|外面.*热|要穿什么|穿短袖|会不会下雨|空气湿度|紫外线|风力"
                ));
                put("geo_code", compilePatterns("地理编码|地址转坐标|在哪里|定位.*地址"));
                put("reverse_geo_code", compilePatterns("逆向地理|坐标.*地址|这个地方是哪"));
                put("keyword_search", compilePatterns(
                        "搜索|查找|附近|找一找|帮我找|哪里有|推荐.*餐厅|好吃.*哪里|怎么走|带我去"
                ));
                put("around_search", compilePatterns("周边|附近|500米|周围.*有什么|周围.*好吃的"));
                put("enterPrompt", compilePatterns("输入提示|搜索补全|自动补全"));
                put("direction", compilePatterns("导航|怎么走|路线|去.*怎么去|带我去|出行路线"));
                put("calculate_distance", compilePatterns("距离|多远|相隔"));
                put("ipLocation", compilePatterns("IP.*位置|我的IP|IP地址"));
                put("bilibili_search", compilePatterns("B站|bilibili|搜索视频|找视频"));
                put("memery_search", compilePatterns("记忆|我记得|之前说过|搜索记忆"));
                put("file_search", compilePatterns("文件|查找文件|我的文件"));
                put("now_time", compilePatterns("现在几点|当前时间|今天几号|日期|时间"));
            }};
    /**
     * 使用结构化输出专用模型（qwen3.5-122b-a10b, temp=0.1, maxTokens=2000），
     * 确保工具决策能稳定输出合法 JSON，不会因 maxTokens 不足被截断。
     */
    @Resource(name = "structuredOutputModel")
    private ChatModel chatModel;
    @Resource(name = "mcpExecutor")
    private TaskExecutor mcpExecutor;
    @Resource
    private ToolCallbackProvider allToolsProvider;
    @Resource
    private ArgumentExtractor argumentExtractor;
    @Resource
    private ModelInvocationService modelInvocation;
    @Resource
    private McpToolToggleRegistry toolToggleRegistry;

    // ==================== 规则增强：触发词配置 ====================
    private Map<String, ToolCallback> toolCallbackMap = Collections.emptyMap();

    private static List<Pattern> compilePatterns(String regexes) {
        return Arrays.stream(regexes.split("\\|"))
                .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE))
                .toList();
    }

    /**
     * 获取所有已注册的工具回调映射（只读视图）
     */
    public Map<String, ToolCallback> getToolCallbackMap() {
        return Collections.unmodifiableMap(toolCallbackMap);
    }

    @PostConstruct
    public void init() {
        log.info("[MCP_INIT] ====== 开始初始化MCP工具 ======");
        ToolCallback[] callbacksArray = allToolsProvider.getToolCallbacks();
        if (callbacksArray.length == 0) {
            log.warn("[MCP_INIT] ⚠ 未获取到任何工具! allToolsProvider={}, 检查 @ConditionalOnProperty(tool.enabled) 是否开启",
                    allToolsProvider.getClass().getName());
            toolCallbackMap = Collections.emptyMap();
            return;
        }
        log.info("[MCP_INIT] 获取到 {} 个工具回调", callbacksArray.length);
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

    public List<ToolProcessorResult> toolProcessor(String query, LoadSession memorySession, Long conversationId, Long chatMessageId) {
        // 增强点：传入 memorySession 以利用对话历史
        List<McpToolDecision> toolDecisionList = decideByRules(query, memorySession);
        if (toolDecisionList.isEmpty()) {
            toolDecisionList = toolDecision(query, memorySession, conversationId, chatMessageId); // 原有 LLM 决策方法保持不变
        } else {
            log.info("[MCP_PROCESSOR] 规则命中 {} 个工具，跳过 LLM", toolDecisionList.size());
        }

        if (toolDecisionList.isEmpty()) {
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
     * @param query         用户问题
     * @param memorySession 对话记忆，用于构建 prompt 上下文
     * @return 工具执行的顺序和参数
     */
    public List<McpToolDecision> toolDecision(String query, LoadSession memorySession, Long conversationId, Long chatMessageId) {
        Map<String, String> tools = getTools();
        if (tools.isEmpty()) {
            log.warn("没有可用的MCP工具，跳过工具决策");
            return List.of();
        }
        Prompt prompt = getPrompt(query, tools, memorySession);
        log.info("[MCP_DECISION] >>> 调用结构化模型进行工具决策, 可用工具={}, thread={}",
                tools.keySet(), Thread.currentThread().getName());
        long t1 = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long cost = System.currentTimeMillis() - t1;
        log.info("[MCP_DECISION] <<< LLM原始响应(前300字), 耗时={}ms:\n{}", cost,
                rawResponse.length() > 300 ? rawResponse.substring(0, 300) + "..." : rawResponse);
        String cleaned = cleanJsonResponse(rawResponse);
        log.info("[MCP_DECISION] 清理后的JSON: {}", cleaned);
        RagTraceContext.setPhase("MCP决策");
        modelInvocation.saveTokenUseAsync(
                conversationId,
                IdUtil.getSnowflakeNextId(),
                (long) prompt.toString().length(),
                (long) rawResponse.length(),
                LoginUserInfoManager.getUserId(),
                cost,
                chatModel.getDefaultOptions().getModel(), "mcp");
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

    private String cleanJsonResponse(String text) {
        if (text == null || text.isBlank()) return "[]";
        String trimmed = text.trim();
        int jsonStart = trimmed.indexOf('[');
        int jsonEnd = trimmed.lastIndexOf(']');
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            return trimmed.substring(jsonStart, jsonEnd + 1);
        }
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
                .filter(cb -> toolToggleRegistry.isEnabled(cb.getToolDefinition().name()))
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb.getToolDefinition().description()));
    }

    private Prompt getPrompt(String query, Map<String, String> tools, LoadSession memorySession) {
        String needToolsExample = """
                [
                  {"id": 1, "toolName": "geo_code", "arguments": {"address": "北京", "city": "北京"}},
                  {"id": 2, "toolName": "weather_query", "arguments": {"city": "110105"}}
                ]
                """;
        String noToolsExample = "[]";
        String systemMessage = String.format(ToolDecisionManager.systemMessage, noToolsExample, needToolsExample, noToolsExample);
        String historyContext = buildHistoryContext(memorySession);
        String userMessage = String.format(
                "可用工具列表（名称 -> 描述）：\n%s\n\n对话历史：%s\n当前问题：%s",
                tools, historyContext, query);

        return new Prompt(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage));
    }

    private String buildHistoryContext(LoadSession session) {
        if (session == null) {
            return "无历史对话";
        }
        String text = session.getHistoryAsText();
        if (text == null || text.isBlank() || "无".equals(text)) {
            return "无历史对话";
        }
        String[] lines = text.split("\n");
        int maxLines = 20;
        if (lines.length > maxLines) {
            return String.join("\n", Arrays.copyOfRange(lines, lines.length - maxLines, lines.length));
        }
        return text;
    }

    // ==================== 规则匹配增强实现 ====================

    /**
     * 基于规则提取参数并生成工具决策（增强版，带记忆上下文）。
     * 步骤：
     * 1. 特殊意图预处理（如“测试所有mcp功能”）
     * 2. 从对话历史中提取上下文实体（城市、地点等）
     * 3. 通过触发词映射粗筛候选工具
     * 4. 对候选工具逐一提取参数并校验必填项
     * 5. 任何步骤失败（无候选、参数不全）返回空列表，交给 LLM
     */
    private List<McpToolDecision> decideByRules(String query, LoadSession memorySession) {
        // 1. 特殊意图拦截（元测试指令）
        if (isTestAllIntent(query)) {
            log.info("[RULE_DECISION] 检测到“测试所有功能”意图，生成全工具自检决策");
            return buildTestAllDecisions();
        }

        // 2. 从记忆上下文提取实体，用于补全参数
        Map<String, String> contextEntities = extractContextEntities(memorySession);
        log.debug("[RULE_DECISION] 从对话历史提取上下文实体: {}", contextEntities);

        // 3. 触发词粗筛 —— 得到一个候选工具集
        Set<String> candidateTools = getCandidateToolsByTrigger(query);
        if (candidateTools.isEmpty()) {
            log.info("[RULE_DECISION] 无触发词命中，规则无法匹配，交给 LLM");
            return List.of();
        }

        // 4. 对每个候选工具尝试提取并校验
        List<McpToolDecision> decisions = new ArrayList<>();
        int id = 1;
        for (String toolName : candidateTools) {
            // 跳过已禁用的工具
            if (!toolToggleRegistry.isEnabled(toolName)) {
                continue;
            }
            try {
                // 调用 ArgumentExtractor，传入上下文实体以补全缺省参数
                Map<String, Object> args = argumentExtractor.extract(toolName, query, new HashMap<>(contextEntities));
                args = Objects.requireNonNullElse(args, Map.of());

                if (isToolTriggeredByRule(toolName, args)) {
                    McpToolDecision decision = McpToolDecision.builder()
                            .id(id++)
                            .toolName(toolName)
                            .arguments(args)
                            .build();
                    decisions.add(decision);
                    log.debug("[RULE_DECISION] 命中工具: {}，参数: {}", toolName, args);
                } else {
                    log.debug("[RULE_DECISION] 工具 {} 参数不全，跳过。提取参数: {}", toolName, args);
                }
            } catch (Exception e) {
                log.warn("[RULE_DECISION] 工具 {} 参数提取异常，跳过。错误: {}", toolName, e.getMessage());
            }
        }

        if (decisions.isEmpty()) {
            log.info("[RULE_DECISION] 无工具通过规则校验，交给 LLM");
        }
        return decisions;
    }

    /**
     * 判断是否为“测试所有MCP功能”等元意图。
     * 可扩展更多模式。
     */
    private boolean isTestAllIntent(String query) {
        if (query == null) return false;
        String q = query.trim().toLowerCase();
        return q.contains("测试所有") || q.contains("测试全部")
                || q.contains("检查所有") || q.contains("扫描工具")
                || q.equals("mcp 自检") || q.equals("mcp test")
                || q.contains("所有功能") && (q.contains("测") || q.contains("试"));
    }

    /**
     * 为“测试所有功能”生成工具决策。
     * 只选择无副作用、无需外部参数或使用默认参数的工具。
     */
    private List<McpToolDecision> buildTestAllDecisions() {
        // 适合自检的工具：无破坏性，无外部依赖或依赖公共 API
        Set<String> testToolNames = Set.of("now_time", "memery_search", "file_search", "ipLocation");

        List<McpToolDecision> decisions = new ArrayList<>();
        int id = 1;
        for (String name : testToolNames) {
            if (!toolCallbackMap.containsKey(name) || !toolToggleRegistry.isEnabled(name)) {
                continue;
            }
            try {
                // 使用空参数（或默认空上下文）让工具执行自检
                Map<String, Object> args = argumentExtractor.extract(name, "", Map.of());
                args = Objects.requireNonNullElse(args, Map.of());
                decisions.add(McpToolDecision.builder()
                        .id(id++)
                        .toolName(name)
                        .arguments(args)
                        .build());
            } catch (Exception e) {
                log.warn("[RULE_DECISION] 自检工具 {} 参数提取失败，跳过。错误: {}", name, e.getMessage());
            }
        }
        return decisions;
    }

    /**
     * 从对话历史（LoadSession）中提取可能的上下文实体，用于补全工具参数。
     * 当前实现采用简单规则：从历史文本中匹配常见地点、城市名等。
     * 可根据实际需要替换为更精确的 NER 或用户配置（如默认城市）。
     */
    private Map<String, String> extractContextEntities(LoadSession session) {
        if (session == null) return Map.of();
        String history = session.getHistoryAsText();
        if (history == null || history.isBlank()) return Map.of();

        Map<String, String> entities = new HashMap<>();

        // 示例：抓取最后一个提及的城市（简单正则）
        // 实际可接入内部 NER 或者查询用户默认设置
        String lastCity = extractLastMatch(history, "(北京|上海|广州|深圳|杭州|成都|南京|武汉|西安|重庆)");
        if (lastCity != null) {
            entities.put("city", lastCity);
        }

        // 若系统维护了用户默认城市，可在此作为兜底：
        // if (!entities.containsKey("city")) entities.put("city", userSettingService.getDefaultCity());

        return entities.isEmpty() ? Map.of() : entities;
    }

    private String extractLastMatch(String text, String regex) {
        var matcher = Pattern.compile(regex).matcher(text);
        String last = null;
        while (matcher.find()) {
            last = matcher.group();
        }
        return last;
    }

    /**
     * 根据 query 中的触发词，返回可能相关的工具名称集合。
     */
    private Set<String> getCandidateToolsByTrigger(String query) {
        return toolCallbackMap.keySet().stream()
                .filter(tool -> hasTriggerMatch(tool, query))
                .collect(Collectors.toSet());
    }

    /**
     * 判断 query 是否包含某个工具的任一触发正则。
     */
    private boolean hasTriggerMatch(String toolName, String query) {
        List<Pattern> patterns = TOOL_TRIGGER_PATTERNS.get(toolName);
        if (patterns == null || patterns.isEmpty()) {
            // 没有配置触发词的工具默认不参与粗筛，交给 LLM
            return false;
        }
        return patterns.stream().anyMatch(p -> p.matcher(query).find());
    }

    /**
     * 判断某个工具是否被规则成功触发（必填参数均已提供且非空）。
     * 保持原有逻辑并增强：无参工具直接返回 true。
     */
    private boolean isToolTriggeredByRule(String toolName, Map<String, Object> args) {
        Set<String> requiredParams = switch (toolName) {
            case "weather_query" -> Set.of("city");
            case "geo_code" -> Set.of("address");
            case "reverse_geo_code" -> Set.of("location");
            case "keyword_search" -> Set.of("keywords");
            case "around_search" -> Set.of("location");
            case "enterPrompt" -> Set.of("keywords");
            case "direction" -> Set.of("directionType", "origin", "destination");
            case "calculate_distance" -> Set.of("startingPoint", "endingPoint");
            case "ipLocation" -> Set.of("ip");
            case "bilibili_search" -> Set.of("keyword");
            case "memery_search" -> Set.of("expr");
            case "file_search" -> Set.of("file_name");
            case "now_time" -> Set.of(); // 无参数，任何情况都触发
            default -> Set.of();        // 未知工具保守不触发（也可根据实际情况调整）
        };

        for (String param : requiredParams) {
            Object value = args.get(param);
            if (value == null || (value instanceof String && ((String) value).isBlank())) {
                return false;
            }
        }
        return true;
    }
}