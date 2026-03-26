package com.XYai.myai.Service;

import reactor.core.publisher.Flux;

/**
 * 聊天服务抽象，定义单轮对话处理能力。
 */
public interface ChatService {

    /**
     * 执行一轮聊天并返回模型回复。
     *
     * @param message 用户输入内容
       * @param conversationId 会话 ID，可为空；为空时由实现类回退到默认会话
       * @return 模型生成的文本回复
       */
      Flux<String> DoChat(String message, String conversationId);
}

