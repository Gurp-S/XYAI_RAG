package com.XYai.myai.RAG.ETLpipeline;

import com.XYai.myai.RAG.Aop.Annotation.TrackETLJob;
import com.XYai.myai.RAG.ETLpipeline.Nodes.Ingestion;
import com.XYai.myai.RAG.ETLpipeline.POJO.IngestionContext;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeConfig;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeLog;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeResult;
import com.XYai.myai.RAG.ETLpipeline.POJO.PipelineDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 数据摄入 (Ingestion) 引擎类。
 * 负责解析流水线定义 (PipelineDefinition)，按照节点拓扑结构依次执行数据处理任务（如解析、分块、存储）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionEngine {

    private final List<Ingestion> nodeHandlers;
    private final ConditionEvaluator conditionEvaluator;

    /**
     * 按节点顺序执行单条文档的 ETL 管道。
     */
    @TrackETLJob
    public IngestionContext execute(PipelineDefinition pipeline,
            IngestionContext context) {
        Objects.requireNonNull(pipeline, "pipeline must not be null");
        // 判空
        IngestionContext targetContext = context == null ? IngestionContext.builder().build() : context;
        // 1. 构建节点映射（ID->设置），便于快速查找
        Map<String, NodeConfig> nodeConfigMap = buildNodeConfigMap(pipeline.getNodes());
        // 获取起始节点的同时判断是否合法
        String startNodeId = validatePipeline(nodeConfigMap);
        Map<String, Ingestion> nodeMap = buildNodeMap();
        // 执行
        executeChain(startNodeId, nodeConfigMap, nodeMap, targetContext);

        return targetContext;
    }

    private Map<String, Ingestion> buildNodeMap() {
        Map<String, Ingestion> map = new HashMap<>();
        for (Ingestion node : nodeHandlers) {
            map.putIfAbsent(node.getNodeType().toLowerCase(), node);
        }
        return map;
    }

    /**
     * 节点映射
     * 
     * @param nodes
     * @return
     */
    private Map<String, NodeConfig> buildNodeConfigMap(List<NodeConfig> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("管道节点不能为空");
        }

        Map<String, NodeConfig> map = new HashMap<>();
        for (NodeConfig node : nodes) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                throw new IllegalArgumentException("节点ID不能为空");
            }
            if (map.putIfAbsent(node.getNodeId(), node) != null) {
                throw new IllegalArgumentException("重复ID: " + node.getNodeId());
            }
        }
        return map;
    }

    /**
     * 判断合法性
     * 
     * @param nodeConfigMap
     * @return
     */
    private String validatePipeline(Map<String, NodeConfig> nodeConfigMap) {
        // 判断是否有未知的后节点
        for (NodeConfig nodeConfig : nodeConfigMap.values()) {
            if (StringUtils.hasText(nodeConfig.getNextNodeId())
                    && !nodeConfigMap.containsKey(nodeConfig.getNextNodeId())) {
                throw new IllegalArgumentException("未知后节点: " + nodeConfig.getNextNodeId());
            }
        }
        // 判断是否有循环执行
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String nodeId : nodeConfigMap.keySet()) {
            detectCycle(nodeId, nodeConfigMap, visiting, visited);
        } // 判断是否有两个起始节点,并返回起始节点
        return findStartNode(nodeConfigMap);
    }

    /**
     * 递归检测节点链路中是否存在循环依赖（死循环）
     * 
     * @param nodeId        当前正在检查的节点ID
     * @param nodeConfigMap 所有节点的配置映射表
     * @param visiting      正在遍历中的节点集合（标记当前递归栈里的节点，用于检测环）
     * @param visited       已经遍历完成的节点集合（避免重复处理）
     */
    private void detectCycle(String nodeId,
            Map<String, NodeConfig> nodeConfigMap,
            Set<String> visiting,
            Set<String> visited) {
        // 节点已经处理完成，返回
        if (visited.contains(nodeId)) {
            return;
        }
        // 加入遍历集合
        // 节点已在当前递归链中 → 检测到循环依赖
        if (!visiting.add(nodeId)) {
            throw new IllegalArgumentException("在此节点附近检测到循环: " + nodeId);
        }

        // 从配置表中获取当前节点的配置信息
        NodeConfig config = nodeConfigMap.get(nodeId);

        // 节点配置存在，后节点ID不为空,递归检查
        if (config != null && StringUtils.hasText(config.getNextNodeId())) {
            detectCycle(config.getNextNodeId(), nodeConfigMap, visiting, visited);
        }
        // 当前节点及其下游节点都检查完毕，从遍历集合中移除
        visiting.remove(nodeId);

        // 标记已处理，后续不再重复检查
        visited.add(nodeId);
    }

    /**
     * 找起始节点
     * 
     * @param nodeConfigMap 节点映射
     * @return 唯一起始节点的 nodeId
     */
    private String findStartNode(Map<String, NodeConfig> nodeConfigMap) {
        Set<String> referenced = new HashSet<>();
        // 寻找被指向的节点
        for (NodeConfig nodeConfig : nodeConfigMap.values()) {
            if (StringUtils.hasText(nodeConfig.getNextNodeId())) {
                referenced.add(nodeConfig.getNextNodeId());
            }
        }
        // 删除被指向的节点
        Set<String> startNodes = new HashSet<>(nodeConfigMap.keySet());
        startNodes.removeAll(referenced);

        if (startNodes.size() != 1) {
            throw new IllegalArgumentException("管道只能有一个起始节点, 发现: " + startNodes.size());
        }

        return startNodes.iterator().next();
    }

    /**
     * 链式执行节点
     * 
     * @param nodeId
     * @param configMap
     * @param nodeMap
     * @param context
     */
    private void executeChain(String nodeId,
            Map<String, NodeConfig> configMap,
            Map<String, Ingestion> nodeMap,
            IngestionContext context) {
        Set<String> seen = new HashSet<>();
        while (nodeId != null) {
            if (!seen.add(nodeId)) {
                throw new IllegalStateException("执行到重复节点: " + nodeId);
            } // 获取起始节点映射
            NodeConfig config = configMap.get(nodeId);
            if (config == null) {
                throw new IllegalStateException("没有找到此节点映射: " + nodeId);
            }
            // 开始时间
            long startNanos = System.nanoTime();
            // 执行状态
            boolean executed = false;
            NodeResult result;
            try {
                // 检查执行条件（满足条件才执行）
                if (conditionEvaluator.evaluate(config.getCondition(), context)) {
                    // 获得节点
                    Ingestion node = nodeMap.get(config.getNodeType().toLowerCase());
                    if (node == null) {
                        throw new IllegalArgumentException("没有该类型的节点处理程序: " + config.getNodeType());
                    }
                    // 执行当前节点
                    executed = true;
                    result = node.execute(context, config);
                } else {
                    result = NodeResult.ok("配置跳过此节点");
                }
            } catch (Exception ex) {
                log.error("节点执行失败, 节点ID={}", config.getNodeId(), ex);
                result = NodeResult.fail(ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            // 花费时间
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            // 添加节点日志
            context.getLogs().add(buildNodeLog(config, result, executed, costMs));
            // 执行下一个节点
            nodeId = config.getNextNodeId();
        }
    }

    /**
     * 创建节点日志
     * 
     * @param config
     * @param result
     * @param executed
     * @param costMs
     * @return
     */
    private NodeLog buildNodeLog(NodeConfig config, NodeResult result, boolean executed, long costMs) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("nodeId", config.getNodeId());
        extra.put("nodeType", config.getNodeType());
        extra.put("executed", executed);
        extra.put("success", result != null && result.isSuccess());
        extra.put("costMs", costMs);
        return NodeLog.builder()
                .nodeName(config.getNodeType())
                .timestamp(Instant.now())
                .level(result != null && result.isSuccess() ? "INFO" : "ERROR")
                .message(result == null ? "null result" : result.getMessage())
                .extra(extra)
                .build();
    }
}
