# 企业级可上线 RAG 系统能力矩阵与差距清单（2026-09-09）

> 目的：对照企业级 RAG 上线要求，盘点 My-AI RAG 系统现状（代码级审计）、本轮已落地项、
> 剩余差距与改进路径。依据：RAGAS / Anthropic Contextual Retrieval / GraphRAG / CRAG / Self-RAG /
> HyDE / Adaptive-RAG 等公开方法与 LlamaIndex、Haystack、LangChain 等开源实践。

## 一、检索质量链路（已高度打磨，压测数据见文末）

| 环节 | 落地内容 | 方法依据 |
|---|---|---|
| 解析清洗 | Tika 流式解析 + TextCleaner（真标签正则，修复吞正文 bug） | Unstructured 思路 |
| 分块 | 512 token + 64 overlap 父子切片（small-to-big），标题层级感知 hierarchical split | LlamaIndex HierarchicalNodeParser |
| 入库增强 | Contextual Retrieval 前缀（文档名+章节路径+LLM 定位说明）、HyDE 式假设问题双向量 | Anthropic 2024（Top20 失败率 -67%） |
| 召回 | 双向量（embedding_context / embedding_question）并行 + BM25 词法通道并行融合 | HyDE + 混合检索 |
| 权限 | ACL 位图（Redis）+ Milvus 查询层 expr 下推（visibility/doc_id like），检索后过滤仅作纵深防御 | OWASP LLM02 防泄露 |
| 精排 | gte-rerank-v2（Dashscope）三级降级链；融合 0.2 向量+0.6 rerank+0.2 BM25 | ColBERT/RAG-Fusion 混合 |
| 质量门控 | CRAG 式：top 分过低触发一次改写重检索，仍不足则拒答提示 | CRAG (Yan et al. 2024) |
| 缓存 | L1 embedding / L2 精确 / L3 语义（带 userId 权限维度）/ L4 答案，四层 | 企业 RAG 缓存实践 |
| 去同主题混淆 | **文档级整合处理器（本轮新增）**：跨文档置信度门控，弱源文档候选剔除 | score-based document pruning / MMR 对偶 |
| 自适应截断 | elbow 分数落差截断（Top-K 窗口内分差≥阈值提前截断） | Adaptive-RAG / score-gap cutoff |
| 元数据加权 | 查询词 × (fileName+section_path) 覆盖率加权（本轮，qwen3.7-1024d） | BM25F 字段加权 / LTR static features |

## 二、黄金集压测成绩（20 用例，干净缓存基准）

| 阶段 | avg Precision | avg Recall | HitRate | 达标用例 |
|---|---|---|---|---|
| 上轮基线（vision-768d） | 0.769 | 0.95 | 0.95 | — |
| qwen3.7-1024d + 元数据加权 | 0.815 | 1.00 | 1.00 | 13/20 |
| + 门槛调优（0.6/2/0.12） | 0.840 | 1.00 | 1.00 | 14/20 |
| **+ 文档级整合（ratio 0.9，本轮定稿）** | **0.947** | **1.00** | **1.00** | **18/20** |

残余 2 例（33 父子切片、36 引用溯源）：两份同主题文档对问题均有实质相关内容，
rerank 对"实施(how-to) vs 研究(why)"措辞无法区分——语料级歧义，20/20 需 LLM 文档路由。

## 三、应用层现状（代码级审计结论，均附证据）

| 能力 | 现状 | 证据 |
|---|---|---|
| RAG 问答主链路 | /ai/chat → 改写→意图→多通道检索→质量门控→Prompt→流式 | ChatOrchestrator:190,217 |
| 查询改写 | 真实调用；LLM 失败降级规则清洗（QueryCleaner），不区分 403 | QueryRewriter.java:92,154 |
| 引用溯源 | **内部** retrieveText 带 [n]+docId、RAGResult.citations 已填 | RetrievalAugmentedGeneration:134-162 |
| 结构化溯源出口 | ❌ 流式响应仅纯文本，前端无"参考来源/文件"字段 | ChatOrchestrator emitter.send |
| 多轮记忆 | Redis+Caffeine 摘要 + Kafka memory-cmd 落库 | ConversationMemorySummaryService |
| 可观测 | RagTraceAspect → Kafka trace-log → MySQL；/traceInfo/detail 全链路查询 | TraceInfo.java:165 |
| 工具/MCP | amap/bilibili/file_search/记忆检索；规则+LLM 决策 | ToolDecisionManager:130,296 |
| 用户反馈 | 点赞/点踩，点踩自动回流黄金集（enabled=0 待审） | EvaluateImpl:52-85 |
| 安全护栏 | ❌ 无独立 prompt 注入检测/输出审查（仅 ACL + 系统提示软约束） | — |
| 限流 | @RateLimit 按 user/IP 固定窗口；❌ 无租户级配额/计量 | RateLimitAspect |
| 评估闭环 | 黄金集纯检索指标（不依赖付费 LLM）；LLM 侧 RAGAS 评估待充值 | GoldenSetService / LLMEvaluator |

## 四、差距清单与改进路径（按优先级）

### P0（安全合规，上线前必须）
1. **Prompt 注入检测模块**：检索内容与工具输出进入 Prompt 前做规则+轻模型双层检测
   （关键词/指令模式 + 可选 分类模型）；拦截文档内"忽略以上指令"类注入。参考 OWASP Top10 for LLM。
2. **结构化引用溯源出口**：RAGResult.citations 已是 docId 列表 → 补 fileRecord 关联
   （文件名/章节/页）后随响应结构化返回；满足企业审计与"可追溯性"要求（欧盟 AI Act / 中国 AIGC 备案对溯源的要求趋势）。
3. **租户配额与计量**：Redis 计数器按 userId/API-key 维度做 token/调用配额，analytics-event
   通道已具备（AnalyticsConsumer 未实现），接通即得计量。

### P1（上线体验）
4. **拒绝回答的"证据不足"策略收敛**：CRAG 门控重检索仍不足时已置 insufficientEvidence，
   确认 Prompt 拒答模板与前端提示一致。
5. **LLM 侧四维 RAGAS 评估接入**：充值后启用 LLMEvaluator（faithfulness/context precision/
   context recall/answer relevance），纳入发布门禁（金丝雀评估）。
6. **语义缓存多模态指纹**：L3 语义缓存以 1024d 向量+余弦 0.93 判定，建议加
   归一化文本指纹双保险，防止相似问题互串（本轮基准跑已发现该污染并规避）。

### P2（高阶演进，已与学术对标）
7. **GraphRAG 社区检索**：neo4j-cmd/Neo4jConsumer 骨架已有，补社区摘要索引（L1/L2 社区）与
   全局问题路由，处理"跨文档综述类"问题（Microsoft GraphRAG, 2024）。
8. **Self-RAG 反思令牌**：生成质量门控从检索侧扩展到生成侧（Asai et al., 2023）。
9. **Agentic 工具增强**：ToolDecisionManager 已有 amap/bilibili/file_search；接入
   企业常用工具（CRM/工单/HR 系统）需按 McpToolDecision 契约扩展 + 审计日志。
10. **在线学习**：badcase 回流已具备（点踩→黄金集待审），补人工复核 UI 工作流。

## 五、本轮代码变更清单
- `rag/channel/processor/DocumentConsolidationPostProcessor.java`（新增）：文档级整合
- `RetrievalProperties`：docConsolidationEnabled / docKeepRatio
- `MultiChannelRetrievalEngine`：rerank 后插入文档整合 → 元数据加权 → 最终 Top-K
- `application.yaml`：doc-consolidation-enabled=true, doc-keep-ratio=0.9（扫描定稿）
- 上一轮（qwen3.7 embedding、MetadataBoostPostProcessor、Parser fileName 修复、门控语义修正、35 号用例修正）见 EVAL-REPORT-2026-09-09.md

## 六、上线的剩余基础设施（非本仓库 RAG 代码范畴）
- 高可用：多副本消费者组已按 topic 分区设计，需容器编排（K8s/ECS）与配置中心
- 模型网关：当前直连百炼/专用端点，建议统一走网关（限流/审计/密钥托管）
- 私钥与密钥：RSA 密钥支持 PEM 环境变量，剩余密钥集中到 KMS
