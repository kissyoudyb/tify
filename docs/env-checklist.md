# Hify 运行所需外部依赖清单

> 综合源：`pom.xml`（父 + 8 子）、`deploy/application.yml.template`、`docker-compose.yml`、`deploy/env.template`、`docs/external-deps.svg`、`README.md`。
> 覆盖：3 类依赖（关键 Java 库 / 中间件 / 外部 API）+ 4 类基础设施（容器 / 反代 / 监控 / Node 前端）。

---

## 1. 中间件（运行时，必须就绪）

| 名称 | 版本要求 | 默认端口 | 连接信息 | 初始化要求 |
|---|---|---|---|---|
| **MySQL** | 8.x（InnoDB · utf8mb4） | 3306 | `${DB_HOST}` / `${DB_PORT:3306}` / `${DB_NAME:hify}` 用户 `${DB_USERNAME}` 密码 `${DB_PASSWORD}` | 1. 创建 `hify` 库（`CREATE DATABASE hify DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`）<br>2. 执行 `hify-app/src/main/resources/db/schema.sql`（⚠ 当前仅 8 张表，详见 `docs/data-model.md` 末尾）<br>3. HikariCP 最小 5 / 最大 20 连接；账号需 `CREATE / DROP / ALTER / INDEX / SELECT / INSERT / UPDATE / DELETE` 权限 |
| **Redis** | 7.x（standalone，集群方案为扩展） | 6379 | `${REDIS_HOST}` / `${REDIS_PORT:6379}` / `${REDIS_PASSWORD}`（可空） / db 0 | 1. 启用 AOF 持久化（避免 30min 配置缓存 / 2h 会话上下文丢失）<br>2. Lettuce 池：min-idle 2 / max-active 10 / max-idle 8<br>3. 生产建议开 ACL，禁止 KEYS 命令 |
| **pgvector** | PostgreSQL 14+ 扩展（pgvector 0.1.4 driver） | 5432 | `${PGVECTOR_HOST}` / `${PGVECTOR_PORT:5432}` / `${PGVECTOR_DB:hify}` / `${PGVECTOR_USERNAME}` / `${PGVECTOR_PASSWORD}` | 1. 安装扩展：`CREATE EXTENSION IF NOT EXISTS vector;`<br>2. 创建 `hify` 库<br>3. ⚠ 注：当前 `KnowledgeBase` / `Document` entity 在 H2 schema 才有生产 DDL，**正式部署前需补 vector 表 DDL**（表名 `document_chunk` 之类） |
| **Nginx** | 1.21+（推荐 1.25 mainline） | 80（HTTP） | 反代 `localhost:8080`（后端） | 1. **必须** `proxy_buffering off;`（否则 SSE 透传失败，聊天流式接口会被缓冲）<br>2. 静态文件服务：前端 dist 目录<br>3. `proxy_read_timeout ≥ 65s`（覆盖 60s 对话超时） |
| **Docker** | 24+ | — | 宿主机 + `docker-compose.yml` | 1. 安装镜像：`hify-backend:latest`（自动 build）、`hify-frontend:latest`<br>2. 启动：`docker compose up -d`<br>3. 容器名 `backend` / `frontend` 共享 `hify-net` bridge |
| **Kubernetes**（生产 ≥ 50 人） | 1.27+ | — | `deploy/k8s/` | 1. 部署 MySQL / Redis / pgvector 为有状态服务（StatefulSet 或外部托管）<br>2. Spring Boot 应用 Deployment 副本 2–4<br>3. Ingress 配置 SSE 友好缓冲（与 Nginx 同源逻辑） |

---

## 2. 关键 Java 库（Maven 依赖，由父 pom 统一管版本）

> 来源：父 `pom.xml` `properties` + `dependencyManagement` + 各子模块 `<dependencies>`。运行时仅列实际被引用的。

| 库 | 版本 | 引入范围 | 备注 |
|---|---|---|---|
| **JDK** | 17 | 运行时 | `<java.version>17</java.version>`，Lombok 1.18.30 编译器注解处理 |
| **Spring Boot** | 3.2.3 | hify-app | starter / web / validation / data-redis / aop / actuator / test（app 才有 starter 全套） |
| **MyBatis-Plus** | 3.5.9 | 所有业务模块 | starter + 独立 jsqlparser（3.5.9+ 分页插件） |
| **MySQL Connector/J** | 8.3.0 | 所有业务模块 | `com.mysql:mysql-connector-j`，驱动类 `com.mysql.cj.jdbc.Driver` |
| **Resilience4j** | 2.2.0 | hify-common + hify-chat | `resilience4j-spring-boot3` + `spring-boot-starter-aop`；熔断每提供商独立 |
| **OkHttp** | 4.12.0 | hify-common + hify-workflow | 同步 HTTP；LLM 调用 / 工作流 `API_CALL` 节点 |
| **OpenTelemetry API** | 1.38.0 | hify-common | **仅 API**，未引入 SDK 实现；后续接 Jaeger 留口子 |
| **Micrometer core** | 1.12.x | hify-common | 埋点 API |
| **Micrometer Prometheus registry** | 1.12.3 | hify-app | 暴露 `/actuator/prometheus` |
| **Logstash Logback Encoder** | 7.4 | hify-common | JSON 结构化日志 |
| **pgvector JDBC** | 0.1.4 | hify-knowledge | 向量类型映射 |
| **MCP Java SDK** | 1.1.1 | hify-mcp | `io.modelcontextprotocol.sdk:mcp`，官方协议层 |
| **Lombok** | 1.18.30 | 所有模块 | `optional=true`；annotation processor |
| **HikariCP** | （Spring Boot 默认 5.x） | hify-app 间接 | min-idle 5 / max-pool 20 / conn-timeout 30s |
| **PostgreSQL JDBC** | （Spring Boot 默认 42.x） | hify-app（runtime） | 仅 pgvector 健康检查；scope=runtime |
| **H2** | （Spring Boot 默认 2.x） | hify-app（runtime） | 仅 `mock` profile，scope=runtime |

---

## 3. 外部 API（出网调用，配置在 Provider / MCP Server）

> LLM 类由 `hify-provider` 通过 Resilience4j 熔断（slidingWindow 10 / failureRate 50% / open 30s）；MCP 类由 `hify-mcp` 转发。

| 服务 | 端点 | 鉴权 | 初始化 |
|---|---|---|---|
| **OpenAI** | `https://api.openai.com/v1` | API Key（Bearer），存于 `provider.auth_config` JSON | 1. 在 `/api/v1/providers` 创建 Provider，type=`OPENAI`<br>2. 填 `apiKey`、可选 `base_url`（Azure/代理）<br>3. 下挂 `model_config`（如 `gpt-4o`）<br>4. 调 `POST /api/v1/providers/{id}/test-connection` 验证 |
| **Anthropic Claude** | `https://api.anthropic.com` | `x-api-key` Header | 同上流程，type=`ANTHROPIC`，model_id 例 `claude-3-5-sonnet-20241022` |
| **Google Gemini** | `https://generativelanguage.googleapis.com` | API Key query | 同上流程，type=`GEMINI`（如有） |
| **Ollama**（本地） | `http://localhost:11434` | 无 / 自定义 | 1. 本机安装 Ollama，`ollama pull llama3` 拉模型<br>2. Provider `base_url` 改 `http://<lan-ip>:11434`，type=`OLLAMA` |
| **Azure OpenAI** | `https://<resource>.openai.azure.com` | API Key + 部署名 | type=`AZURE_OPENAI`，`model_id` 填 **deployment name** 而非模型名 |
| **OpenAI 兼容** | 任意 | 按目标服务 | type=`OPENAI_COMPATIBLE`，`base_url` 覆盖默认 |
| **MCP Servers** | 用户配置的 `endpoint` | 协议规定 | 1. 创建 `/api/v1/mcp-servers` 记录<br>2. `POST /{id}/test` 验证握手<br>3. `GET /{id}/tools` 拉工具清单<br>4. Agent 端通过 `PUT /api/v1/agents/{id}/tools` 绑定 |

---

## 4. 基础设施（运维旁路，非强阻塞）

| 组件 | 端口 | 用途 | 初始化 |
|---|---|---|---|
| **Spring Boot Actuator** | 8080（与 API 同端口） | `/actuator/health` `/actuator/prometheus` | 暴露 Prometheus 指标，需配合 Micrometer registry |
| **Prometheus** | 9090 | 抓取应用指标 | `deploy/grafana-dashboard.json` 提供 dashboard |
| **Grafana** | 3000 | 可视化 | 导入 `deploy/grafana-dashboard.json` |
| **日志收集（ELK / Loki）** | — | JSON 日志管线 | Logstash Logback Encoder 输出 JSON 到 stdout，挂载到 `./logs` 或 stdout driver |
| **Jaeger**（未来） | 14268 / 16686 | 全链路追踪 | 需引入 OpenTelemetry SDK + exporter，目前仅 API 在 classpath |

---

## 5. 本地开发最小集（无需外部 API）

按 README 第二节“mock profile”：

```bash
mvn clean install -DskipTests
mvn spring-boot:run -pl hify-app -Dspring-boot.run.profiles=mock
cd hify-web && npm run dev
```

- **MySQL 必需**：mock profile 仍走真实 MySQL（以 schema.sql 初始化为基线）
- **Redis / pgvector 可选**：未配置时 `health` 接口返回 `skipped`
- **LLM API 可选**：不创建 Provider 即可启动，但对话接口会 502 "无 Provider"

> 注：CLI 提示 `mvn clean package -DskipTests` 用于本地打 jar；Maven 版本过低需 `brew upgrade maven`（Mac）。

---

## 6. 端口总览

| 端口 | 用途 | 备注 |
|---|---|---|
| 80 | Nginx HTTP | `FRONTEND_EXPOSE_PORT` 可改 |
| 8080 | Spring Boot | `BACKEND_EXPOSE_PORT` 可改 |
| 3306 | MySQL | 外部或容器 |
| 6379 | Redis | 外部或容器 |
| 5432 | PostgreSQL / pgvector | 外部或容器 |
| 9090 | Prometheus | 运维旁路 |
| 3000 | Grafana | 运维旁路 |
| 11434 | Ollama | 外部（可选） |

---

## 7. 启动检查清单（生产部署前逐项 ✓）

- [ ] MySQL 8+ 已起，库 `hify` 已建，账号权限到位，schema.sql 已执行（当前仅 8 张表，⚠ 7 张待补）
- [ ] Redis 7+ 已起，AOF 开启，密码已设
- [ ] pgvector 扩展已安装，库 `hify` 已建，向量表 DDL 已执行（需补）
- [ ] Nginx 反代配置加载，`proxy_buffering off` 已生效
- [ ] LLM 提供商至少 1 个（OpenAI / Claude / Ollama）的 `apiKey` 已填，连通性测试通过
- [ ] `/api/v1/health` 返回 200 且 `components` 全部 `UP` 或 `skipped`
- [ ] Dockerfile 镜像已构建，docker-compose 容器三件套（backend / frontend / nginx）都已 `Up`
- [ ] JVM 内存按预估峰值分配（`JVM_OPTS`，生产建议 512m–1g）
- [ ] Prometheus 已纳入抓取，Grafana dashboard 已导入
- [ ] 备份策略：MySQL 全量 + 增量、Redis AOF、pgvector 因 embedding 重建成本高建议原始文档同步备份
