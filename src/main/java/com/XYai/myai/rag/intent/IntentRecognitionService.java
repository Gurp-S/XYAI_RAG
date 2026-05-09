package com.XYai.myai.rag.intent;


import com.XYai.myai.rag.intent.pojo.SubQuestionIntent;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;

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

