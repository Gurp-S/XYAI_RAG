package com.XYai.myai.RAG.intent;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.RAG.Memory.POJO.LoadSession;
import com.XYai.myai.RAG.intent.POJO.*;
import com.XYai.myai.RAG.rewrite.POJO.RewriteResult;
import com.XYai.myai.mapper.IntentNodeMapper;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.huaban.analysis.jieba.JiebaSegmenter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 意图识别服务实现。实现流程：
 * 1. 优先使用 RewriteResult 中已拆分的子查询进行识别；
 * 2. 依次尝试 Redis 缓存、数据库精确匹配、向量检索等策略；
 * 3. 若均未命中则使用兜底策略（调用 LLM 通过意图列表匹配或分词降级）。
 */
@Slf4j
@Service
public class IntentRecognitionServiceIml implements IntentRecognitionService {
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

    private static final JiebaSegmenter JIEBA = new JiebaSegmenter();
    private static final String INTENT_NODE_NAME = "intent:tree:node:";
    private static final String INTENT_NODE_CHILDREN_PREFIX = "intent:tree:children:";

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
        List<String> list = CollUtil.isNotEmpty(rewriteResult.getSubQuery()) ?
                rewriteResult.getSubQuery() :
                List.of(rewriteResult.getRewrittenQuery());
        // 并行识别每个子问题
        List<CompletableFuture<SubQuestionIntent>> tasks = list.stream().map(
                query -> CompletableFuture.supplyAsync(
                        () -> classifyIntent(query, load)
                )).toList();
        List<SubQuestionIntent> subIntent = tasks.stream().map(
                CompletableFuture::join
        ).toList();
        return subIntent.subList(0, Math.min(3, list.size()));
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
        List<String> tokenizes = tokenizeWithJieba(query);
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
        //Select * form IntentNode where Son = 0
        log.info("兜底进行");
        //上下文转化
        String context = com.alibaba.fastjson2.JSON.toJSONString(load);
        //TODO加锁先查redis

        List<IntentNode> leafNodesBySql = intentNodeMapper.selectList(
                new QueryWrapper<IntentNode>().eq("children_count", 0)
        );

        //返回格式
        String nodesScoreJSON = "{\"nodeScoreList\":[{\"intentNodeName\":,\"score\":}]}";
        // 2. 构建标准化 System Prompt（意图名称 + 描述）
        StringBuilder systemPrompt = new StringBuilder();
        //加入上下文
        systemPrompt.append("你是意图识别助手，请根据用户问题和上下文，从下面的意图列表中匹配最匹配的三个意图,并进行置信度打分\n");
        systemPrompt.append("如果匹配的意图节点score<0.3,不返回意图节点,识别用户意图,根据JSON构建并返回,\n");
        systemPrompt.append("上下文:\n");
        systemPrompt.append(context);
        systemPrompt.append("严格按照提供的JSON格式返回,每条都要有intentNodeName和score,score 必须是 0~1 的小数\n" +
                "按降序排列,JSON格式:");
        systemPrompt.append(nodesScoreJSON);
        systemPrompt.append("意图列表：\n");
        for (IntentNode node : leafNodesBySql) {
            systemPrompt.append("- IntentName:").append(node.getName());
        }
        // 2. 正确调用AI：系统提示词 + 用户问题
        String fullPrompt = "用户问题：<<%s>>".formatted(query);
        //得到结果(分数加意图节点名)
        Prompt prompt = new Prompt(
                new SystemMessage(String.valueOf(systemPrompt)),
                new UserMessage(fullPrompt)
                );
        String intentResult = chatModel.call(prompt).getResult().getOutput().getText();
        log.info(intentResult);
        //转换对象
        NodesScore nodeScoreLLM = JSON.parseObject(intentResult, NodesScore.class);
        //构建结果
        log.info(String.valueOf(nodeScoreLLM));
        SubQuestionIntent intentNodes = new SubQuestionIntent();
        intentNodes.setNodeScore(nodeScoreLLM);
        intentNodes.setSubIntent(new ArrayList<>());
        for (NodeScore nodeScore : nodeScoreLLM.getNodeScoreList()) {
            String intentNodeJSON =
                    stringRedisTemplate.opsForValue().get(INTENT_NODE_NAME + nodeScore.getIntentNodeName());
            IntentNode intentNode =
                    com.alibaba.fastjson2.JSON.parseObject(intentNodeJSON, IntentNode.class);
            //添加
            intentNodes.getSubIntent().add(intentNode);
            //TODO添加到节点
            //cacheNodesToRedis(intentNode);
        }
        log.info(String.valueOf(intentNodes));
        //返回对应树
        return intentNodes;
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
            stringRedisTemplate.opsForValue().set(INTENT_NODE_NAME + token.getName(), json);
            // 2) 父子关系
            if (StrUtil.isNotBlank(token.getParentName())) {
                stringRedisTemplate.opsForSet().add(INTENT_NODE_CHILDREN_PREFIX + token.getParentName(), token.getName());
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
            String nodeName = stringRedisTemplate.opsForValue().get(INTENT_NODE_NAME + tokenize);
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
     * 使用 jieba 进行简单分词并做基础清洗（去空、trim）。
     *
     * @param text 待分词文本
     * @return 处理后的 token 列表
     */
    private List<String> tokenizeWithJieba(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> raw = JIEBA.sentenceProcess(text);
        return raw.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}