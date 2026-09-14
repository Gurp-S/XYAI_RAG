package com.XYai.myai.rag.evaluate.service;

import com.XYai.myai.mapper.SystemEvaluateMapper;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.evaluate.pojo.EvaluateResult;
import com.XYai.myai.rag.evaluate.pojo.SystemEvaluatePOJO;
import com.XYai.myai.rag.evaluate.strategy.LLMEvaluator;
import com.XYai.myai.rag.evaluate.strategy.RerankEvaluator;
import com.XYai.myai.rag.evaluate.strategy.RuleEvaluator;
import com.XYai.myai.xyAdmin.mapper.SystemConfigMapper;
import com.XYai.myai.xyAdmin.pojo.SystemConfig;
import com.XYai.myai.xyAdmin.service.SystemConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class SystemEvaluateService {

    @Resource
    private RuleEvaluator ruleEvaluator;
    @Resource
    private RerankEvaluator rerankEvaluator;
    @Resource
    private LLMEvaluator llmEvaluator;
    @Resource
    private SystemConfigService systemConfigService;
    @Resource
    private SystemEvaluateMapper systemEvaluateMapper;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Resource
    private SystemConfigMapper systemConfigMapper;
    @Resource(name = "evaluateExecutor")
    private TaskExecutor evaluateExecutor;

    /**
     * 三层开关（默认全开，volatile保证多线程可见性，setter 自动持久化）
     */
    @Getter
    private volatile boolean ruleEnabled = true;
    @Getter
    private volatile boolean rerankEnabled = true;
    @Getter
    private volatile boolean llmEnabled = true;

    /**
     * 从持久化配置加载评估开关
     */
    public void loadPersistedConfig() {
        String ruleVal = systemConfigService.getConfig("evaluate", "ruleEnabled");
        if (ruleVal != null) ruleEnabled = Boolean.parseBoolean(ruleVal);
        String rerankVal = systemConfigService.getConfig("evaluate", "rerankEnabled");
        if (rerankVal != null) rerankEnabled = Boolean.parseBoolean(rerankVal);
        String llmVal = systemConfigService.getConfig("evaluate", "llmEnabled");
        if (llmVal != null) llmEnabled = Boolean.parseBoolean(llmVal);
        log.info("已从 DB 加载评估开关: rule={}, rerank={}, llm={}", ruleEnabled, rerankEnabled, llmEnabled);
    }

    public void setRuleEnabled(boolean ruleEnabled) {
        this.ruleEnabled = ruleEnabled;
        systemConfigService.setConfig("evaluate", "ruleEnabled", String.valueOf(ruleEnabled), "规则评估开关");
    }

    public void setRerankEnabled(boolean rerankEnabled) {
        this.rerankEnabled = rerankEnabled;
        systemConfigService.setConfig("evaluate", "rerankEnabled", String.valueOf(rerankEnabled), "重排评估开关");
    }

    public void setLlmEnabled(boolean llmEnabled) {
        this.llmEnabled = llmEnabled;
        systemConfigService.setConfig("evaluate", "llmEnabled", String.valueOf(llmEnabled), "LLM评估开关");
    }

    /**
     * 异步提交评估任务（不阻塞主流程）
     */
    public void submitEvaluate(Long conversationId, ChatMessage message,
                               List<String> retrievedChunks, long latencyMs,
                               String userQuestion) {
        // 空值防护
        if (message == null || conversationId == null) {
            log.warn("[EVAL] 跳过评估：参数不完整 conversationId={}", conversationId);
            return;
        }
        SystemConfig systemConfig = systemConfigMapper.selectOne(
                new QueryWrapper<SystemConfig>().eq("config_key", "chat_default"));
        // 空集合处理
        List<String> chunks = retrievedChunks == null ? Collections.emptyList() : retrievedChunks;
        EvaluateResult result = evaluate(message, chunks, latencyMs, userQuestion, conversationId);
        // 构建数据库记录
        SystemEvaluatePOJO record = SystemEvaluatePOJO.builder()
                .chatMessageId(message.getChatMessageId())
                .conversationId(conversationId)
                .userId(message.getUserId())
                .overallScore(result.getOverallF1())
                .retrievalScore(result.getRetrievalF1())
                .faithfulnessScore(result.getFaithfulnessF1())
                .answerRelevanceScore(result.getRelevanceF1())
                .completenessScore(result.getCompletenessF1())
                .retrievedDocCount(chunks.size())
                .latencyMs(latencyMs)
                .modelName(systemConfig.getConfigValue())
                .ruleScore(result.getRuleScore())
                .rerankScore(result.getRerankScore())
                .llmScore(result.getLlmScore())
                .build();

        systemEvaluateMapper.insert(record);
        log.info("[EVAL] 评估完成 conversationId={}, 总分={}", conversationId, result.getOverallF1());
    }

    /**
     * 核心评估逻辑：规则+重排+LLM 三路评估
     */
    public EvaluateResult evaluate(ChatMessage message, List<String> retrievedChunks,
                                   long latencyMs, String userQuestion, Long conversationId) {
        // 空答案直接跳过
        String answer = message.getAssistantMessage();
        if (answer == null || answer.isBlank()) {
            return EvaluateResult.skipped();
        }
        final String finalAnswer = answer; // 供 Lambda 使用

        // 获取问题（兼容空值）
        String rawQuestion = (userQuestion != null && !userQuestion.isBlank())
                ? userQuestion
                : message.getUserMessage();
        if (rawQuestion == null)
            rawQuestion = "";
        final String finalQuestion = rawQuestion; // 供 Lambda 使用

        // 异步执行三路评估（开关控制）
        CompletableFuture<Double> ruleFut = ruleEnabled
                ? CompletableFuture.supplyAsync(
                () -> ruleEvaluator.evaluate(finalQuestion, finalAnswer, retrievedChunks, latencyMs)
                      .getTotalScore(),
                evaluateExecutor)
                : CompletableFuture.completedFuture(0.5);

        CompletableFuture<Double> rerankFut = rerankEnabled
                ? CompletableFuture.supplyAsync(
                () -> rerankEvaluator.evaluate(finalQuestion, finalAnswer, retrievedChunks), evaluateExecutor)
                : CompletableFuture.completedFuture(0.5);

        CompletableFuture<Double> llmFut = llmEnabled
                ? CompletableFuture.supplyAsync(
                () -> llmEvaluator.evaluate(finalQuestion, finalAnswer, retrievedChunks, conversationId, message.getChatMessageId()), evaluateExecutor)
                : CompletableFuture.completedFuture(0.5);

        // 等待结果
        double ruleScore = ruleFut.join();
        double rerankScore = rerankFut.join();
        double llmScore = llmFut.join();

        // 计算综合分数
        double precision = (ruleScore + rerankScore) / 2.0;
        double overallF1 = f1(precision, llmScore);
        double retrievalF1 = f1(ruleScore, rerankScore);
        double faithfulnessF1 = f1(rerankScore, llmScore);
        double completenessF1 = f1(ruleScore, llmScore);

        // 直接使用 overallF1 作为 answerRelevanceScore，删除冗余变量
        return new EvaluateResult(
                overallF1, retrievalF1, faithfulnessF1,
                overallF1, completenessF1,
                ruleScore, rerankScore, llmScore);
    }

    /**
     * F1分数计算公式
     */
    private double f1(double p, double r) {
        return (p + r == 0) ? 0.0 : (2 * p * r) / (p + r);
    }
}