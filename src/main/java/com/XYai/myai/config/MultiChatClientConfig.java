package com.XYai.myai.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /**
     * 默认主 embedding：OpenAI 兼容的百炼 text-embedding-v4（1024 维）。
     * rag.embedding.provider 未配置或为 openai 时生效。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "openai", matchIfMissing = true)
    public EmbeddingModel embeddingModel(OpenAiEmbeddingModel openAiEmbeddingModel) {
        // Ollama 未部署时不可作为主 embedding，否则索引与检索全部失败
        return openAiEmbeddingModel;
    }

    /**
     * 通用 OpenAI 兼容 embedding（任意自建/第三方 /v1/embeddings 端点）。
     * rag.embedding.provider=openai-compatible 时生效；
     * 切换时必须同步修改 milvus embedding-dimension 并重建集合重索引。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "openai-compatible")
    public EmbeddingModel openAiCompatibleEmbeddingModel(
            @Value("${rag.embedding.openai-compatible.base-url}") String baseUrl,
            @Value("${rag.embedding.openai-compatible.api-key}") String apiKey,
            @Value("${rag.embedding.openai-compatible.model}") String model,
            @Value("${rag.embedding.openai-compatible.dimensions:0}") int dimensions) {
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        OpenAiEmbeddingOptions.Builder options = OpenAiEmbeddingOptions.builder().model(model);
        if (dimensions > 0) {
            // MRL 支持的模型（qwen3 系列等）可用 dimensions 控制输出维度
            options.dimensions(dimensions);
        }
        log.info("Embedding 提供方: openai-compatible, model={}, baseUrl={}, dimensions={}",
                model, baseUrl, dimensions > 0 ? dimensions : "默认");
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options.build());
    }

    /**
     * 百炼原生多模态 embedding（tongyi-embedding-vision 系列，768 维）。
     * rag.embedding.provider=dashscope-vision 时生效；
     * 切换时必须同步修改 milvus embedding-dimension=768 并重建集合重索引。
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "dashscope-vision")
    public EmbeddingModel dashscopeVisionEmbeddingModel(
            @Value("${spring.ai.openai.api-key}") String visionApiKey,
            @Value("${rag.embedding.vision.base-url:https://dashscope.aliyuncs.com/api/v1/services/embeddings/multimodal-embedding/multimodal-embedding}") String visionBaseUrl,
            @Value("${rag.embedding.vision.model:tongyi-embedding-vision-flash-2026-03-06}") String visionModel,
            @Value("${rag.embedding.vision.dimensions:768}") int visionDimensions) {
        return new DashscopeVisionEmbeddingModel(visionApiKey, visionBaseUrl, visionModel, visionDimensions);
    }


    // ==================== Rerank ====================

    /**
     * 百炼原生重排服务（gte-rerank 系列，rag.rerank.provider=dashscope 时生效）。
     * 复用 spring.ai.openai.api-key（百炼同一 key 覆盖原生 API）。
     */
    @Bean
    @ConditionalOnProperty(name = "rag.rerank.provider", havingValue = "dashscope", matchIfMissing = true)
    public com.XYai.myai.commonUtils.DashscopeRerankerService dashscopeRerankerService(
            @Value("${spring.ai.openai.api-key}") String rerankApiKey,
            @Value("${rag.rerank.dashscope.base-url:https://dashscope.aliyuncs.com}") String rerankBaseUrl,
            @Value("${rag.rerank.dashscope.model:gte-rerank-v2}") String rerankModel) {
        log.info("Rerank 提供方: dashscope, model={}", rerankModel);
        return new com.XYai.myai.commonUtils.DashscopeRerankerService(rerankApiKey, rerankBaseUrl, rerankModel);
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

}