package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.ConfigPersistence;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.etlpipeline.UploadTaskStore;
import com.XYai.myai.rag.etlpipeline.pojo.PipelineProperties;
import com.XYai.myai.rag.etlpipeline.pojo.TaskState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/xyAdmin/etl")
public class ETLManager {

    @Resource
    private UploadTaskStore uploadTaskStore;

    @Resource
    private PipelineProperties pipelineProperties;

    @Resource
    private ConfigPersistence configPersistence;

    /**
     * 获取当前 ETL 管道完整配置（含节点开关、管道参数、所有可用节点）
     */
    @GetMapping("/pipeline/config")
    public Result<Map<String, Object>> getPipelineConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enricherEnable", pipelineProperties.getEnricherEnable());
        config.put("defaultChunkSize", pipelineProperties.getDefaultChunkSize());
        config.put("defaultOverlapSize", pipelineProperties.getDefaultOverlapSize());
        config.put("allNodeTypes", PipelineProperties.ALL_NODE_TYPES);
        config.put("nodeLabels", PipelineProperties.NODE_LABELS);

        // 节点开关列表
        List<Map<String, Object>> nodeConfigs = new ArrayList<>();
        for (PipelineProperties.PipelineNodeDef def : pipelineProperties.getNodes()) {
            Map<String, Object> nd = new LinkedHashMap<>();
            nd.put("nodeType", def.getNodeType());
            nd.put("label", def.getLabel());
            nd.put("enabled", def.isEnabled());
            nd.put("nextNodeType", def.getNextNodeType());
            nodeConfigs.add(nd);
        }
        config.put("nodes", nodeConfigs);

        return Result.success(config);
    }

    /**
     * 更新 ETL 管道配置（运行时生效）
     * 支持更新节点开关、管道参数
     */
    @PostMapping("/pipeline/update")
    public Result<String> updatePipeline(
            @RequestParam(required = false) Boolean enricherEnable,
            @RequestParam(required = false) Integer defaultChunkSize,
            @RequestParam(required = false) Integer defaultOverlapSize,
            @RequestBody(required = false) Map<String, Object> body) {

        try {
            // 简单参数更新
            if (enricherEnable != null)
                pipelineProperties.setEnricherEnable(enricherEnable);
            if (defaultChunkSize != null && defaultChunkSize > 0)
                pipelineProperties.setDefaultChunkSize(defaultChunkSize);
            if (defaultOverlapSize != null && defaultOverlapSize >= 0)
                pipelineProperties.setDefaultOverlapSize(defaultOverlapSize);

            // JSON body 节点开关更新：{ "nodes": [{"nodeType":"enricher","enabled":false}, ...] }
            if (body != null && body.containsKey("nodes")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nodeUpdates = (List<Map<String, Object>>) body.get("nodes");
                for (Map<String, Object> update : nodeUpdates) {
                    String nodeType = (String) update.get("nodeType");
                    Boolean enabled = update.containsKey("enabled") ? (Boolean) update.get("enabled") : null;
                    if (nodeType != null && enabled != null) {
                        PipelineProperties.PipelineNodeDef def = pipelineProperties.getNode(nodeType);
                        if (def != null) {
                            def.setEnabled(enabled);
                            log.debug("节点 [{}] 开关已设为 {}", nodeType, enabled);
                        }
                    }
                }
            }

            configPersistence.save("pipeline", pipelineProperties);
            log.info("管理员更新了ETL管道配置");
            return Result.success("管道配置已更新");
        } catch (Exception e) {
            log.error("更新ETL管道配置失败", e);
            return Result.error(500, "更新失败：" + e.getMessage());
        }
    }

    /**
     * 查询上传任务状态
     */
    @GetMapping("/task")
    public Result<Object> getTask(@RequestParam String taskId) {
        TaskState state = uploadTaskStore.get(taskId);
        if (state == null)
            return Result.error(404, "任务不存在: " + taskId);
        return Result.success(state);
    }

    /**
     * 删除上传任务记录
     */
    @PostMapping("/task/remove")
    public Result<String> removeTask(@RequestParam String taskId) {
        uploadTaskStore.remove(taskId);
        return Result.success("任务记录已删除");
    }
}
