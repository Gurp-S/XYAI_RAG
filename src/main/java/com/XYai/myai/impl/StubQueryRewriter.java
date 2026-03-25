package com.XYai.myai.impl;

import com.XYai.myai.core.rewrite.QueryRewriter;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 查询重写桩实现。
 */
@Service
public class StubQueryRewriter implements QueryRewriter {

    /**
     * 直接返回带标记的重写文本。
     */
    @Override
    public String rewrite(String userId, String original, Map<String, Object> context) {
        return "[rewritten] " + original;
    }
}

