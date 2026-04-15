package com.XYai.myai.rag.intent;

import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.intent.POJO.IntentProperties;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 意图识别结果。
 *
 */
@RestController
public class IntentResult{

    @Resource
    private IntentRecognitionService intentRecognitionService;
    @Resource
    private IntentProperties intentProperties;

    /**
     * 意图识别入口（Controller 层）：根据重写后的查询调用 IntentRecognitionService 并返回意图列表。
     *
     * @param rewriteResult 重写后的查询对象
     * @param load
     * @return 识别出的意图列表（可能为空）
     */
    public List<SubQuestionIntent> recognize(RewriteResult rewriteResult, LoadSession load){
        if(!intentProperties.getIntentEnabled()) {
            return List.of();
        }
        return intentRecognitionService.recognize(rewriteResult,load);

    }
}
