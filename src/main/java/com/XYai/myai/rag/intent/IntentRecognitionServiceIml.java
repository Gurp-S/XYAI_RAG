package com.XYai.myai.rag.intent;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.mapper.IntentNodeMapper;
import com.XYai.myai.rag.intent.POJO.*;
import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;
import com.XYai.myai.redis.RedisKeyConfig;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.wltea.analyzer.lucene.IKAnalyzer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 意图识别服务实现。实现流程：
 * 1. 优先使用 RewriteResult 中已拆分的子查询进行识别；
 * 2. 依次尝试 Redis 缓存、数据库精确匹配、向量检索等策略；
 * 3. 若均未命中则使用兜底策略（调用 LLM 通过意图列表匹配或分词降级）。
 */
@Slf4j
@Service
public class IntentRecognitionServiceIml implements IntentRecognitionService {
    private static final Analyzer IK_ANALYZER = new IKAnalyzer(true);
    private static final String INTENT_NODE_NAME = "intent:tree:node:";
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ChatModel chatModel;
    @Resource
    private IntentNodeMapper intentNodeMapper;
    @Resource
    private IntentProperties intentProperties;
    @Resource
    private VectorStore vectorStore;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 识别一组查询（可能包含拆分后的子问题）并返回意图列表。
     * 该方法并行识别每个子问题，最终返回最多前三个识别结果。
     *
     * @param rewriteResult 含重写后查询及可选子查询的对象
     * @param load          加载的上下问
     * @return 最多三个 SubQuestionIntent 结果
     */
    public List<SubQuestionIntent> recognize(RewriteResult rewriteResult, LoadSession load) {
        // 加载子问题（优先使用已分解的子查询）
        List<String> list;
        if (rewriteResult == null) {
            list = List.of();
        } else if (CollUtil.isNotEmpty(rewriteResult.getSubQuery())) {
            list = rewriteResult.getSubQuery();
        } else if (!StrUtil.isBlank(rewriteResult.getRewrittenQuery())) {
            list = List.of(rewriteResult.getRewrittenQuery());
        } else {
            list = List.of();
        }
        // 并行识别每个子问题
        List<CompletableFuture<SubQuestionIntent>> tasks = list.stream().map(
                query -> CompletableFuture.supplyAsync(
                        () -> classifyIntent(query, load)
                )).toList();
        List<SubQuestionIntent> subIntent = tasks.stream().map(
                CompletableFuture::join
        ).toList();
        List<SubQuestionIntent> nonNull = subIntent.stream().filter(Objects::nonNull).toList();
        return nonNull.subList(0, Math.min(3, nonNull.size()));
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
        //TODO 同义词映射
        //分词判断
        List<String> tokenizes = tokenizeWithIk(query);
        //读取redis意图树.有->返回,没有->数据库查询
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
        //TODO RAG向量检索,没有->兜底策略加载所有意图子节点
        if (intentProperties.getVectorEnabled()) {
            SubQuestionIntent byRag = matchIntentFromRag(tokenizes);
            if (byRag != null) {
                log.info("向量判断成功");
                return byRag;
            }
        }
        // 1. 查询所有叶子节点(兜底)
        if (!intentProperties.getUpdateIntentEnabled()) {
            log.info("兜底更新未开启,降级返回原文");
            return getDegradeIntentResult(query);
        }
        //返回降级策略
        return fallback(query, load);
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
     * @param tokenizes 分词后的 token 列表
     * @return 匹配到的 IntentNode 列表（可能为空）
     */
    private SubQuestionIntent matchIntentFromRag(List<String> tokenizes) {
        List<IntentNode> matches = new ArrayList<>();
        List<NodeScore> list = new ArrayList<>();
        for (String tokenize : tokenizes) {
            // TODO: 向量检索实现
            vectorStore.getName();
        }
        return matches.isEmpty() && list.isEmpty() ? null
                : SubQuestionIntent.builder().subIntent(matches).nodeScore(NodesScore.builder()
                                                                           .nodeScoreList(list).build()).build();
    }

    /**
     * 兜底策略：当其他匹配策略未命中且允许更新意图树时，
     * 使用 LLM 对预置意图叶子节点进行匹配，然后将结果缓存到 Redis。
     *
     * @param query 原始查询
     * @param load  上下文
     * @return 识别到的意图对象
     */
    private SubQuestionIntent fallback(String query, LoadSession load) {
        try {
            log.info("兜底进行: query={}", query);

            // 1. 查叶子节点
            // 1.1先查redis失败查mysql
            RSet<Object> set = redissonClient.getSet(RedisKeyConfig.intentNodeLeaveKey());
            List<IntentNode> leafNodes = set.stream().map(o -> JSON.parseObject(o.toString(), IntentNode.class)).toList();
            if (leafNodes == null || leafNodes.isEmpty()) {
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
        List<NodeScore> list = new ArrayList<>();
        List<IntentNode> matches = new ArrayList<>();
        for (String tokenize : tokenizes) {
            IntentNode getLeafNodes = intentNodeMapper.selectById(tokenize);
            if (getLeafNodes != null) {
                matches.add(getLeafNodes);
                list.add(NodeScore.builder().intentNodeName(tokenize).score(0.95).build());
            }
        }
        return matches.isEmpty() && list.isEmpty() ? null
                : SubQuestionIntent.builder().subIntent(matches).nodeScore(NodesScore.builder()
                                                                           .nodeScoreList(list).build()).build();
    }

    /**
     * 将意图节点缓存到 Redis，便于快速匹配。
     * TODO修改
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
        //redis用hash存,hashkey为名字,value为id
        //id使用如 "group-hr"、"group-hr-leave-annual"格式可以直接返回树
        List<IntentNode> matches = new ArrayList<>();
        List<NodeScore> list = new ArrayList<>();
        for (String tokenize : tokenizes) {
            if (StrUtil.isBlank(tokenize)) {
                continue;
            }
            // 1) 先按 name -> nodeId 查
            // 安全获取 Redis 中的意图ID
            String nodeName = stringRedisTemplate.opsForValue().get(RedisKeyConfig.intentNodeNameKey(tokenize));
            if (StrUtil.isBlank(nodeName)) {
                continue;
            }
            IntentNode node =
                    com.alibaba.fastjson2.JSON.parseObject(nodeName, IntentNode.class);
            if (node != null) {
                matches.add(node);
                list.add(NodeScore.builder().intentNodeName(tokenize).score(0.95).build());
            }
        }
        return matches.isEmpty() && list.isEmpty() ? null
                : SubQuestionIntent.builder().subIntent(matches).nodeScore(NodesScore.builder()
                                                                           .nodeScoreList(list).build()).build();
    }

    /**
     * 使用 IKAnalyzer（Lucene 分词器）进行中文分词并做基础清洗（去空、trim）。
     *
     * @param text 待分词文本
     * @return 处理后的 token 列表
     */
    private List<String> tokenizeWithIk(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        try (TokenStream tokenStream = IK_ANALYZER.tokenStream("", text)) {
            CharTermAttribute termAttr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String term = termAttr.toString().trim();
                if (!term.isEmpty()) {
                    tokens.add(term);
                }
            }
            tokenStream.end();
        } catch (IOException e) {
            log.warn("IKAnalyzer 分词失败，回退返回原始文本分割", e);
            // 回退：简单按空格拆分
            String[] parts = text.trim().split("\\s+");
            for (String p : parts) {
                if (!p.isBlank()) tokens.add(p.trim());
            }
        }
        return tokens;
    }
}