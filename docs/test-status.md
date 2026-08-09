# Hify 测试现状盘点

> 时间：2026-08-08
> 扫描范围：`hify-*/src/test/**`、`tests/**`、`e2e/**`、`integration-test/**`、`cypress/**`、`playwright/**`
> 排除：`node_modules/`、`target/`

## 1. 测试总量

| 类型 | 文件数 | 备注 |
|---|---|---|
| 单元测试（`*Test.java` / `*Tests.java`） | **0** | 所有模块 `src/test` 目录不存在 |
| 集成测试（`*IT.java` / `tests/`） | **0** | — |
| E2E（`e2e/` / cypress / playwright） | **0** | — |
| 前端单元测试（`.spec.ts` / `.test.ts`） | **0** | `hify-web/package.json` 没配 Vitest / Jest |
| Maven surefire / failsafe 插件 | **未配置** | `mvn test` 实际什么都不做 |

**结论：本项目没有任何自动化测试。**

唯一被"测"过的是这次会话里我用 curl 跑的 5 个端点（详见 `docs/smoke-test-result.md`）—— 那不是 CI 测试，是临时手动验证。

## 2. Controller 测试覆盖

| Controller | 是否有测试 |
|---|---|
| `HealthController`（hify-app） | 没有 |
| `ProviderController`（hify-provider） | 没有 |
| `McpController`（hify-mcp） | 没有 |
| `AgentController`（hify-agent） | 没有 |
| `WorkflowController`（hify-workflow） | 没有 |
| `KnowledgeController`（hify-knowledge） | 没有 |
| `ChatController`（hify-chat） | 没有 |

**7 个 Controller × 0 覆盖率**。

## 3. 核心 Service 测试覆盖

| Service | 是否有测试 | 备注 |
|---|---|---|
| `ProviderServiceImpl` | 没有 | 含缓存 + model_config 复杂 join，最容易出 bug |
| `ProviderConnectionTestService` | 没有 | 上次会话刚修过 cache eviction 问题，应该有回归测试 |
| `ProviderHealthCheckTask` | 没有 | 状态机 UP/DEGRADED/DOWN 切换逻辑 |
| `ChatServiceImpl` | 没有 | 流式 SSE + 多轮上下文窗口，CLAUDE.md 重点 |
| `AgentServiceImpl` | 没有 | 含 modelConfigId / knowledgeBaseId / workflowId 外键校验 |
| `KnowledgeServiceImpl` | 没有 | 文档分块 + 向量检索 |
| `WorkflowService` | 没有 | 节点执行顺序 + 条件分支 |
| `McpService` | 没有 | JSON-RPC 客户端 |

**8 个核心 Service × 0 覆盖率**。

## 4. 关键路径覆盖情况（对照 `docs/critical-paths.md`）

| # | 链路 | 起点接口 | 当前测试 | 评级 |
|---|---|---|---|---|
| 1 | **Provider连通性闭环** | `POST /api/v1/providers/{id}/test-connection` | 无 | ❌ 没有 |
| 2 | **SSE 流式对话全链路** | `POST /api/v1/chat/sessions/{sessionId}/messages/stream` | 无 | ❌ 没有 |
| 3 | **MCP 工具调用链** | `POST /api/v1/agents/{id}/tools` | 无 | ❌ 没有 |
| 4 | **RAG 检索 → 引用回答** | `POST /api/v1/knowledge-bases/{kbId}/documents` | 无 | ❌ 没有（且表 DDL 刚补，模块功能未完全验证） |
| 5 | **熔断 + 重试回归** | `POST /api/v1/providers` + bad key | 无 | ❌ 没有 |
| 6 | **多轮上下文窗口** | `POST /api/v1/chat/sessions/{sessionId}/messages` | 无 | ❌ 没有 |
| 7 | **逻辑删除穿透** | `DELETE /api/v1/agents/{id}` | 无 | ❌ 没有（潜在数据完整性 bug，无人守门） |
| 8 | **跨模块写一致性** | `POST /api/v1/chat/sessions/{sessionId}/messages` | 无 | ❌ 没有 |

**8 条关键路径 × 0 覆盖**。

## 5. 项目级影响

- 本次会话暴露的 **D1 ~ D4 缺陷 + authConfig 命名 + 缓存反序列化 + 缓存不刷新 + list/detail 不一致** 全靠手动 curl 抓出来，没有任何回归保护
- 一旦后续重构 ProviderService / ChatService 改缓存策略，**这些 bug 都可能复现而无人发现**
- CI 上 `mvn test` 通过不能代表任何东西——因为根本没测试可跑

## 6. 建议补测的优先顺序

按"bug 已经出现过 + 改造时容易再出问题"打分：

| 优先级 | 测试 | 目标 |
|---|---|---|
| **P0** | `ProviderConnectionTestService.test()` 集成测试 | 防 D3 (Result反序列化) + cache eviction + model_config 同步策略回归 |
| **P0** | `ProviderService.list()` vs `getDetail()` 数据一致性 | 防 list/detail 不一致 bug 复发 |
| **P1** | `ChatService.sendMessage()` mock LLM 单元测试 | 防 max_context_turns 截断逻辑出 bug |
| **P1** | `AgentService.create()` 外键校验 | 防 model_config_id / knowledge_base_id 缺失时静默通过 |
| **P2** | `ProviderHealthCheckTask` 状态机迁移 | 防 failCount 累计 / DOWN 阈值逻辑出错 |
| **P2** | `McpService.debugTool()` mock MCP Server | 防 JSON-RPC 序列化错 |
| **P3** | E2E（Playwright 或 curl 脚本）：链路 2 / 4 / 8 | 真实 LLM 跑通 SSE / RAG / 跨模块一致性 |

## 7. 不补测试的风险

按 `docs/critical-paths.md` 链路 7（**逻辑删除穿透**）举例：这是已经被标红"一旦命中即证明 CLAUDE.md 风险已显化"的一条，但没有测试守门，下次有人改 AgentService.delete 时极易引入新缺陷，且不会被任何 CI 步骤拦下。