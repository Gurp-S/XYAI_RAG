# XYAI 求职作品集企业化改造 TODO

> 目标：把当前项目升级为“可演示、可压测、可审计、可交付”的企业级 RAG 工程。
> 输出格式按你的要求：每条都包含 目标类/文件 + 做什么(What) + 怎么做(How)。

## 0. 审查覆盖范围

- 后端：Controller / Config / User / RAG(Chat, Rewrite, Intent, Memory, Channel, Milvus, ETL, MCP) / Mapper / Exception / Service
- 前端：Vue 组件、Pinia Store、Router、全局样式、构建脚本
- 运行与工程：YAML 配置、SQL 初始化、Python 运维脚本、README、测试

---

## 1. 当前薄弱点（Bad Parts，按类给出修法）

| ID  | 当前问题（What is bad）                                  | 修复方向（How）                                             | 目标类/文件                                                                                                                                                                                                                               | 优先级 |
|-----|----------------------------------------------------|-------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----|
| W01 | 存在明文 OSS key、DB 密码、LLM API key。                    | 立即轮换泄露密钥；本地文件仅保留占位；改为环境变量/密钥管理。    0                  | [application-local.yaml](main/resources/application-local.yaml)                                                                                                                                                                      | P0  |
| W02 | 使用 static USERID；大量接口直接收 userId 参数，存在越权风险。         | 移除 static 用户态；统一从鉴权上下文取 userId。      0                | [UserController.java](com/XYai/myai/user/UserController.java)                                                                                                                                                                        | P0  |
| W03 | 未携带 userId header 时直接放行；非强制鉴权。                     | 区分白名单与受保护接口；受保护接口必须鉴权失败即 401。                         | [LoginHandlerInterceptor.java](com/XYai/myai/config/LoginHandlerInterceptor.java)                                                                                                                                                    | P0  |
| W04 | 登录使用明文密码比对。                                        | 引入 BCrypt；迁移密码字段并支持平滑升级。                              | [UserServiceImpl.java](com/XYai/myai/user/service/UserServiceImpl.java)                                                                                                                                                              | P0  |
| W05 | 初始化 SQL 写入默认弱口令。                                   | 删除默认密码样例；改成初始化脚本不带真实凭据。                               | [MYsql.sql](main/resources/sql/MYsql.sql)                                                                                                                                                                                            | P0  |
| W06 | CORS 开放过大（无 origin 白名单）。                           | 配置环境化 origin 白名单、凭据策略和方法白名单。                          | [MvcConfiguration.java](com/XYai/myai/config/MvcConfiguration.java)                                                                                                                                                                  | P0  |
| W07 | 限流异常返回纯字符串，错误契约不统一。                                | 全局异常统一为 Result 或 ProblemDetail；按错误码分层。                | [ExceptionController.java](com/XYai/myai/exception/ExceptionController.java)                                                                                                                                                         | P0  |
| W08 | 标注了 @RestController，但实际是服务组件。                      | 改为 @Service；Controller 职责和 Service 职责分离。              | [QueryRewriter.java](com/XYai/myai/rag/rewrite/QueryRewriter.java)                                                                                                                                                                   | P1  |
| W09 | 标注 @RestController 但无 HTTP endpoint，职责混乱。          | 改为 @Service；由上层服务调用。                                  | [IntentResult.java](com/XYai/myai/rag/intent/IntentResult.java)                                                                                                                                                                      | P1  |
| W10 | 类名小写且命名不规范，接口拼写 QueryReweiter。                     | 统一命名：QueryRewriterServiceImpl / QueryRewriterService。 | [rewriter.java](com/XYai/myai/rag/rewrite/rewriter.java)                                                                                                                                                                             | P1  |
| W11 | DoChat 方法过长、职责过多（重写/意图/检索/提示词/流式/记忆）。              | 拆分为编排器 + 各子服务，便于测试与容错。                                | [ChatServiceImpl.java](com/XYai/myai/rag/chat/Service/Impl/ChatServiceImpl.java)                                                                                                                                                     | P0  |
| W12 | 无结果时返回 null；调用方需做额外空指针处理。                          | 全链路改为空列表语义，禁止返回 null 集合。                              | [MultiChannelRetrievalEngine.java](com/XYai/myai/rag/channel/MultiChannelRetrievalEngine.java)                                                                                                                                       | P1  |
| W13 | 每次检索创建新线程池 Executors.newFixedThreadPool，存在资源泄漏。    | 使用统一注入线程池 Bean，禁止请求内创建线程池。                            | [VectorGlobalSearchChannel.java](com/XYai/myai/rag/channel/search/VectorGlobalSearchChannel.java)                                                                                                                                    | P0  |
| W14 | 在接口方法上加 TimeLimiter/CircuitBreaker，但方法非异步返回，语义不完整。 | 将通道执行改 CompletableFuture；或在引擎层统一包裹 resilience。        | [SearchChannel.java](com/XYai/myai/rag/channel/pojo/SearchChannel.java)                                                                                                                                                              | P1  |
| W15 | ES 通道是空壳实现，返回空字符串/null。                            | 完成最小可用实现或从注册中移除。                                      | [ESSearchChannel.java](com/XYai/myai/rag/channel/search/ESSearchChannel.java)                                                                                                                                                        | P2  |
| W16 | 过滤器未真正执行规则。                                        | 增加阈值、权限、时效过滤并参数化。                                     | [FilterPostProcessor.java](com/XYai/myai/rag/channel/processor/FilterPostProcessor.java)                                                                                                                                             | P1  |
| W17 | 重排器未实现。                                            | 接入 rerank 模型或使用 BM25+embedding 融合重排。                  | [RerankPostProcessor.java](com/XYai/myai/rag/channel/processor/RerankPostProcessor.java)                                                                                                                                             | P1  |
| W18 | 向量意图识别与部分兜底逻辑仍是 TODO；JSON 解析脆弱。                    | 完成向量召回；增加 JSON schema 校验与容错回退。                        | [IntentRecognitionServiceIml.java](com/XYai/myai/rag/intent/IntentRecognitionServiceIml.java)                                                                                                                                        | P0  |
| W19 | splitByWindow 兜底切分逻辑未把分块写入列表，等效失效。                 | 修复窗口切分循环并补单测覆盖长文本与边界。                                 | [Chunker.java](com/XYai/myai/rag/etlpipeline/nodes/Chunker.java)                                                                                                                                                                     | P0  |
| W20 | runAsync 未指定线程池；任务状态统计逻辑有误。                        | 改用受管线程池；修正成功数计算；失败可重试。 0                              | [UploadController.java](com/XYai/myai/rag/etlpipeline/UploadController.java)                                                                                                                                                         | P0  |
| W21 | 无 TTL/过期清理；Redis 镜像无过期时间。                          | task 状态写入时设置 TTL + 定时清理。                              | [UploadTaskStore.java](com/XYai/myai/rag/etlpipeline/UploadTaskStore.java)                                                                                                                                                           | P1  |
| W22 | search 空结果返回 null；delete 表达式拼接有注入风险。               | 统一返回空列表；chunkId 参数强校验并参数化表达式。                         | [MilvusService.java](com/XYai/myai/rag/milvus/MilvusService.java)                                                                                                                                                                    | P0  |
| W23 | ensureReadyForRead 未加载时返回 null，语义弱。                | 返回显式状态对象（LOADED/UNLOADED/NOT_FOUND）。       0          | [MilvusCollectionService.java](com/XYai/myai/rag/milvus/MilvusCollectionService.java)                                                                                                                                                | P1  |
| W24 | 多处 runAsync 未绑定业务线程池；load 内有无效 intersect 调用。       | 统一使用 memoryCompactExecutor；清理无效 Redis 操作。  0          | [ConversationMemorySummaryService.java](com/XYai/myai/rag/memory/ConversationMemorySummaryService.java)                                                                                                                              | P0  |
| W25 | 包名 iml 拼写异常；追踪实体放在 Annotation 包，可维护性差。             | 统一包结构：trace/entity, trace/service/impl。               | [TraceRecordServiceIml.java](com/XYai/myai/monitorEndpoint/service/iml/TraceRecordServiceIml.java)                                                                                                                                   | P1  |
| W26 | 预留模型路由类为空壳，容易误导。                                   | 实现真实模型路由/健康探测，或删除无效类。                                 | [ModelSelector.java](com/XYai/myai/rag/chat/pojo/ModelSelector.java), [ModelRoutingExecutor.java](com/XYai/myai/rag/chat/pojo/ModelRoutingExecutor.java), [ModelHealthStore.java](com/XYai/myai/rag/chat/pojo/ModelHealthStore.java) | P2  |
| W27 | 仅写入数据无断言，不是真正测试。                                   | 拆分单元测试+集成测试，增加断言和失败场景。                                | [MyAiApplicationTests.java](test/java/com/XYai/myai/MyAiApplicationTests.java)                                                                                                                                                       | P0  |
| W28 | localStorage 持续累积，conversationCache 无上限。           | 做 LRU/按会话数与大小淘汰策略。                                    | [index.js](../frontend/src/store/index.js)                                                                                                                                                                                           | P1  |
| W29 | SSE 读取无断线重连与心跳，弱网体验不足。                             | 增加重连策略、超时提示、指数退避。                                     | [MainChat.vue](../frontend/src/components/MainChat.vue)                                                                                                                                                                              | P1  |
| W30 | 单组件承担过多网络/状态/渲染职责。                                 | 拆为 composable + 子组件（toolbar/table/modal）。             | [MilvusManager.vue](../frontend/src/components/MilvusManager.vue)                                                                                                                                                                    | P1  |
| W31 | 登录仅靠 userId+password 表单，不使用 token/session。         | 改为标准登录票据（JWT/Session）+ 自动刷新策略。  0                     | [Login.vue](../frontend/src/components/Login.vue)                                                                                                                                                                                    | P0  |
| W32 | sync:dist 依赖 PowerShell，跨平台 CI 不友好。                | 改为 cross-platform 脚本（node/shx/rsync）。                 | [package.json](../frontend/package.json)                                                                                                                                                                                             | P1  |
| W33 | 文档存在旧包路径示例，和当前项目结构不一致。                             | 重写 README：架构图、启动步骤、配置说明、压测说明。                         | [README.md](../README.md)                                                                                                                                                                                                            | P2  |
| W34 | 运维脚本未纳入统一发布流程。                                     | 形成标准 runbook 与 CI 调用入口。                               | [scripts/](../scripts/)                                                                                                                                                                                                              | P2  |

---

## 2. 改进任务清单（每条都包含 Class + What + How）

## 2.1 P0（先做：安全、正确性、稳定性）

| ID  | 目标类/文件                                                                                                                                                 | What（做什么）         | How（怎么做）                                                           | 验收标准                              |
|-----|--------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------|--------------------------------------------------------------------|-----------------------------------|
| T01 | [application.yaml](main/resources/application.yaml), [application-local.yaml](main/resources/application-local.yaml)                                   | 完成密钥治理            | 1) 删除本地明文密钥 2) 改为 ${ENV_VAR} 3) 启动时校验缺失项并 fail-fast                | 仓库无明文密钥，启动日志明确缺失配置                |
| T02 | [LoginHandlerInterceptor.java](com/XYai/myai/config/LoginHandlerInterceptor.java), [MvcConfiguration.java](com/XYai/myai/config/MvcConfiguration.java) | 建立强制鉴权边界          | 1) 定义匿名白名单 2) 其余接口必须鉴权 3) 未鉴权统一返回 401                              | 未登录访问受保护接口必定失败                    |
| T03 | [UserController.java](com/XYai/myai/user/UserController.java)                                                                                          | 去掉 static USERID  | 1) 删除静态字段 2) 所有 userId 从登录上下文获取 3) 接口参数移除可伪造 userId                | 无静态用户态，全链路以 token/user context 为准 |
| T04 | [UserServiceImpl.java](com/XYai/myai/user/service/UserServiceImpl.java), [User.java](com/XYai/myai/user/pojo/User.java)                                | 密码加密与认证升级         | 1) 引入 BCryptPasswordEncoder 2) 登录改 matches 3) 新增密码迁移脚本             | 明文密码不再参与比较                        |
| T05 | [MYsql.sql](main/resources/sql/MYsql.sql)                                                                                                              | 清理默认弱口令样例         | 1) 删除示例明文口令 2) 使用不可登录样例或初始化脚本外置                                    | 初始化脚本不泄露账户口令                      |
| T06 | [ExceptionController.java](com/XYai/myai/exception/ExceptionController.java), [Result.java](com/XYai/myai/config/Result.java)                          | 统一错误契约            | 1) 增加业务错误码枚举 2) 全局异常映射 3) 限流异常返回结构化 JSON                           | 前端可稳定按 code 渲染错误态                 |
| T07 | [Chunker.java](com/XYai/myai/rag/etlpipeline/nodes/Chunker.java)                                                                                       | 修复 fallback 分块失效  | 1) 正确 push 分块到 chunks 2) 修复循环结束条件 3) 补边界测试                         | fallback 模式可产出有效 chunks           |
| T08 | [UploadController.java](com/XYai/myai/rag/etlpipeline/UploadController.java)                                                                           | 上传异步改造为受管执行       | 1) 注入专用 executor 2) CompletableFuture 指定 executor 3) 增加异常分类与重试码    | 高并发上传不阻塞主线程                       |
| T09 | [UploadController.java](com/XYai/myai/rag/etlpipeline/UploadController.java)                                                                           | 修复上传统计准确性         | 1) 成功/失败计数按文件维度 2) 返回结构包含明细 3) 前端按 task 读取统计                       | 返回统计和实际处理数量一致                     |
| T10 | [UploadTaskStore.java](com/XYai/myai/rag/etlpipeline/UploadTaskStore.java)                                                                             | 增加 task TTL 与清理机制 | 1) Redis set 时带过期 2) 内存 map 定时清理 3) 查询过期返回标准码                      | 任务状态不会无限增长                        |
| T11 | [MilvusService.java](com/XYai/myai/rag/milvus/MilvusService.java)                                                                                      | 修复删除表达式与空结果语义     | 1) chunkId 数字校验 2) 非法参数拒绝 3) search 改返回空列表                         | 删除接口可防注入且行为一致                     |
| T12 | [ConversationMemorySummaryService.java](com/XYai/myai/rag/memory/ConversationMemorySummaryService.java)                                                | 统一异步线程池与 Redis 逻辑 | 1) runAsync 使用 memoryCompactExecutor 2) 移除无效 intersect 3) 异常与重试可观测 | 摘要流程稳定且可追踪                        |
| T13 | [VectorGlobalSearchChannel.java](com/XYai/myai/rag/channel/search/VectorGlobalSearchChannel.java)                                                      | 修复线程池泄漏           | 1) 去掉每次 newFixedThreadPool 2) 复用 searchChannelExecutor 3) 增加并发上限   | 线程数稳定无持续上涨                        |
| T14 | [IntentRecognitionServiceIml.java](com/XYai/myai/rag/intent/IntentRecognitionServiceIml.java)                                                          | 补齐意图识别核心缺口        | 1) 实现向量意图分支 2) fallback JSON 校验 3) 低置信度回退策略标准化                     | 意图命中率与稳定性可评估                      |
| T15 | [MyAiApplicationTests.java](test/java/com/XYai/myai/MyAiApplicationTests.java)                                                                         | 改造测试基线            | 1) 删除“无断言”测试 2) 增加 Controller/Service 单测 3) 加入异常路径测试               | CI 中测试可拦截回归                       |

## 2.2 P1（增强：性能、可维护、可观测）

| ID  | 目标类/文件                                                                                                                                                               | What（做什么）          | How（怎么做）                                                                                 | 验收标准            |
|-----|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------|------------------------------------------------------------------------------------------|-----------------|
| T16 | [ChatServiceImpl.java](com/XYai/myai/rag/chat/Service/Impl/ChatServiceImpl.java)                                                                                     | 拆分聊天编排             | 1) 拆 QueryRewriteStage/IntentStage/RetrieveStage/PromptStage 2) 使用 pipeline 编排 3) 每阶段可观测 | DoChat 复杂度明显下降  |
| T17 | [ChatService.java](com/XYai/myai/rag/chat/Service/ChatService.java)                                                                                                  | 统一命名规范             | 1) DoChat -> doChatStream 2) 接口语义显式化 3) 调用点统一替换                                          | 命名符合 Java 规范    |
| T18 | [MultiChannelRetrievalEngine.java](com/XYai/myai/rag/channel/MultiChannelRetrievalEngine.java)                                                                       | 统一空结果返回策略          | 1) return List.of() 2) 后处理判空保护 3) 增加结果统计日志                                               | 无 NPE，日志可追检索结果  |
| T19 | [SearchChannel.java](com/XYai/myai/rag/channel/pojo/SearchChannel.java)                                                                                              | 规范 resilience 接入方式 | 1) 在引擎层统一加熔断超时 2) fallback 返回空结果对象 3) 指标上报                                               | 单通道失败不拖垮主流程     |
| T20 | [FilterPostProcessor.java](com/XYai/myai/rag/channel/processor/FilterPostProcessor.java)                                                                             | 实装过滤规则             | 1) 相似度阈值 2) 版本/权限过滤 3) 配置化阈值                                                             | 低质量召回明显减少       |
| T21 | [RerankPostProcessor.java](com/XYai/myai/rag/channel/processor/RerankPostProcessor.java)                                                                             | 实装重排               | 1) 接入 reranker 或轻量重排器 2) 输出重排分数 3) 与召回质量评测联动                                             | topK 准确率提升      |
| T22 | [ESSearchChannel.java](com/XYai/myai/rag/channel/search/ESSearchChannel.java)                                                                                        | ES 通道最小可用化         | 1) 完成 isEnabled/search/getName 2) 加配置开关 3) 无 ES 时自动降级                                    | 通道可启停且行为可预期     |
| T23 | [MilvusCollectionService.java](com/XYai/myai/rag/milvus/MilvusCollectionService.java)                                                                                | 显式加载状态返回对象         | 1) 新增 CollectionReadiness 2) 控制器按状态码返回 3) 前端显示未加载原因                                      | 读写前置状态可解释       |
| T24 | [RagTraceAspect.java](com/XYai/myai/rag/aop/RagTraceAspect.java), [TraceRecordServiceIml.java](com/XYai/myai/monitorEndpoint/service/iml/TraceRecordServiceIml.java) | 追踪体系重构             | 1) entity 从 Annotation 包迁出 2) trace/span 字段标准化 3) 失败链路补全                                 | 追踪数据模型清晰        |
| T25 | [QueryRewriter.java](com/XYai/myai/rag/rewrite/QueryRewriter.java), [IntentResult.java](com/XYai/myai/rag/intent/IntentResult.java)                                  | 组件角色纠偏             | 1) @RestController 改 @Service 2) 控制器与服务解耦 3) 增加构造注入                                      | 分层清晰，注入稳定       |
| T26 | [rewriter.java](com/XYai/myai/rag/rewrite/rewriter.java), [QueryReweiterService.java](com/XYai/myai/rag/rewrite/QueryReweiterService.java)                           | 规范命名与包结构           | 1) 修复 Reweiter 拼写 2) 类名首字母大写 3) 统一接口/实现命名                                                | IDE 搜索与维护成本下降   |
| T27 | [ThreadPoolConfig.java](com/XYai/myai/config/ThreadPoolConfig.java), [CommonConfiguration.java](com/XYai/myai/config/CommonConfiguration.java)                       | 线程池治理              | 1) 所有异步任务绑定命名线程池 2) 队列/拒绝策略指标化 3) 压测调优                                                   | 压测时线程池行为可控      |
| T28 | [HttpMCPClient.java](com/XYai/myai/rag/mcp/client/HttpMCPClient.java), [MCPService.java](com/XYai/myai/rag/mcp/service/MCPService.java)                              | MCP 安全加固           | 1) serverUrl 白名单 2) 参数 schema 校验 3) 超时重试熔断                                               | MCP 调用可控且可审计    |
| T29 | [LLMMCPParameterExtractor.java](com/XYai/myai/rag/mcp/LLMMCPParameterExtractor.java)                                                                           | 参数抽取能力落地           | 1) 接 LLM 输出 JSON schema 2) 校验 required 字段 3) 失败回退人工参数                                    | MCP 自动参数提取可用    |
| T30 | [PipelineDefinitionFactory.java](com/XYai/myai/rag/etlpipeline/factory/PipelineDefinitionFactory.java)                                                               | 管道定义配置化            | 1) chunk 参数外置 2) pipeline 版本号 3) 可灰度切换节点                                                 | 不改代码可调 pipeline |

## 2.3 P2（完善：前端体验与工程交付）

| ID  | 目标类/文件                                                                                                                                                                                                                               | What（做什么）  | How（怎么做）                                                  | 验收标准           |
|-----|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------|-----------------------------------------------------------|----------------|
| T31 | [Login.vue](../frontend/src/components/Login.vue), [UserController.java](com/XYai/myai/user/UserController.java)                                                                                                                     | 登录体系产品化    | 1) 接入 token/session 2) 前端保存短期凭据 3) 过期自动登出                 | 用户会话安全可控       |
| T32 | [index.js](../frontend/src/store/index.js)                                                                                                                                                                                           | 本地缓存治理     | 1) conversationCache 设上限 2) LRU 淘汰 3) 大对象压缩或截断            | 本地存储稳定不爆容量     |
| T33 | [MainChat.vue](../frontend/src/components/MainChat.vue)                                                                                                                                                                              | SSE 弱网恢复能力 | 1) 心跳超时 2) 断线自动重连 3) 用户可手动续传                              | 弱网聊天可恢复        |
| T34 | [MilvusManager.vue](../frontend/src/components/MilvusManager.vue)                                                                                                                                                                    | 组件拆分减复杂度   | 1) 抽 composable(useMilvusManager) 2) 表格/弹窗子组件化 3) 接口调用集中化 | 组件可维护性显著提升     |
| T35 | [router.js](../frontend/src/router.js), [App.vue](../frontend/src/App.vue)                                                                                                                                                           | 路由与视图状态统一  | 1) 以路由为单一真源 2) currentView 由路由派生 3) 历史恢复统一入口              | 刷新与跳转行为一致      |
| T36 | [package.json](../frontend/package.json)                                                                                                                                                                                             | 跨平台构建脚本    | 1) 用 node 脚本替代 powershell 2) Linux/macOS CI 验证 3) 文档更新    | CI 在多平台可执行     |
| T37 | [README.md](../README.md), [roadmap-12w.md](../docs/roadmap-12w.md), [weekly-checklist.md](../docs/weekly-checklist.md)                                                                                                              | 文档与代码一致性治理 | 1) 更新真实包路径与启动命令 2) 增加架构图 3) 增加排障 SOP                      | 新同学 30 分钟可启动项目 |
| T38 | [scripts/README.md](../scripts/README.md), [scripts/](../scripts/)                                                                                                                                                                   | 运维脚本工程化    | 1) 统一参数化命令 2) 输出结构化日志 3) 接入发布流程                           | 备份/检查脚本可自动运行   |
| T39 | [.github/workflows/](../.github/workflows/)                                                                                                                                                                                          | CI/CD 建立   | 1) 后端测试+前端构建+安全扫描 2) PR 门禁 3) 版本化产物                       | 合并前自动质量校验      |
| T40 | [ModelSelector.java](com/XYai/myai/rag/chat/pojo/ModelSelector.java), [ModelRoutingExecutor.java](com/XYai/myai/rag/chat/pojo/ModelRoutingExecutor.java), [ModelHealthStore.java](com/XYai/myai/rag/chat/pojo/ModelHealthStore.java) | 多模型路由能力成型  | 1) 健康分与熔断窗口 2) 主备切换 3) 结果质量回传调度                           | 模型故障可自动切换      |

---

## 3. 建议执行顺序（4 周冲刺版）

1. 第 1 周（安全与鉴权）
   - T01, T02, T03, T04, T05, T06
2. 第 2 周（正确性与稳定性）
   - T07, T08, T09, T10, T11, T12, T13, T15
3. 第 3 周（RAG 核心质量）
   - T16, T18, T19, T20, T21, T22, T23, T30
4. 第 4 周（前端与工程化）
   - T31, T32, T33, T34, T35, T36, T39

---

## 4. 投递简历可展示的“企业化亮点”

- 安全：密钥治理、鉴权边界、限流与异常契约统一
- 稳定：多通道检索熔断隔离、异步任务治理、线程池治理
- 质量：RAG 召回过滤重排闭环、意图识别完善、SSE 弱网恢复
- 工程：CI/CD 门禁、可观测追踪、文档与 runbook 完整

---

## 5. 备注

- 本文件是根目录新建 todo.md，和 docs/toDO.md 并行存在。
- 建议后续把 docs/toDO.md 的存量条目逐步归并到本文件，避免双份计划漂移。
