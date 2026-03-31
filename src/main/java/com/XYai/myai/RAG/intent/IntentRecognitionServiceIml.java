package com.XYai.myai.RAG.intent;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.RAG.rewrite.RewriteResult;
import com.XYai.myai.mapper.IntentNodeMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaban.analysis.jieba.JiebaSegmenter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
public class IntentRecognitionServiceIml implements IntentRecognitionService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ChatModel chatModel;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private IntentNodeMapper intentNodeMapper;
    @Resource
    private IntentProperties intentProperties;

    private static final JiebaSegmenter JIEBA = new JiebaSegmenter();
    private static final String INTENT_NODE_HASH = "intent:tree:";


    public List<SubQuestionIntent> recognize(RewriteResult rewriteResult) {
        // 加载子问题（优先使用已分解的子查询）
        List<String> list = CollUtil.isNotEmpty(rewriteResult.getSubQuery()) ?
                rewriteResult.getSubQuery() :
                List.of(rewriteResult.getRewrittenQuery());
        //获取意图
        List<CompletableFuture<SubQuestionIntent>> tasks = list.stream().map(
                query -> CompletableFuture.supplyAsync(
                        () -> classifyIntent(query)
                )).toList();
        List<SubQuestionIntent> subIntent = tasks.stream().map(
                CompletableFuture::join
        ).toList();
        return subIntent.subList(0, Math.min(3, list.size()));
    }

    private SubQuestionIntent classifyIntent(String query) {
        if (query == null || StrUtil.isBlank(query)) return null;
        //分词判断
        List<String> tokenizes = tokenizeWithJieba(query);
        //读取redis意图树.有->返回,没有->数据库查询
        List<IntentNode> byRedis = matchIntentFromRedis(tokenizes);
        if (!byRedis.isEmpty()) {
            log.info("redis判断成功");
            return SubQuestionIntent.builder().subIntent(byRedis).build();
        }
        //数据库查询,没有->向量检索
        List<IntentNode> bySql = getBySql(tokenizes);
        if (!bySql.isEmpty()) {
            log.info("数据库判断成功");
            return SubQuestionIntent.builder().subIntent(bySql).build();
        }
        //TODO RAG向量检索,没有->兜底策略加载所有意图子节点
        List<IntentNode> byRag = new ArrayList<>();
        if (!byRag.isEmpty()) {
            log.info("向量判断成功");
        }
        // 1. 查询所有叶子节点(兜底)
        if(!intentProperties.getUpdateIntentEnabled()){
            log.info("兜底更新未开启,分词降级返回");
            List<IntentNode> degradeIntent = degradeJieba(tokenizes);
            return SubQuestionIntent.builder().subIntent(degradeIntent).build();
        }
        //返回降级策略
        return fallback(query);
    }

    private SubQuestionIntent fallback(String query) {
        //Select * form IntentNode where Son = 0
        log.info("兜底进行");
        //先查redis
        //TODO加锁
        List<IntentNode> leafNodes = intentNodeMapper.selectList(
                new QueryWrapper<IntentNode>().eq("children_count", 0)
        );
        //返回格式
        BeanOutputConverter<SubQuestionIntent> outputConverter = new BeanOutputConverter<>(SubQuestionIntent.class);
        String converterFormat = outputConverter.getFormat();
        // 2. 构建标准化 System Prompt（意图名称 + 描述）新节点设置TopK++,id+name,父节点设置
        StringBuilder systemPrompt = new StringBuilder();
        //TODO加入上下文
        systemPrompt.append("你是意图识别助手，请根据用户问题和上下文，从下面的意图列表中匹配最匹配的三个\n");
        systemPrompt.append("将用户意图,严格按照提供的JSON格式返回,要求:");
        systemPrompt.append("subIntent中,name设置为用户问题的意图(中文),id为匹配的意图nodeId+'-'+name(英文),parent_name设置为最匹配的意图name,topK++,其他设置为空,JSON格式:");
        systemPrompt.append(converterFormat);
        systemPrompt.append("意图列表：\n");
        for (IntentNode node : leafNodes) {
            systemPrompt.append("- nodeId：").append(node.getNodeId()).append("- name：").append(node.getName());
        }
        // 2. 正确调用AI：系统提示词 + 用户问题
        String promptTemplate = """
                %s
                用户问题：<<%s>>
                """;
        // 最终 Prompt
        String fullPrompt = String.format(promptTemplate, systemPrompt, query);
        //得到结果
        String intentResult = chatModel.call(fullPrompt);
        SubQuestionIntent intentNodes = new SubQuestionIntent();
        try {
            //转换为节点对象
            intentNodes = objectMapper.readValue(intentResult, SubQuestionIntent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("结果转换失败", e);
        }
        //将树保存再redis(如果开启)
        for (IntentNode token : intentNodes.getSubIntent()) {
            cacheNodesToRedis(token);
        }
        //返回对应树
        return intentNodes;
    }

    private static List<IntentNode> degradeJieba(List<String> tokenizes) {
        List<IntentNode> degradeIntent = new ArrayList<>();
        for (String tokenize : tokenizes) {
            IntentNode degrade = new IntentNode();
            degrade.setNodeId(tokenize);
            degradeIntent.add(degrade);
        }
        return degradeIntent;
    }

    private List<IntentNode> getBySql(List<String> tokenizes) {
        List<IntentNode> matches = new ArrayList<>();
        for (String tokenize : tokenizes) {
            IntentNode getLeafNodes = intentNodeMapper.selectById(tokenize);
            if(getLeafNodes!=null) matches.add(getLeafNodes);
        }
        return matches;
    }

    private void cacheNodesToRedis(IntentNode token) {
        try {
            stringRedisTemplate.opsForHash().put(INTENT_NODE_HASH, token.getName(), token.getNodeId());
        } catch (Exception e) {
            log.warn("缓存意图节点到 redis 失败", e);
        }
    }

    private List<IntentNode> matchIntentFromRedis(List<String> tokenizes) {
        List<IntentNode> matches = new ArrayList<>();
        //redis用hash存,hashkey为名字,value为id
        //id使用如 "group-hr"、"group-hr-leave-annual"格式可以直接返回树
        for (String tokenize : tokenizes) {
            Object nodeObj = stringRedisTemplate.opsForHash()
                    .get(INTENT_NODE_HASH, tokenize);
            if (nodeObj == null) continue;
            String node = nodeObj.toString();
            IntentNode intentNode = new IntentNode();
            intentNode.setNodeId(node);
            matches.add(intentNode);
        }
        return matches;
    }

    /**
     * 使用 jieba 进行简单分词并做基础清洗（去空、trim）
     */
    private List<String> tokenizeWithJieba(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> raw = JIEBA.sentenceProcess(text);
        System.out.println(raw);
        return raw.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}