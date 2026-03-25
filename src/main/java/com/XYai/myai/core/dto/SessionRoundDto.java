package com.XYai.myai.core.dto;

/**
 * 单轮会话 DTO。
 *
 * @param user 用户输入
 * @param assistant AI 回复
 * @param ts 时间戳（毫秒）
 */
public record SessionRoundDto(String user, String assistant, long ts) {

    /**
     * 创建当前时间的会话轮次。
     */
    public static SessionRoundDto now(String user, String assistant) {
        return new SessionRoundDto(user, assistant, System.currentTimeMillis());
    }

}

