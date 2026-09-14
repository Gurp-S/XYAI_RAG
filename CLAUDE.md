# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

XYAI is an enterprise-grade RAG (Retrieval-Augmented Generation) QA platform. Backend: Spring Boot 3 + Java 21, Frontend: Vue 3 + Vite. Three deployment targets: backend JAR, admin management UI (`newXyAdmin`), and user-facing chat UI (`xyUser`).

## Build & Run Commands

### Backend (Maven)
```bash
.\mvnw.cmd clean package                    # Build JAR
.\mvnw.cmd spring-boot:run                   # Run dev server (port 8080)
.\mvnw.cmd clean package -DskipTests         # Skip tests during build
```

### Admin Frontend (newXyAdmin) — port 5174
```bash
cd newXyAdmin
npm install
npm run dev                                  # Dev server
npm run build                                # Production build
```

### User Chat Frontend (xyUser) — port 5173
```bash
cd xyUser
npm install
npm run dev                                  # Dev server
npm run build                                # Production build
npm run lint                                 # ESLint check + fix
```

### Infrastructure
```bash
docker-compose up -d                         # Start MySQL + Redis + Milvus + back/front
```

### Database
- SQL schema: `src/main/resources/sql/MYsql.sql` (auto-executed on startup via `spring.sql.init.mode=always`)
- Config: `src/main/resources/application.yaml` (includes MySQL, Redis, Milvus, Neo4j, Ollama, AI model APIs)

## Project Structure

### Backend (`src/main/java/com/XYai/myai/`)
```
├── rag/                          # Core RAG engine
│   ├── RetrievalAugmentedGeneration.java     # 8-step RAG orchestration
│   ├── chat/                     # Chat engine + Model routing + Streaming
│   │   ├── ChatOrchestrator.java            # Main chat orchestration
│   │   ├── ChatController.java              # REST API endpoints
│   │   ├── ModelRouterService.java          # Model routing logic
│   │   └── ModelInvocationService.java      # AI model invocation
│   ├── memory/                   # 3-tier memory (Redis window + LLM summary + Milvus vector)
│   ├── rewrite/                  # Query rewriting (rule-based + LLM)
│   ├── intent/                   # Intent recognition (4-layer: Redis/DB/Vector/LLM)
│   ├── channel/                  # Multi-channel retrieval + BM25/Filter/Rerank
│   ├── graph/                    # Neo4j knowledge graph entity recognition
│   ├── mcp/                      # MCP (Model Context Protocol) tool system
│   ├── milvus/                   # Milvus vector store operations + ACL
│   ├── etlpipeline/              # ETL pipeline (Tika → IK chunk → LLM enrich → Milvus)
│   ├── evaluate/                 # System evaluation (LLM/Rerank/Rule)
│   ├── aop/                      # Full-link tracing via @RagTraceNode aspect
│   └── ragPojo/                  # RAG data models
├── config/                       # ThreadPool, Redis, OSS, Milvus, MCP, Security configs
├── security/                     # JWT auth + Spring Security + RSA keys
├── user/                         # User management
├── xyAdmin/                      # Admin REST API controllers
├── mapper/                       # MyBatis-Plus mappers
├── monitorEndpoint/              # Thread pool + Trace record endpoints
├── commonUtils/                  # IK analyzer, Redis utils, Ollama reranker
└── exception/                    # Global exception handling
```

### Frontend Architecture

**Two independent Vue 3 + Vite apps**:

1. **newXyAdmin** (Admin Management UI): Element Plus + ECharts dashboard. Routes: Dashboard, Chat, Model Management, File Management, ETL Pipeline, MCP Tools, Intents, RAG Config, Trace, User/Group Management, Evaluations, Announcements. Proxy: `/xyAdmin`, `/upload`, `/ai` → backend `:8080`.

2. **xyUser** (User Chat UI): Custom chat interface with markdown rendering (marked + highlight.js + KaTeX). No UI framework dependency. Dev password protection via Vite plugin. Proxy: `/upload`, `/ai`, `/user`, `/milvus`, `/oss`, `/evaluate`, `/metadata`, `/xyAdmin` → backend `:8080`.

## Key Architecture Patterns

### RAG Pipeline (8 steps in `RetrievalAugmentedGeneration.java`)
1. User context → 2. MCP tool results (async) → 3. Session memory (3-tier) → 4. Query rewrite → 5. Entity recognition (Neo4j) → 6. Multi-channel doc retrieval → BM25 → Filter → Rerank → 7. Merge results → 8. Model routing + streaming output

### Async Architecture
- No WebFlux/Reactor — uses `CompletableFuture` + `SseEmitter` (Spring MVC)
- `TransmittableThreadLocal` for traceId/user context across thread pools
- Separated thread pools: ioBound, chat, mcp, trace, evaluate, upload
- Streaming via `Consumer<String> onChunk` callback pattern

### Full-Link Tracing
- `@RagTraceNode` / `@RagTraceRoot` annotations on each RAG step
- AOP aspect auto-records timing, status, errors via `TransmittableThreadLocal`

### Model Routing
- Failover/Latency-based/Round-robin strategies
- Circuit breaker per model (Resilience4j)
- Cross-provider fallback (Aliyun Bailian → Ollama)

### ETL Pipeline
- Pluggable nodes: Parser → Chunker → Enricher → Indexer (chain-configured via `NodeConfig`)
- Condition evaluator for per-node skip logic
- Cycle detection in pipeline topology

## Key Configurations

- `application.yaml` — AI model keys, Milvus, Neo4j, Redis, JWT, retrieval params
- `application-local.yaml` — Local overrides (gitignored)
- JWT RSA keys: `src/main/resources/keys/` (private_pkcs8.pem, public_x509.pem)
- MCP server config: `src/main/resources/mcp-server-config.json`
- Rate limiting Lua script: `src/main/resources/luaScript/limit.lua`

## Database

- MySQL with MyBatis-Plus (tables: chat_conversation, user, file_record, intent_node, trace_record, system_config, etc.)
- Redis via Redisson (SortedSet for memory window sliding, hash for intent cache)
- Neo4j for knowledge graph
- Milvus for vector storage (collection: `my_ai`, COSINE metric, ivf_flat index)

## Important Notes

- Frontend dev servers require the backend running on port 8080
- xyUser frontend has a Vite dev password plugin (default pwd: `xyRag2026`, set `?pwd=xyRag2026` in URL to auth)
- The admin frontend (newXyAdmin) auto-initializes DB schema on startup
- Milvus scripts in `scripts/` directory for collection management (Python/pymilvus)
- OSS uploads are disabled by default (`upload.ossEnabled: false`)
- Intent recognition is disabled by default (`intent.intent-enabled: false`)
