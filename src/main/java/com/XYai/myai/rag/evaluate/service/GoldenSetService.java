package com.XYai.myai.rag.evaluate.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.XYai.myai.mapper.GoldenCaseMapper;
import com.XYai.myai.mapper.GoldenResultMapper;
import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.evaluate.pojo.GoldenCasePOJO;
import com.XYai.myai.rag.evaluate.pojo.GoldenResultPOJO;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 黄金问答集评估服务（RAGAS 风格，检索侧指标）。
 * <p>
 * 指标定义：
 * <ul>
 *   <li>Context Precision：检索结果中与期望文档相关的占比</li>
 *   <li>Context Recall：期望文档被检索覆盖的比例</li>
 *   <li>Hit Rate：至少命中一个期望文档的比例（0/1）</li>
 * </ul>
 * 匹配规则：期望元素支持 fileId（前缀匹配该文件任意分块）或 fileId:chunkId（精确匹配）。
 */
@Slf4j
@Service
public class GoldenSetService {

    @Resource
    private GoldenCaseMapper goldenCaseMapper;
    @Resource
    private GoldenResultMapper goldenResultMapper;
    @Resource
    private MultiChannelRetrievalEngine retrievalEngine;
    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 门控阈值：批次平均 precision/recall 低于阈值时标记不达标 */
    @Value("${evaluate.golden.precision-threshold:0.8}")
    private double precisionThreshold;
    @Value("${evaluate.golden.recall-threshold:0.7}")
    private double recallThreshold;
    /** 单批最大用例数，防止误触发全量 LLM 链路 */
    @Value("${evaluate.golden.max-cases-per-run:100}")
    private int maxCasesPerRun;

    /** 启动时建表（CREATE TABLE IF NOT EXISTS，幂等） */
    @PostConstruct
    public void initSchema() {
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS xy_evaluate_golden_case (
                        id              BIGINT AUTO_INCREMENT PRIMARY KEY,
                        question        VARCHAR(1000) NOT NULL COMMENT '用户问题',
                        ground_truth    TEXT          NULL COMMENT '标准答案',
                        expected_doc_ids TEXT         NULL COMMENT '期望命中文档JSON数组',
                        source          VARCHAR(16)   DEFAULT 'MANUAL' COMMENT 'MANUAL/BADCASE',
                        enabled         TINYINT       DEFAULT 1 COMMENT '1启用 0待审',
                        remark          VARCHAR(2000) NULL COMMENT '备注',
                        create_time     DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        INDEX idx_golden_case_enabled (enabled)
                    ) COMMENT '黄金问答集用例表'
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS xy_evaluate_golden_result (
                        id                BIGINT AUTO_INCREMENT PRIMARY KEY,
                        run_id            BIGINT        NOT NULL COMMENT '跑批批次ID',
                        case_id           BIGINT        NOT NULL COMMENT '用例ID',
                        context_precision DOUBLE        NULL,
                        context_recall    DOUBLE        NULL,
                        hit_rate          DOUBLE        NULL,
                        retrieved_count   INT           NULL,
                        latency_ms        BIGINT        NULL,
                        passed            TINYINT       NULL COMMENT '1通过门控 0不达标',
                        detail_json       TEXT          NULL,
                        create_time       DATETIME DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_golden_result_run (run_id),
                        INDEX idx_golden_result_case (case_id)
                    ) COMMENT '黄金集跑批结果表'
                    """);
            log.info("黄金集评估表初始化完成");
        } catch (Exception e) {
            log.error("黄金集评估表初始化失败（可能已存在或权限不足）", e);
        }
    }

    @Data
    public static class RunSummary {
        private Long runId;
        private int totalCases;
        private int passedCases;
        private double avgContextPrecision;
        private double avgContextRecall;
        private double hitRate;
        private double precisionThreshold;
        private double recallThreshold;
        private boolean gatePassed;
    }

    /**
     * 对所有启用用例执行一轮检索评估，落库并返回批次汇总。
     * 以当前登录用户身份跑检索（权限语义与线上完全一致）。
     */
    public RunSummary runAll() {
        List<GoldenCasePOJO> cases = goldenCaseMapper.selectList(
                new QueryWrapper<GoldenCasePOJO>().eq("enabled", 1).last("LIMIT " + maxCasesPerRun));
        long runId = IdUtil.getSnowflakeNextId();
        RunSummary summary = new RunSummary();
        summary.setRunId(runId);
        summary.setPrecisionThreshold(precisionThreshold);
        summary.setRecallThreshold(recallThreshold);
        if (cases == null || cases.isEmpty()) {
            summary.setGatePassed(false);
            return summary;
        }

        double sumPrecision = 0, sumRecall = 0, sumHit = 0;
        int passed = 0;
        for (GoldenCasePOJO goldenCase : cases) {
            GoldenResultPOJO result = evaluateCase(goldenCase);
            result.setRunId(runId);
            boolean casePassed = result.getContextPrecision() != null
                    && result.getContextRecall() != null
                    && result.getContextPrecision() >= precisionThreshold
                    && result.getContextRecall() >= recallThreshold;
            result.setPassed(casePassed ? 1 : 0);
            goldenResultMapper.insert(result);

            sumPrecision += result.getContextPrecision() == null ? 0 : result.getContextPrecision();
            sumRecall += result.getContextRecall() == null ? 0 : result.getContextRecall();
            sumHit += result.getHitRate() == null ? 0 : result.getHitRate();
            if (casePassed) passed++;
        }
        int n = cases.size();
        summary.setTotalCases(n);
        summary.setPassedCases(passed);
        summary.setAvgContextPrecision(sumPrecision / n);
        summary.setAvgContextRecall(sumRecall / n);
        summary.setHitRate(sumHit / n);
        // 门控语义与 Javadoc/字段注释一致：按批次平均 precision/recall 判定（RAGAS 风格），
        // 同主题多文档场景下单用例精度天然波动，逐用例全过过于严苛
        summary.setGatePassed(summary.getAvgContextPrecision() >= precisionThreshold
                && summary.getAvgContextRecall() >= recallThreshold);
        log.info("黄金集跑批完成 runId={} cases={} passed={} avgP={} avgR={}",
                runId, n, passed, summary.getAvgContextPrecision(), summary.getAvgContextRecall());
        return summary;
    }

    private GoldenResultPOJO evaluateCase(GoldenCasePOJO goldenCase) {
        GoldenResultPOJO.GoldenResultPOJOBuilder builder = GoldenResultPOJO.builder().caseId(goldenCase.getId());
        List<String> expected = parseExpectedDocIds(goldenCase.getExpectedDocIds());
        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> retrieved;
        try {
            RewriteResult rewritten = RewriteResult.builder()
                    .rewrittenQuery(goldenCase.getQuestion())
                    .subQuery(null)
                    .build();
            retrieved = retrievalEngine.retrieve(Map.of(), rewritten, 0L, goldenCase.getQuestion());
        } catch (Exception e) {
            log.error("黄金用例检索失败 caseId={}", goldenCase.getId(), e);
            retrieved = List.of();
        }
        long latency = System.currentTimeMillis() - t0;
        if (retrieved == null) retrieved = List.of();

        // 匹配：每个检索结果是否命中期望集合；每个期望是否被覆盖
        int relevantCount = 0;
        List<String> retrievedIds = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        for (RetrievedChunk chunk : retrieved) {
            if (chunk == null) continue;
            String docId = chunk.getId();
            if (docId == null && chunk.getMetadata() != null) {
                Object v = chunk.getMetadata().get("doc_id");
                docId = v == null ? null : String.valueOf(v);
            }
            if (docId == null) continue;
            retrievedIds.add(docId);
            String matched = matchExpected(docId, expected);
            if (matched != null) {
                relevantCount++;
                covered.add(matched);
            }
        }

        double precision = retrievedIds.isEmpty() ? 0.0 : (double) relevantCount / retrievedIds.size();
        double recall = expected.isEmpty() ? 0.0 : (double) covered.size() / expected.size();
        double hit = relevantCount > 0 ? 1.0 : 0.0;

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("retrieved", retrievedIds);
        detail.put("coveredExpected", covered);
        detail.put("expected", expected);

        return builder
                .contextPrecision(round3(precision))
                .contextRecall(round3(recall))
                .hitRate(hit)
                .retrievedCount(retrievedIds.size())
                .latencyMs(latency)
                .detailJson(JSON.toJSONString(detail))
                .build();
    }

    private List<String> parseExpectedDocIds(String json) {
        if (StrUtil.isBlank(json)) return List.of();
        try {
            List<String> list = JSON.parseArray(json, String.class);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            // 兼容逗号分隔
            return Arrays.stream(json.split(","))
                    .map(String::strip)
                    .filter(StrUtil::isNotBlank)
                    .toList();
        }
    }

    /** 返回命中的期望元素；fileId 前缀匹配（fileId:chunk 或等于 fileId），fileId:chunkId 精确匹配 */
    private String matchExpected(String docId, List<String> expected) {
        for (String exp : expected) {
            if (exp.equals(docId)) return exp;
            if (docId.startsWith(exp + ":")) return exp;
        }
        return null;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
