package com.XYai.myai.xyAdmin;

import com.XYai.myai.security.annotation.AdminOnly;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.rag.chat.ModelHealthStore;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.xyAdmin.mapper.TokenRecordMapper;
import com.XYai.myai.xyAdmin.pojo.TokenRecord;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@AdminOnly
@RestController
@RequestMapping("/xyAdmin/chat")
public class ChatManager {

    @Resource
    private TokenRecordMapper tokenRecordMapper;

    @Resource
    private ChatConversationMapper chatConversationMapper;

    @Resource
    private ModelHealthStore healthStore;

    /**
     * 获取全部 Token 用量总和
     */
    @GetMapping("/token/all")
    public Result<Map<String, Object>> getAllToken() {
        long msgCount = chatConversationMapper.selectCount(null);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();
            wrapper.select("COALESCE(SUM(total_tokens), 0) as total_tokens",
                    "COALESCE(SUM(prompt_tokens), 0) as prompt_tokens",
                    "COALESCE(SUM(completion_tokens), 0) as completion_tokens",
                    "COUNT(DISTINCT conversation_id) as session_count");
            var list = tokenRecordMapper.selectMaps(wrapper);
            if (!list.isEmpty()) {
                Map<String, Object> agg = list.getFirst();
                result.put("totalTokens", agg.getOrDefault("total_tokens", 0));
                result.put("promptTokens", agg.getOrDefault("prompt_tokens", 0));
                result.put("completionTokens", agg.getOrDefault("completion_tokens", 0));
                result.put("sessionCount", agg.getOrDefault("session_count", 0));
            }
        } catch (Exception e) {
            log.warn("Token记录表尚未初始化", e);
            result.put("totalTokens", 0);
            result.put("promptTokens", 0);
            result.put("completionTokens", 0);
            result.put("sessionCount", 0);
        }
        result.put("messageCount", msgCount);
        return Result.success(result);
    }

    /**
     * 获取指定用户的 Token 用量
     */
    @GetMapping("/token/user")
    public Result<Map<String, Object>> getUserToken(@RequestParam Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();
            wrapper.select("COALESCE(SUM(total_tokens), 0) as total_tokens",
                    "COALESCE(SUM(prompt_tokens), 0) as prompt_tokens",
                    "COALESCE(SUM(completion_tokens), 0) as completion_tokens",
                    "COUNT(*) as call_count")
                    .eq("user_id", userId);
            var list = tokenRecordMapper.selectMaps(wrapper);
            if (!list.isEmpty()) {
                Map<String, Object> agg = list.getFirst();
                result.put("totalTokens", agg.getOrDefault("total_tokens", 0));
                result.put("promptTokens", agg.getOrDefault("prompt_tokens", 0));
                result.put("completionTokens", agg.getOrDefault("completion_tokens", 0));
                result.put("callCount", agg.getOrDefault("call_count", 0));
            }
        } catch (Exception e) {
            log.warn("getUserToken Token查询异常", e);
            result.put("totalTokens", 0);
            result.put("promptTokens", 0);
            result.put("completionTokens", 0);
            result.put("callCount", 0);
        }
        QueryWrapper<ChatConversation> cw = new QueryWrapper<>();
        cw.eq("user_id", userId);
        result.put("messageCount", chatConversationMapper.selectCount(cw));
        return Result.success(result);
    }

    /**
     * 获取指定时间段的 Token 用量
     */
    @GetMapping("/token/time")
    public Result<Map<String, Object>> getTimeToken(
            @RequestParam LocalDateTime startTime,
            @RequestParam LocalDateTime endTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();
            wrapper.select("COALESCE(SUM(total_tokens), 0) as total_tokens",
                    "COALESCE(SUM(prompt_tokens), 0) as prompt_tokens",
                    "COALESCE(SUM(completion_tokens), 0) as completion_tokens",
                    "COUNT(*) as call_count")
                    .between("created_at", startTime, endTime);
            var list = tokenRecordMapper.selectMaps(wrapper);
            if (!list.isEmpty()) {
                Map<String, Object> agg = list.get(0);
                result.put("totalTokens", agg.getOrDefault("total_tokens", 0));
                result.put("promptTokens", agg.getOrDefault("prompt_tokens", 0));
                result.put("completionTokens", agg.getOrDefault("completion_tokens", 0));
                result.put("callCount", agg.getOrDefault("call_count", 0));
            }
        } catch (Exception e) {
            log.warn("getTimeToken Token查询异常", e);
            result.put("totalTokens", 0);
            result.put("promptTokens", 0);
            result.put("completionTokens", 0);
            result.put("callCount", 0);
        }
        return Result.success(result);
    }

    /**
     * 获取所有模型的健康状态（JSON格式）
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> getLLMInfo() {
        return Result.success(healthStore.getAllHealthStatus());
    }

    /**
     * 获取 Token 使用统计（按模型分组）
     */
    @GetMapping("/token/byModel")
    public Result<List<Map<String, Object>>> getTokenByModel() {
        try {
            QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();
            wrapper.select("model_name",
                    "COALESCE(SUM(total_tokens), 0) as total_tokens",
                    "COALESCE(SUM(prompt_tokens), 0) as prompt_tokens",
                    "COALESCE(SUM(completion_tokens), 0) as completion_tokens",
                    "COUNT(*) as call_count",
                    "COALESCE(AVG(cost_ms), 0) as avg_cost_ms")
                    .isNotNull("model_name")
                    .groupBy("model_name");
            return Result.success(tokenRecordMapper.selectMaps(wrapper));
        } catch (Exception e) {
            log.warn("Token分组查询异常", e);
            return Result.success(java.util.List.of());
        }
    }

    /**
     * 分页获取 Token 记录详情
     * @param page 页码
     * @param size 每页大小
     * @param modelName 搜索模型名称
     * @param userId 搜索用户ID
     * @param callType 搜索调用类型
     * @param sortBy 排序字段：total_tokens, cost_ms, created_at
     * @param sortOrder asc/desc
     */
    @GetMapping("/token/records")
    public Result<Map<String, Object>> getTokenRecordsPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String modelName,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String callType,
            @RequestParam(defaultValue = "created_at") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {

        QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();

        // 搜索条件
        if (modelName != null && !modelName.isBlank()) {
            wrapper.like("model_name", modelName);
        }
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        if (callType != null && !callType.isBlank()) {
            wrapper.eq("call_type", callType);
        }

        // 排序（兼容驼峰与下划线）
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        String column = resolveSortColumn(sortBy);
        wrapper.orderBy(true, isAsc, column);

        Page<TokenRecord> pageResult = tokenRecordMapper.selectPage(
                new Page<>(page, size), wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        result.put("records", pageResult.getRecords());
        return Result.success(result);
    }

    /**
     * 将前端排序字段映射为数据库列名，兼容驼峰与下划线
     */
    private String resolveSortColumn(String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "totaltokens", "total_tokens" -> "total_tokens";
            case "prompttokens", "prompt_tokens" -> "prompt_tokens";
            case "completiontokens", "completion_tokens" -> "completion_tokens";
            case "costms", "cost_ms" -> "cost_ms";
            case "createdat", "created_at" -> "created_at";
            default -> "created_at";   // 安全兜底
        };
    }

    /**
     * 获取模型名称列表（用于搜索下拉）
     */
    @GetMapping("/modelNames")
    public Result<List<String>> getModelNames() {
        try {
            QueryWrapper<TokenRecord> wrapper = new QueryWrapper<>();
            wrapper.select("DISTINCT model_name").isNotNull("model_name");
            List<TokenRecord> records = tokenRecordMapper.selectList(wrapper);
            List<String> names = records.stream()
                    .map(TokenRecord::getModelName)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return Result.success(names);
        } catch (Exception e) {
            log.warn("获取模型名称列表失败", e);
            return Result.success(java.util.List.of());
        }
    }
}
