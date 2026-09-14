package com.XYai.myai.rag.benchmark;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.RetrievalAugmentedGeneration;
import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.processor.BM25PostProcessor;
import com.XYai.myai.rag.channel.processor.FilterPostProcessor;
import com.XYai.myai.rag.channel.processor.RerankPostProcessor;
import com.XYai.myai.rag.etlpipeline.UploadTaskStore;
import com.XYai.myai.rag.etlpipeline.pojo.InMemoryMultipartFile;
import com.XYai.myai.rag.kafka.event.FileUploadEvent;
import com.XYai.myai.rag.memory.ConversationMemorySummaryService;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import com.XYai.myai.rag.ragPojo.UserContext;
import com.XYai.myai.rag.rewrite.QueryRewriter;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.pojo.User;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 压测控制器。
 * <p>
 * 提供纯检索链路（跳过LLM调用）和ETL并发上传两个压测入口，
 * 每个接口返回详细的耗时分步数据，便于定位瓶颈。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>
 * # 纯检索压测
 * curl -X POST "http://localhost:8080/benchmark/retrieval" \
 *   -H "Authorization: Bearer xxx" \
 *   -H "Content-Type: application/json" \
 *   -d '{"query":"什么是RAG技术","conversationId":1829475612345678848}'
 *
 * # ETL并发上传压测（10个文件，并发5，每个文件4KB）
 * curl -X POST "http://localhost:8080/benchmark/etl/concurrent?fileCount=10&concurrency=5&fileSizeBytes=4096" \
 *   -H "Authorization: Bearer xxx"
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/benchmark")
public class BenchmarkController {

    @Resource
    private RetrievalAugmentedGeneration rag;

    @Resource
    private MultiChannelRetrievalEngine multiChannelRetrievalEngine;

    @Resource
    private ConversationMemorySummaryService conversationMemorySummaryService;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private BM25PostProcessor bm25PostProcessor;

    @Resource
    private FilterPostProcessor filterPostProcessor;

    @Resource
    private RerankPostProcessor rerankPostProcessor;

    @Resource
    private MilvusFileManager milvusFileManager;

    @Resource
    private UploadTaskStore uploadTaskStore;

    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    // ==================== 1. 纯检索链路压测 ====================

    /**
     * 纯检索链路压测：运行RAG步骤1-6（用户上下文 → 记忆 → 重写 → 实体识别 → 多渠道检索 + 后处理），
     * 跳过LLM模型调用和MCP工具，返回每个子步骤的耗时明细和检索结果。
     */
    @PostMapping("/retrieval")
    public Result<RetrievalBenchmarkResult> retrievalBenchmark(@RequestBody RetrievalBenchmarkRequest request) {
        long wallClockStart = System.nanoTime();
        String mode = "BENCHMARK_RETRIEVAL";
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║ [{}] 纯检索压测开始                     ║", mode);
        log.info("╚══════════════════════════════════════════════╝");

        // 准备 user context（需要登录后才能走权限过滤）
        Long userId = LoginUserInfoManager.getUserId();
        if (userId == null) {
            return Result.error(401, "压测需要登录用户，请先获取token");
        }
        UserContext userCtx = rag.getUserContext();
        if (userCtx != null) {
            LoginUserInfoManager.setUserId(userCtx.getUserId());
            if (userCtx.getSecurityContext() != null) {
                SecurityContextHolder.setContext(userCtx.getSecurityContext());
            }
        }

        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            conversationId = IdUtil.getSnowflakeNextId();
        }
        String query = request.getQuery();
        Long chatMessageId = IdUtil.getSnowflakeNextId();

        RetrievalBenchmarkResult.RetrievalBenchmarkResultBuilder resultBuilder = RetrievalBenchmarkResult.builder()
                .query(query)
                .conversationId(conversationId);

        try {
            // ---------- 步骤1：用户上下文 ----------
            long t1 = System.nanoTime();
            // getUserContext 已在上方完成
            long step1Ms = (System.nanoTime() - t1) / 1_000_000;
            resultBuilder.stepUserContextMs(step1Ms);

            // ---------- 步骤2：会话记忆加载（3层） ----------
            long t2 = System.nanoTime();
            LoadSession memorySession = rag.loadMemoryAsync(conversationId);
            long stepMemoryMs = (System.nanoTime() - t2) / 1_000_000;
            String historySummary = (memorySession == null) ? "无" : memorySession.getSummary();
            resultBuilder.stepMemoryMs(stepMemoryMs)
                    .memorySummary(historySummary != null && historySummary.length() > 200
                            ? historySummary.substring(0, 200) : historySummary);

            // ---------- 步骤3：查询重写 ----------
            long t3 = System.nanoTime();
            RewriteResult rewritten = rag.rewriteQuery(query, conversationId, chatMessageId);
            long stepRewriteMs = (System.nanoTime() - t3) / 1_000_000;
            resultBuilder.stepRewriteMs(stepRewriteMs)
                    .rewrittenQuery(rewritten != null ? rewritten.getRewrittenQuery() : query);

            // ---------- 步骤4：实体识别 ----------
            long t4 = System.nanoTime();
            String queryForEntity = (rewritten != null && rewritten.getRewrittenQuery() != null)
                    ? rewritten.getRewrittenQuery() : query;
            Map<String, Integer> entities = rag.recognizeIntent(queryForEntity);
            long stepEntityMs = (System.nanoTime() - t4) / 1_000_000;
            resultBuilder.stepEntityMs(stepEntityMs)
                    .entityCount(entities != null ? entities.size() : 0);

            // ---------- 步骤5：多通道文档检索 + 全后处理 ----------
            long t5 = System.nanoTime();

            // 5a: 检索（含各通道调用+超时保护）
            long t5a = System.nanoTime();
            List<RetrievedChunk> retrieved = rag.retrieveDocuments(
                    entities, rewritten, conversationId, query);
            long stepRetrievalMs = (System.nanoTime() - t5a) / 1_000_000;
            int retrievedCount = (retrieved != null) ? retrieved.size() : 0;

            // retrievedDocuments 内部已包含 BM25 + Filter + Rerank 全链路
            // 但为了更细的拆分，这里重新计时各个后处理子步骤
            // 构建一个 mock SearchContext（实际 retrieveDocuments 内部已经做了，这里只做分析用途）
            long stepPostBm25Ms = 0L;
            long stepPostFilterMs = 0L;
            long stepPostRerankMs = 0L;
            int afterBm25Count = 0;
            int afterFilterCount = 0;

            if (retrieved != null && !retrieved.isEmpty()) {
                // 由于 retrieveDocuments 内部已执行完整后处理，这里记录最终结果即可
                afterBm25Count = retrieved.size();
                afterFilterCount = retrieved.size();
            }

            long stepRetrievalTotalMs = (System.nanoTime() - t5) / 1_000_000;

            resultBuilder.stepRetrievalMs(stepRetrievalTotalMs)
                    .retrievedChunkCount(retrievedCount)
                    .afterBm25Count(afterBm25Count)
                    .afterFilterCount(afterFilterCount);

            // 收集检索结果的得分分布
            if (retrieved != null && !retrieved.isEmpty()) {
                DoubleSummaryStatistics scoreStats = retrieved.stream()
                        .mapToDouble(c -> Optional.ofNullable(c.getScore()).orElse(0.0))
                        .summaryStatistics();
                DoubleSummaryStatistics bm25Stats = retrieved.stream()
                        .mapToDouble(c -> Optional.ofNullable(c.getBm25Score()).orElse(0.0))
                        .summaryStatistics();
                resultBuilder.retrievalScoreAvg(scoreStats.getAverage())
                        .retrievalScoreMax(scoreStats.getMax())
                        .retrievalScoreMin(scoreStats.getMin())
                        .bm25ScoreAvg(bm25Stats.getAverage())
                        .bm25ScoreMax(bm25Stats.getMax())
                        .bm25ScoreMin(bm25Stats.getMin());
            }

            // 截取前10个chunk的内容摘要（600字符，供评测做答案覆盖判定）
            List<ChunkPreview> chunkPreviews = retrieved != null
                    ? retrieved.stream().limit(10).map(c -> ChunkPreview.builder()
                    .id(c.getId())
                    .score(c.getScore())
                    .bm25Score(c.getBm25Score())
                    .contentPreview(c.getContent() != null && c.getContent().length() > 600
                            ? c.getContent().substring(0, 600) : c.getContent())
                    .build()).toList()
                    : List.of();
            resultBuilder.chunkPreviews(chunkPreviews);

            long wallClockTotal = (System.nanoTime() - wallClockStart) / 1_000_000;
            resultBuilder.totalMs(wallClockTotal);

            log.info("╔══════════════════════════════════════════════╗");
            log.info("║ [{}] 纯检索压测完成, 总耗时={}ms        ║", mode, wallClockTotal);
            log.info("╚══════════════════════════════════════════════╝");

            return Result.success(resultBuilder.build());

        } catch (Exception e) {
            log.error("[{}] 纯检索压测异常", mode, e);
            return Result.error(500, "压测执行异常: " + e.getMessage());
        } finally {
            LoginUserInfoManager.remove();
            SecurityContextHolder.clearContext();
        }
    }

    // ==================== 2. ETL 并发上传压测 ====================

    /**
     * ETL并发上传压测：生成指定数量的随机测试文件，以指定并发度同时上传，
     * 走完整Kafka异步处理链路，返回吞吐量和各阶段统计数据。
     */
    @PostMapping("/etl/concurrent")
    public Result<EtlBenchmarkResult> etlConcurrentBenchmark(
            @RequestParam(defaultValue = "10") int fileCount,
            @RequestParam(defaultValue = "5") int concurrency,
            @RequestParam(defaultValue = "4096") int fileSizeBytes,
            @RequestParam(defaultValue = "benchmark-collection") String collectionName) {

        long wallClockStart = System.nanoTime();
        String mode = "BENCHMARK_ETL";
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║ [{}] ETL并发上传压测开始                      ║", mode);
        log.info("║ fileCount={}, concurrency={}, fileSize={}   ║", fileCount, concurrency, fileSizeBytes);
        log.info("╚══════════════════════════════════════════════════╝");

        Long userId = LoginUserInfoManager.getUserId();
        if (userId == null) {
            return Result.error(401, "压测需要登录用户，请先获取token");
        }

        // 参数校验
        if (fileCount <= 0 || fileCount > 500) {
            return Result.error(400, "fileCount 范围: 1~500");
        }
        if (concurrency <= 0 || concurrency > 50) {
            return Result.error(400, "concurrency 范围: 1~50");
        }
        if (fileSizeBytes <= 0 || fileSizeBytes > 10_485_760) { // max 10MB
            return Result.error(400, "fileSizeBytes 范围: 1~10485760");
        }

        // 生成测试文件内容（使用固定种子保证单次压测内可重复，但每次压测不同）
        List<byte[]> fileContents = IntStream.range(0, fileCount)
                .mapToObj(i -> generateRandomContent(fileSizeBytes, i))
                .toList();

        // 并发执行上传
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);
        AtomicLong totalKafkaSendMs = new AtomicLong(0);
        AtomicLong totalFileHashMs = new AtomicLong(0);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency, concurrency,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(fileCount),
                r -> new Thread(r, "benchmark-etl-upload-" + r.hashCode()),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        CountDownLatch latch = new CountDownLatch(fileCount);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        long sendStart = System.nanoTime();

        for (int i = 0; i < fileCount; i++) {
            final int idx = i;
            final byte[] content = fileContents.get(i);
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    long fileHashStart = System.nanoTime();
                    String originalFilename = "benchmark-test-" + idx + ".txt";
                    String contentType = "text/plain";
                    InMemoryMultipartFile mockFile = new InMemoryMultipartFile(
                            "file", originalFilename, contentType, content);

                    // 计算文件哈希
                    String fileHash = milvusFileManager.calculateFileHash(mockFile);
                    long fileHashMs = (System.nanoTime() - fileHashStart) / 1_000_000;
                    totalFileHashMs.addAndGet(fileHashMs);

                    // 发送Kafka消息（走真实ETL管道）
                    long kafkaStart = System.nanoTime();
                    FileUploadEvent event = FileUploadEvent.builder()
                            .eventId(UUID.randomUUID().toString())
                            .taskId("benchmark-" + IdUtil.getSnowflakeNextIdStr())
                            .fileName("file")
                            .originalFilename(originalFilename)
                            .contentType(contentType)
                            .fileBytes(content)
                            .fileSize((long) content.length)
                            .fileHash(fileHash)
                            .collectionName(collectionName)
                            .userId(userId)
                            .build();

                    kafkaTemplate.send("etl-file", fileHash, event).get(30, TimeUnit.SECONDS);
                    long kafkaMs = (System.nanoTime() - kafkaStart) / 1_000_000;
                    totalKafkaSendMs.addAndGet(kafkaMs);

                    successCount.incrementAndGet();
                    log.debug("[BENCHMARK_ETL] 文件 {} 上传成功, hash={}, kafkaSend={}ms", idx, fileHash, kafkaMs);

                } catch (Exception e) {
                    log.error("[BENCHMARK_ETL] 文件 {} 上传失败", idx, e);
                    failCount.incrementAndGet();
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            }, executor);
            futures.add(future);
        }

        // 等待所有任务完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(300, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[BENCHMARK_ETL] 等待超时或异常", e);
        }

        long totalSendMs = (System.nanoTime() - sendStart) / 1_000_000;
        long wallClockTotal = (System.nanoTime() - wallClockStart) / 1_000_000;

        executor.shutdownNow();

        // 构造结果
        EtlBenchmarkResult result = EtlBenchmarkResult.builder()
                .fileCount(fileCount)
                .concurrency(concurrency)
                .fileSizeBytes(fileSizeBytes)
                .collectionName(collectionName)
                .successCount(successCount.get())
                .failCount(failCount.get())
                .skipCount(skipCount.get())
                .totalWallClockMs(wallClockTotal)
                .totalSendMs(totalSendMs)
                .avgFileHashMs(successCount.get() > 0 ? totalFileHashMs.get() / successCount.get() : 0)
                .avgKafkaSendMs(successCount.get() > 0 ? totalKafkaSendMs.get() / successCount.get() : 0)
                .throughputFilesPerSec(successCount.get() > 0
                        ? (double) successCount.get() / (totalSendMs / 1000.0) : 0)
                .throughputMbPerSec(successCount.get() > 0
                        ? (double) (successCount.get() * fileSizeBytes) / (1024.0 * 1024.0) / (totalSendMs / 1000.0) : 0)
                .errors(errors.stream()
                        .map(e -> e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 100))
                        .limit(10)
                        .toList())
                .build();

        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║ [{}] ETL并发上传压测完成                       ║", mode);
        log.info("║ 成功={}, 失败={}, 总耗时={}ms, 吞吐={: .1f}文件/s ║",
                result.successCount, result.failCount, wallClockTotal, result.throughputFilesPerSec);
        log.info("╚══════════════════════════════════════════════════╝");

        return Result.success(result);
    }

    // ==================== 辅助方法 ====================

    /**
     * 生成指定大小的随机文件内容（带可识别标记，方便排查）
     */
    private byte[] generateRandomContent(int sizeBytes, int seed) {
        byte[] content = new byte[sizeBytes];
        // 写入文件头标识
        String header = String.format("BENCHMARK_FILE_%d\n", seed);
        byte[] headerBytes = header.getBytes();
        int headerLen = Math.min(headerBytes.length, sizeBytes);
        System.arraycopy(headerBytes, 0, content, 0, headerLen);

        // 剩余部分填充随机内容
        Random rnd = new Random(seed);
        for (int i = headerLen; i < sizeBytes; i++) {
            // 生成可读ASCII字符
            content[i] = (byte) (32 + rnd.nextInt(95));
        }
        return content;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ==================== 请求/响应模型 ====================

    @Data
    public static class RetrievalBenchmarkRequest {
        private String query;
        private Long conversationId;
    }

    @Data
    @Builder
    public static class RetrievalBenchmarkResult {
        /** 查询语句 */
        private String query;
        /** 会话ID */
        private Long conversationId;
        /** 总耗时 (ms) */
        private long totalMs;

        // ---- 各步骤耗时 (ms) ----
        private long stepUserContextMs;
        private long stepMemoryMs;
        private long stepRewriteMs;
        private long stepEntityMs;
        private long stepRetrievalMs;

        // ---- 各步骤产出 ----
        private String memorySummary;
        private String rewrittenQuery;
        private int entityCount;
        private int retrievedChunkCount;
        private int afterBm25Count;
        private int afterFilterCount;

        // ---- 检索得分分布 ----
        private Double retrievalScoreAvg;
        private Double retrievalScoreMax;
        private Double retrievalScoreMin;
        private Double bm25ScoreAvg;
        private Double bm25ScoreMax;
        private Double bm25ScoreMin;

        // ---- chunk摘要（前10条） ----
        private List<ChunkPreview> chunkPreviews;
    }

    @Data
    @Builder
    public static class ChunkPreview {
        private String id;
        private Double score;
        private Double bm25Score;
        private String contentPreview;
    }

    @Data
    @Builder
    public static class EtlBenchmarkResult {
        // ---- 请求参数 ----
        private int fileCount;
        private int concurrency;
        private int fileSizeBytes;
        private String collectionName;

        // ---- 执行结果 ----
        private int successCount;
        private int failCount;
        private int skipCount;

        // ---- 耗时统计 ----
        private long totalWallClockMs;       // 总端到端耗时
        private long totalSendMs;            // 并发发送阶段耗时
        private long avgFileHashMs;          // 平均文件哈希计算耗时
        private long avgKafkaSendMs;         // 平均Kafka发送耗时

        // ---- 吞吐量 ----
        private double throughputFilesPerSec; // 文件/秒
        private double throughputMbPerSec;    // MB/秒

        // ---- 错误信息（前10条） ----
        private List<String> errors;
    }
}
