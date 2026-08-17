# Tify 核心数据模型

> 来源：扫描 12 个 `*Entity.java` + `tify-app/src/main/resources/db/schema*.sql`。
> 全部表共 15 张，但 `schema.sql`（生产 MySQL）只 DDL 了 8 张；剩余 7 张目前仅在 H2 schema + 实体类中预留，**生产部署前需要补全 DDL**。

## 通用约定

- 主键：所有表 `id BIGINT NOT NULL AUTO_INCREMENT`
- 时间：`created_at` / `updated_at`（DATETIME，由 MyBatis-Plus 自动填充）
- 逻辑删除：`deleted TINYINT(1)`，0=正常 1=删除（`BaseEntity` 字段）
- 外键：**应用层维护**，不建数据库级 FK
- 字符集：`utf8mb4` / `utf8mb4_unicode_ci`
- 所有表继承 `BaseEntity`（含 `id`, `createdAt`, `updatedAt`, `deleted`），下文仅列出业务字段

---

## 1. provider · 模型提供商
源：`tify-provider` `Provider.java` · `schema.sql:11`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键，自增 |
| `name` | VARCHAR(100) | 展示名称，**唯一**（UK: `idx_provider_name`） |
| `type` | VARCHAR(30) | **枚举**：`OPENAI` / `ANTHROPIC` / `OLLAMA` / `AZURE_OPENAI` / `OPENAI_COMPATIBLE` |
| `base_url` | VARCHAR(500) | API 基础地址 |
| `auth_config` | JSON | 鉴权配置（结构随 type 变化，加密存储） |
| `description` | VARCHAR(500) | 备注 |
| `enabled` | TINYINT(1) | 1 启用 / 0 禁用 |

---

## 2. model_config · 模型配置
源：`tify-provider` `ModelConfig.java` · `schema.sql:29`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `provider_id` 🔗 | BIGINT | FK → `provider.id`（IDX: `idx_model_config_provider_id`） |
| `name` | VARCHAR(100) | 展示名称，如 "GPT-4o" |
| `model_id` | VARCHAR(100) | 实际传给 API 的模型标识 / Azure deployment name |
| `context_size` | INT | 上下文窗口（token），默认 4096 |
| `extra_params` | JSON | 模型级扩展参数，如 `{"maxTokens":4096}` |
| `enabled` | TINYINT(1) | 是否启用 |

关系：provider **1:N** model_config

> 唯一索引：`UNIQUE KEY uk_model_config_provider_model (provider_id, model_id)`
> 防止 `ProviderConnectionTestService.refreshModels` 在并发或重试时产生 `(provider_id, model_id)` 重复行。
> Agent / Chat / Workflow 全部按 `model_config.id` 单条访问，本 UK 不影响现有数据。

---

## 3. provider_health · 供应商健康状态
源：`tify-provider` `ProviderHealth.java` · `schema.sql:47`

> 高频写入，**不继承 BaseEntity**，**无逻辑删除**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `provider_id` 🔗 | BIGINT | FK → `provider.id`（UK: `idx_provider_health_provider_id`） |
| `status` | VARCHAR(20) | **枚举**：`UP` / `DOWN` / `DEGRADED` / `UNKNOWN`，默认 `UNKNOWN` |
| `last_check_at` | DATETIME | 最后一次探测时间 |
| `last_success_at` | DATETIME | 最后一次成功时间 |
| `fail_count` | INT | 连续失败次数（配合熔断器阈值） |
| `latency_ms` | INT | 最近一次响应延迟 |
| `error_message` | VARCHAR(500) | 最近失败原因 |
| `updated_at` | DATETIME | 由 MyBatis-Plus 自动更新 |

关系：provider **1:1** provider_health

---

## 4. mcp_server · MCP 工具服务
源：`tify-mcp` `McpServer.java` · `schema.sql:64`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `name` | VARCHAR(100) | MCP Server 名称，**唯一**（UK: `idx_mcp_server_name`） |
| `endpoint` | VARCHAR(500) | 服务端点地址 |
| `description` | VARCHAR(500) | 描述 |
| `enabled` | TINYINT(1) | 是否启用 |

> **缓存策略**：MCP Server 数量少，工具 schema 不落库，每次 `listToolsDetail` 实时从 Server 拉取。
> 所以本表只存"Server 接入信息"，不存"工具详情"。

---

## 4.1 refund_application · 退款申请（tify-mcp-refund 自管）
源：`tify-mcp-refund` `RefundApplication.java` · `tify-mcp-refund/src/main/resources/schema-mysql.sql`

> 本表属于**独立子项目** `tify-mcp-refund`（端口 9001），使用主 MySQL 的独立 schema `tify_refund`。
> tify-mcp-refund 不依赖 tify-common，所以审计字段（created_at / updated_at / deleted）由本地 MyBatis-Plus MetaObjectHandler 自管。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `order_id` | VARCHAR(64) | 关联订单号（IDX: `idx_refund_order_id`） |
| `user_id` | VARCHAR(64) | 用户 ID（IDX: `idx_refund_user_id`） |
| `amount` | DECIMAL(10,2) | 退款金额 |
| `reason` | VARCHAR(500) | 退款原因 |
| `status` | VARCHAR(20) | 状态枚举：`PENDING` / `APPROVED` / `PROCESSING` / `COMPLETED` / `REJECTED` / `CANCELLED`（IDX: `idx_refund_status`） |
| `reject_reason` | VARCHAR(500) NULL | 拒绝原因 |
| `created_at` | DATETIME | 创建时间 |
| `updated_at` | DATETIME | 更新时间 |
| `deleted` | TINYINT(1) | 逻辑删除 |

复合索引：`idx_refund_order_created (order_id, created_at DESC)` — 给"按订单号查最新申请"用。

---

## 5. agent · Agent 配置
源：`tify-agent` `Agent.java` · `schema.sql:80`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `name` | VARCHAR(100) | Agent 名称，**唯一**（UK: `idx_agent_name`） |
| `description` | VARCHAR(500) | 描述 |
| `system_prompt` | TEXT | System Prompt |
| `model_config_id` 🔗 | BIGINT | FK → `model_config.id`（IDX: `idx_agent_model_config_id`） |
| `temperature` | DECIMAL(3,2) | 温度 0.00~1.00，默认 0.70 |
| `max_tokens` | INT | 最大输出 token，默认 2048 |
| `max_context_turns` | INT | 保留最近几轮上下文，默认 10 |
| `knowledge_base_id` 🔗 | BIGINT (NULL) | FK → `knowledge_base.id`，NULL 表示不启用 RAG |
| `workflow_id` 🔗 | BIGINT (NULL) | FK → `workflow.id`，NULL 表示不启用工作流 |
| `enabled` | TINYINT(1) | 是否启用 |

---

## 6. agent_tool · Agent ↔ MCP Server 关联（M:N）
源：`tify-agent` `AgentTool.java` · `schema.sql:101`

> **不继承 BaseEntity**，无 `deleted`，无 `id` 以外的逻辑删除（直接物理删除）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `agent_id` 🔗 | BIGINT | FK → `agent.id` |
| `mcp_server_id` 🔗 | BIGINT | FK → `mcp_server.id` |
| `created_at` / `updated_at` | DATETIME | MyBatis-Plus 自动填充 |

UK: `(agent_id, mcp_server_id)` 唯一索引防重复绑定

---

## 7. chat_session · 对话会话
源：`tify-chat` `ChatSession.java` · `schema.sql:115`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `agent_id` 🔗 | BIGINT | FK → `agent.id`（IDX: `idx_chat_session_agent_id`） |
| `title` | VARCHAR(200) | 会话标题 |
| `status` | VARCHAR(20) | **枚举**：`ACTIVE` / `ARCHIVED`，默认 `ACTIVE` |

关系：agent **1:N** chat_session

---

## 8. chat_message · 对话消息
源：`tify-chat` `ChatMessage.java` · `schema.sql:130`

> **增长最快的表**，必须走 `idx_chat_message_session_id` + 游标分页，禁止 `SELECT COUNT(*)`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `session_id` 🔗 | BIGINT | FK → `chat_session.id`（IDX: `idx_chat_message_session_id`） |
| `role` | VARCHAR(20) | **枚举**：`user` / `assistant` / `system` |
| `content` | LONGTEXT | 消息内容 |
| `tokens` | INT | 消耗 token 数 |
| `finish_reason` | VARCHAR(50) | LLM finish_reason |
| `latency_ms` | INT | 首 token 到流结束耗时 |

关系：chat_session **1:N** chat_message

---

## 9. knowledge_base · 知识库
源：`tify-knowledge` `KnowledgeBase.java` · ⚠️ **仅 H2 schema，未进生产 schema.sql**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `name` | VARCHAR(100) | 知识库名称 |
| `description` | VARCHAR(500) | 描述 |
| `enabled` | TINYINT(1) | 是否启用 |

---

## 10. document · 文档
源：`tify-knowledge` `Document.java` · ⚠️ **仅 H2 schema**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `knowledge_base_id` 🔗 | BIGINT | FK → `knowledge_base.id` |
| `name` | VARCHAR | 文件名 |
| `file_type` | VARCHAR | 文件类型（如 pdf / docx） |
| `file_size` | BIGINT | 字节数 |
| `status` | VARCHAR(20) | **枚举**：`PENDING` / `PROCESSING` / `DONE` / `FAILED` |
| `error_message` | VARCHAR | 处理失败原因 |
| `chunk_count` | INT | 切分块数 |

关系：knowledge_base **1:N** document

> 文档分块（chunk）按 CLAUDE.md 设计应独立 `document_chunk` 表存向量；当前 entity 类中**未实现**，向量数据设想由 pgvector 承担。

---

## 11. workflow · 工作流定义
源：`tify-workflow` `Workflow.java` · ⚠️ **仅 H2 schema**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `name` | VARCHAR | 工作流名称 |
| `description` | VARCHAR | 描述 |
| `status` | VARCHAR(20) | **枚举**：`DRAFT` / `PUBLISHED` / `DISABLED` |

---

## 12. workflow_node · 工作流节点
源：`tify-workflow` `WorkflowNode.java` · ⚠️ **仅 H2 schema**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `workflow_id` 🔗 | BIGINT | FK → `workflow.id` |
| `node_key` | VARCHAR | 工作流内唯一标识（如 `classify`、`router`） |
| `type` | VARCHAR(20) | **枚举**：`LLM` / `CONDITION` / `API_CALL` / `KNOWLEDGE` / `START` / `END` |
| `name` | VARCHAR | 节点展示名 |
| `config` | TEXT (JSON) | 节点配置 JSON |

关系：workflow **1:N** workflow_node

---

## 13. workflow_edge · 工作流连线
源：`tify-workflow` `WorkflowEdge.java` · ⚠️ **仅 H2 schema**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `workflow_id` 🔗 | BIGINT | FK → `workflow.id` |
| `source_node_key` | VARCHAR | 起始节点 key |
| `target_node_key` | VARCHAR | 目标节点 key |
| `condition_expr` | VARCHAR (NULL) | 条件表达式，NULL 表示无条件 |

关系：workflow **1:N** workflow_edge

---

## 14. workflow_run · 工作流执行实例
源：`tify-workflow` `WorkflowRun.java` · ⚠️ **仅 H2 schema**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `workflow_id` 🔗 | BIGINT | FK → `workflow.id` |
| `status` | VARCHAR(20) | **枚举**：`RUNNING` / `SUCCESS` / `FAILED` |
| `input` | TEXT (JSON) | 入参快照 |
| `output` | TEXT (JSON) | 出参快照 |
| `error` | TEXT | 失败原因 |
| `elapsed_ms` | INT | 总耗时 |
| `finished_at` | DATETIME | 结束时间 |

关系：workflow **1:N** workflow_run

---

## 15. workflow_node_run · 节点执行实例
源：`tify-workflow` `WorkflowNodeRun.java` · ⚠️ **仅 H2 schema**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` 🔑 | BIGINT | 主键 |
| `workflow_run_id` 🔗 | BIGINT | FK → `workflow_run.id` |
| `node_key` | VARCHAR | 节点 key |
| `node_type` | VARCHAR(20) | 节点类型（同 workflow_node.type） |
| `status` | VARCHAR(20) | **枚举**：`RUNNING` / `SUCCESS` / `FAILED` |
| `outputs` | TEXT (JSON) | 节点输出快照 `ctx.snapshot()` |
| `error` | TEXT | 失败原因 |
| `elapsed_ms` | INT | 节点耗时 |
| `finished_at` | DATETIME | 结束时间 |

关系：workflow_run **1:N** workflow_node_run

---

## 枚举汇总

| 枚举字段 | 取值 |
|---|---|
| `provider.type` | OPENAI / ANTHROPIC / OLLAMA / AZURE_OPENAI / OPENAI_COMPATIBLE |
| `provider_health.status` | UP / DOWN / DEGRADED / UNKNOWN |
| `chat_session.status` | ACTIVE / ARCHIVED |
| `chat_message.role` | user / assistant / system |
| `document.status` | PENDING / PROCESSING / DONE / FAILED |
| `workflow.status` | DRAFT / PUBLISHED / DISABLED |
| `workflow_node.type` | LLM / CONDITION / API_CALL / KNOWLEDGE / START / END |
| `workflow_run.status` | RUNNING / SUCCESS / FAILED |
| `workflow_node_run.status` | RUNNING / SUCCESS / FAILED |

---

## 模型关系速览

```
provider (1) ──< (N) model_config (1) ──< (N) agent
     │                                       │
     │                                       ├── (M:N) mcp_server  via agent_tool
     │                                       ├── (N:1) knowledge_base (NULL OK)
     │                                       ├── (N:1) workflow (NULL OK)
     │                                       │
     │                                       └──< (N) chat_session >── (1) chat_message
     │
     └── (1:1) provider_health

knowledge_base (1) ──< (N) document
workflow       (1) ──< (N) workflow_node
              └── (1) ──< (N) workflow_edge
              └── (1) ──< (N) workflow_run >── (1) workflow_node_run
```

---

## ⚠️ 生产 DDL 缺口

`schema.sql`（生产 MySQL DDL）只声明 8 张表（`provider` 到 `chat_message`），未涵盖：

- `knowledge_base` / `document`（RAG 入口表）
- `workflow` / `workflow_node` / `workflow_edge` / `workflow_run` / `workflow_node_run`

剩余 7 张表的 DDL 当前仅存在于 `schema-h2.sql`（H2 方言）。**生产部署前必须把 H2 DDL 翻译为 MySQL 方言并合并到 `schema.sql`**，否则 RAG 与工作流相关接口无法落库。
