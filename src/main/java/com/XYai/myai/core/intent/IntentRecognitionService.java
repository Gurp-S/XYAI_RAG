package com.XYai.myai.core.intent;


/**
 * 意图识别服务接口。
 */
public interface IntentRecognitionService {

    /**
     * 识别用户当前问题的业务意图。
     *
     * @param text 用户输入文本
     * @return 意图识别结果（含置信度和额外信息）
     */
    String recognize(String text);
}

