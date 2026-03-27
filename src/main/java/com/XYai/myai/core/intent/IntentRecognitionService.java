package com.XYai.myai.core.intent;


import com.XYai.myai.core.dto.RewriteResult;

import java.util.List;

/**
 * 意图识别服务接口。
 */
public interface IntentRecognitionService {

    /**
     * 识别用户当前问题的业务意图。
     *
     * @param rewriteResult 重写对象
     * @return 意图识别结果（含置信度和额外信息）
     */
    List<String> recognize(RewriteResult rewriteResult);
}

