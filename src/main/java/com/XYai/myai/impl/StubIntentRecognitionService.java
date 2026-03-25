package com.XYai.myai.impl;

import com.XYai.myai.core.intent.IntentRecognitionService;
import com.XYai.myai.core.intent.IntentResult;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 意图识别桩实现，用于开发阶段联调。
 */
@Service
public class StubIntentRecognitionService implements IntentRecognitionService {

    /**
     * 返回固定意图结果，便于快速验证调用链。
     */
    @Override
    public IntentResult recognize(String userId, String text) {
        return new IntentResult("general_qa", 0.99, Map.of());
    }
}

