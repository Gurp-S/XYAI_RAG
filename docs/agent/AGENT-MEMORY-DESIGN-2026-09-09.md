# RAG-Agent：Agent 循环 + 分层记忆架构（2026-09-09）

> 目标：把 My-AI 从"单轮 RAG 问答机器人"演进为具备 **感知→规划→行动→观察→反思** 循环、
> 拥有**分层记忆系统**的 RAG-Agent。
> 设计对标：ReAct (Yao et al. 2022)、Generative Agents (Park et al. 2023)、
> MemGPT/Letta (Packer et al. 2023)、Self-RAG (Asai et al. 2023)、
> LangGraph 状态机、Dify/Coze 工作流 Agent、OpenAI Assistants（threads+steps+tools）。

## 一、现状与差距（代码级）

| 维度 | 现状 | 证据 |
|---|---|---|
| 感知 | 查询改写 + IK 实体识别 + 意图（有 intent 包） | QueryRewriter / IntentRecognitionService |
| 行动 | 单轮：MCP工具→RAG检索→CRAG 门控（**仅一次改写重试**）→模型流式回答 | ChatOrchestrator.doChat / RetrievalQualityGate |
| 记忆 | 每会话 Redis 近 N 轮原文 + 每会话 LLM 摘要（**跨会话无长期层**）；摘要依赖 LLM（当前 403 无法生成） | ConversationMemorySummaryService |
| 反思/循环 | ❌ 无规划、无多步循环、无"证据不足→澄清/改道"决策状态机 | — |
| 评估 | 检索侧黄金集（LLM 无关）已闭环；生成侧 RAGAS 待充值 | GoldenSetService |

## 二、Agent 循环设计（ReAct × LangGraph 状态机）

```
                ┌──────────────────────────────────────────────┐
 user message → │  AgentLoopEngine（新增, 编排/状态机）          │
                │  循环 max_steps=3，每步:                      │
                │  plan   → 意图 + 子任务规划（Rewrite/Intent） │
                │  act    → 行动选择 Policy:                    │
                │            a) RAG检索（多通道）                │
                │            b) 工具(MCP file_search/amap/…)    │
                │            c) 长期记忆召回                     │
                │            d) 澄清提问(证据不足且无记忆)       │
                │  observe→ CRAG 质量门控分数 = 观测            │
                │  reflect→ 修正/终止条件:                      │
                │            step1: 检索证据不足 → 改写重检索    │
                │            step2: 仍不足+记忆相关 → 记忆分支    │
                │            step3: 仍不足+无记忆 → 澄清/拒答    │
                └──────────────────────────────────────────────┘
```
- 每步行动与观测写入现有 trace-log（RagTraceAspect）实现全链路审计；
- Policy 为确定性规则（LLM 可用时可升级为 LLM planner，输出保持状态机契约兼容）。

## 三、记忆分层设计（MemGPT / Generative Agents 映射）

| 层 | 内容 | 现状 | 状态 |
|---|---|---|---|
| L0 工作记忆 | 当前对话上下文窗口 | Redis 近 keepTurns 轮 + 会话摘要 | ✅ 已有 |
| L1 会话摘要 | 每会话滚动摘要（压缩 N 轮→摘要） | Kafka memory-cmd 消费生成 | ⚠️ 依赖 LLM，403 时停滞 |
| **L2 长期事实（本轮新增）** | 跨会话用户事实：规则抽取（记住/我叫/我在…/我的…）+ 重要度 + 命中计数 | `xy_memory_fact` 表 + Caffeine | ✅ 已上线（LLM 无关） |
| L3 外部知识 | 文档向量库 + Neo4j 图 | 多通道检索 / Graph 通道 | ✅ 已有 |
| L4 反思/巩固 | 低价值记忆淘汰、冲突合并、每日反思摘要 | eviction（importance×hit）+ bigram 去重 | 🟡 部分（去重/淘汰已有，反思待做） |

L2 召回打分（Generative Agents 同构）：
`score = 0.45·relevance(IK词重叠) + 0.35·importance + 0.20·recency(e^{-days/14})`
显式提问（"我的项目叫什么"）自动提升重要度权重 +0.25。

## 四、本轮已落地（可直接验证）

1. **LongTermMemoryService**（新）：启动幂等建表 `xy_memory_fact`；
   10 类触发句式规则抽取（非锚定、句读截断、疑问片段守卫、bigram≥0.8 同义去重、
   超 300 条按 importance×hit 淘汰）；`remember()` 写 / `recall()` 三因子召回。
2. **编排器接线**（ChatOrchestrator）：
   - 每轮对话先 `remember()`（LLM 无关，与模型可用性解耦）；
   - Prompt 增加「用户长期记忆(个性化背景)」槽位（MemGPT memory injection）；
   - **决策分支**：CRAG 证据不足 → 若长期记忆相关(score≥0.22) → 允许"基于记忆的个性化答复"
     并明确标注；否则 → 要求模型明示"知识库无资料"不得编造（Agentic 澄清/拒答策略）。
3. 端到端验证（curl 实测）：
   - 发送"我叫李雷，我在星辰科技做AI平台后端开发，我的项目叫企业级RAG系统，请记住这些。"
     → 表内精确 3 条事实（无重复、无噪声）；
   - 提问"我的项目叫什么名字？" → 门控 bestScore=0.0 不足 → **记忆个性化分支 topScore=0.663** 触发；
   - 提问句本身不再被误写为记忆（疑问片段守卫生效）。

## 五、里程碑路线（后续迭代）

| 阶段 | 内容 | 依赖 |
|---|---|---|
| M1 Agent 循环状态机 | 抽出 AgentLoopEngine，把现有线性编排改为 plan→act→observe→reflect 状态机，trace 落审计 | 纯编排重构，可先行 |
| M2 LLM planner | 行动选择/子任务分解交 LLM（结构化输出契约不变），403 解除即生效 | 模型充值 |
| M3 L1 摘要降级 | LLM 摘要失败时用规则"关键词+长句"提取回退，保证无 LLM 也可滚动压缩 | — |
| M4 记忆反思 | 每日/每周对 L2 做冲突合并与重要性衰减（时间平方根衰减，Generative Agents） | — |
| M5 记忆即通道 | 把 L2 注册为 SearchChannel（memory-fact），纳入多通道召回与黄金集评估 | — |
| M6 前端 Agent 视图 | SSE 协议增加 act/observe 事件类型；结构化引用溯源出口（见企业级矩阵 P0-2） | 前端联调 |

## 六、与黄金集评估的关系
- 本轮改动仅影响对话编排路径；多通道检索/精排链路未动，黄金集检索侧指标不受影响（P=0.947/R=1.0/Hit=1.0 保持）。
- M5 后建议新增"记忆召回命中率"评估维度（用户画像 QA 集），与文档 QA 集分离计分。
