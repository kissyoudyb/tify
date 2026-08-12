# Tify — Agent 入口指南

> 简版 AI Agent 开发平台（参考 Dify），本地部署，20–50 人规模。
> 完整项目规范见 [`CLAUDE.md`](CLAUDE.md)；本文件只做精简导航。

## 1. 项目定位

- **做什么**：多模型接入（OpenAI / Claude / Gemini / Ollama）、Agent 配置、对话引擎、RAG 知识库、简版工作流、MCP 工具接入。
- **不做什么**：可视化工作流拖拽 / 多租户 / 插件市场 / 计费 / WebSocket（用 SSE 替代）。
- **技术栈**：Spring Boot 3.2.3 + MyBatis-Plus + MySQL 8 + Redis 7 + pgvector / Vue 3 + Element Plus + Vite / Docker + K8s。

## 2. 核心架构

模块化单体，按模块分 Maven 子项目，模块间走 Service 接口，不直接互引 Mapper / Entity。

- **架构总览**：[`docs/architecture.svg`](docs/architecture.svg)
- **模块依赖**：[`docs/module-deps.svg`](docs/module-deps.svg)（无循环）
- **对外依赖**：[`docs/external-deps.svg`](docs/external-deps.svg)
- **REST 接口清单**：[`docs/api-list.md`](docs/api-list.md)（42 个接口）
- **数据模型**：[`docs/data-model.md`](docs/data-model.md) · [`docs/data-model-er.svg`](docs/data-model-er.svg)

## 3. 关键模块

| 模块 | 一句话职责 |
|---|---|
| `tify-app` | Spring Boot 启动入口，引入所有业务模块 |
| `tify-chat` | 对话引擎 · 流式 SSE · 多轮上下文 |
| `tify-agent` | Agent 配置（绑模型 / 绑工具 / System Prompt） |
| `tify-workflow` | 顺序 + 条件分支的工作流编排 |
| `tify-knowledge` | RAG 文档摄入 + pgvector 检索 |
| `tify-provider` | 多 LLM 提供商适配 + 熔断 |
| `tify-mcp` | MCP 工具调用网关（外部 JSON-RPC） |
| `tify-common` | 基类 / 异常 / 常量 / 工具（所有模块依赖） |

## 4. 关键约定

- **接口风格**：`/api/v1/{资源}`，统一 `Result<T>` 响应，分页用 `PageResult<T>`。
- **错误码**：按模块分段 1000–7999（详见 CLAUDE.md），禁止硬编码。
- **线程池**：LLM 调用必须用 `@Qualifier("llmExecutor")`；SSE 用 `sseExecutor`；异步非关键用 `asyncExecutor`。**禁止业务代码 `new Thread()`**。
- **熔断**：每个 LLM 提供商独立 Resilience4j 熔断器（slidingWindow 10 / failureRate 50% / open 30s）。
- **超时**：对话 60s，连通性测试 10s，所有外部调用必须配超时。
- **缓存**：Provider / Agent 配置 Redis Cache-Aside TTL 30min；会话上下文 TTL 2h；消息走 MySQL 不缓存。
- **数据库**：表名小写下划线，主键 `id` bigint 自增，逻辑删除 `deleted` 字段，外键在应用层维护。
- **包结构**：每个模块 `controller / service / service-impl / mapper / entity / dto / config / exception / constant`。
- **基础设施**：Nginx S 反代需 `proxy_buffering off`（SSE 透传关键）。

## 5. 怎么跑

```bash
# 1. 编译（tify-* 互相依赖，先 install）
mvn clean install -DskipTests

# 2. 启动后端（mock profile = H2 内存数据库，无需 MySQL）
mvn spring-boot:run -pl tify-app -Dspring-boot.run.profiles=mock

# 3. 启动前端
cd tify-web && npm run dev

# 4. 健康检查
curl http://localhost:8080/api/v1/health
```

生产部署走 `docker-compose.yml`（Nginx + 后端 + 前端），中间件（MySQL / Redis / pgvector）由 `deploy/` 配置。详细环境变量见 `deploy/env.template`。

## 6. 禁区

> 待补充。预期覆盖：不要破坏现有 REST 契约、不要绕开 ErrorCode 枚举、不要在业务代码用默认线程池、不要把 chat_message 列表查询用 `SELECT COUNT(*)` 等。

<!-- TODO: 把 CLAUDE.md「行为指令」里真正"禁止"的事项精炼进来 -->

## 7. 历史包袱

> 待补充。预期覆盖：生产 schema.sql 缺 7 张表（knowledge_base / document / workflow*，见 data-model.md ⚠ 节）、mock profile 与生产 H2 行为差异、Nginx SSE 缓冲问题踩坑记录等。

<!-- TODO: 启动后把 README 提到过的"打包依赖找不到 / Maven 版本太低"等真实历史坑沉淀到这里 -->
