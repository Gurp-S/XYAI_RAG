package com.XYai.myai.rag.intent;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.commonUtils.IK.IKAnalyzerTokenize;
import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.mapper.IntentNodeMapper;
import com.XYai.myai.rag.intent.pojo.*;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.milvus.config.MilvusVectorStoreConfig;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 意图识别服务实现。实现流程：
 * 1. 优先使用 RewriteResult 中已拆分的子查询进行识别；
 * 2. 依次尝试 Redis 缓存、数据库精确匹配、向量检索等策略；
 * 3. 若均未命中则使用兜底策略（调用 LLM 通过意图列表匹配或分词降级）。
 */
@Slf4j
@Service
public class IntentRecognitionServiceIml implements IntentRecognitionService {
    private static final String INTENT_NODE_NAME = "intent:tree:node:";
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 使用结构化输出专用模型（qwen3.5-122b-a10b, temp=0.1, maxTokens=2000），
     * 确保意图识别能稳定输出合法 JSON 结构。
     */
    @Resource(name = "structuredOutputModel")
    private ChatModel chatModel;
    @Resource
    private IntentNodeMapper intentNodeMapper;
    @Resource
    private IntentProperties intentProperties;
    @Resource
    private MilvusVectorStoreConfig milvusVectorStoreConfig;
    @Resource
    private IKAnalyzerTokenize ikAnalyzerTokenize;

    private List<Document> docs;

    @Resource(name = "intentExecutor")
    private TaskExecutor intentExecutor;

    /**
     * 识别一组查询（可能包含拆分后的子问题）并返回意图列表。
     * 该方法并行识别每个子问题，最终返回最多前三个识别结果。
     *
     * @param rewriteResult 含重写后查询及可选子查询的对象
     * @param load          加载的上下问
     * @return 最多三个 SubQuestionIntent 结果
     */
    public List<SubQuestionIntent> recognize(RewriteResult rewriteResult, LoadSession load) {
        // 1. 解析子问题列表（与原逻辑一致）
        List<String> queries;
        if (rewriteResult == null) {
            queries = List.of();
        } else if (CollUtil.isNotEmpty(rewriteResult.getSubQuery())) {
            queries = rewriteResult.getSubQuery();
        } else if (!StrUtil.isBlank(rewriteResult.getRewrittenQuery())) {
            queries = List.of(rewriteResult.getRewrittenQuery());
        } else {
            queries = List.of();
        }

        if (queries.isEmpty()) {
            return Collections.emptyList();
        }

        List<CompletableFuture<SubQuestionIntent>> futures = queries.stream()
                .map(query -> CompletableFuture
                        .supplyAsync(() -> classifyIntent(query, load), intentExecutor)
                        .orTimeout(4000, TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> {
                            log.warn("意图识别子任务失败或超时: query={}", query, ex);
                            return getDegradeIntentResult(query);
                        })
                )
                .toList();

        // 4. 等待全部完成（不强制要求结果）
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        // 5. 收集结果，取前 3 个非空值
        return futures.stream()
                .map(CompletableFuture::join)          // 此时已全部完成
                .filter(Objects::nonNull)
                .limit(3)
                .toList();
    }

    /**
     * 单条查询的意图分类逻辑：
     * 1. 使用分词结果尝试从 Redis 匹配意图；
     * 2. 未命中则尝试数据库匹配；
     * 3. 未命中则尝试向量检索；
     * 4. 若仍未命中则根据配置选择降级或使用 LLM 兜底匹配。
     *
     * @param query 单条子查询文本
     * @param load  山下问
     * @return 匹配到的 SubQuestionIntent，未命中时可能返回降级结果
     */
    private SubQuestionIntent classifyIntent(String query, LoadSession load) {
        if (query == null || StrUtil.isBlank(query)) return null;
        // 分词判断（同义词映射可在后续版本添加）
        List<String> tokenizes = ikAnalyzerTokenize.tokenize(query);
        log.info("意图识别的问题:{}",query);
        // 读取redis意图树.有->返回,没有->数据库查询
        if (intentProperties.getRedisEnabled()) {
            SubQuestionIntent byRedis = matchIntentFromRedis(tokenizes);
            if (byRedis != null) {
                log.info("redis判断成功{}", byRedis);
                return byRedis;
            }
        }
        //数据库查询,没有->向量检索
        if (intentProperties.getDBEnabled()) {
            SubQuestionIntent bySql = matchIntentFromSql(tokenizes);
            if (bySql != null) {
                log.info("数据库判断成功");
                return bySql;
            }
        }
        // RAG向量检索,没有->兜底策略加载所有意图子节点
        SubQuestionIntent byRag = null;
        if (intentProperties.getVectorEnabled()) {
            byRag = matchIntentFromRag(query);
            Optional<Double> maxScoreOptional = byRag.getNodeScore().getNodeScoreList().stream()
                    .map(NodeScore::getScore).max(Comparator.naturalOrder());
            Double maxScore = maxScoreOptional.orElse(0.0);
            if (maxScore > 0.75) {
                // 分数低不返回
                log.info("向量判断成功");
                return byRag;
            }
        }
        // 1. 查询所有叶子节点(兜底)
        if (!intentProperties.getUpdateIntentEnabled()) {
            log.info("兜底更新未开启,降级返回原文");
            return getDegradeIntentResult(query);
        }
        //返回降级策略(复用向量的节点)
        return fallback(query, load, byRag);
    }

    @NotNull
    private SubQuestionIntent getDegradeIntentResult(String query) {
        IntentNode degradeIntent = new IntentNode();
        degradeIntent.setName(query);
        degradeIntent.setNodeId("query-degrade");
        degradeIntent.setCreatedAt(LocalDateTime.now());
        degradeIntent.setTopK(5);
        degradeIntent.setCollectionName("default");
        List<IntentNode> degradeIntentResult = new ArrayList<>();
        degradeIntentResult.add(degradeIntent);
        List<NodeScore> nodeScores = new ArrayList<>();
        nodeScores.add(NodeScore.builder().intentNodeName(query).score(0.1).build());
        return SubQuestionIntent.builder().subIntent(degradeIntentResult)
                .nodeScore(NodesScore.builder().nodeScoreList(nodeScores).build()).build();
    }

    /**
     * 使用 RAG/向量检索方式匹配意图（目前为占位实现）。
     *
     * @return 匹配到的 IntentNode 列表（可能为空）
     */
    private SubQuestionIntent matchIntentFromRag(String query) {
        // 构造搜索请求：取前3个，相似度阈值 0.7
        List<Document> docs = milvusVectorStoreConfig.getIntentVectorStore().similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(0.3)
                .build());

        if (docs == null || docs.isEmpty()) {
            return null;
        }
        List<IntentNode> matches = new ArrayList<>();
        List<NodeScore> nodeScores = new ArrayList<>();
        for (Document doc : docs) {
            // 从 metadata 还原 IntentNode
            IntentNode node = new IntentNode();
            node.setNodeId((String) doc.getMetadata().get("nodeId"));
            node.setName((String) doc.getMetadata().get("name"));
            node.setParentName((String) doc.getMetadata().get("parentName"));
            node.setCollectionName((String) doc.getMetadata().get("collectionName"));
            Object topKObj = doc.getMetadata().get("topK");
            node.setTopK(topKObj instanceof Number ? ((Number) topKObj).intValue() : 5);
            matches.add(node);
            double score = 0.0;
            if (doc.getMetadata().containsKey("score")) {
                score = ((Number) doc.getMetadata().get("score")).doubleValue();
            } else if (doc.getMetadata().containsKey("distance")) {
                // 距离需转换为相似度，假设余弦距离 range [0,2]
                double dist = ((Number) doc.getMetadata().get("distance")).doubleValue();
                score = 1 - dist / 2;   // 近似转换
            }
            nodeScores.add(NodeScore.builder()
                    .intentNodeName(node.getName())
                    .score(score)
                    .build());
        }
        return SubQuestionIntent.builder()
                .subIntent(matches)
                .nodeScore(NodesScore.builder().nodeScoreList(nodeScores).build())
                .build();
    }

    /**
     * 兜底策略：当其他匹配策略未命中且允许更新意图树时，
     * 使用 LLM 对预置意图叶子节点进行匹配，然后将结果缓存到 Redis。
     *
     * @param query 原始查询
     * @param load  上下文
     * @param byRag
     * @return 识别到的意图对象
     */
    private SubQuestionIntent fallback(String query, LoadSession load, SubQuestionIntent byRag) {
        try {
            log.info("兜底进行: query={}", query);
            // 优先使用ByRAG没有再查
            List<IntentNode> subIntent = byRag.getSubIntent();
            List<IntentNode> leafNodes = subIntent == null ? List.of() : subIntent;
            // 1. 查叶子节点
            // 1.1先查redis失败查mysql
            Set<String> set = stringRedisTemplate.opsForSet().members(RedisKeyConfig.intentNodeLeaveKey());
            if (leafNodes.isEmpty()) {
                leafNodes = set.stream().map(o -> JSON.parseObject(o.toString(), IntentNode.class)).toList();
            }
            if (leafNodes.isEmpty()) {
                leafNodes = intentNodeMapper.selectList(
                        new QueryWrapper<IntentNode>().eq("children_count", 0)
                );
            }
            if (leafNodes == null || leafNodes.isEmpty()) {
                log.warn("无叶子节点，返回降级结果");
                return getDegradeIntentResult(query);
            }

            // 2. 构建 Prompt（示例用非空值）
            String systemPrompt = buildFallbackPrompt(leafNodes);
            Prompt prompt = new Prompt(
                    new SystemMessage(systemPrompt),
                    new UserMessage("用户问题: " + query)
            );

            // 3. 调用模型 + 空值校验
            var response = chatModel.call(prompt);
            if (response == null || response.getResult() == null) {
                log.warn("模型返回空响应");
                return getDegradeIntentResult(query);
            }

            String intentResult = response.getResult().getOutput().getText();
            if (intentResult == null || intentResult.isBlank()) {
                log.warn("模型输出为空");
                return getDegradeIntentResult(query);
            }

            // 4. 安全解析 JSON
            NodesScore nodeScoreLLM = safeParseNodesScore(intentResult);
            if (nodeScoreLLM == null || nodeScoreLLM.getNodeScoreList() == null) {
                log.warn("解析结果为空");
                return getDegradeIntentResult(query);
            }

            // 5. 构建结果（过滤低分 + 空值保护）
            List<IntentNode> matchedNodes = new ArrayList<>();
            for (NodeScore score : nodeScoreLLM.getNodeScoreList()) {
                if (score == null || score.getIntentNodeName() == null || score.getScore() == null) {
                    continue;  // 跳过无效项
                }
                if (score.getScore() < 0.4) {
                    continue;  // 低置信度过滤
                }

                // 查 Redis（加空值保护）
                String json = stringRedisTemplate.opsForValue()
                        .get(RedisKeyConfig.intentNodeNameKey(score.getIntentNodeName()));
                if (json != null && !json.isBlank()) {
                    IntentNode node = JSON.parseObject(json, IntentNode.class);
                    if (node != null) {
                        matchedNodes.add(node);
                    }
                }
            }

            // 6. 如果无有效节点，返回降级结果
            if (matchedNodes.isEmpty()) {
                log.debug("无有效匹配节点，返回降级结果");
                return getDegradeIntentResult(query);
            }

            // 7. 返回
            return SubQuestionIntent.builder()
                    .subIntent(matchedNodes)
                    .nodeScore(NodesScore.builder()
                            .nodeScoreList(nodeScoreLLM.getNodeScoreList().stream()
                                    .filter(Objects::nonNull)
                                    .filter(s -> s.getScore() != null && s.getScore() >= 0.4)
                                    .toList())
                            .build())
                    .build();

        } catch (Exception e) {
            log.error("fallback 异常: query={}", query, e);
            return getDegradeIntentResult(query);
        }
    }

    // 解析 JSON
    private NodesScore safeParseNodesScore(String raw) {
        try {
            return JSON.parseObject(raw, NodesScore.class);
        } catch (Exception e1) {
            try {
                String json = extractJson(raw);
                if (json != null) {
                    return JSON.parseObject(json, NodesScore.class);
                }
            } catch (Exception e2) {
                log.debug("JSON 解析失败: raw={}", raw);
            }
        }
        return null;
    }

    /**
     * 从文本中提取第一个 JSON 对象
     */
    private String extractJson(String raw) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return null;
    }

    // 构建 Prompt
    private String buildFallbackPrompt(List<IntentNode> leafNodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是意图识别助手，请从下面的意图列表中匹配最相关的 1-3 个意图，并给出 0~1 的置信度分数。\n");
        sb.append("规则:\n");
        sb.append("1. 只返回 JSON 格式，不要额外说明\n");
        sb.append("2. score<0.4 的意图不要返回\n");
        sb.append("3. 按 score 降序排列\n");
        sb.append("4. 如果没有匹配，返回空数组 []\n");
        sb.append("\n输出格式示例:\n");
        sb.append("{\"nodeScoreList\":[{\"intentNodeName\":\"示例意图\",\"score\":0.95}]}\n");
        sb.append("\n意图列表:\n");
        for (IntentNode node : leafNodes) {
            if (node != null && node.getName() != null) {
                sb.append("- ").append(node.getName()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 从数据库按 token 查找意图节点（精确 id 查询）。
     *
     * @param tokenizes 分词结果
     * @return 匹配到的 IntentNode 列表
     */
    private SubQuestionIntent matchIntentFromSql(List<String> tokenizes) {
        if (tokenizes.isEmpty()) return null;

        // 去重、去空
        List<String> cleanTokens = tokenizes.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();

        // 批量按 id 查询
        List<IntentNode> allMatched = intentNodeMapper.selectList(
                new QueryWrapper<IntentNode>().in("node_id", cleanTokens));
        if (allMatched.isEmpty()) return null;

        List<NodeScore> nodeScores = allMatched.stream()
                .map(node -> NodeScore.builder()
                        .intentNodeName(node.getName())
                        .score(0.95)
                        .build())
                .toList();

        return SubQuestionIntent.builder()
                .subIntent(allMatched)
                .nodeScore(NodesScore.builder().nodeScoreList(nodeScores).build())
                .build();
    }

    /**
     * 将意图节点缓存到 Redis，便于快速匹配。
     *
     * @param token 要缓存的意图节点
     */
    private void cacheNodesToRedis(IntentNode token) {
        if (token == null || StrUtil.isBlank(token.getNodeId())) {
            return;
        }
        try {
            // 1) 节点完整 JSON
            String json = com.alibaba.fastjson2.JSON.toJSONString(token);
            stringRedisTemplate.opsForValue().set(RedisKeyConfig.intentNodeNameKey(token.getName()), json);
            // 2) 父子关系
            if (StrUtil.isNotBlank(token.getParentName())) {
                stringRedisTemplate.opsForSet().add(RedisKeyConfig.intentNodeChildrenKey(token.getParentName()), token.getName());
            }
        } catch (Exception e) {
            log.warn("缓存意图节点到 redis 失败", e);
        }
    }

    /**
     * 从 Redis 中按 token 查找意图节点的简单实现（Hash lookup）。
     *
     * @param tokenizes 分词结果
     * @return 匹配到的 IntentNode 列表
     */
    private SubQuestionIntent matchIntentFromRedis(List<String> tokenizes) {
        if (tokenizes.isEmpty()) return null;

        // 1. 构建 Redis key 列表（去重、去空）
        List<String> cleanTokens = tokenizes.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();

        List<String> redisKeys = cleanTokens.stream()
                .map(RedisKeyConfig::intentNodeNameKey)
                .toList();

        // 2. 批量获取 JSON 字符串
        List<String> jsons = stringRedisTemplate.opsForValue().multiGet(redisKeys);
        if (jsons == null || jsons.isEmpty()) return null;

        // 3. 解析并收集有效的 IntentNode
        List<IntentNode> matches = new ArrayList<>();
        List<NodeScore> nodeScores = new ArrayList<>();

        for (int i = 0; i < cleanTokens.size(); i++) {
            String json = jsons.get(i);
            if (StrUtil.isBlank(json)) continue;
            try {
                IntentNode node = JSON.parseObject(json, IntentNode.class);
                if (node != null) {
                    matches.add(node);
                    nodeScores.add(NodeScore.builder()
                            .intentNodeName(cleanTokens.get(i))
                            .score(0.95)
                            .build());
                }
            } catch (Exception e) {
                log.debug("解析 Redis 中的意图节点失败: key={}", redisKeys.get(i));
            }
        }

        if (matches.isEmpty()) return null;
        return SubQuestionIntent.builder()
                .subIntent(matches)
                .nodeScore(NodesScore.builder().nodeScoreList(nodeScores).build())
                .build();
    }
}