# Hify REST 接口清单

> 来源：扫描所有 `*Controller.java`（不含 `package-info.java`），共 **8 个 controller**、**39 个接口**。
> 基础路径：所有业务接口位于 `/api/v1/` 前缀下。

## 统一响应结构

```java
Result<T> { int code; String message; T data; }   // 成功 code=200, message="success"
PageResult<T> { List<T> list; long total; int page; int pageSize; }
```

`HealthController` 是唯一不走 `Result` 包装的接口，直接返回 `ResponseEntity<Map>`。

---

## 1. hify-app · HealthController
`/api/v1/health`

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| GET | `/api/v1/health` | 聚合健康检查：MySQL / Redis / pgvector 探活 | 无 | `ResponseEntity<Map>` `{ status: "UP"|"DOWN", components: { mysql, redis, pgvector } }`；任一组件 DOWN → HTTP 503 |

---

## 2. hify-provider · ProviderController
`/api/v1/providers`

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| POST | `/api/v1/providers` | 创建模型提供商配置 | `ProviderCreateRequest` (name, type, baseUrl, apiKey, ...) | `Result<Provider>` |
| GET | `/api/v1/providers` | 分页查询提供商列表 | `ProviderQueryRequest` (page, pageSize, name, type, ...) | `Result<PageResult<ProviderDetailResponse>>` |
| GET | `/api/v1/providers/{id}` | 详情 | `id: Long` | `Result<ProviderDetailResponse>` |
| PUT | `/api/v1/providers/{id}` | 更新配置 | `id` + `ProviderUpdateRequest` | `Result<Provider>` |
| DELETE | `/api/v1/providers/{id}` | 删除（逻辑删除） | `id` | `Result<Void>` |
| POST | `/api/v1/providers/{id}/test-connection` | 连通性测试（10s 超时） | `id` | `Result<ConnectionTestResult>` |

---

## 3. hify-mcp · McpController
`/api/v1/mcp-servers`

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| POST | `/api/v1/mcp-servers` | 注册 MCP Server | `McpServerCreateRequest` (name, transportType, endpoint, ...) | `Result<McpServerVO>` |
| GET | `/api/v1/mcp-servers` | 分页查询 | `McpQueryRequest` (page, pageSize, name) | `Result<PageResult<McpServerVO>>` |
| GET | `/api/v1/mcp-servers/{id}` | 详情 | `id` | `Result<McpServerVO>` |
| PUT | `/api/v1/mcp-servers/{id}` | 更新 | `id` + `McpServerUpdateRequest` | `Result<McpServerVO>` |
| DELETE | `/api/v1/mcp-servers/{id}` | 删除 | `id` | `Result<Void>` |
| POST | `/api/v1/mcp-servers/{id}/test` | 测试连接 | `id` | `Result<McpTestResult>` |
| GET | `/api/v1/mcp-servers/{id}/tools` | 列出该 Server 暴露的工具 | `id` | `Result<List<McpToolDetail>>` |
| POST | `/api/v1/mcp-servers/{id}/debug` | 调试调用指定工具 | `id` + `McpDebugRequest` (toolName, arguments) | `Result<McpDebugResult>` |

---

## 4. hify-agent · AgentController
`/api/v1/agents`

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| POST | `/api/v1/agents` | 创建 Agent | `AgentCreateRequest` (name, systemPrompt, modelConfigId, ...) | `Result<AgentDetailResponse>` |
| GET | `/api/v1/agents` | 分页查询 | `AgentQueryRequest` (page, pageSize, name, ...) | `Result<PageResult<AgentListItem>>` |
| GET | `/api/v1/agents/{id}` | 详情 | `id` | `Result<AgentDetailResponse>` |
| PUT | `/api/v1/agents/{id}` | 更新 | `id` + `AgentUpdateRequest` | `Result<AgentDetailResponse>` |
| PUT | `/api/v1/agents/{id}/tools` | 绑定/解绑 MCP 工具 | `id` + `AgentToolBindRequest` (toolIds: List<Long>) | `Result<Void>` |
| DELETE | `/api/v1/agents/{id}` | 删除 | `id` | `Result<Void>` |

---

## 5. hify-workflow · WorkflowController
`/api/v1/workflows`

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| POST | `/api/v1/workflows` | 创建工作流（含节点/连线） | `WorkflowCreateRequest` (name, nodes, edges, ...) | `Result<WorkflowDetailVO>` |
| GET | `/api/v1/workflows` | 分页查询 | `WorkflowQueryRequest` (page, pageSize, name) | `Result<PageResult<WorkflowListItem>>` |
| GET | `/api/v1/workflows/{id}` | 详情（含节点/连线） | `id` | `Result<WorkflowDetailVO>` |
| PUT | `/api/v1/workflows/{id}` | 更新 | `id` + `WorkflowUpdateRequest` | `Result<WorkflowDetailVO>` |
| DELETE | `/api/v1/workflows/{id}` | 删除 | `id` | `Result<Void>` |

---

## 6. hify-knowledge · KnowledgeController
`/api/v1/knowledge-bases` 和 `/api/v1/documents`（两个资源前缀）

### 知识库

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| POST | `/api/v1/knowledge-bases` | 创建知识库 | `KnowledgeBaseCreateRequest` (name, description, embeddingModel) | `Result<KnowledgeBaseVO>` |
| GET | `/api/v1/knowledge-bases` | 分页查询 | `page, pageSize, name?` | `Result<PageResult<KnowledgeBaseVO>>` |
| GET | `/api/v1/knowledge-bases/{id}` | 详情 | `id` | `Result<KnowledgeBaseVO>` |
| PUT | `/api/v1/knowledge-bases/{id}` | 更新 | `id` + `KnowledgeBaseUpdateRequest` | `Result<KnowledgeBaseVO>` |
| DELETE | `/api/v1/knowledge-bases/{id}` | 删除 | `id` | `Result<Void>` |

### 文档

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| POST | `/api/v1/knowledge-bases/{kbId}/documents` | 上传文档（multipart） | `kbId` + `file: MultipartFile` | `Result<DocumentVO>` |
| GET | `/api/v1/knowledge-bases/{kbId}/documents` | 列出知识库下文档 | `kbId`, `page, pageSize` | `Result<PageResult<DocumentVO>>` |
| GET | `/api/v1/documents/{id}` | 文档详情 | `id` | `Result<DocumentVO>` |
| GET | `/api/v1/documents/{id}/chunks` | 文档分块列表（含向量元数据） | `id` | `Result<List<ChunkVO>>` |
| DELETE | `/api/v1/documents/{id}` | 删除文档（含向量） | `id` | `Result<Void>` |

---

## 7. hify-chat · ChatController
`/api/v1/chat`

### 会话

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| POST | `/api/v1/chat/sessions` | 创建会话 | `SessionCreateRequest` (agentId, title?) | `Result<SessionResp>` |
| GET | `/api/v1/chat/sessions` | 分页列出会话 | `agentId?`, `page, pageSize` | `Result<PageResult<SessionResp>>` |
| DELETE | `/api/v1/chat/sessions/{sessionId}` | 删除会话（含消息） | `sessionId` | `Result<Void>` |

### 消息

| 方法 | 路径 | 说明 | 主要入参 | 返回结构 |
|---|---|---|---|---|
| GET | `/api/v1/chat/sessions/{sessionId}/messages` | 分页拉取消息历史 | `sessionId`, `page, pageSize` | `Result<PageResult<MessageResp>>` |
| POST | `/api/v1/chat/sessions/{sessionId}/messages/stream` | **SSE 流式对话**（`text/event-stream`） | `sessionId` + `SendMessageRequest` (content, stream=true) | `SseEmitter`（事件流） |
| POST | `/api/v1/chat/sessions/{sessionId}/messages` | 同步对话，返回完整回复 | `sessionId` + `SendMessageRequest` (content) | `Result<MessageResp>` |

---

## 错误码

所有错误由 `GlobalExceptionHandler` 统一处理，统一返回 `Result.fail(code, message)`，codemap 详见 `ErrorCode` 枚举：

| 区间 | 模块 |
|---|---|
| 1000–1999 | 通用（参数、未授权、内部错误） |
| 2000–2999 | Provider |
| 3000–3999 | Agent |
| 4000–4999 | Chat |
| 5000–5999 | MCP |
| 6000–6999 | Workflow |
| 7000–7999 | Knowledge |

---

## 接口统计

| 模块 | Controller | 接口数 |
|---|---|---|
| hify-app | HealthController | 1 |
| hify-provider | ProviderController | 6 |
| hify-mcp | McpController | 8 |
| hify-agent | AgentController | 6 |
| hify-workflow | WorkflowController | 5 |
| hify-knowledge | KnowledgeController | 10 |
| hify-chat | ChatController | 6 |
| **合计** | **7** | **42** |

> 注：HealthController 1 + Provider 6 + Mcp 8 + Agent 6 + Workflow 5 + Knowledge 10 + Chat 6 = **42**。
