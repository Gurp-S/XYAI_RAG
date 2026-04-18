目标：基于项目依赖，提供一套可运行的 Docker Compose 部署方案（包含 MySQL、Redis、Milvus、后端、前端）。

快速开始（Windows 命令提示符）

1) 复制示例 env 并编辑敏感信息

    copy .env.example .env
    REM 编辑 .env 中的密码/配置

2) 构建并启动

    docker-compose up -d --build

3) 查看日志

    docker-compose logs -f backend

4) 停止并删除（包括数据卷）

    docker-compose down -v

说明与提示

- 依赖项回顾（来自 `pom.xml`）：
  - milvus-sdk-java (io.milvus) -> 需要运行 Milvus 服务并暴露 19530
  - spring-boot-starter-data-redis / redisson -> 需要 Redis
  - mysql-connector-j -> 需要 MySQL
  - spring-ai / spring-ai-starter-vector-store-milvus -> 依赖 Milvus 与嵌入模型

- Milvus 镜像： compose 中使用了 `milvusdb/milvus:v2.2.11-standalone` 作为示例。
  实际使用前请确认与 `milvus-sdk-java` 的兼容性；如果你在生产使用其它版本，请替换为合适的 Milvus 服务器镜像。

- 前端：构建产物将由 nginx 提供，浏览器访问容器的 80 端口。

- 生产建议：
  - 使用 Redis Streams / RabbitMQ 替换简单队列实现以便可靠消费/确认。
  - Milvus 在生产通常需要额外的存储卷与 tuning（memory/CPU/IO），不要直接用默认的 standalone 镜像在高负载下运行。
  - 为后端配置 JVM 参数（Xms/Xmx）与日志挂载。

常见问题与调试

- 如果后端无法连接 MySQL：检查 `.env` 中 URL、容器名 `mysql` 是否能被解析（compose 内部 network 名称）。
- Milvus 连接问题：确认 SDK 使用的 host/port 与容器映射一致（默认 milvus:19530）。

