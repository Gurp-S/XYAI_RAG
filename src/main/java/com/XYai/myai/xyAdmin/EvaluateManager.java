package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.SystemEvaluateMapper;
import com.XYai.myai.rag.evaluate.pojo.SystemEvaluatePOJO;
import com.XYai.myai.rag.evaluate.service.SystemEvaluateService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/xyAdmin/evaluate")
public class EvaluateManager {

    @Resource
    private SystemEvaluateMapper systemEvaluateMapper;

    @Resource
    private SystemEvaluateService systemEvaluateService;

    /**
     * 分页获取评估记录（支持搜索、排序）
     * @param page 页码
     * @param size 每页大小
     * @param modelName 搜索模型名称
     * @param sortBy 排序字段：rule_score, rerank_score, llm_score, total_score, create_time
     * @param sortOrder asc/desc
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String modelName,
            @RequestParam(defaultValue = "create_time") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {

        QueryWrapper<SystemEvaluatePOJO> wrapper = new QueryWrapper<>();

        // 搜索条件
        if (modelName != null && !modelName.isBlank()) {
            wrapper.like("model_name", modelName);
        }

        // 排序：通过映射兼容驼峰与下划线字段名
        String orderColumn = mapEvaluateSortColumn(sortBy);
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);
        wrapper.orderBy(true, isAsc, orderColumn);

        Page<SystemEvaluatePOJO> pageResult = systemEvaluateMapper.selectPage(
                new Page<>(page, size), wrapper);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", pageResult.getTotal());
        result.put("page", pageResult.getCurrent());
        result.put("size", pageResult.getSize());
        result.put("records", pageResult.getRecords());
        return Result.success(result);
    }

    /**
     * 将前端传入的排序字段映射为数据库列名
     * 支持驼峰命名（如 ruleScore）和下划线命名（如 rule_score）
     */
    private String mapEvaluateSortColumn(String sortBy) {
        if (sortBy == null) return "create_time";
        return switch (sortBy.toLowerCase()) {
            case "rulescore", "rule_score" -> "rule_score";
            case "rerankscore", "rerank_score" -> "rerank_score";
            case "llmscore", "llm_score" -> "llm_score";
            case "overallscore", "overall_score" -> "overall_score";
            case "createtime", "create_time" -> "create_time";
            default -> "create_time";   // 安全兜底
        };
    }

    /**
     * 获取所有模型名称列表（用于搜索下拉）
     */
    @GetMapping("/modelNames")
    public Result<List<String>> getModelNames() {
        List<SystemEvaluatePOJO> records = systemEvaluateMapper.selectList(
                new QueryWrapper<SystemEvaluatePOJO>()
                        .select("DISTINCT model_name")
                        .isNotNull("model_name"));
        List<String> names = records.stream()
                .map(SystemEvaluatePOJO::getModelName)
                .filter(java.util.Objects::nonNull)
                .toList();
        return Result.success(names);
    }

    /**
     * 获取三层评估开关状态
     */
    @GetMapping("/config")
    public Result<Map<String, Object>> getConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("ruleEnabled", systemEvaluateService.isRuleEnabled());
        cfg.put("rerankEnabled", systemEvaluateService.isRerankEnabled());
        cfg.put("llmEnabled", systemEvaluateService.isLlmEnabled());
        return Result.success(cfg);
    }

    /**
     * 更新三层评估开关
     */
    @PostMapping("/config")
    public Result<String> setConfig(
            @RequestParam(required = false) Boolean ruleEnabled,
            @RequestParam(required = false) Boolean rerankEnabled,
            @RequestParam(required = false) Boolean llmEnabled) {
        if (ruleEnabled != null)
            systemEvaluateService.setRuleEnabled(ruleEnabled);
        if (rerankEnabled != null)
            systemEvaluateService.setRerankEnabled(rerankEnabled);
        if (llmEnabled != null)
            systemEvaluateService.setLlmEnabled(llmEnabled);
        log.info("管理员更新评估开关: rule={}, rerank={}, llm={}",
                systemEvaluateService.isRuleEnabled(),
                systemEvaluateService.isRerankEnabled(),
                systemEvaluateService.isLlmEnabled());
        return Result.success("评估配置已更新");
    }

    /**
     * 删除评估记录
     */
    @PostMapping("/delete")
    public Result<String> delete(@RequestParam Long chatMessageId) {
        systemEvaluateMapper.deleteById(chatMessageId);
        return Result.success("已删除");
    }
}
