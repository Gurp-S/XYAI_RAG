package com.XYai.myai.rag.chat;

import com.XYai.myai.mapper.ModelCandidateMapper;
import com.XYai.myai.rag.chat.pojo.ModelCandidateEntity;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ModelRegistryService {

    private final ModelCandidateMapper mapper;
    private final Map<String, ChatClient> mutableModelMap;
    private final Environment environment;

    public ModelRegistryService(ModelCandidateMapper mapper,
            @Qualifier("modelClientMap") Map<String, ChatClient> mutableModelMap,
            Environment environment) {
        this.mapper = mapper;
        this.mutableModelMap = mutableModelMap;
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        List<ModelCandidateEntity> enabled = mapper.findAllEnabled();
        for (ModelCandidateEntity entity : enabled) {
            registerChatClient(entity);
        }
        log.info("[ModelRegistry] 启动加载 {} 个启用的模型: {}",
                enabled.size(), enabled.stream().map(ModelCandidateEntity::getName).toList());
    }

    // ======================== 查询 ========================

    /** 获取全部模型列表（按优先级排序） */
    @Cacheable(value = "modelCandidates", key = "'all'")
    public List<ModelCandidateEntity> listAll() {
        return mapper.findAllOrderByPriority();
    }

    /** 根据名称查找 */
    public ModelCandidateEntity findByName(String name) {
        return mapper.findByName(name);
    }

    // ======================== 新增（含 priority 挤占） ========================

    @Transactional
    @CacheEvict(value = "modelCandidates", allEntries = true)
    public ModelCandidateEntity add(ModelCandidateEntity entity) {
        // 1. 查重
        if (findByName(entity.getName()) != null) {
            throw new IllegalArgumentException("模型 [" + entity.getName() + "] 已存在");
        }

        // 2. 挤占：目标 priority 及之后的全部 +1
        int target = entity.getPriority();
        List<ModelCandidateEntity> all = mapper.findAllOrderByPriority();
        for (ModelCandidateEntity existing : all) {
            if (existing.getPriority() >= target) {
                mapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModelCandidateEntity>()
                                .eq(ModelCandidateEntity::getName, existing.getName())
                                .set(ModelCandidateEntity::getPriority, existing.getPriority() + 1));
            }
        }

        // 3. 插入
        entity.setEnabled(true);
        mapper.insert(entity);

        // 4. 归一化优先级（消除跳跃）
        normalizePriorities();

        // 5. 动态注册 ChatClient
        registerChatClient(entity);

        log.info("[ModelRegistry] 新增模型: {} (priority={})", entity.getName(), entity.getPriority());
        return entity;
    }

    // ======================== 删除 ========================

    @Transactional
    @CacheEvict(value = "modelCandidates", allEntries = true)
    public void remove(String name) {
        ModelCandidateEntity entity = findByName(name);
        if (entity == null)
            return;
        mapper.deleteByName(name);
        mutableModelMap.remove(name);
        normalizePriorities();
        log.info("[ModelRegistry] 删除模型: {}", name);
    }

    // ======================== 启停 ========================

    @Transactional
    @CacheEvict(value = "modelCandidates", allEntries = true)
    public void setEnabled(String name, boolean enabled) {
        mapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModelCandidateEntity>()
                        .eq(ModelCandidateEntity::getName, name)
                        .set(ModelCandidateEntity::isEnabled, enabled));
        if (enabled) {
            ModelCandidateEntity entity = findByName(name);
            if (entity != null)
                registerChatClient(entity);
        } else {
            mutableModelMap.remove(name);
        }
        log.info("[ModelRegistry] 模型 {} → enabled={}", name, enabled);
    }

    // ======================== 更新模型配置 ========================

    @Transactional
    @CacheEvict(value = "modelCandidates", allEntries = true)
    public void update(ModelCandidateEntity entity) {
        mapper.updateById(entity);
        if (entity.isEnabled()) {
            registerChatClient(entity);
        } else {
            mutableModelMap.remove(entity.getName());
        }
        normalizePriorities();
    }

    // ======================== 优先级归一化 ========================

    @Transactional
    public void normalizePriorities() {
        List<ModelCandidateEntity> sorted = mapper.findAllOrderByPriority();
        for (int i = 0; i < sorted.size(); i++) {
            ModelCandidateEntity e = sorted.get(i);
            int expected = i + 1;
            if (e.getPriority() != expected) {
                mapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ModelCandidateEntity>()
                                .eq(ModelCandidateEntity::getName, e.getName())
                                .set(ModelCandidateEntity::getPriority, expected));
            }
        }
    }

    // ======================== 动态注册 ChatClient ========================

    private void registerChatClient(ModelCandidateEntity entity) {
        if (mutableModelMap.containsKey(entity.getName())) {
            log.debug("[ModelRegistry] 模型 {} 的 ChatClient 已存在，跳过", entity.getName());
            return;
        }
        try {
            String apiKey = environment.getProperty("spring.ai.openai.api-key");
            String baseUrl = environment.getProperty("spring.ai.openai.base-url");
            if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
                log.error("[ModelRegistry] API配置缺失，无法注册模型 {}", entity.getName());
                return;
            }
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
            ChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(entity.getApiModel())
                            .temperature(entity.getTemperature())
                            .maxTokens(entity.getMaxTokens())
                            .build())
                    .build();
            ChatClient client = ChatClient.builder(chatModel).build();
            mutableModelMap.put(entity.getName(), client);
            log.info("[ModelRegistry] 动态注册模型: {} (API={}, mapSize={})",
                    entity.getName(), entity.getApiModel(), mutableModelMap.size());
        } catch (Exception e) {
            log.error("[ModelRegistry] 注册模型 {} 失败", entity.getName(), e);
        }
    }
}