package com.XYai.myai.core.intent;

import java.util.Map;

/**
 * 意图识别结果。
 *
 * @param intent 意图编码
 * @param confidence 置信度
 * @param extra 额外信息
 */
public record IntentResult(String intent, double confidence, Map<String, Object> extra) {
}

