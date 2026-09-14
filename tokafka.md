# Kafka 改造计划

> 目标：使用 Kafka 解耦跨系统操作，解决数据不一致问题，降低用户请求延迟。

---

## 一、现状分析

### 线程池一览

| Bean | 线程模型 | 用途 |
|------|---------|------|
| uploadExecutor | 虚拟线程 | 上传 ETL 流水线 |
| traceDbExecutor | 平台线程(2-4, queue=2048) | 链路追踪写入 MySQL |
| evaluateExecutor | 虚拟线程 | 评估结果入库 |
| memeryExecutor | 虚拟线程 | 记忆持久化 |
| summaryExecutor | 虚拟线程 | LLM 摘要生成 |
| neo4jExecutor | 虚拟线程 | Neo4j 图谱操作 |
| searchChannelExecutor | 虚拟线程 | 多通道检索 |
| chatExecutor | 虚拟线程 | 流式对话 |
| 其余(mcp/intent/graph/milvus) | 虚拟线程 | 各自领域 |

### 系统间依赖

```
UploadController
  └─ IngestionEngine.execute()         同步链，无事务
       ├─ Parser                       → 纯内存
       ├─ Chunker                      → 纯内存
       ├─ Enricher                     → LLM + Neo4j (fire-and-forget)
       └─ Indexer                      → Milvus + MySQL + Redis
                                                 ↑ 这三个系统无事务

MilvusController
  ├─ /delete/doc                       → Redis(读+删) + Milvus(删)
  ├─ /delete                           → Redis(删权限) + Milvus(未删! 向量残留)
  ├─ /share                            → Redis(多key写入)
  └─ /share/accept                     → Redis(验证+授权)

ChatOrchestrator
  └─ doChat                            → SSE流式(不换)
       ├─ RAG pipeline                 → 同步join
       ├─ SystemEvaluateService        → fire-and-forget
       └─ ConversationMemorySummary    → fire-and-forget
```

---

## 二、改造列表

### A. 异步中适合换 Kafka 的（8处）

| # | 位置 | 当前线程池 | 做的事 | 跨系统 | 延迟收益 | 优先级 |
|---|------|-----------|--------|-------|---------|-------|
| A1 | UploadController.processFilesInAsync | uploadExecutor | ETL全链：Parser→Chunker→Enricher→Indexer + DB + Redis | MySQL+Milvus+Redis | 30s→1ms | **P0** |
| A2 | RagTraceAspect (15+调用点) | traceDbExecutor | @RagTraceNode拦截写链路追踪 | MySQL | 攒批写入 | **P1** |
| A3 | SystemEvaluateService.submitEvaluate | evaluateExecutor | LLM评估+规则评估+评分入库 | MySQL | 3-15s移出 | **P1** |
| A4 | ConversationMemorySummaryService.doCompressIfNeeded | memeryExecutor | 检查阈值+触发压缩 | Redis+MySQL | LLM移出 | **P2** |
| A5 | ConversationMemorySummaryService.saveMessage | memeryExecutor | 保存聊天消息到MySQL | MySQL | 可靠性 | **P2** |
| A6 | ConversationMemorySummaryService.generateAndSaveSummary | summaryExecutor | LLM摘要+存Redis+存DB | Redis+MySQL | 重LLM可重试 | **P2** |
| A7 | Enricher → batchInsertTriples | uploadExecutor | LLM富化后写Neo4j | Neo4j | 可靠性 | **P2** |
| A8 | MultiChannelRetrievalEngine.incrementFileChunkCount | searchChannelExecutor | 检索递增加1 | MySQL | 可靠性 | **P3** |

### B. 异步中不换 Kafka 的（9处）

| # | 位置 | 做的事 | 原因 |
|---|------|--------|------|
| B1 | ChatOrchestrator.doChat | SSE流式对话 | 实时响应路径 |
| B2 | ModelRoutingExecutor | 多模型anyOf取最快 | 延迟敏感 |
| B3 | RetrievalAugmentedGeneration.loadMCPToolsAsync | MCP工具→join到prompt | 结果必须join |
| B4 | MultiChannelRetrievalEngine.多通道检索 | 各通道并行join | 结果合并后构建prompt |
| B5 | VectorGlobalSearchChannel | 多query+双向量join | join后返回引擎 |
| B6 | ToolDecisionManager | MCP并行join | 结果注入prompt |
| B7 | IntentRecognitionServiceIml | 多查询并行join | 结果驱动检索 |
| B8 | Indexer.batchInsertWithParallelEmbedding | 同批次并行embedding→join | 内部I/O并发 |
| B9 | OssServiceImpl.partUploadExecutor | OSS分片并发 | 内部I/O并发 |

### C. 同步操作中"必须"解耦的（14处）🔥

| # | 位置 | 做的事 | 跨系统 | 当前风险 | 优先级 |
|---|------|--------|-------|---------|-------|
| C1 | **Indexer.execute → saveFileRecord** | Milvus成功后写MySQL文件记录 | **Milvus→MySQL** | Milvus成功→MySQL失败→有向量无记录 | **P0** |
| C2 | **Indexer.execute → addFileUserACl** | Milvus成功后写Redis权限位图 | **Milvus→Redis** | Milvus成功→Redis失败→搜不到 | **P0** |
| C3 | **MilvusController.dropFileChunk** | 删Redis权限+删Milvus向量 | **Redis+Milvus** | Redis成功→Milvus失败→权限被删但向量还在 | **P0** |
| C4 | **MilvusCollectionService.drop** | 删Redis权限(**但不删Milvus!**) | **Redis+Milvus(没删)** | 向量永久残留，永远无法清理 | **P0** |
| C5 | **MilvusCollectionService.rebuild** | 只删Redis(**不重建!**) | **Redis(只删不建)** | 同drop，向量残留 | **P0** |
| C6 | **MilvusAclManager.deleteDocumentAcl** | 读Redis映射→删位图→写计数→删Milvus | **Redis→Milvus** | 中间中断→权限不一致 | **P1** |
| C7 | **MilvusAclManager.addFileUserACl(List)** | 写Redis位图+集合映射+文件ID(3个key) | **Redis(批量)** | 部分key写入失败→权限不完整 | **P1** |
| C8 | **MilvusAclManager.grantFileChunksToUser** | Lua执行SETBIT+INCR批量写 | **Redis(批量)** | 执行中断→位与计数器不一致 | **P1** |
| C9 | **MilvusFileManager.shareFiles** | 读Redis→授权→grant位图 | **Redis(3步)** | 中间失败→部分授权 | **P1** |
| C10 | **MilvusFileManager.updateFileChunk** | 删旧数据+重新插入管道 | **Redis+MySQL+Milvus** | 删成功但重新插入失败→数据丢失 | **P1** |
| C11 | **MilvusController.share/accept** | 验证用户+shareFiles授权 | **MySQL→Redis** | 验证通过但授权失败→用户不知情 | **P2** |
| C12 | **Neo4jKnowledgeGraphService.deleteChunkRelations** | 删文件时清理Neo4j关系 | **Neo4j** | 删向量成功但删图谱失败→孤儿节点 | **P2** |
| C13 | **MilvusAclManager.deleteCollectionDocumentAcl** | 批量删Redis用户文件位图 | **Redis(批量)** | 批量删中断→残留权限 | **P2** |
| C14 | **UploadController.uploadToOss** | OSS上传(重试3次) | **HTTP外部** | OSS失败阻塞整个ETL | **P3** |

---

## 三、Kafka 主题设计

### Topic 定义

| Topic | 分区策略 | 保留期 | 消费组 | 包含的操作 |
|-------|---------|--------|-------|-----------|
| `etl-file` | fileHash | 7天 | `ingest-group` | A1前半段(Parser+Chunker)，发出N个chunk消息 |
| `etl-chunk` | null(轮询) | 7天 | `chunk-group`(分区数=并发度) | Enricher+Indexer+增量ACL+完成追踪 |
| `acl-mutate` | collectionName | 7天 | `acl-group` | C3~C11, C13 |
| `analytics-log` | 轮询 | 3天 | `analytics-group` | A2, A3, A8 |
| `memory-persist` | conversationId | 3天 | `memory-group` | A4, A5, A6 |
| `neo4j-cleanup` | 轮询 | 3天 | `neo4j-group` | C12, A7 |

### 合并建议（Topic 有限时）

```
Topic: pipeline-file     # 文件级串行(16分区，fileHash%16)
  └─ A1前半段(Parse+Chunk)

Topic: pipeline-chunk    # chunk级并行(8分区，key=null轮询)
  └─ Enricher+Indexer+增量ACL

Topic: data-mutate       # 权限变更(partition=collectionName)  
  └─ C3~C11, C13, C6

Topic: async-log         # 异步日志(partition=轮询)
  └─ A2, A3, A8, A4, A5, C12

---

## 四、关键设计模式

### 4.1 上传 ETL（chunk级并行流水线）

```
topic: etl-file          # 文件级：Parser→Chunker（串行）
topic: etl-chunk         # chunk级：Enrich→Embed→Insert（并行）

生产者(UploadController)
  │
  ├─ send(etl-file, fileHash, FileUploadEvent)
  │   └─ 1ms返回，用户不等待
  │
  ▼
etl-file 消费者 (单线程消费同一fileHash)
  │
  ├─ Parser (解析文件 → 纯文本)
  ├─ Chunker (分块 → N个chunk)
  │
  ├─ 对每个chunk: send(etl-chunk, chunkId, ChunkEvent)
  │
  └─ 完成后：commit offset

  ┌──────────────────────────────────────────────────┐
  │ etl-chunk 消费者池 (多线程并行，N个消费者)         │
  │                                                 │
  │ chunk_1 → enrich(LLM) → embed → Milvus insert   │
  │ chunk_2 → enrich(LLM) → embed → Milvus insert   │
  │ chunk_3 → enrich(LLM) → embed → Milvus insert   │
  │ ...                                              │
  │                                                 │
  │ 每个chunk独立:                                    │
  │   成功 → 写Redis ACL增量 + commit                │
  │   失败 → retry 3次 → DLT(不影响其他chunk)         │
  └──────────────────────────────────────────────────┘

当某个fileHash的所有chunk都入库完成:
  ─► 写MySQL文件记录(COMPLETED)
  ─► 触发Neo4j三元组入库
```

**为什么这样设计：**

```
当前(串行):
  时间 │━━ Parsing ━━│━━ Chunking ━━│━━ Enrich(全部) ━━━━━━━━━━━│━━ Index(全部) ━━│
       └── 1s ──────┘── 0.5s ─────┘── LLM×N = N×3s ──────────┘── 1s ──────────┘
                                                                   总时间 ≈ N×3 + 2.5s

改造后(chunk并行):
  时间 │━━ P ━━│━━ C ━━│
       │              ├─ chunk_1 → Enrich(LLM) → Embed → Insert │
       │              ├─ chunk_2 → Enrich(LLM) → Embed → Insert │
       │              ├─ chunk_3 → Enrich(LLM) → Embed → Insert │
       │              └─ ...                                     │
       └── 1.5s ────┘
                              总时间 ≈ 1.5s + max(chunk并行) ≈ 1.5s + 3s = 4.5s
                             (当前: N=10时 ≈ 32s, 改造后 ≈ 4.5s)
```

```java
// ============ Topic ============
// etl-file:   file级，partition=fileHash，保证同一文件串行
//            (固定16分区，fileHash%16分配)
// etl-chunk:  chunk级，key=null(轮询)，多消费者并行消费
//            (固定8分区，和consumer并发数一致，chunkId哈希均匀分布)
//            说明: 分区数在创建topic时固定，不随chunk数量变化。
//            用null key让Kafka轮询分配，天然负载均衡。
//            即使一个文件有1000个chunk，也只分散到8个分区。

// ============ 生产者 ============
// UploadController
@PostMapping("/upload")
public Result<String> upload(MultipartFile file) {
    String taskId = IdUtil.getSnowflakeNextId();
    kafkaTemplate.send("etl-file", fileHash, new FileUploadEvent(taskId, fileBytes));
    return Result.success(taskId);  // 1ms 返回
}

// ============ etl-file 消费者 ============
// 串行: Parser → Chunker → 每个chunk发消息 → 记录文件总chunk数到Redis
@KafkaListener(topics = "etl-file", groupId = "ingest-group")
public void consumeFile(FileUploadEvent event) {
    // 1. Parser
    String text = parser.parse(event.getFileBytes());
    // 2. Chunker
    List<Chunk> chunks = chunker.chunk(text);
    // 3. 记录文件总chunk数到Redis（用于判断所有chunk是否完成）
    uploadTracker.initFile(event.getTaskId(), chunks.size());
    // 4. 每个chunk独立发送消息
    for (Chunk chunk : chunks) {
        kafkaTemplate.send("etl-chunk", chunk.getId(),
            new ChunkEvent(event.getTaskId(), chunk, event.getCollectionName(), event.getUserId()));
    }
}

// ============ etl-chunk 消费者 ============
// 并行: enrich → embed → Milvus insert → 增量Redis ACL → 通知完成
// key=null(轮询)，chunk间无顺序要求，天然负载均衡
@KafkaListener(topics = "etl-chunk", groupId = "chunk-group", concurrency = "8")
public void consumeChunk(ChunkEvent event) {
    // 1. Enrich(LLM: 生成假设问题+三元组)
    Chunk enriched = enricher.enrich(event.getChunk());

    // 2. Embed + Milvus insert
    indexer.insertSingle(enriched);

    // 3. 增量写Redis ACL(只写这一个chunk的位)
    milvusAclManager.grantSingleChunk(event.getUserId(), enriched.getId(), event.getCollectionName());

    // 4. 通知该chunk完成
    uploadTracker.completeOne(event.getTaskId());

    // 5. 如果该文件所有chunk都完成了 → 收尾
    if (uploadTracker.isFileComplete(event.getTaskId())) {
        // 5a. 写MySQL文件记录
        fileRecordMapper.insert(event.getFileRecord());
        // 5b. 触发Neo4j三元组入库(批量)
        neo4jService.batchInsertTriples(event.getAllTriples());
        // 5c. 清理tracker
        uploadTracker.cleanup(event.getTaskId());
    }
}
```

### 4.2 chunk完成跟踪器（UploadTracker）

```java
@Component
public class UploadTracker {
    // Redis: xyai:upload:{taskId}:total = 总chunk数
    // Redis: xyai:upload:{taskId}:done = 已完成的chunk数(INCR)
    // Redis: xyai:upload:{taskId}:status = PENDING/COMPLETED/FAILED

    public void initFile(String taskId, int totalChunks) {
        stringRedisTemplate.opsForValue()
            .set("xyai:upload:" + taskId + ":total", String.valueOf(totalChunks));
        stringRedisTemplate.opsForValue()
            .set("xyai:upload:" + taskId + ":done", "0");
    }

    public void completeOne(String taskId) {
        Long done = stringRedisTemplate.opsForValue()
            .increment("xyai:upload:" + taskId + ":done");
        String total = stringRedisTemplate.opsForValue()
            .get("xyai:upload:" + taskId + ":total");
        if (done != null && total != null && done >= Long.parseLong(total)) {
            // 所有chunk完成
        }
    }

    public boolean isFileComplete(String taskId) {
        String done = stringRedisTemplate.opsForValue()
            .get("xyai:upload:" + taskId + ":done");
        String total = stringRedisTemplate.opsForValue()
            .get("xyai:upload:" + taskId + ":total");
        return done != null && total != null
            && Long.parseLong(done) >= Long.parseLong(total);
    }

    public void cleanup(String taskId) {
        stringRedisTemplate.delete(
            "xyai:upload:" + taskId + ":total",
            "xyai:upload:" + taskId + ":done"
        );
    }
}
```

### 4.3 权限变更（acl-mutate）

```java
// 生产端：所有ACL操作改为发消息
public void addFileUserACl(List<Document> documents, String collectionName) {
    kafkaTemplate.send("acl-mutate", collectionName, new AclGrantEvent(documents, collectionName));
}

// 消费端：执行真正的Redis写入
@KafkaListener(topics = "acl-mutate", groupId = "acl-group")
public void consumeAcl(AclGrantEvent event) {
    // 幂等执行SETBIT+INCR
    // 成功 → commit
    // 失败 → 重试3次 → DLT
}
```

### 4.4 状态机 + 幂等保证

```
每个消息携带:
  - eventId: UUID (幂等键)
  - sourceSystem: 来源
  - payload: 业务数据
  
消费者:
  1. 查Redis去重表(eventId已处理？→跳过)
  2. 执行业务逻辑
  3. 标记eventId为已处理
  4. commit offset
```

### 4.5 对账机制

```java
@Scheduled(fixedDelay = 300_000)  // 每5分钟
public void reconcile() {
    // 1. 扫描 Redis 中 status=PENDING 超过10分钟的任务
    // 2. 对每个任务: 查 Milvus 中实际存在的chunk数
    //    - chunk数=total → 补写MySQL文件记录+Redis ACL
    //    - chunk数&lt;total → 重新发缺失chunk到 etl-chunk
    //    - chunk数=0 → 标记失败+清理
    // 3. 扫描 Milvus 中的孤儿数据(有向量但MySQL无记录)
    //    - 从 Milvus 删除
}
```

---

## 五、实施路线图

```
Phase 1 (P0) — 3天
┌─────────────────────────────────────────────────────┐
│ etl-file + etl-chunk topic                          │
│ ├─ UploadController 改为只发消息，不阻塞等待           │
│ ├─ etl-file消费者: Parser→Chunker→发出N个chunk消息   │
│ ├─ etl-chunk消费者池: Enrich+Embed+Insert+增量ACL    │
│ ├─ UploadTracker: Redis跟踪chunk完成状态             │
│ ├─ 全部chunk完成→写MySQL文件记录+Neo4j三元组          │
│ ├─ drop/rebuild 改为发消息+真正执行Milvus删除/重建     │
│ └─ dropFileChunk ACL+向量操作原子化                   │
│ 收益: 上传不卡顿 + 三系统一致 + chunk级并行(N=10时     │
│       32s→4.5s) + 无残留向量                         │
└─────────────────────────────────────────────────────┘

Phase 2 (P1) — 2天
┌─────────────────────────────────────────────────────┐
│ acl-mutate + analytics-log topic                    │
│ ├─ 所有ACL操作(REDIS批量位图) → 消息队列            │
│ ├─ 链路追踪traceDbExecutor → 攒批写入               │
│ ├─ 评估evaluateExecutor → 异步可靠                   │
│ └─ shareFiles → 幂等授权                             │
│ 收益: ACL操作一致 + DB写入压力降低                    │
└─────────────────────────────────────────────────────┘

Phase 3 (P2) — 2天
┌─────────────────────────────────────────────────────┐
│ memory-persist + neo4j-cleanup topic                │
│ ├─ 对话消息持久化 → Kafka消费者                      │
│ ├─ LLM摘要生成 → 可靠异步(失败重试)                  │
│ ├─ Neo4j三元组入库 + 关系清理 → 消息驱动              │
│ └─ Neo4j孤儿节点回收                                 │
│ 收益: 不丢消息 + 图谱一致                             │
└─────────────────────────────────────────────────────┘

Phase 4 (P3) — 1天
┌─────────────────────────────────────────────────────┐
│ 优化 + 对账                                          │
│ ├─ use-count → async可靠计数                        │
│ ├─ OSS上传 → 从ETL主路径移出                        │
│ └─ 定时对账任务(扫描PENDING任务+Milvus孤儿数据)      │
│ 收益: 兜底保护                                       │
└─────────────────────────────────────────────────────┘
```

---

## 六、风险与注意

| 风险 | 应对 |
|------|------|
| Kafka 成为新单点 | 集群部署，acks=all |
| 消息乱序(同一文件的chunk) | partition=fileHash，保证同一文件顺序 |
| 消息重复消费 | eventId 幂等去重 |
| 消费者积压 | 监控lag，动态扩容consumer |
| 历史数据迁移 | 现有数据不动，只对新数据走Kafka |
| 消费失败导致消息堆积 | DLT死信队列，人工介入 |
