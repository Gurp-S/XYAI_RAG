package com.XYai.myai.rag.intent;


import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;

import java.util.List;

/**
 * 意图识别服务接口。
 */
public interface IntentRecognitionService {

    /**
     * 识别用户当前问题的业务意图。
     *
     * @param rewriteResult 重写对象
     * @param load
     * @return 意图识别结果（含置信度和额外信息）
     */
    List<SubQuestionIntent> recognize(RewriteResult rewriteResult, LoadSession load);
}

