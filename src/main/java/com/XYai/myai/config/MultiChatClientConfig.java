package com.XYai.myai.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模型ChatClient配置
 * 模型客户端由 ModelRegistryService 在启动时从 DB 动态注册到 map，
 * 此处仅保留基础设施 Bean 和结构化输出专用 Bean（兼容旧引用）。
 */
@Slf4j
@Configuration
public class MultiChatClientConfig {

    private static final Map<String, ChatClient> map = new ConcurrentHashMap<>();
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;
    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;
    @Resource
    private Environment environment;

    /**
     * 获取可变模型映射（供运行时注册新模型）
     */
    public static Map<String, ChatClient> getMutableModelMap() {
        return map;
    }

    /**
     * 创建共享的 OpenAiApi 实例
     */
    @Bean
    public OpenAiApi openAiApi() {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
    }

    // ==================== Embedding ====================

//    @Bean
//    @Primary
//    public EmbeddingModel embeddingModel(OpenAiEmbeddingModel openAiEmbeddingModel) {
//        return openAiEmbeddingModel;
//    }


    @Bean
    @Primary
    public EmbeddingModel embeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel) {
        return ollamaEmbeddingModel;
    }
    // ==================== 结构化输出专用 Bean（兼容旧引用） ====================

    @Bean("structuredOutputModel")
    @Primary
    public ChatModel structuredOutputModel(OpenAiApi openAiApi) {
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-r1-distill-qwen-7b")
                        .temperature(0.1)
                        .maxTokens(2000)
                        .build())
                .build();
    }

    @Bean("structuredOutputClient")
    public ChatClient structuredOutputClient(
            @Qualifier("structuredOutputModel") ChatModel model) {
        return ChatClient.builder(model).build();
    }

    // ==================== 默认客户端 ====================

    @Bean
    @Primary
    public ChatClient defaultChatClient() {
        ChatClient fallback = ChatClient.builder(
                        OpenAiChatModel.builder()
                                .openAiApi(openAiApi())
                                .defaultOptions(OpenAiChatOptions.builder().model("qwen-turbo").build())
                                .build())
                .build();
        return map.isEmpty() ? fallback : map.values().iterator().next();
    }

    // ==================== 模型名称到 ChatClient 的动态映射 ====================

    @Bean("modelClientMap")
    public Map<String, ChatClient> modelClientMap() {
        log.info("模型映射已初始化(动态), 当前大小: {}", map.size());
        return map;
    }

    // ==================== 辅助方法 ====================

    /**
     * 运行时动态添加 ChatClient（供 ModelRegistryService / 旧 LLMManager 调用）
     */
    public void addChatModel(String modelName, String model, double temperature, int maxTokens) {
        try {
            String resolvedApiKey = apiKey;
            String resolvedBaseUrl = baseUrl;
            if (resolvedApiKey == null || resolvedApiKey.isBlank()) {
                resolvedApiKey = environment.getProperty("spring.ai.openai.api-key");
            }
            if (resolvedBaseUrl == null || resolvedBaseUrl.isBlank()) {
                resolvedBaseUrl = environment.getProperty("spring.ai.openai.base-url");
            }
            if (resolvedApiKey == null || resolvedBaseUrl == null) {
                log.error("[动态注册] API配置不完整，无法注册模型 {}", modelName);
                return;
            }
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(resolvedApiKey)
                    .baseUrl(resolvedBaseUrl)
                    .build();
            ChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(temperature)
                            .maxTokens(maxTokens)
                            .build())
                    .build();
            ChatClient client = ChatClient.builder(chatModel).build();
            map.put(modelName, client);
            log.info("[动态注册] 模型 {} (API: {}) 已添加到运行时映射", modelName, model);
        } catch (Exception e) {
            log.error("[动态注册] 注册模型 {} 失败", modelName, e);
        }
    }
}