package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;

public class Announcement {

    public Result<String> sentMessage() {
        return Result.success();
    }

    public Result<String> getHistoryMessage() {
        return Result.success();
    }

    public Result<String> deleteMessage() {
        return Result.success();
    }

    public Result<String> updateMessage() {
        return Result.success();
    }
}