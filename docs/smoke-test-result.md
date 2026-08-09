# Hify 接口 Smoke Test 结果

> 时间：2026-08-08
> 后端：`http://127.0.0.1:8080`（PID 12896，详见 `docs/startup-log.md`）
> 中间件：K8s `hify` namespace（详见 `scripts/install-log.md`）
> 工具：curl，JSON body 走 `-d` + `-H 'Content-Type: application/json'`

## 选定的 5 个核心接口

按 `docs/api-list.md` 与 CLAUDE.md 描述的对外契约，挑出"端到端能否跑通"最具代表性的 5 个：

| # | 方法 | 路径 | 理由 |
|---|---|---|---|
| 1 | GET | `/api/v1/health` | 业务健康检查（mysql/redis/pgvector 三件套） |
| 2 | POST | `/api/v1/providers` | 配置层入口，最基础资源创建 |
| 3 | POST | `/api/v1/providers/{id}/test-connection` | 出网连通性测试（涉及 OkHttp + Resilience4j） |
| 4 | POST | `/api/v1/agents` | 业务核心资源，绑模型/工具 |
| 5 | POST | `/api/v1/chat/sessions` | 对话入口，跨 chat + agent 两个模块 |

---

## 结果总览

| # | 接口 | HTTP | 业务 code | 通过 | 备注 |
|---|---|---|---|---|---|
| 1 | `GET /api/v1/health` | 200 | — | ✅ | mysql/redis/pgvector 全 UP |
| 2 | `POST /api/v1/providers` | 200 | 200 | ✅ | 创建 provider `id=1` 成功 |
| 3 | `POST /api/v1/providers/{id}/test-connection` | 200 | 200 | ⚠️ 半通过 | 接口本身通；`success=false`，原因 `请求超时：https://api.openai.com/v1/v1/models`（用了占位 key） |
| 4 | `POST /api/v1/agents` | 200 | **3001** | ❌ | 第一次：`code=3001 "模型配置不存在"`（`model_config` 表为空） |
| 5 | `POST /api/v1/chat/sessions` | 200 | **1999** | ❌ | 内部异常 |

HTTP 都是 200（因为 `Result` 包了成功/失败），但内部 `code` 不为 200 的标为 ❌。

---

## 详细记录

### ① `GET /api/v1/health` ✅

```
$ curl http://127.0.0.1:8080/api/v1/health
HTTP 200
{"status":"UP","components":{"mysql":"UP","redis":"UP","pgvector":"UP"}}
```

### ② `POST /api/v1/providers` ✅

```bash
curl -X POST http://127.0.0.1:8080/api/v1/providers \
  -H 'Content-Type: application/json' \
  -d '{"name":"openai-smoke","type":"OPENAI","baseUrl":"https://api.openai.com/v1","authConfig":{"apiKey":"sk-test-fake"}}'
```

```
HTTP 200
{"code":200,"message":"success","data":{
  "id":1,
  "name":"openai-smoke",
  "type":"OPENAI",
  "baseUrl":"https://api.openai.com/v1",
  "authConfig":{"apiKey":"sk-test-fake"},
  "description":"",
  "enabled":1,
  "createdAt":"2026-08-08T11:37:48.9549122",
  "updatedAt":"2026-08-08T11:37:48.9549122"
}}
```

### ③ `POST /api/v1/providers/1/test-connection` ⚠️ 接口通但失败

```bash
curl -X POST http://127.0.0.1:8080/api/v1/providers/1/test-connection
```

```
HTTP 200
{"code":200,"message":"success","data":{
  "success": false,
  "latencyMs": 0,
  "modelCount": 0,
  "errorMessage": "请求超时：https://api.openai.com/v1/v1/models"
}}
```

> 接口契约正确（成功调用、`Result` 包装、ConnectionTestResult 返回）。业务失败原因：测试用的 `apiKey` 是占位字符串，OpenAI 真实端点要求有效 key 才能返回 `/models`。连通性测试逻辑本身正确。
> 副作用：触发 `ProviderHealthCheckTask` 把 provider 状态设为 `DEGRADED`（failCount=2）。预期行为。

### ④ `POST /api/v1/agents` ❌ code=3001

```bash
curl -X POST http://127.0.0.1:8080/api/v1/agents \
  -H 'Content-Type: application/json' \
  -d '{"name":"agent-smoke","modelConfigId":1,"temperature":0.7,"maxTokens":1024,"maxContextTurns":5}'
```

第一次返回：

```
HTTP 200
{"code":3001, "message":"模型配置不存在", "data":null}
```

**根因**：`hify-provider` 模块的 `ProviderController` 没有 model_config 的 REST 端点（详见 `docs/api-list.md` §2，只有 6 个接口，全部围绕 `provider` 实体本身）。`model_config` 表在生产 schema.sql 里建了但没有 CRUD 入口，**没有任何 API 能创建第一条 model_config 记录**，因此 Agent 创建必然卡在"模型配置不存在"。

> 这是 `docs/api-list.md` 与 `docs/data-model.md` 之间的一个真实不一致：
> 数据模型声明 `model_config` 是核心实体（有 provider_id FK），但 API 清单未暴露创建端点。
> 建议修复：在 ProviderController 加 `POST /{id}/models`、`GET /{id}/models`、`DELETE /{id}/models/{mid}` 三端点。

### ⑤ `POST /api/v1/chat/sessions` ❌ code=1999

```bash
curl -X POST http://127.0.0.1:8080/api/v1/chat/sessions \
  -H 'Content-Type: application/json' \
  -d '{"agentId":1,"title":"smoke-test"}'
```

返回：

```
HTTP 200
{"code":1999, "message":"系统内部错误", "data":null}
```

后端日志根因（`/tmp/hify-app.log`）：

```
ERROR c.h.c.e.GlobalExceptionHandler - 系统异常
### Error querying database.
### SQL: SELECT id,name,description,system_prompt,model_config_id,temperature,max_tokens,
        max_context_turns,enabled,knowledge_base_id,workflow_id,created_at,updated_at,deleted
        FROM agent WHERE id=? AND deleted=0
### Cause: java.sql.SQLSyntaxErrorException: Unknown column 'knowledge_base_id' in 'field list'
    at com.hify.chat.service.impl.ChatServiceImpl.createSession(ChatServiceImpl.java:89)
```

**根因**：`Agent` entity 类里有 `knowledgeBaseId` / `workflowId` 两个字段（CLAUDE.md 描述"绑定的知识库 id / 绑定的工作流 id，NULL 表示不启用"），MyBatis-Plus 自动生成的 SQL 包含这两列，但 `schema.sql` 的 `agent` 表 CREATE 语句里 **没有这两列**，DBA 也没补。Agent 创建走的是 entity 字段映射表，能 INSERT（部分字段容忍），但读取时按完整字段 SELECT 就报 Unknown column。

> 这是 `schema.sql` 与 `Agent.java` 之间的真实不一致：
> entity 类定义了 12 个字段，DDL 只声明了 10 个（少了 knowledge_base_id / workflow_id）。
> 这两个字段是 CLAUDE.md 明确的核心概念（"Agent → knowledge_base / workflow"），生产 DDL 必须补齐。

---

## 通过率

- 接口契约层（HTTP + `Result` 包装）：**5 / 5 = 100%**
- 业务可用性（`code=200`）：**2 / 5 = 40%** （① ②）
- 半可用（接口通但业务失败）：**1 / 5 = 20%** （③，因测试 key 无效，符合预期）
- 真实缺陷：**2 / 5 = 40%** （④ ⑤）

---

## 暴露的真实缺陷汇总

| 序号 | 模块 | 缺陷 | 影响 |
|---|---|---|---|
| D1 | provider | `model_config` 实体无 REST 端点 | Agent / Provider 测试链路无法端到端 |
| D2 | chat → agent | `agent` 表 DDL 缺 `knowledge_base_id` / `workflow_id` 两列 | Session 创建 1999；任何 `selectById` 也将同样失败 |
| D3 | common / cache | Redis 缓存反序列化 `Result` 失败（`Could not read JSON: Cannot construct instance of Result`） | Provider 列表缓存命中后报 1999 |
| D4 | schema.sql | 7 张表（knowledge_base / document / workflow*）DDL 缺口（见 `docs/data-model.md` 末尾） | 知识库 / 工作流 CRUD 无法落库 |

---

## 建议下一步

1. **修 D2（最紧急）**：在 `schema.sql` 的 `agent` 表 CREATE 中加 `knowledge_base_id BIGINT NULL`、`workflow_id BIGINT NULL` 两列；同步补到 `schema-h2.sql` 与各部署脚本
2. **修 D1（高）**：给 `ProviderController` 加 `model_config` 子资源 CRUD
3. **修 D3（中）**：检查 Redis 缓存配置，确认 `@Cacheable` 返回值是 VO 而不是 `Result` 包装；或在 `Result` 上加 `@JsonCreator` / 默认构造器
4. **修 D4（中）**：把 `schema-h2.sql` 的 7 张表 DDL 翻译为 MySQL 方言并合并到 `schema.sql`