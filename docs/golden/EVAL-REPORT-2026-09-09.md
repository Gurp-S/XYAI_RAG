# RAG 黄金集评估报告（2026-09-09）

## 本轮变更

| 变更 | 内容 |
|---|---|
| 35 号用例修正 | CRAG 纠错环用例期望文件由 a01d85a7（实施计划）修正为 84bfb363（对标研究），DB 与 `docs/golden/golden_cases_resolved.json` 同步 |
| Embedding 模型切换 | `tongyi-embedding-vision-flash`(768d) → **qwen3.7-text-embedding**（专用 MaaS 端点，OpenAI 兼容，1024d，MRL 已验证）；新增 provider `rag.embedding.provider=openai-compatible`；Milvus 集合重建（1024d）并全量重索引（100 chunks） |
| 元数据加权 | 新增 `MetadataBoostPostProcessor`：查询词对 fileName+section_path 的覆盖率 × weight 加成最终分数（BM25F 字段加权思想 / LTR static features） |
| 分数落差截断 | 新增 elbow 自适应截断（Adaptive-RAG / score-gap cutoff 思路）：Top-K 窗口内相邻分差 ≥ 0.12 时提前截断，保底 keepTop |
| 元数据修复 | **Parser bug**：`MultipartFile.getName()` 返回表单字段名 "file"，入库 fileName 全错 → 改用 `getOriginalFilename()`，ChunkConsumer 再以 Kafka 事件文件名兜底覆盖 |
| 门控语义修正 | `GoldenSetService.gatePassed` 由"逐用例全过"修正为与 Javadoc 一致的"批次平均 P/R 达标"（RAGAS 风格） |
| 评估卫生 | 发现 L3 语义缓存（进程内存）在跑批内跨用例污染（余弦≥0.93 误命中），基准跑批须关闭语义缓存 |

## 压测过程（20 用例黄金集，干净缓存）

| Run | 配置 | avgP | avgR | HitRate | 达标用例 | 门控 |
|---|---|---|---|---|---|---|
| 基线（上轮） | vision-768d, gate 0.45/4 | 0.769 | 0.95 | 0.95 | — | — |
| A（干净基线） | qwen3.7-1024d, boost 0.08, gate 0.45/4 | 0.815 | 1.00 | 1.00 | 13/20 | PASS(avg) |
| B | gate 0.55/3, boost 0.12 | 0.823 | 1.00 | 1.00 | 14/20 | PASS |
| **C（上轮定稿）** | **gate 0.60/2, boost 0.12** | **0.840** | **1.00** | **1.00** | **14/20** | **PASS** |
| D | gate 0.70/2, boost 0.15 | 0.829 | 1.00 | 1.00 | 13/20 | PASS |
| **E（本轮）** | **+ 文档级整合 ratio 0.8** | **0.940** | **1.00** | **1.00** | **18/20** | **PASS** |
| **F（本轮定稿）** | **+ 文档级整合 ratio 0.9** | **0.947** | **1.00** | **1.00** | **18/20** | **PASS** |
| G | + 文档级整合 ratio 0.95 | 0.942 | 1.00 | 1.00 | 18/20 | PASS |
| **H（最终）** | **+ 上下文感知 rerank（重排输入拼文档名/章节路径）** | **0.954** | **1.00** | **1.00** | **18/20** | **PASS** |

## 定稿配置（已写入 application.yaml）

```yaml
rag.retrieval:
  rerank-gate-relative: 0.6
  rerank-gate-keep-top: 2
  metadata-boost-weight: 0.12
  doc-consolidation-enabled: true
  doc-keep-ratio: 0.9          # 文档级整合：弱源文档分 < 最优文档分×0.9 剔除
  score-elbow-enabled: true
  score-elbow-gap: 0.12
```

## 残余未达标用例（33/36）

两份 RAG 同主题文档（对标研究 vs 实施计划）对"父子切片/引用溯源"均有实质内容且
rerank 无法区分 how-to/why 措辞，属语料级固有歧义；20/20 需 LLM 文档路由（见企业级能力矩阵文档）。

## 运维要点

- 启动：`SPRING_PROFILES_ACTIVE=local,emb`（application-emb.yaml 持有专用端点与 key）
- 换 embedding 模型必须：清 `xyai:cache:*`（emb/rt/ans 三层）→ 删 `xyai:file:hash:{<hash>}` → drop+recreate 集合 → 重索引
- 基准评估前必须关闭语义缓存（`--retrieval.cache.semantic-enabled=false`），否则跑批内跨用例污染
- 生产当前以语义缓存开启模式运行
