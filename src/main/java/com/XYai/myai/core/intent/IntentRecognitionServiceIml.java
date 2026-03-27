package com.XYai.myai.core.intent;


import cn.hutool.core.collection.CollUtil;
import com.XYai.myai.core.dto.RewriteResult;
import com.XYai.myai.core.dto.SubQuestionIntent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class IntentRecognitionServiceIml implements IntentRecognitionService{
    @Resource
    private StringRedisTemplate stringRedisTemplate;


    public List<String> recognize(RewriteResult rewriteResult) {
        //加载子问题没有加载重写问题
        List<String> list = CollUtil.isNotEmpty(rewriteResult.getSub_query())?
                rewriteResult.getSub_query():
                List.of(rewriteResult.getRewritten_query());
        //获取意图树根
        String Intent = rewriteResult.getIntent();
        List<CompletableFuture<SubQuestionIntent>> tasks = list.stream().map(
                v ->CompletableFuture.supplyAsync(
                        () -> classifyIntent(v)//TODO报错
                )).toList();
        List<SubQuestionIntent> subIntent = tasks.stream().map(
                CompletableFuture::join
        ).toList();

        return capTotalIntents(list);
    }

    private SubQuestionIntent classifyIntent(String v) {
//      1. [前置准备] 意图体系加载与缓存 (Metadata Loading)
//      1.1 优先读取 Redis/本地缓存 (高性能，毫秒级)。

//      1.2 缓存未命中则查库并回写。

//      1.3 树结构扁平化：提取所有叶子节点，构建包含"意图名称+描述"的标准化 System Prompt。

        return null;
    }

    private List<String> capTotalIntents(List<String> list) {
        return null;
    }
}