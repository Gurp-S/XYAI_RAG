package com.XYai.myai.core.post;

import com.XYai.myai.core.dto.RetrievalResult;

import java.util.Map;

/**
 * 检索结果后处理器接口。
 */
@FunctionalInterface
public interface PostProcessor {

    /**
     * 对检索结果执行后处理（去重、重排、过滤等）。
     *
     * @param input 原始检索结果
     * @param context 后处理上下文参数
     * @return 处理后的检索结果
     */
    RetrievalResult process(RetrievalResult input, Map<String, Object> context);
}

