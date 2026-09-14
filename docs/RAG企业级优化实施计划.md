# RAG 企业级优化实施计划（2026-09-08）

> 配套调研报告：`docs/RAG系统对标研究与优化路线.md`
> 范围：安全 / 评估闭环 / RAG 主链路 / 知识处理深度，四个包共 14 项，用户已确认全部实施。
> 注意：根目录 `plan.md` 是数据库查询优化方案，与本计划互不影响。

## 包① 安全（P0）

| # | 问题（代码定位） | 方案 | 文件 |
|---|---|---|---|
| 1 | ACL 是检索后过滤：`VectorGlobalSearchChannel.searchByField()` 的 SearchParam 无 withExpr，全库裸搜 Top-5 后由 FilterPostProcessor 事后剔除；未授权文档占满名额导致有权内容检索不到 | 查询层 expr 下推：`metadata["visibility"] == "public" OR doc_id like "{fileId}:%"`（文件级，授权文件集来自 Redis，与 FilterPostProcessor.loadAuthorizedFileIds 同语义）；后置 chunk 级过滤保留为纵深防御；expr 子句上限 200，超出降级为不下推并告警 | `VectorGlobalSearchChannel`、`MilvusAclManager`（新增 getAuthorizedFileIds）、`RetrievalProperties`（新增开关） |
| 2 | SYSTEM_MESSAGE 第4条"参考资料：作为辅助，无关忽略"在对抗 RAG；无拒答约束、无引用要求 | 重写为 grounded prompt：仅基于参考文档回答 + [n] 引用 + 证据不足明确拒答 | `ModelInvocationService` |
| 3 | 入库无注入清洗（零宽字符/隐藏文本/HTML注释可携带间接注入载荷） | TextCleaner 增加确定性清洗：零宽与双向控制字符、HTML 注释、NFKC 归一化 | `TextCleaner` |
| 4 | `MultiChannelRetrievalEngine.retrieve()` 无通道时返回 null，下游 NPE 风险 | 返回 List.of() | `MultiChannelRetrievalEngine` |

## 包② 评估闭环（P0）

| # | 问题 | 方案 | 文件 |
|---|---|---|---|
| 5 | 🔴 `Enricher.execute()` 第40行 `if(chunk!=null){return chunk;}` 条件反写，LLM 增强/三元组抽取为死代码 | 改为 chunk==null 时返回 | `Enricher` |
| 6 | 有单条消息评估（LLM/Rerank/Rule F1），无批量黄金集基准 | 新表 `xy_evaluate_golden_case`（question/ground_truth/expected_doc_ids/source/enabled）与 `xy_evaluate_golden_result`（run_id/case_id/context_precision/context_recall/hit_rate/latency_ms/detail）；`GoldenSetService.runAll()` 逐条跑检索链路算指标；管理端 CRUD + 一键跑批；门控阈值配置 `evaluate.golden.*` | 新增 mapper/pojo/service + `EvaluateManager` 暴露接口 + SQL |
| 7 | 点踩消息无回流 | userEvaluate 反馈为差评时自动插入待审黄金用例（source=BADCASE, enabled=0），人工完善后启用 | `EvaluateImpl` |

## 包③ RAG 主链路（P0/P1）

| # | 方案 | 文件 |
|---|---|---|
| 8 引用溯源 | buildRAGResult 将 chunks 组装为 `[n] (来源:docId)\n内容`；Prompt 要求 [n] 引用 | `RetrievalAugmentedGeneration` |
| 9 CRAG 门控 | rerank 最高分 < 阈值 → 用原问题+改写问题重检索一次 → 仍低则 RAGResult 注入"证据不足"提示（配合 #2 拒答） | 新增 `RetrievalQualityGate`，`ChatOrchestrator` 接入 |
| 10 深度提升 | 通道召回 TopK 5→20（配置化 retrieval.channel-top-k），精排后最终 TopK 配置 retrieval.final-top-k（默认8），SearchContext.topK 由引擎统一设置 | `VectorGlobalSearchChannel`、`MultiChannelRetrievalEngine`、`RetrievalProperties` |
| 11 多层缓存 | L1 查询 embedding 缓存（Redis, key=sha256(query)）；L2 检索结果缓存（key=归一化query+userId，TTL 10min）；L3 语义检索缓存（embedding 相似度≥0.93 命中返回缓存 chunk 列表）；答案缓存（query+retrieval hash→完整回答，命中模拟流式回放）。全部带 userId 权限维度 | 新增 `RetrievalCacheService`，`VectorGlobalSearchChannel`/`ChatOrchestrator` 接入 |

## 包④ 知识处理深度（P1）

| # | 方案 | 文件 |
|---|---|---|
| 12 Contextual Retrieval | Chunker 已有 section_path；Enricher（修复后）生成 LLM 上下文说明，与标题/章节路径合成 context_prefix 写入 metadata；Indexer 将 context_prefix 拼在 embedding_context 文本前 | `Chunker`/`Enricher`/`Indexer` |
| 13 父子切片 | 每章节生成父块（metadata parent=true，doc_id 后缀 `:p`），与子块一同入库；检索召回/精排仅用子块；rerank 后 ParentExpandProcessor 将 Top 子块展开为父块内容（Milvus 批量查询），解决"命中碎片、生成缺上下文" | `Chunker`/新增 `ParentExpandProcessor`/`Indexer`/`VectorGlobalSearchChannel` |

## 实施顺序与验收

1. 包① → 2. 包② → 3. 包③ → 4. 包④。每包完成后 `mvnw compile` 验证编译。
5. 黄金集跑批结果 + 压测复测（TTFT、缓存命中对照）作为最终验收数据。
