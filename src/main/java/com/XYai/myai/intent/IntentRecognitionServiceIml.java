package com.XYai.myai.intent;


import cn.hutool.core.collection.CollUtil;
import com.XYai.myai.rewrite.RewriteResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class IntentRecognitionServiceIml implements IntentRecognitionService{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String INTENT_NODE_HASH = "intent:tree:node:";
    private static final String INTENT_KEYWORD_SET = "intent:keyword:";
    private static final String INTENT_ROOT = "root";


    public List<String> recognize(RewriteResult rewriteResult){
        //加载子问题没有加载重写问题
        List<String> list = CollUtil.isNotEmpty(rewriteResult.getSubQuery()) ?
                rewriteResult.getSubQuery() :
                List.of(rewriteResult.getRewrittenQuery());
        //获取意图树根
        List<CompletableFuture<SubQuestionIntent>> tasks = list.stream().map(
                query ->CompletableFuture.supplyAsync(
                        () -> classifyIntent(query)//TODO报错
                )).toList();
        List<SubQuestionIntent> subIntent = tasks.stream().map(
                CompletableFuture::join
        ).toList();

        return capTotalIntents(list);
    }

    private SubQuestionIntent classifyIntent(String query){
        //      1. [前置准备] 意图体系加载与缓存 (Metadata Loading)
        //      1.1 优先读取 Redis/本地缓存 (高性能，毫秒级)。
        Set<String> intentIds = matchIntentFromRedis(query);
        if (CollUtil.isEmpty(intentIds)) {
            log.warn("未匹配到意图：{}", query);
            return null;
        }
        //stringRedisTemplate.opsForHash().putAll();
        //      1.2 缓存未命中则查库并回写。

        //      1.3 树结构扁平化：提取所有叶子节点，构建包含"意图名称+描述"的标准化 System Prompt。
        // 2. 取第一个匹配的意图
        String bestIntentId = intentIds.iterator().next();
        // 3. 从 Redis 读取完整意图节点
        String nodeJson = (String) stringRedisTemplate.opsForHash()
                .get(INTENT_NODE_HASH + bestIntentId, "data");
        // 4. 封装成 SubQuestionIntent
        return null;
    }

    private Set<String> matchIntentFromRedis(String query) {
        Set<String> matchedIds = new HashSet<>();
        return matchedIds;
    }


    private List<String> capTotalIntents(List<String> list) {
        return null;
    }
}