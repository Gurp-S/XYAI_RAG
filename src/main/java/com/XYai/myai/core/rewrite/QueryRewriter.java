package com.XYai.myai.core.rewrite;

import java.util.Map;

/**
 * 查询重写接口。
 *
 * <p>将用户原始提问转换为更适合检索的标准查询。</p>
 */
@FunctionalInterface
public interface QueryRewriter {

    /**
     * 重写查询文本。
     *
     * @param userId 用户 ID
     * @param originalQuery 原始查询
     * @param context 辅助上下文（如意图、历史摘要）
     * @return 重写后的查询
     */
    String rewrite(String userId, String originalQuery, Map<String, Object> context);
}

