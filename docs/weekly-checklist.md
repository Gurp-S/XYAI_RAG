# Weekly Checklist

> 用途：记录每周目标、每日推进和状态变更。
>
> 状态统一使用：`ToDo / Doing / Done`
>
> 建议每天收工前更新一次，保持计划真实。

## 1. 使用规则

- 只保留一个当前周计划块
- 每日结束前更新任务状态
- 遇到阻塞时，补充阻塞原因与下一步动作
- 已完成任务必须留下证据链接或简短说明

## 2. 本周信息

- Week: `W1`
- Objective: `待填写`
- Owner: `待填写`
- Start Date: `待填写`
- End Date: `待填写`

## 3. 本周任务清单

| ID | Priority | Task | Status | Owner | ETA | Evidence | Notes |
|---|---|---|---|---|---|---|---|
| P0-1 | P0 | 配置与密钥外置治理 | ToDo |  |  |  |  |
| P0-2 | P0 | 认证授权与用户边界 | ToDo |  |  |  |  |
| P0-3 | P0 | 统一可观测性基线 | ToDo |  |  |  |  |
| P0-4 | P0 | 稳定性保护机制 | ToDo |  |  |  |  |
| P0-5 | P0 | API 契约与错误规范 | ToDo |  |  |  |  |
| P0-6 | P0 | RAG 入库治理 | ToDo |  |  |  |  |
| P0-7 | P0 | 链路追踪与审计留痕 | ToDo |  |  |  |  |
| P0-8 | P0 | CI/CD + 备份恢复门禁 | ToDo |  |  |  |  |

## 4. P0 开发执行清单

> 用法：每个 P0 都拆成“先改什么、再补什么、最后验什么”。
> 你可以每天从这里挑 1~3 项推进，并把状态同步回上面的任务表。

### P0-1 配置与密钥外置治理

- [ ] 梳理 `application.yaml`、`application-local.yaml` 中的数据库、Redis、Milvus、OSS、AI Key 配置项
- [ ] 把明文密钥替换为环境变量占位或本地私有配置约定
- [ ] 检查 `@Value` / `@ConfigurationProperties` 注入点，补齐缺失配置的启动失败提示
- [ ] 统一本地 / 开发 / 测试 / 生产四套配置来源说明
- [ ] 记录一份“哪些配置已经外置、哪些仍需处理”的清单
- [ ] 验收：仓库中无明文密钥，启动日志能明确告诉你缺了什么配置

### P0-2 认证授权与用户边界

- [ ] 梳理登录入口、鉴权过滤器、拦截器、用户上下文传递方式
- [ ] 统一用户来源，不再依赖散落的静态状态作为业务依据
- [ ] 给 `ChatController`、上传接口、管理接口补统一鉴权约束
- [ ] 让请求结束后清理用户上下文，避免线程复用污染
- [ ] 定义未授权、非法用户、权限不足的返回格式
- [ ] 验收：未登录请求被拒绝，登录后日志可识别当前用户

### P0-3 统一可观测性基线

- [ ] 给请求入口补 `traceId / requestId` 生成与透传
- [ ] 统一日志格式，确保一次请求可以串起 controller、service、milvus、redis 等日志
- [ ] 梳理追踪链路：`RagTraceAspect`、`RagTraceContext`、`TraceRecordServiceIml`
- [ ] 补健康检查与基础指标暴露，至少覆盖应用、数据库、Redis、Milvus
- [ ] 统一日志字段：用户、会话、任务、集合名、节点名、耗时、异常信息
- [ ] 验收：能凭日志和指标快速定位一次请求的问题点

### P0-4 稳定性保护机制

- [ ] 梳理外部依赖调用点：模型、Redis、MySQL、Milvus、OSS、HTTP 工具链
- [ ] 给核心调用补超时与重试策略
- [ ] 对上传、检索、生成三条主链路定义降级路径
- [ ] 收敛限流入口，统一由配置或公共组件控制
- [ ] 配置线程池隔离与队列策略，避免异步任务拖垮主线程
- [ ] 验收：失败注入或高并发下，服务不会被单点问题击穿

### P0-5 API 契约与错误规范

- [ ] 统一 `Result<T>` 的返回语义，补齐成功、业务失败、系统失败的状态码分层
- [ ] 扩展全局异常处理，把参数校验异常、业务异常、系统异常统一映射
- [ ] 给主要 Controller 的入参补校验注解与空值校验
- [ ] 统一前后端错误码、错误消息、空结果、部分成功返回格式
- [ ] 输出一份接口返回规范，避免同类错误在不同接口返回不同结构
- [ ] 验收：同类异常返回格式一致，联调时不再靠猜

### P0-6 RAG 入库治理

- [ ] 梳理上传 → 解析 → 切分 → 入库 → 刷新索引的完整链路
- [ ] 把入库链路拆成可重试、可记录状态的步骤
- [ ] 强化任务状态流转，让前端可看到每个节点的实时状态
- [ ] 在 `MilvusCollectionService` 中补去重、幂等、显式刷新、失败记录
- [ ] 为解析、切分、索引节点补失败恢复与状态追踪
- [ ] 验收：重复入库可识别，失败任务可重试，入库后可检索

### P0-7 链路追踪与审计留痕

- [ ] 梳理 `TraceRecordService`、Mapper、审计表的持久化路径
- [ ] 让关键入口都写入追踪上下文：用户、会话、文档、任务、traceId
- [ ] 增加追踪查询接口，支持按用户、会话、traceId、文档回查
- [ ] 补回放所需审计字段：开始时间、结束时间、节点耗时、失败原因、当前节点类型
- [ ] 确保问答、检索、入库的关键步骤都能落到审计记录里
- [ ] 验收：能按 traceId 回看完整链路和失败原因

### P0-8 CI/CD + 备份恢复门禁

- [ ] 梳理 `pom.xml` 的构建产物和打包方式，明确发布制品
- [ ] 固化构建、发布、回滚的脚本或命令步骤
- [ ] 增加发布前检查清单：配置、数据库、Redis、Milvus、关键表/集合
- [ ] 建立 MySQL、Redis、Milvus 的备份与恢复步骤
- [ ] 写清发布 / 回滚 / 恢复三种场景的最小闭环
- [ ] 验收：能完成一次完整的构建、发布、回滚或恢复演练

## 5. 每日更新模板

### YYYY-MM-DD

- ToDo:
  - 
- Doing:
  - 
- Done:
  - 
- Blockers:
  - 
- Comment:
  - 

### YYYY-MM-DD

- ToDo:
  - 
- Doing:
  - 
- Done:
  - 
- Blockers:
  - 
- Comment:
  - 

### YYYY-MM-DD

- ToDo:
  - 
- Doing:
  - 
- Done:
  - 
- Blockers:
  - 
- Comment:
  - 

## 6. 周末复盘

- 本周完成了什么：
  - 
- 哪些任务延期或阻塞：
  - 
- 下周要优先处理什么：
  - 
- 需要补充的文档/决策：
  - 

## 7. 状态流转规则

- `ToDo`：未开始
- `Doing`：进行中或被阻塞但仍在推进
- `Done`：已完成并有证据

## 8. 备注

- 若某任务长期阻塞，先拆成更小的子任务，再继续推进
- 若计划调整，保留历史记录，不要直接覆盖掉有价值的结论
  P0-1 配置与密钥外置治理
  必须改
  src/main/resources/application.yaml
  src/main/resources/application-local.yaml
  src/main/java/com/XYai/myai/Config/CommonConfiguration.java
  src/main/java/com/XYai/myai/Config/AiPrimaryConfiguration.java
  src/main/java/com/XYai/myai/Config/OssConfig.java
  src/main/java/com/XYai/myai/Config/RedissonConfig.java
  src/main/java/com/XYai/myai/Config/RedisTemplateConfiguration.java
  src/main/java/com/XYai/myai/RAG/Milvus/MilvusCollectionService.java
  联动改
  src/main/java/com/XYai/myai/RAG/ETLpipeline/Oss/AliossProperties.java
  src/main/java/com/XYai/myai/RAG/Memory/POJO/MemoryProperties.java
  src/main/java/com/XYai/myai/RAG/rewrite/POJO/RewriterProperties.java
  src/main/java/com/XYai/myai/RAG/intent/POJO/IntentProperties.java
  src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/PipelineProperties.java
  src/main/java/com/XYai/myai/Config/DevProxyController.java

P0-2 认证授权与用户边界
必须改
src/main/java/com/XYai/myai/User/UserController.java
src/main/java/com/XYai/myai/User/LoginUserInfoManager.java
src/main/java/com/XYai/myai/Config/LoginHandlerInterceptor.java
src/main/java/com/XYai/myai/Config/MvcConfiguration.java
src/main/java/com/XYai/myai/Controller/ChatController.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusController.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/UploadController.java
联动改
src/main/java/com/XYai/myai/Config/Result.java
src/main/java/com/XYai/myai/Exception/ExceptionController.java
src/main/java/com/XYai/myai/Service/UserService.java
src/main/java/com/XYai/myai/Service/iml/UserServiceIml.java

P0-3 统一可观测性基线
必须改
src/main/java/com/XYai/myai/RAG/Aop/RagTraceAspect.java
src/main/java/com/XYai/myai/RAG/RagTraceContext.java
src/main/java/com/XYai/myai/Service/TraceRecordService.java
src/main/java/com/XYai/myai/Service/iml/TraceRecordServiceIml.java
src/main/java/com/XYai/myai/mapper/TraceRecordMapper.java
src/main/java/com/XYai/myai/mapper/NodeRecordMapper.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/TraceRecord.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/RagTraceRoot.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/RagTraceNode.java
联动改
src/main/java/com/XYai/myai/Controller/ChatController.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/UploadController.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusCollectionService.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Factory/PipelineDefinitionFactory.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/IngestionEngine.java
src/main/java/com/XYai/myai/RAG/Aop/TaskStatusManager.java
src/main/java/com/XYai/myai/RAG/Aop/TaskStatusSnapshot.java

P0-4 稳定性保护机制
必须改
src/main/java/com/XYai/myai/RAG/Aop/rateLimitAspect.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/rateLimit.java
src/main/java/com/XYai/myai/Exception/rateLimitException.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusService.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusCollectionService.java
src/main/java/com/XYai/myai/RAG/Chat/Service/ChatService.java
src/main/java/com/XYai/myai/RAG/Chat/Service/Impl/ChatServiceIml.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/IngestionEngine.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/ConditionEvaluator.java
联动改
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Fetcher.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Parser.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Chunker.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Indexer.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Enricher.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Ingestion.java
src/main/java/com/XYai/myai/RAG/Memory/ConversationMemorySummaryService.java
src/main/java/com/XYai/myai/RAG/rewrite/rewriter.java

P0-5 API 契约与错误规范
必须改
src/main/java/com/XYai/myai/Config/Result.java
src/main/java/com/XYai/myai/Exception/ExceptionController.java
src/main/java/com/XYai/myai/Exception/PipelineException.java
src/main/java/com/XYai/myai/Exception/ToolExecutionException.java
src/main/java/com/XYai/myai/Exception/rateLimitException.java
src/main/java/com/XYai/myai/Controller/ChatController.java
src/main/java/com/XYai/myai/User/UserController.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/UploadController.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusController.java
联动改
src/main/java/com/XYai/myai/RAG/Chat/Service/ChatService.java
src/main/java/com/XYai/myai/RAG/Chat/Service/Impl/ChatServiceIml.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusCollectionService.java
src/main/java/com/XYai/myai/Service/UserService.java
src/main/java/com/XYai/myai/Service/iml/UserServiceIml.java

P0-6 RAG 入库治理
必须改
src/main/java/com/XYai/myai/RAG/ETLpipeline/UploadController.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/IngestionEngine.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/ConditionEvaluator.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Factory/PipelineDefinitionFactory.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Factory/UploadIngestionContextFactory.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusCollectionService.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/IngestionContext.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/NodeResult.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/NodeLog.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/PipelineDefinition.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/UploadProperties.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/UpLoadAccumulator.java
联动改
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Fetcher.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Parser.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Chunker.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Indexer.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Enricher.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Nodes/Ingestion.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Oss/OssService.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Oss/AliossProperties.java
src/main/java/com/XYai/myai/RAG/Aop/TaskStatusManager.java
src/main/java/com/XYai/myai/RAG/Aop/TaskStatusSnapshot.java

P0-7 链路追踪与审计留痕
必须改
src/main/java/com/XYai/myai/Service/TraceRecordService.java
src/main/java/com/XYai/myai/Service/iml/TraceRecordServiceIml.java
src/main/java/com/XYai/myai/mapper/TraceRecordMapper.java
src/main/java/com/XYai/myai/mapper/NodeRecordMapper.java
src/main/java/com/XYai/myai/RAG/Aop/RagTraceAspect.java
src/main/java/com/XYai/myai/RAG/RagTraceContext.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/TraceRecord.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/RagTraceRoot.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/RagTraceNode.java
src/main/java/com/XYai/myai/RAG/Aop/Annotation/NodeRecord.java
联动改
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/NodeLog.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/NodeResult.java
src/main/java/com/XYai/myai/RAG/Chat/Service/Impl/ChatServiceIml.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/UploadController.java
src/main/java/com/XYai/myai/Controller/ChatController.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/IngestionEngine.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/POJO/NodeConfig.java

P0-8 CI/CD + 备份恢复门禁
必须改
pom.xml
README.md
scripts/README.md
scripts/backup_milvus_collection.py
scripts/check_milvus_collection.py
scripts/create_milvus_collection.py
scripts/drop_milvus_collection.py
scripts/milvus_insert_search_test.py
联动改
src/main/resources/application.yaml
src/main/resources/application-local.yaml
src/main/java/com/XYai/myai/Config/CommonConfiguration.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusCollectionService.java
src/main/java/com/XYai/myai/RAG/Milvus/MilvusService.java
src/main/java/com/XYai/myai/RAG/ETLpipeline/Oss/OssService.java
src/main/java/com/XYai/myai/RAG/Chat/Service/Impl/ChatServiceIml.java
