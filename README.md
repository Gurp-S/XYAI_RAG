# XYAI — 企业级 RAG 智能问答平台

> **技术栈**: Spring Boot 3 · MySQL · Redis · Spring AI · Milvus · Neo4j · Tika · Ollama
>
> 本仓库为学习与扩展用的 RAG（检索增强生成）智能体平台，包含核心接口与模块实现（检索、意图识别、问题重写、会话记忆、文档入库 Pipeline、MCP 工具等）。

## 项目概述

XYAI 是一个面向企业场景的**检索增强生成（RAG）智能问答平台**，覆盖从文档摄入、语义理解到模型路由的完整 AI 应用链路。后端采用 **CompletableFuture + SseEmitter** 架构（无响应式编程），前端基于 Vue 3 + Element Plus 构建管理端，支持对话、文件管理、系统配置、链路追踪可视化等能力。

---

## 核心架构

### RAG 全流程（8 步链路）

```
用户输入
    │
    ├─ 步骤1：获取用户上下文（权限/身份）
    ├─ 步骤2：异步加载 MCP 工具结果
    ├─ 步骤3：异步加载会话记忆（Redis窗口 + LLM摘要 + 向量召回）
    ├─ 步骤4：查询重写（规则 → LLM）
    ├─ 步骤5：实体识别（Neo4j 知识图谱）
    ├─ 步骤6：多通道文档检索 → BM25打分 → 过滤 → Rerank
    ├─ 步骤7：合并 MCP + 记忆 + 检索 → 构建 RAGResult
    ├─ 步骤8：格式化最终 Prompt → 模型路由流式调用
    │
    └─ 后处理：记忆保存 → Token 记录 → 系统评估
```

每步均通过 `@RagTraceNode` 注解自动记录执行耗时与状态，支持全链路追踪可视化。

---

## 功能详述

### 1. 会话记忆系统（三层渐进）

| 层级       | 技术方案                           | 说明                         |
|----------|--------------------------------|----------------------------|
| **短期窗口** | Redis 滑动窗口（Redisson SortedSet） | 保存最近 N 轮对话，滑动过期            |
| **中期摘要** | LLM 结构化摘要（独立线程池）               | 每次对话后异步合并生成摘要，避免 Prompt 过长 |
| **长期向量** | Milvus 向量召回                    | 基于语义相似度检索历史相关记忆            |

**亮点**：摘要生成使用独立线程池隔离，不阻塞主流程；历史加载只取最近 keepTurns 轮，避免全量拉取导致性能问题。

### 2. ETL 管道（可插拔设计）

```
Tika 解析 → IK 分块 → LLM 增强 → Milvus 入库
```

- **可插拔节点**：Parser → Chunker → Enricher → Indexer，通过 `NodeConfig` 链式编排
- **条件执行**：支持 `ConditionEvaluator` 按条件跳过特定节点
- **循环检测**：引擎自动校验管道拓扑，防止循环依赖
- **任务追踪**：每个上传任务记录节点级执行日志
- **OSS 集成**：支持阿里云 OSS 文件存储

**亮点**：管道定义支持动态配置，节点可独立启用/禁用；单节点异常不影响整条管道，异常上下文自动传递。

### 3. 查询重写（规则 → LLM 二级兜底）

- **规则重写**：基于 IK 分词和预置规则的轻量快速重写
- **LLM 重写**：调用 ChatModel 进行语义级查询改写与子问题拆分
- **兜底**：规则未命中时自动降级到 LLM

### 4. 意图识别 / 实体识别（四层递进）

```
IK 分词 → Redis 缓存 → DB 精确匹配 → 向量检索 → LLM 兜底
```

- **Redis 层**：分词结果直接匹配缓存意图树节点（最快，~1ms）
- **数据库层**：按词精准查询意图节点
- **向量层**：Milvus 语义检索，阈值 0.75 过滤
- **LLM 兜底层**：所有叶子节点全量匹配，JSON 结构化输出，低分过滤（<0.4）
- **Neo4j 实体识别**：基于知识图谱的实体节点与社区发现

**亮点**：意图识别结果**缓存到 Redis**，下次命中直接返回；LLM 兜底输出结构化 JSON，支持安全解析和异常降级。

### 5. 多通道检索 + 后处理

**检索通道**（并行执行，单通道超时/异常不中断整体流程）：
- **实体社区召回**：Neo4j 图查询 → 关联文档检索
- **向量召回**：Milvus 全局语义检索
- **记忆召回**：基于语义相似度的历史对话检索

**后处理链路**（固定顺序编排）：
```
BM25 打分 → 规则过滤（权限/版本/低质量） → Rerank 重排（Ollama 语义模型）
```

**亮点**：每个通道独立线程池隔离，10s 超时保护；后处理结果异步记录文档使用频次，支持热度分析。

### 6. MCP 工具系统（规则 → LLM 二级决策）

**决策链路**：
1. **规则匹配**：预置触发词正则（天气/导航/搜索/时间等），命中后通过 `ArgumentExtractor` 提取参数
2. **LLM 匹配**：结构化输出模型（qwen3.5-122b-a10b, temp=0.1）进行工具选择与参数补全
3. **执行**：并行执行选中的工具，单工具失败不影响其他

**亮点**：从对话历史自动提取上下文实体补全参数（如城市名）；支持"测试所有 MCP"元指令；工具可独立启用/禁用。

### 7. 模型路由与降级

| 模式       | 说明                             |
|----------|--------------------------------|
| **快速模式** | 并发请求多个模型，取最快响应（5s 超时），牺牲质量保速度  |
| **标准模式** | 按优先级依次尝试：首选模型 → 按分数降序 → 跨供应商降级 |
| **流式模式** | 支持 SSE 实时流式输出，适用快速/标准/指定模型     |

**降级策略**：模型健康检测 → 自动跳过不可用模型 → 跨供应商切换（Anthropic → OpenAI → Ollama 等）。配置通过管理端动态调整，支持按供应商/模型名/优先级组合筛选。

### 8. 全链路追踪

通过 AOP 切面 (`@RagTraceRoot` / `@RagTraceNode`) 自动记录：

```java
@RagTraceNode(name = "多通道文档检索", type = "文档检索")
public List<RetrievedChunk> retrieveDocuments() {  }
```

- **节点粒度**：每个 RAG 步骤独立记录（START → RUNNING → SUCCESS/ERROR）
- **耗时统计**：自动计算各节点耗时（ms），支持异步 CompletableFuture
- **异常捕获**：节点异常自动记录错误信息，不影响全局链路
- **可视化**：管理端 Trace 面板展示完整调用链

**亮点**：基于 `TransmittableThreadLocal` 实现跨线程池上下文传递，完美支持异步链路追踪；`wrapCompletableFutureNode` 确保异步回调中正确恢复上下文并记录节点。

### 9. 文件上传与权限隔离

- **Tika 解析**：支持 PDF、Word、Excel、PPT、图片等多种格式
- **用户隔离**：文件绑定用户/用户组，检索时按权限过滤
- **Milvus ACL**：Metadata 级权限控制，用户仅检索自己的文件
- **使用统计**：自动记录文档被引用的频次

### 10. 系统评估体系

- **LLM 评估**：调用模型对回答质量打分
- **Rerank 评估**：基于重排分数评估召回质量
- **规则评估**：关键词/长度等硬规则检查
- **异步执行**：评估在独立线程池执行，不阻塞主流程

---

## 架构特性

### 异步非阻塞架构（无 Reactor）

```
请求 → SseEmitter(0) → ChatOrchestrator.chat()
    → CompletableFuture.runAsync(chatExecutor)
        → 同步 executeRAGSync() + 等待 MCP
        → modelInvocation.callModelStream()  // 内部 Flux.toIterable() 桥接
        → SseEmitter.send() 逐行推送
    → emitter.complete()
```

- **无 WebFlux**：全栈基于 Spring MVC + SseEmitter，无 Reactor 依赖
- **CompletableFuture**：所有异步操作用 CF + 自定义线程池，生命周期可控
- **TTL 上下文传递**：`TransmittableThreadLocal` 解决跨线程池 traceId/用户身份传递
- **Callback 模式**：流式输出通过 `Consumer<String> onChunk` 回调节拍，而非 Flux 推模式

### 线程池隔离

| 线程池                | 用途                       | 策略                |
|--------------------|--------------------------|-------------------|
| `ioBoundExecutor`  | 基础 IO 池（意图/搜索/MCP/记忆等共用） | CallerRuns        |
| `chatExecutor`     | 对话主流程编排                  | → ioBoundExecutor |
| `mcpExecutor`      | MCP 工具执行                 | → ioBoundExecutor |
| `traceExecutor`    | 链路追踪记录                   | CallerRuns        |
| `evaluateExecutor` | 系统评估                     | 静默丢弃              |
| `uploadExecutor`   | 文件上传                     | CallerRuns        |

统一 `TaskDecorator` 自动传递 `userId` 与 `SecurityContext`。

---

## 管理端功能

基于 Vue 3 + Element Plus 构建，支持：

- **仪表盘**：ECharts 数据可视化
- **对话管理**：会话查看、消息检索
- **模型管理**：供应商配置、模型增删、健康检测
- **文件管理**：上传/检索/权限分配
- **MCP 工具管理**：工具启用/禁用
- **意图树管理**：可视化编辑意图节点
- **ETL 管理**：管道配置、任务状态查看
- **系统配置**：RAG 参数、Prompt 模板动态调整
- **链路追踪**：Trace 面板展示节点耗时与状态
- **用户管理**：用户/用户组/权限体系
- **Token 统计**：用量记录与可视化

---

## 快速开始

### 环境要求

- JDK 21+
- MySQL 8.0+
- Redis 7.0+
- Milvus 2.3+
- Neo4j 5.0+
- Ollama（可选，本地模型部署）

### 构建运行

```powershell
# 后端
.\mvnw.cmd clean package
java -jar target\My-AI-0.0.1-SNAPSHOT.jar

# 前端管理端
cd newXyAdmin
npm install
npm run dev
```

---

## 项目结构

```
src/main/java/com/XYai/myai/
├── rag/
│   ├── RetrievalAugmentedGeneration.java    # RAG 主流程编排（8步）
│   ├── chat/                                # 对话引擎 + 模型路由 + 流式输出
│   ├── memory/                              # 三层会话记忆系统
│   ├── rewrite/                             # 查询重写（规则 + LLM）
│   ├── intent/                              # 意图识别（Redis/DB/向量/LLM 四层）
│   ├── channel/                             # 多通道检索 + BM25/过滤/Rerank
│   ├── graph/                               # Neo4j 知识图谱实体识别
│   ├── mcp/                                 # MCP 工具决策与执行
│   ├── milvus/                              # Milvus 向量库操作 + ACL
│   ├── etlpipeline/                         # ETL 可插拔管道
│   ├── evaluate/                            # 系统评估（LLM/Rerank/规则）
│   ├── aop/                                 # 全链路追踪切面
│   └── ragPojo/                             # RAG 数据模型
├── security/                                # JWT 认证 + 权限控制
├── user/                                    # 用户体系
├── xyAdmin/                                 # 管理端 API
├── config/                                  # 线程池/Redis/Oss 等配置
└── mapper/                                  # MyBatis Plus 数据映射
```

---

## 配置示例

### application.yml 核心配置

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
    openai:
      api-key: ${OPENAI_API_KEY}
  datasource:
    url: jdbc:mysql://localhost:3306/xyai
    username: root
    password: ${DB_PASSWORD}
  data:
    redis:
      host: localhost
      port: 6379
milvus:
  uri: http://localhost:19530
neo4j:
  uri: bolt://localhost:7687
```
