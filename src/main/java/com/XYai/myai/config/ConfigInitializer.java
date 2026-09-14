package com.XYai.myai.config;

import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.etlpipeline.pojo.UploadProperties;
import com.XYai.myai.rag.evaluate.service.SystemEvaluateService;
import com.XYai.myai.rag.intent.pojo.IntentProperties;
import com.XYai.myai.rag.memory.pojo.MemoryProperties;
import com.XYai.myai.rag.rewrite.pojo.RewriterProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 应用启动时从 DB 加载持久化的 RAG 配置并覆盖到内存中的 Properties 对象上。
 * 使管理员在运行时修改的配置在重启后依然生效。
 */
@Slf4j
@Component
public class ConfigInitializer {

    @Resource
    private ConfigPersistence configPersistence;

//    @Resource
//    private PipelineProperties pipelineProperties;
    @Resource
    private UploadProperties uploadProperties;
    @Resource
    private IntentProperties intentProperties;
    @Resource
    private MemoryProperties memoryProperties;
    @Resource
    private RetrievalProperties retrievalProperties;
    @Resource
    private RewriterProperties rewriterProperties;
    @Resource
    private CacheConfig cacheConfig;
    @Resource
    private SystemEvaluateService systemEvaluateService;

    @PostConstruct
    public void init() {
        log.info("====== 开始加载持久化配置 ======");
        //configPersistence.load("pipeline", pipelineProperties);
        configPersistence.load("upload", uploadProperties);
        configPersistence.load("intent", intentProperties);
        configPersistence.load("memory", memoryProperties);
        configPersistence.load("retrieval", retrievalProperties);
        configPersistence.load("rewriter", rewriterProperties);
        configPersistence.load("cache", cacheConfig);
        // SystemEvaluateService 自行加载评估开关
        systemEvaluateService.loadPersistedConfig();
        log.info("====== 持久化配置加载完成 ======");
    }
}
