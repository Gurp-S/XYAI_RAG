package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.CacheConfig;
import com.XYai.myai.config.ConfigPersistence;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
import com.XYai.myai.rag.etlpipeline.pojo.PipelineProperties;
import com.XYai.myai.rag.etlpipeline.pojo.UploadProperties;
import com.XYai.myai.rag.evaluate.pojo.EvaluateProperties;
import com.XYai.myai.rag.intent.pojo.IntentProperties;
import com.XYai.myai.rag.mcp.ToolProperties;
import com.XYai.myai.rag.memory.pojo.MemoryProperties;
import com.XYai.myai.rag.rewrite.pojo.RewriterProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;

/**
 * RAG 配置管理后台
 * 提供运行时查看和更新各模块配置的能力
 */
@Slf4j
@RestController
@RequestMapping("/xyAdmin/rag/properties")
public class RagPropertiesManager {

    @Resource
    private PipelineProperties pipelineProperties;

    @Resource
    private UploadProperties uploadProperties;

    @Resource
    private IntentProperties intentProperties;

    @Resource
    private MemoryProperties memoryProperties;

    @Resource
    private RetrievalProperties retrievalProperties;

    @Resource
    private RewriterProperties rewriterProperties;

    @Resource
    private ModelRouterProperties routerProperties;

    @Resource
    private EvaluateProperties evaluateProperties;

    @Resource
    private CacheConfig cacheConfig;

    @Resource
    private ToolProperties toolProperties;

    @Resource
    private ConfigPersistence configPersistence;


    /**
     * 获取所有 RAG 相关配置（含中文描述）
     */
    @GetMapping
    public Result<Map<String, Object>> getAllProperties() {
        Map<String, Object> all = new LinkedHashMap<>();

        // Pipeline (管道配置)
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("enricherEnable", pipelineProperties.getEnricherEnable());
        pipeline.put("enricherQuestionEnable", pipelineProperties.getEnricherQuestionEnable());
        pipeline.put("enricherTriplesEnable", pipelineProperties.getEnricherTriplesEnable());
        pipeline.put("defaultMaxParseChars", pipelineProperties.getDefaultMaxParseChars());
        pipeline.put("defaultChunkSize", pipelineProperties.getMaxNumChunks());
        pipeline.put("minChunkSizeChars", pipelineProperties.getMinChunkSizeChars());
        pipeline.put("pdfChunkSize", pipelineProperties.getPdfChunkSize());
        pipeline.put("pdfOverlapSize", pipelineProperties.getPdfOverlapSize());
        pipeline.put("wordChunkSize", pipelineProperties.getWordChunkSize());
        pipeline.put("wordOverlapSize", pipelineProperties.getWordOverlapSize());
        pipeline.put("textChunkSize", pipelineProperties.getTextChunkSize());
        pipeline.put("textOverlapSize", pipelineProperties.getTextOverlapSize());
        pipeline.put("tableChunkSize", pipelineProperties.getTableChunkSize());
        pipeline.put("tableOverlapSize", pipelineProperties.getTableOverlapSize());
        pipeline.put("codeChunkSize", pipelineProperties.getCodeChunkSize());
        pipeline.put("codeOverlapSize", pipelineProperties.getCodeOverlapSize());
        pipeline.put("htmlChunkSize", pipelineProperties.getHtmlChunkSize());
        pipeline.put("htmlOverlapSize", pipelineProperties.getHtmlOverlapSize());
        Map<String, String> pipelineLabels = new HashMap<>();
        pipelineLabels.put("enricherEnable", "语义增强开关");
        pipelineLabels.put("enricherQuestionEnable", "问题增强");
        pipelineLabels.put("enricherTriplesEnable", "三元组增强");
        pipelineLabels.put("defaultMaxParseChars", "最大解析字符数");
        pipelineLabels.put("defaultChunkSize", "默认分块大小");
        pipelineLabels.put("defaultOverlapSize", "默认重叠大小");
        pipelineLabels.put("pdfChunkSize", "PDF分块大小");
        pipelineLabels.put("pdfOverlapSize", "PDF重叠大小");
        pipelineLabels.put("wordChunkSize", "Word分块大小");
        pipelineLabels.put("wordOverlapSize", "Word重叠大小");
        pipelineLabels.put("textChunkSize", "文本分块大小");
        pipelineLabels.put("textOverlapSize", "文本重叠大小");
        pipelineLabels.put("tableChunkSize", "表格分块大小");
        pipelineLabels.put("tableOverlapSize", "表格重叠大小");
        pipelineLabels.put("codeChunkSize", "代码分块大小");
        pipelineLabels.put("codeOverlapSize", "代码重叠大小");
        pipelineLabels.put("htmlChunkSize", "HTML分块大小");
        pipelineLabels.put("htmlOverlapSize", "HTML重叠大小");
        all.put("_labels_pipeline", pipelineLabels);

        // Upload
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("upLoadEnabled", uploadProperties.getUpLoadEnabled());
        upload.put("ragEnabled", uploadProperties.getRagEnabled());
        upload.put("ossEnabled", uploadProperties.getOssEnabled());
        upload.put("maxInMemoryFileBytes", uploadProperties.getMaxInMemoryFileBytes());
        upload.put("maxErrorMessageLength", uploadProperties.getMaxErrorMessageLength());
        all.put("_labels_upload", Map.of(
                "upLoadEnabled", "上传功能",
                "ragEnabled", "RAG入库",
                "ossEnabled", "OSS上传",
                "maxInMemoryFileBytes", "内存阈值",
                "maxErrorMessageLength", "退避基数"));
        all.put("upload", upload);

        // Intent
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("updateIntentEnabled", intentProperties.getUpdateIntentEnabled());
        intent.put("intentEnabled", intentProperties.getIntentEnabled());
        intent.put("DBEnabled", intentProperties.getDBEnabled());
        intent.put("redisEnabled", intentProperties.getRedisEnabled());
        intent.put("vectorEnabled", intentProperties.getVectorEnabled());
        intent.put("cacheTreeToRedisEnabled", intentProperties.getCacheTreeToRedisEnabled());
        all.put("_labels_intent", Map.of(
                "updateIntentEnabled", "意图更新",
                "intentEnabled", "意图识别",
                "DBEnabled", "数据库意图",
                "redisEnabled", "Redis意图",
                "vectorEnabled", "向量意图",
                "cacheTreeToRedisEnabled", "树缓存Redis"));
        all.put("intent", intent);

        // Memory
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("historyKeepTurns", memoryProperties.getHistoryKeepTurns());
        memory.put("summaryStartTurns", memoryProperties.getSummaryStartTurns());
        memory.put("summaryMaxChars", memoryProperties.getSummaryMaxChars());
        memory.put("summaryEnabled", memoryProperties.getSummaryEnabled());
        all.put("_labels_memory", Map.of(
                "historyKeepTurns", "保留对话轮数",
                "summaryStartTurns", "触发摘要轮数",
                "summaryMaxChars", "摘要最大字符",
                "summaryEnabled", "摘要压缩"));
        all.put("memory", memory);

        // Retrieval (检索配置)
        Map<String, Object> retrieval = new LinkedHashMap<>();
        retrieval.put("rerankLLM", retrievalProperties.getRerankLLM());
        retrieval.put("commonScoreThreshold", retrievalProperties.getCommonScoreThreshold());
        retrieval.put("highScoreThreshold", retrievalProperties.getHighScoreThreshold());
        retrieval.put("lowScoreThreshold", retrievalProperties.getLowScoreThreshold());
        retrieval.put("vectorOnlyThreshold", retrievalProperties.getVectorOnlyThreshold());
        retrieval.put("minContentLength", retrievalProperties.getMinContentLength());
        retrieval.put("maxContentLength", retrievalProperties.getMaxContentLength());
        all.put("_labels_retrieval", Map.of(
                "rerankLLM", "LLM重排",
                "commonScoreThreshold", "通用分阈值",
                "highScoreThreshold", "高分阈值",
                "lowScoreThreshold", "低分阈值",
                "vectorOnlyThreshold", "纯向量阈值",
                "minContentLength", "最小内容长度",
                "maxContentLength", "最大内容长度"));
        all.put("retrieval", retrieval);

        // Rewriter
        Map<String, Object> rewriter = new LinkedHashMap<>();
        rewriter.put("rewriterMinChars", rewriterProperties.getRewriterMinChars());
        rewriter.put("rewriterEnabled", rewriterProperties.getRewriterEnabled());
        all.put("_labels_rewriter", Map.of(
                "rewriterMinChars", "重写最小字符数",
                "rewriterEnabled", "查询重写"));
        all.put("rewriter", rewriter);

        // Router
        Map<String, Object> router = new LinkedHashMap<>();
        router.put("strategy", routerProperties.getStrategy());
        router.put("firstPacketTimeout", routerProperties.getFirstPacketTimeout());
        router.put("cascadeTimeout", routerProperties.getCascadeTimeout());
        router.put("enableFirstPacketDetect", routerProperties.isEnableFirstPacketDetect());
        all.put("_labels_router", Map.of(
                "strategy", "路由策略",
                "firstPacketTimeout", "首包超时(ms)",
                "cascadeTimeout", "级联超时(ms)",
                "enableFirstPacketDetect", "首包探测"));
        all.put("router", router);

        // Cache (缓存配置)
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("dictTime", cacheConfig.getDictTime());
        cache.put("dictCount", cacheConfig.getDictCount());
        cache.put("shortTime", cacheConfig.getShortTime());
        cache.put("shortCount", cacheConfig.getShortCount());
        cache.put("defaultTime", cacheConfig.getDefaultTime());
        cache.put("defaultCount", cacheConfig.getDefaultCount());
        all.put("_labels_cache", Map.of(
                "dictTime", "词典缓存时间(min)",
                "dictCount", "词典缓存数量",
                "shortTime", "短时缓存时间(min)",
                "shortCount", "短时缓存数量",
                "defaultTime", "默认缓存时间(min)",
                "defaultCount", "默认缓存数量"));
        all.put("cache", cache);

        // Evaluate (评估配置)
        Map<String, Object> evaluate = new LinkedHashMap<>();
        evaluate.put("LLMEnable", evaluateProperties.getLLMEnable());
        evaluate.put("rerankEnable", evaluateProperties.getRerankEnable());
        evaluate.put("ruleEnable", evaluateProperties.getRuleEnable());
        all.put("_labels_evaluate", Map.of(
                "LLMEnable", "LLM评估",
                "rerankEnable", "重排评估",
                "ruleEnable", "规则评估"));
        all.put("evaluate", evaluate);

        // Tool (MCP工具配置)
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("enabled", toolProperties.isEnabled());
        tool.put("amapEnabled", toolProperties.getAmap().isEnabled());
        tool.put("bilibiliEnabled", toolProperties.getBilibili().isEnabled());
        tool.put("commonToolsEnabled", toolProperties.getCommonTools().isEnabled());
        all.put("_labels_tool", Map.of(
                "enabled", "工具总开关",
                "amapEnabled", "高德地图",
                "bilibiliEnabled", "B站工具",
                "commonToolsEnabled", "通用工具"));
        all.put("tool", tool);

        return Result.success(all);
    }

    /**
     * 更新指定模块的配置项
     * 通过通用 Map 方式批量更新运行时属性
     */
    @PostMapping("/update")
    public Result<String> updateProperties(@RequestParam String module, @RequestBody Map<String, Object> props) {
        try {
            switch (module) {
                case "pipeline" -> {
                    updatePipelineProps(props);
                    configPersistence.save("pipeline", pipelineProperties);
                }
                case "upload" -> {
                    updateUploadProps(props);
                    configPersistence.save("upload", uploadProperties);
                }
                case "intent" -> {
                    updateIntentProps(props);
                    configPersistence.save("intent", intentProperties);
                }
                case "memory" -> {
                    updateMemoryProps(props);
                    configPersistence.save("memory", memoryProperties);
                }
                case "retrieval" -> {
                    updateRetrievalProps(props);
                    configPersistence.save("retrieval", retrievalProperties);
                }
                case "rewriter" -> {
                    updateRewriterProps(props);
                    configPersistence.save("rewriter", rewriterProperties);
                }
                case "router" -> {
                    updateRouterProps(props);
                    configPersistence.save("router", routerProperties);
                }
                case "cache" -> {
                    updateCacheProps(props);
                    configPersistence.save("cache", cacheConfig);
                }
                case "evaluate" -> {
                    updateEvaluateProps(props);
                    configPersistence.save("evaluate", evaluateProperties);
                }
                case "tool" -> {
                    updateToolProps(props);
                    configPersistence.save("tool", toolProperties);
                }
                default -> {
                    return Result.error(400, "未知模块: " + module);
                }
            }
            log.info("管理员更新了RAG配置: module={}, props={}", module, props);
            return Result.success("模块 [" + module + "] 配置已更新");
        } catch (Exception e) {
            log.error("更新RAG配置失败: module={}", module, e);
            return Result.error(500, "更新失败：" + e.getMessage());
        }
    }

    private void updatePipelineProps(Map<String, Object> props) {
        if (props.containsKey("enricherEnable"))
            pipelineProperties.setEnricherEnable(boolVal(props.get("enricherEnable")));
        if (props.containsKey("enricherQuestionEnable"))
            pipelineProperties.setEnricherQuestionEnable(boolVal(props.get("enricherQuestionEnable")));
        if (props.containsKey("enricherTriplesEnable"))
            pipelineProperties.setEnricherTriplesEnable(boolVal(props.get("enricherTriplesEnable")));
        if (props.containsKey("defaultMaxParseChars"))
            pipelineProperties.setDefaultMaxParseChars(intVal(props.get("defaultMaxParseChars")));
        if (props.containsKey("defaultChunkSize"))
            pipelineProperties.setMaxNumChunks(intVal(props.get("defaultChunkSize")));
        if (props.containsKey("minChunkSizeChars"))
            pipelineProperties.setMinChunkSizeChars(intVal(props.get("minChunkSizeChars")));
        if (props.containsKey("pdfChunkSize"))
            pipelineProperties.setPdfChunkSize(intVal(props.get("pdfChunkSize")));
        if (props.containsKey("pdfOverlapSize"))
            pipelineProperties.setPdfOverlapSize(intVal(props.get("pdfOverlapSize")));
        if (props.containsKey("wordChunkSize"))
            pipelineProperties.setWordChunkSize(intVal(props.get("wordChunkSize")));
        if (props.containsKey("wordOverlapSize"))
            pipelineProperties.setWordOverlapSize(intVal(props.get("wordOverlapSize")));
        if (props.containsKey("textChunkSize"))
            pipelineProperties.setTextChunkSize(intVal(props.get("textChunkSize")));
        if (props.containsKey("textOverlapSize"))
            pipelineProperties.setTextOverlapSize(intVal(props.get("textOverlapSize")));
        if (props.containsKey("tableChunkSize"))
            pipelineProperties.setTableChunkSize(intVal(props.get("tableChunkSize")));
        if (props.containsKey("tableOverlapSize"))
            pipelineProperties.setTableOverlapSize(intVal(props.get("tableOverlapSize")));
        if (props.containsKey("codeChunkSize"))
            pipelineProperties.setCodeChunkSize(intVal(props.get("codeChunkSize")));
        if (props.containsKey("codeOverlapSize"))
            pipelineProperties.setCodeOverlapSize(intVal(props.get("codeOverlapSize")));
        if (props.containsKey("htmlChunkSize"))
            pipelineProperties.setHtmlChunkSize(intVal(props.get("htmlChunkSize")));
        if (props.containsKey("htmlOverlapSize"))
            pipelineProperties.setHtmlOverlapSize(intVal(props.get("htmlOverlapSize")));
    }

    private void updateUploadProps(Map<String, Object> props) {
        if (props.containsKey("upLoadEnabled"))
            uploadProperties.setUpLoadEnabled(boolVal(props.get("upLoadEnabled")));
        if (props.containsKey("ragEnabled"))
            uploadProperties.setRagEnabled(boolVal(props.get("ragEnabled")));
        if (props.containsKey("ossEnabled"))
            uploadProperties.setOssEnabled(boolVal(props.get("ossEnabled")));
    }

    private void updateIntentProps(Map<String, Object> props) {
        if (props.containsKey("updateIntentEnabled"))
            intentProperties.setUpdateIntentEnabled(boolVal(props.get("updateIntentEnabled")));
        if (props.containsKey("intentEnabled"))
            intentProperties.setIntentEnabled(boolVal(props.get("intentEnabled")));
        if (props.containsKey("DBEnabled"))
            intentProperties.setDBEnabled(boolVal(props.get("DBEnabled")));
        if (props.containsKey("redisEnabled"))
            intentProperties.setRedisEnabled(boolVal(props.get("redisEnabled")));
        if (props.containsKey("vectorEnabled"))
            intentProperties.setVectorEnabled(boolVal(props.get("vectorEnabled")));
        if (props.containsKey("cacheTreeToRedisEnabled"))
            intentProperties.setCacheTreeToRedisEnabled(boolVal(props.get("cacheTreeToRedisEnabled")));
    }

    private void updateMemoryProps(Map<String, Object> props) {
        if (props.containsKey("historyKeepTurns"))
            memoryProperties.setHistoryKeepTurns(intVal(props.get("historyKeepTurns")));
        if (props.containsKey("summaryStartTurns"))
            memoryProperties.setSummaryStartTurns(intVal(props.get("summaryStartTurns")));
        if (props.containsKey("summaryMaxChars"))
            memoryProperties.setSummaryMaxChars(intVal(props.get("summaryMaxChars")));
        if (props.containsKey("summaryEnabled"))
            memoryProperties.setSummaryEnabled(boolVal(props.get("summaryEnabled")));
    }

    private void updateRetrievalProps(Map<String, Object> props) {
        if (props.containsKey("rerankLLM"))
            retrievalProperties.setRerankLLM(boolVal(props.get("rerankLLM")));
        if (props.containsKey("commonScoreThreshold"))
            retrievalProperties.setCommonScoreThreshold(doubleVal(props.get("commonScoreThreshold")));
        if (props.containsKey("highScoreThreshold"))
            retrievalProperties.setHighScoreThreshold(doubleVal(props.get("highScoreThreshold")));
        if (props.containsKey("lowScoreThreshold"))
            retrievalProperties.setLowScoreThreshold(doubleVal(props.get("lowScoreThreshold")));
        if (props.containsKey("vectorOnlyThreshold"))
            retrievalProperties.setVectorOnlyThreshold(doubleVal(props.get("vectorOnlyThreshold")));
        if (props.containsKey("minContentLength"))
            retrievalProperties.setMinContentLength(intVal(props.get("minContentLength")));
        if (props.containsKey("maxContentLength"))
            retrievalProperties.setMaxContentLength(intVal(props.get("maxContentLength")));
    }

    private void updateRewriterProps(Map<String, Object> props) {
        if (props.containsKey("rewriterMinChars"))
            rewriterProperties.setRewriterMinChars(intVal(props.get("rewriterMinChars")));
        if (props.containsKey("rewriterEnabled"))
            rewriterProperties.setRewriterEnabled(boolVal(props.get("rewriterEnabled")));
    }

    private void updateRouterProps(Map<String, Object> props) {
        // Router properties are currently backed by hardcoded defaults in ModelRouterProperties.
        // This method accepts update requests to prevent frontend errors.
        if (!props.isEmpty()) {
            log.info("Router properties update requested (currently using hardcoded defaults): {}", props);
        }
    }

    private void updateCacheProps(Map<String, Object> props) {
        if (props.containsKey("dictTime"))
            cacheConfig.setDictTime(intVal(props.get("dictTime")));
        if (props.containsKey("dictCount"))
            cacheConfig.setDictCount(intVal(props.get("dictCount")));
        if (props.containsKey("shortTime"))
            cacheConfig.setShortTime(intVal(props.get("shortTime")));
        if (props.containsKey("shortCount"))
            cacheConfig.setShortCount(intVal(props.get("shortCount")));
        if (props.containsKey("defaultTime"))
            cacheConfig.setDefaultTime(intVal(props.get("defaultTime")));
        if (props.containsKey("defaultCount"))
            cacheConfig.setDefaultCount(intVal(props.get("defaultCount")));
    }

    private void updateEvaluateProps(Map<String, Object> props) {
        if (props.containsKey("LLMEnable"))
            evaluateProperties.setLLMEnable(boolVal(props.get("LLMEnable")));
        if (props.containsKey("rerankEnable"))
            evaluateProperties.setRerankEnable(boolVal(props.get("rerankEnable")));
        if (props.containsKey("ruleEnable"))
            evaluateProperties.setRuleEnable(boolVal(props.get("ruleEnable")));
    }

    private void updateToolProps(Map<String, Object> props) {
        if (props.containsKey("enabled"))
            toolProperties.setEnabled(boolVal(props.get("enabled")));
        if (props.containsKey("amapEnabled"))
            toolProperties.getAmap().setEnabled(boolVal(props.get("amapEnabled")));
        if (props.containsKey("bilibiliEnabled"))
            toolProperties.getBilibili().setEnabled(boolVal(props.get("bilibiliEnabled")));
        if (props.containsKey("commonToolsEnabled"))
            toolProperties.getCommonTools().setEnabled(boolVal(props.get("commonToolsEnabled")));
    }

    private Double doubleVal(Object val) {
        if (val instanceof Double d)
            return d;
        if (val instanceof Number n)
            return n.doubleValue();
        if (val instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Boolean boolVal(Object val) {
        if (val instanceof Boolean b)
            return b;
        if (val instanceof String s)
            return Boolean.parseBoolean(s);
        return null;
    }

    private Integer intVal(Object val) {
        if (val instanceof Integer i)
            return i;
        if (val instanceof Number n)
            return n.intValue();
        if (val instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
