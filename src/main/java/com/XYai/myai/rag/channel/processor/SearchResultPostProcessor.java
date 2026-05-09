package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;

import java.util.List;

/**
 * 召回后处理器统一契约。
 *
 * <p>用于定义“召回结果合并后”的处理步骤，例如去重、过滤、重排。</p>
 * <p>处理器应尽量保持幂等：同一输入重复执行不应产生不可控副作用。</p>
 */
public interface SearchResultPostProcessor {

    /**
     * 处理器名称（用于日志、排障、可观测性）。
     */
    String getName();

    /**
     * 执行顺序，数值越小越先执行。
     */
    int getOrder();

    /**
     * 处理召回结果。
     *
     * @param chunks  召回候选列表（可能为空）
     * @param context 本次检索上下文（query、意图、用户信息等）
     * @return 处理后的候选列表
     */
    List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context);
}
