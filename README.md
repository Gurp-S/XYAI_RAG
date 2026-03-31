# My-AI — 企业级 RAG 智能体平台（示例工程）

此仓库为学习与扩展用的 RAG（检索增强生成）智能体平台示例，包含核心接口与模块骨架（检索、意图识别、问题重写、会话记忆、文档入库 Pipeline、MCP 工具等）。

---

## 前置条件

- JDK 21
- PowerShell（Windows）或 Bash
- 可选：Docker（用于快速启动 Redis）

项目已包含 Maven Wrapper：`mvnw` / `mvnw.cmd`，建议使用 wrapper 运行而无需全局安装 Maven。

---

## 在本地运行（Windows PowerShell）

1. 进入项目根目录：

```powershell
Set-Location 'D:\lea\My-AI'
```

2. 启动应用（开发模式）：

```powershell
.\mvnw.cmd spring-boot:run
```

或先打包再运行：

```powershell
.\mvnw.cmd clean package
java -jar target\*.jar
```

---

## 运行测试

运行全部测试：

```powershell
.\mvnw.cmd test
```

运行单个测试类：

```powershell
.\mvnw.cmd -Dtest=RateLimitIntegrationTest test
```

---

## 在 IntelliJ 中调试

- 打开项目根目录。
- 在代码需要处设置断点，选择 Run -> Debug `MyAiApplication` 或对应测试类。

---

## 核心接口位置（待实现）

- 意图识别：`com.XYai.myai.core.intent.IntentRecognitionService`
- 问题重写：`com.XYai.myai.core.rewrite.QueryRewriter`
- 检索器：`com.XYai.myai.core.retriever.Retriever` 与 `RetrieverCoordinator`
- 后处理器：`com.XYai.myai.core.post.PostProcessor`
- 会话记忆：`com.XYai.myai.core.memory.MemoryStore`
- 文档入库 Pipeline：`com.XYai.myai.core.pipeline.PipelineNode` 等
- MCP 工具：`com.XYai.myai.core.mcp.MCPToolExecutor`

这些接口已生成为源码文件，方便你按需实现具体逻辑（向量库、LLM、检索器适配器等）。

---

## 快速实现示例（Stub Retriever）

在 `src/main/java/com/XYai/myai/impl/StubRetriever.java` 新建一个类：

```java
package com.XYai.myai.impl;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class StubRetriever implements Retriever {
    @Override
    public CompletableFuture<RetrievalResult> retrieve(String query, int topK, Map<String, Object> hints) {
        DocumentChunk doc = new DocumentChunk("stub-1", "stub", "示例文档内容: " + query, Map.of());
        SearchResult sr = new SearchResult(doc, 0.9);
        return CompletableFuture.completedFuture(new RetrievalResult(List.of(sr), Map.of()));
    }
}
```

把它加入容器后，你可以用 Postman/curl 调用 Controller 验证流程是否通畅。

---

## 常见问题排查

- 找不到 Lua 脚本：确保 `src/main/resources/luaScript/limit.lua` 存在（已包含到本仓库）。
- Redis 报错：确认本地 Redis 服务可用或用 Docker 启动：

```powershell
docker run -d -p 6379:6379 --name my-redis redis
```

- Ollama 注入为 null：确认 `CommonConfiguration` 中创建 `OllamaChatModel` 成功，或在测试/本地替换为 stub bean。

---

## 下一步（可选）

- 我可以为你生成一组 Stub 实现（Retriever、RAGOrchestrator、MCP 工具）并写好单元测试，或把现有集成测试改为更轻量的 `@WebMvcTest`。回复 `生成 stub` 或 `改写测试` 即可。

---

如需我继续生成 stub 实现并提交到仓库，请回复“生成 stub”。

