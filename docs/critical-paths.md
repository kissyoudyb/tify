# Tify 关键测试链路

> 来源：`docs/api-list.md`（42 接口）+ `docs/data-model.md`（15 表）+ `CLAUDE.md` + `AGENTS.md`
> 原则：8 条以内；只挑"改造时容易出问题"的链路；一条链路 = 一次会话能跑完的端到端路径

## 链路一览

| # | 链路名 | 起点（哪个接口） | 关键节点 | 终点（什么状态） |
|---|---|---|---|---|
| 1 | **Provider 连通性闭环** | `POST /api/v1/providers/{id}/test-connection` | OkHttp 出网 → OpenAI/Claude/Ollama 真实端点 → Resilience4j 熔断计数+1 → 写入 `provider_health.fail_count` / `latency_ms` / `error_message` | `Result.success=true` 或 `false`，后者含真实 `errorMessage`（**链路 5/6 也会写熔断计数**，这是共享可变状态） |
| 2 | **SSE 流式对话全链路** | `POST /api/v1/chat/sessions/{sessionId}/messages/stream` | ChatService 读取 `chat_session` → 查 `agent` 拿 `modelConfigId` → `provider` + `model_config` 拿 baseUrl/apiKey → OkHttp 建连 LLM → SSE chunk → 异步写 `chat_message` 表 → `chat_session.updated_at` 刷新 | 首 token latency < 2s + 流结束 1 行 `chat_message` (`role=assistant`) + `chat_session.updated_at` 变更 |
| 3 | **MCP 工具调用链** | `POST /api/v1/agents/{id}/tools` | `agent_tool` 关联表 INSERT（UK 去重） → ChatService 把 toolIds 注入 prompt → MCP Java SDK JSON-RPC over HTTP → MCP Server 真实执行 → 结果回填到 LLM 第二轮 | 关联表新增 N 行且无重复；Agent 实际拿到工具返回值（**MCP 链路是 tify-mcp 模块唯一对外能力**） |
| 4 | **RAG 检索 → 引用回答** | `POST /api/v1/knowledge-bases/{kbId}/documents` | 文件上传 → 异步分块 → embedding 写入 `pgvector.document_chunk` → Chat 触发 RAG → pgvector 相似度检索 → topK chunks 注入 prompt | 上传完成后 `document.status=DONE` + `chunk_count > 0`；对话接口能拿到相关 chunk 引用 |
| 5 | **熔断 + 重试回归** | `POST /api/v1/providers` (type=OPENAI, bad key) | Resilience4j 滑动窗口 10 次触发 → 熔断器打开 → 后续 LLM 调用 30s 内快速失败 → 半开探测 → 状态机迁移 | `provider_health.status` 在 UP → DEGRADED → OPEN → HALF_OPEN 间流转；`fail_count` 累计正确 |
| 6 | **多轮上下文窗口** | `POST /api/v1/chat/sessions/{sessionId}/messages` | ChatService 拉最近 N 轮 `chat_message` (受 `agent.max_context_turns` 控制) → 拼装 prompt → token 预算校验 (`model_config.context_size`) → LLM | LLM 收到的 message 数 == min(历史条数, max_context_turns)；超长上下文触发截断告警而非崩溃 |
| 7 | **逻辑删除穿透** | `DELETE /api/v1/agents/{id}` | BaseEntity 软删 → MyBatis-Plus `@TableLogic` 自动加 `deleted=0` 过滤 → 但**关联引用未级联**：`chat_session` / `agent_tool` 中仍持有已删 agent_id | 列表接口 `GET /api/v1/agents` 不再返回该记录；但 `GET /api/v1/chat/sessions?agentId={id}` 仍可能返回历史 session（**潜在数据完整性缺陷**） |
| 8 | **跨模块写一致性** | `POST /api/v1/chat/sessions/{sessionId}/messages` | 写 `chat_message` (DB) + 异步更新 `Redis session:{sessionId}` 上下文 (TTL 2h) + 失败时无回滚 | DB 成功 + Redis 命中；Redis 故障时 DB 不应回滚但下次读会重新加载（**Redis Result 反序列化问题 D3 也会在这里触发**） |

## 为什么是这 8 条

| 维度 | 对应链路 | 出问题的高频原因 |
|---|---|---|
| 跨模块调用多 | 2、3、4、6 | 任一中间件 schema/字段变更都会断 |
| 强依赖外部世界 | 1、3、4 | 网络/API 兼容 / 出网白名单 |
| 共享可变状态 | 5、8 | 熔断计数器、Redis 缓存是单点故障 |
| 缺乏级联保护 | 7 | 逻辑删除 + 应用层外键 = 一致性裸奔 |
| 异步 + 落库并发 | 2、4、8 | 容易出现"接口返回了但数据没落库" |

## 不在 8 条里但值得记一笔

- **GET /api/v1/health**：重要的兜底检查，但不属于业务路径
- **CRUD 单表（GET/POST/PUT/DELETE 单条）**：MyBatis-Plus 生成代码 + 单表 = 风险低，不值得占用指标
- **MCP debug/test**：是开发辅助接口，生产不会触发

## 建议用法

每条链路配一条 `curl` 或一段脚本塞进 CI：

| 链路 | 建议自动化方式 |
|---|---|
| 1、5 | 集成测试：mock LLM 端点，断言 `provider_health` 字段 |
| 2、6、8 | 端到端测试：真实 LLM（小模型）+ 真实 Redis，断言 SSE 事件流与 DB 行 |
| 3 | mock MCP Server（HTTP/JSON-RPC），断言 `agent_tool` 行与 LLM 第二轮请求体 |
| 4 | 上传固定文本 → 等 `status=DONE` → 对话接口断言 prompt 注入痕迹 |
| 7 | 删 agent → 立即查 `chat_session`，断言"按 agentId 过滤仍返回旧记录"（记录 bug，待修） |

链路 7 一旦命中即证明 `docs/data-model.md` 标记的"外键在应用层维护"风险已显化，是项目最该被尽早告警的一条。