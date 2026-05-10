package com.XYai.myai.config;

import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
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

import java.util.HashMap;
import java.util.Map;

/**
 * 多模型ChatClient配置
 * 完全对应YAML中router.candidates配置的6个模型
 */
@Slf4j
@Configuration
public class MultiChatClientConfig {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Resource
    private ModelRouterProperties routerProperties;
    private String format;

    /**
     * 创建共享的OpenAiApi实例（所有模型复用）
     */
    @Bean
    public OpenAiApi openAiApi() {
        return OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
    }

    // ==================== 按照YAML配置的6个模型分别创建 ====================

    /**
     * 1. qwen-turbo 模型（优先级1）
     */
    @Bean("qwen35FlashModel")
    @Primary
    public ChatModel qwen35FlashModel(OpenAiApi openAiApi) {
        return createChatModel(openAiApi, "qwen-turbo", 0.3, 1000);
    }

    @Bean("qwen35FlashClient")
    public ChatClient qwen35FlashClient(@Qualifier("qwen35FlashModel") ChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * 2. qwen3.6-flash 模型（优先级2）
     */
    @Bean("qwen36FlashModel")
    public ChatModel qwen36FlashModel(OpenAiApi openAiApi) {
        return createChatModel(openAiApi, "qwen3.6-flash", 0.3, 1000);
    }

    @Bean("qwen36FlashClient")
    public ChatClient qwen36FlashClient(@Qualifier("qwen36FlashModel") ChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * 3. qwen-flash 模型（优先级3）
     */
    @Bean("qwenFlashModel")
    public ChatModel qwenFlashModel(OpenAiApi openAiApi) {
        return createChatModel(openAiApi, "qwen-turbo", 0.5, 1500);
    }

    @Bean("qwenFlashClient")
    public ChatClient qwenFlashClient(@Qualifier("qwenFlashModel") ChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * 4. qwen3.5-122b-a10b 模型（优先级4）- 适合复杂推理
     */
    @Bean("qwen122bModel")
    public ChatModel qwen122bModel(OpenAiApi openAiApi) {
        return createChatModel(openAiApi, "qwen3.5-122b-a10b", 0.7, 4000);
    }

    @Bean("qwen122bClient")
    public ChatClient qwen122bClient(@Qualifier("qwen122bModel") ChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * 5. qwen3.5-397b-a17b 模型（优先级5）- 适合创意写作、英文内容
     */
    @Bean("qwen397bModel")
    public ChatModel qwen397bModel(OpenAiApi openAiApi) {
        return createChatModel(openAiApi, "qwen3.5-397b-a17b", 0.7, 4000);
    }

    @Bean("qwen397bClient")
    public ChatClient qwen397bClient(@Qualifier("qwen397bModel") ChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * 6. 结构化输出专用模型（用于工具决策、意图识别、查询重写等需要可靠 JSON 的任务）
     * 使用 122B 大模型 + 低温度，确保输出结构稳定、不截断
     */
    @Bean("structuredOutputModel")
    public ChatModel structuredOutputModel(OpenAiApi openAiApi) {
        return createChatModel(openAiApi, "qwen3.6-27b", 0.1, 2000);
    }

    @Bean("structuredOutputClient")
    public ChatClient structuredOutputClient(@Qualifier("structuredOutputModel") ChatModel model) {
        return ChatClient.builder(model).build();
    }

    /**
     * 7. ollama-local 本地模型（兜底）
     * 注意：Ollama使用专用API，需要单独配置
     * TODO: 如果Ollama不支持OpenAI兼容模式，需要改用 OllamaChatModel
     */
    @Bean
    @Primary
    public EmbeddingModel embeddingModel(OllamaEmbeddingModel ollamaEmbeddingModel) {
        return ollamaEmbeddingModel;
    }

    // ==================== 默认客户端 ====================

    /**
     * 默认聊天客户端（使用qwen-turbo）
     * 与spring.ai.openai.chat.options.model保持一致
     */
    @Bean
    @Primary
    public ChatClient defaultChatClient(@Qualifier("qwen35FlashClient") ChatClient qwen35FlashClient) {
        return qwen35FlashClient;
    }

    // ==================== 模型名称到ChatClient的映射 ====================

    /**
     * 模型名称到ChatClient的映射
     * key必须与YAML中candidates的name完全一致
     */
    @Bean("modelClientMap")
    public Map<String, ChatClient> modelClientMap(
            @Qualifier("qwen35FlashClient") ChatClient qwen35FlashClient,
            @Qualifier("qwen36FlashClient") ChatClient qwen36FlashClient,
            @Qualifier("qwenFlashClient") ChatClient qwenFlashClient,
            @Qualifier("qwen122bClient") ChatClient qwen122bClient,
            @Qualifier("qwen397bClient") ChatClient qwen397bClient,
            @Qualifier("structuredOutputClient") ChatClient structuredOutputClient) {

        Map<String, ChatClient> map = new HashMap<>();

        // 按YAML配置的candidates添加（key必须与YAML中的name完全一致）
        map.put("qwen-turbo", qwen35FlashClient);
        map.put("qwen3.6-flash", qwen36FlashClient);
        map.put("qwen-flash", qwenFlashClient);
        map.put("qwen3.5-122b-a10b", qwen122bClient);
        map.put("qwen3.5-397b-a17b", qwen397bClient);
        map.put("structured-output", structuredOutputClient);


        log.info(format, map.size(), map.keySet());

        // 验证YAML中配置的模型是否都有对应的Client
        validateModelMapping(map);

        return map;
    }

    // ==================== 辅助方法 ====================

    /**
     * 验证YAML中配置的模型是否都有对应的ChatClient
     */
    private void validateModelMapping(Map<String, ChatClient> modelClientMap) {
        if (routerProperties == null || routerProperties.getCandidates() == null) {
            log.warn("ModelRouterProperties未加载或candidates为空");
            return;
        }

        for (ModelRouterProperties.ModelCandidate candidate : routerProperties.getCandidates()) {
            if (!candidate.isEnabled()) {
                log.info("模型 [{}] 已禁用，跳过验证", candidate.getName());
                continue;
            }
            if (!modelClientMap.containsKey(candidate.getName())) {
                log.warn("YAML中配置的模型 [{}] 没有对应的ChatClient Bean，请检查MultiChatClientConfig",
                        candidate.getName());
            } else {
                log.info("✓ 模型 [{}] 已正确映射到ChatClient", candidate.getName());
            }
        }
    }

    /**
     * 创建ChatModel的通用方法
     */
    private ChatModel createChatModel(OpenAiApi api, String model, double temperature, int maxTokens) {
        var options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }
}