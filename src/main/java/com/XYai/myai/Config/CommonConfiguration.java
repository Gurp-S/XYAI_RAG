package com.XYai.myai.Config;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import static dev.langchain4j.model.chat.request.ResponseFormatType.JSON;

/**
 * 应用通用配置，包含 Ollama 模型与结构化输出格式定义。
 */
@Configuration
public class CommonConfiguration {

    public static final String PERSON_JSON_RESPONSE_FORMAT = "personJsonResponseFormat";

    /**
     * 创建 Ollama 聊天模型 Bean。
     *
     * @param baseUrl Ollama 服务地址
     * @param modelName 模型名称
     * @return 可注入的 OllamaChatModel
     */
    @Bean
    public OllamaChatModel ollamaChatModel(
            @Value("${ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${ollama.chat.model:deepseek-r1:8b}") String modelName
    ) {
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.3)
                .timeout(java.time.Duration.ofMinutes(5)) // 添加较长的超时时间，防止并非测试时 Ollama 回复慢导致超时
                .build();
    }

    /**
     * 创建结构化 JSON 响应格式定义。
     *
     * @return Person 结构化输出格式
     */
    @Bean(PERSON_JSON_RESPONSE_FORMAT)
    public ResponseFormat personJsonResponseFormat() {
        return ResponseFormat.builder()
                .type(JSON)
                .jsonSchema(JsonSchema.builder()
                        .name("Person")
                        .rootElement(JsonObjectSchema.builder()
                                .addStringProperty("name")
                                .addIntegerProperty("age")
                                .addNumberProperty("height")
                                .addBooleanProperty("married")
                                .required("name", "age", "height", "married")
                                .build())
                        .build())
                .build();
    }

    /**
     * 加载 Redis 限流 Lua 脚本。
     *
     * @return Lua 脚本执行对象，返回类型为 Boolean
     */
    @Bean
    public RedisScript<Boolean> loadRedisScript(){
        DefaultRedisScript<Boolean> redisLimitScript = new DefaultRedisScript<>();
        //lua脚本路径
        redisLimitScript.setLocation(new ClassPathResource("luaScript/limit.lua"));
        //lua脚本返回值
        redisLimitScript.setResultType(java.lang.Boolean.class);
        return redisLimitScript;
    }
}