package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.chat.ModelHealthStore;

public class ChatManager {
    public Result<Long> getAllToken() {
        return Result.success();
    }

    public Result<Long> getUserToken() {
        return Result.success();
    }

    public Result<Long> getTimeToken() {
        return Result.success();
    }

    public Result<String> addLLM() {
        return Result.success();
    }

    public Result<String> deleteLLM() {
        return Result.success();
    }

    public Result<String> updateLLM() {
        return Result.success();
    }

    public Result<ModelHealthStore> getLLMInfo() {
        return Result.success();
    }
}
