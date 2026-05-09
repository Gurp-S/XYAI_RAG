package com.XYai.myai.rag.evaluate;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.evaluate.impl.EvaluateImpl;
import com.XYai.myai.rag.evaluate.pojo.UserEvaluatePOJO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/evaluate")
public class Evaluate {

    @Resource
    private EvaluateImpl evaluateImpl;

    @PostMapping("/user")
    public Result<String> userEvaluate(@RequestParam String conversationId, @RequestParam String chatMessageId, @RequestParam Integer feedback){
        log.info("userEvaluate conversationId={}, chatMessageId={}, feedback={}", conversationId, chatMessageId, feedback);
        return evaluateImpl.userEvaluate(conversationId,chatMessageId,feedback);
    }

    @PostMapping("/system")
    public Result<String> systemEvaluate(@RequestParam String conversationId, @RequestParam Long userId, @RequestParam String chatMessageId){
        return evaluateImpl.systemEvaluate(conversationId,userId,chatMessageId);
    }
}
