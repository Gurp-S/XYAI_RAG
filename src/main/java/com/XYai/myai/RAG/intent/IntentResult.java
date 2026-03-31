package com.XYai.myai.RAG.intent;

import com.XYai.myai.RAG.rewrite.RewriteResult;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
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
     * 意图识别
     * @param rewriteResult 重写对象
     * @return 意图
     */
    public List<SubQuestionIntent> recognize(RewriteResult rewriteResult){
        if(intentProperties.getIntentEnabled()) {
            List.of();
        }
        List<SubQuestionIntent> result = intentRecognitionService.recognize(rewriteResult);
        return result;

    }
}
