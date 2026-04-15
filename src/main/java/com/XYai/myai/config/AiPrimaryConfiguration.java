package com.XYai.myai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AI 相关组件的主配置类。
 * 用于配置主聊天模型 (ChatModel) 和嵌入模型 (EmbeddingModel)。
 */
@Configuration
public class AiPrimaryConfiguration {

    @Bean
    @Primary
    public ChatModel chatModel(OpenAiChatModel openAiChatModel) {
        return openAiChatModel;
    }

    @Bean
    @Primary
    public EmbeddingModel embeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel) {
        return ollamaEmbeddingModel;
    }
}
