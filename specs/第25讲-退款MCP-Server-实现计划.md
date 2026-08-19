# 第 25 讲 · 退款 MCP Server（tify-mcp-refund）实现计划

## 概述

在 `tify-mcp` 已有的 **MCP 管理面**之上，落地一个**真实业务**的 MCP Server —— `tify-mcp-refund`。
让智能客服（Agent）能通过 tify-mcp 调到退款服务，处理"申请退款 / 查进度 / 撤销 / 异常路径"等高频诉求。

**核心交付**:Server 自建 + tify-mcp 联调一体的完整闭环，25 讲 4 个验收场景全部跑通。

---

## 1. 模块定位

| 维度 | 决策 |
|---|---|
| 模块名 | `tify-mcp-refund`（与 `tify-mcp` 平级，独立 Spring Boot 应用） |
| Maven 坐标 | `com.phadcalc.llm.tify:tify-mcp-refund:0.0.1-SNAPSHOT` |
| 端口 | **9001**（独立部署） |
| 数据库 | MySQL 独立 schema `tify_refund`；dev profile 用 H2 内存库 |
| 与 tify-mcp 关系 | **反向独立** — Server 端不感知 tify-mcp，仅暴露标准 MCP 协议 `/sse` + `/messages` |

### 与 tify-common 的依赖关系

`tify-mcp-refund` **故意不依赖** `tify-common`：

- 避免 Server 端反向依赖主工程（保持单向依赖链）
- 审计字段（created_at / updated_at / deleted）由本地 MyBatis-Plus MetaObjectHandler 自管
- 错误码使用本地 `RefundErrorCode` 枚举（5001-5004 段），不与主工程 `ErrorCode` 共享

---

## 2. 工具集（4 个 MCP Tools）

每个工具的 description 严格按"什么场景下调用"措辞，便于 LLM 准确选型。

| 工具名 | 入参 | 出参 | 业务规则 |
|---|---|---|---|
| `check_refund_eligibility` | `orderId: string` | `eligible, reason, deadline, amount` | 7 天内 + 已签收 → 可退；超期/未签收 → 不可退 |
| `submit_refund` | `orderId, reason, userId, amount` | `refundId, status, statusLabel, estimatedDays` | 同订单已有 PENDING/APPROVED/PROCESSING 申请 → 拒绝并返回原 refundId |
| `get_refund_status` | `orderId?` 或 `refundId?`（二选一） | `refundId, orderId, status, statusLabel, submittedAt, rejectReason, estimatedArrival` | 用户记得订单号不记得退款单号；Server 做转换 |
| `cancel_refund` | `refundId: number` | `success, message` | 仅 PENDING 可撤销；其他状态返回友好提示 |

**返回值设计要点**：
- `status` 英文枚举给程序判断，`statusLabel` 中文给 LLM 直接说给用户
- 调用失败返回 `{"error": "原因"}` + `isError=true`，**不抛异常**中断对话

---

## 3. 数据模型

`refund_application` 表（DDL 落在 `tify-mcp-refund/src/main/resources/schema-mysql.sql`）：

```
id              BIGINT       PK AUTO_INCREMENT
order_id        VARCHAR(64)  IDX
user_id         VARCHAR(64)  IDX
amount          DECIMAL(10,2)
reason          VARCHAR(500)
status          VARCHAR(20)  IDX  PENDING/APPROVED/PROCESSING/COMPLETED/REJECTED/CANCELLED
reject_reason   VARCHAR(500) NULL
created_at      DATETIME
updated_at      DATETIME
deleted         TINYINT(1)   逻辑删除

INDEX idx_refund_order_created (order_id, created_at DESC)
```

**Mock 订单数据**（一期简化，硬编码在 `OrderMockService`）：

| 订单号 | 状态 | 退款资格 |
|---|---|---|
| ORD-001 | 已签收 5 天,¥299 | ✅ 可退（7 天内） |
| ORD-002 | 已签收 10 天,¥199 | ❌ 超期 |
| ORD-003 | 未签收,¥599 | ❌ 未签收 |

---

## 4. 启动方式

### 启动 refund server（dev profile，H2 内存库）

```bash
cd tify-mcp-refund
mvn spring-boot:run -Dspring-boot.run.profiles=dev
# 验证：curl http://localhost:9001/health
```

### 启动 tify 主工程（mock profile，含 tify-mcp）

```bash
cd tify-app
mvn spring-boot:run -Dspring-boot.run.profiles=mock
# 验证：curl http://localhost:8080/api/v1/health
```

### 注册 + 连通测试

```bash
# 注册
curl -X POST http://localhost:8080/api/v1/mcp-servers \
  -H "Content-Type: application/json" \
  -d '{"name":"refund-server","endpoint":"http://localhost:9001/sse","description":"refund mcp server"}'

# 连通测试（自动拉取工具列表）
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/test
# → success=true, tools=[check_refund_eligibility, submit_refund, get_refund_status, cancel_refund]
```

---

## 5. 验收场景（25 讲 4 个）

### 场景 1：发起退款

```bash
# 1a. 查退款资格
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/debug \
  -H "Content-Type: application/json" \
  -d '{"toolName":"check_refund_eligibility","arguments":{"orderId":"ORD-001"}}'
# → {"eligible":true,"reason":"符合退款条件","deadline":"2026-08-19","amount":299.00}

# 1b. 提交退款
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/debug \
  -H "Content-Type: application/json" \
  -d '{"toolName":"submit_refund","arguments":{"orderId":"ORD-001","userId":"u001","amount":299.00,"reason":"goods damaged"}}'
# → {"refundId":1,"orderId":"ORD-001","status":"PENDING","statusLabel":"审核中","estimatedDays":3}
```

### 场景 2：查进度

```bash
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/debug \
  -H "Content-Type: application/json" \
  -d '{"toolName":"get_refund_status","arguments":{"orderId":"ORD-001"}}'
# → {"refundId":1,"status":"PENDING","statusLabel":"审核中","estimatedArrival":"2026-08-20",...}
```

### 场景 3：撤销退款

```bash
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/debug \
  -H "Content-Type: application/json" \
  -d '{"toolName":"cancel_refund","arguments":{"refundId":1}}'
# → {"success":true,"message":"已为您撤销退款申请"}

# 复核状态
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/debug \
  -H "Content-Type: application/json" \
  -d '{"toolName":"get_refund_status","arguments":{"orderId":"ORD-001"}}'
# → status=CANCELLED, statusLabel="已撤销"
```

### 场景 4：异常路径

```bash
# 4a. 重复退款（先 submit 再 submit 第二次）
# 第二次返回 Server 端友好中文错误: "该订单已有进行中的退款申请，编号：2"

# 4b. 撤销不存在的 refundId
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/debug \
  -H "Content-Type: application/json" \
  -d '{"toolName":"cancel_refund","arguments":{"refundId":99999}}'
# → "退款申请不存在：编号 99999"

# 4c. 超期订单（ORD-002 已签收 10 天）
curl -X POST http://localhost:8080/api/v1/mcp-servers/1/debug \
  -H "Content-Type: application/json" \
  -d '{"toolName":"check_refund_eligibility","arguments":{"orderId":"ORD-002"}}'
# → {"eligible":false,"reason":"已超过 7 天退款期，无法退款",...}
```

---

## 6. 联调中遇到的坑（经验沉淀）

### 6.1 Jackson 3 冲突（最致命）

**问题**：
- `mcp:1.1.1` 默认引入 `mcp-json-jackson3:1.1.1`，后者传递依赖 `tools.jackson.core:jackson-databind:3.0.3`
- Spring Boot 3.4.x 自带 Jackson 2.18.x
- 调用 MCP Server 时触发 `NoSuchMethodError: 'com.fasterxml.jackson.annotation.OptBoolean com.fasterxml.jackson.annotation.JsonProperty.isRequired()'`

**修复**（`tify-mcp/pom.xml`）：
```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>1.1.1</version>
    <exclusions>
        <exclusion>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-json-jackson3</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-json-jackson2</artifactId>
    <version>1.1.1</version>
</dependency>
```

**教训**：`mvn -am package` 只构建不上传，必须先 `mvn install -DskipTests` 把新 jar 推到本地 maven 仓库，下游模块才能拿到新版本。

### 6.2 MCP SDK Server 端坐标选择

- 客户端用 `io.modelcontextprotocol.sdk:mcp:1.1.1`
- Server 端用 `io.modelcontextprotocol.sdk:mcp-spring-webmvc:0.18.3`（maven-metadata 显示 1.1.1 系列无此 artifact）
- **MCP Java SDK 当前仍处于快速迭代期**，0.x 和 1.x 系列并存，需要按需选用

### 6.3 `HttpClientSseClientTransport` 端点 URL

- `.builder(endpoint)` 中 `endpoint` 必须是**完整 SSE URL（含 `/sse` 后缀）**
- tify-mcp 的 `mcp_server.endpoint` 字段直接存 `http://host:port/sse` 即可

### 6.4 `mvn -am` vs `mvn install`

- `mvn -pl A -am package`：构建 A 及其依赖，但**不**把 jar 装到 `~/.m2/repository`
- 下游模块依赖时看到的是**本地仓库里的旧 jar**
- **必须**：`mvn -pl <上游模块> install -DskipTests`，再构建下游

### 6.5 Git Bash + curl 中文编码

- `curl -d '{"reason":"商品破损"}'` 在 Git Bash 下，UTF-8 字节被 cmd 解析为 GBK 中间字节
- Jackson 报 `Invalid UTF-8 middle byte 0xcc`
- **解决方案**：测试时用 ASCII 字符串，或写 JSON 文件 `-d @file.json`

---

## 7. 实施任务清单（13 个 Task · 已全部完成）

| Task | 内容 | 状态 |
|---|---|---|
| 1 | 建 Maven 子模块骨架 | ✅ |
| 2 | 启动类和最小 Web 配置 | ✅ |
| 3 | 实体 + Mapper + DDL | ✅ |
| 4 | RefundService 4 个方法 + OrderMockService | ✅ |
| 5 | McpServerConfig 注册 Server 端点 | ✅ |
| 6 | 注册 4 个工具到 McpSyncServer | ✅ |
| 7 | Server 端错误处理 + 日志 | ✅ |
| 8 | 审视 tify-mcp 现有能力 | ✅ |
| 9 | tify-mcp debug 接口端到端测试 | ✅ |
| 10 | 场景 1-4 完整复现 25 讲验收 | ✅ |
| 11 | tify-mcp 数据落库验证 | ✅ |
| 12 | 文档 | ✅ |
| 13 | 联调回归与边界用例 | ✅ |

---

## 8. 已知限制与下一步

- `tify-mcp` 的 `debugTool` 返回的 `McpDebugResult.success` 字段**未读取** Server 端 `CallToolResult.isError`，只看是否抛异常。Server 返回的中文错误信息体现在 `result` 字段，对 LLM 信息完整。如需区分，需在 `McpServiceImpl.callTool` 中读 `result.isError()` 并映射到 `McpDebugResult.success=false`。
- `mcp_tool` 表当前不存在（符合 CLAUDE.md "MCP Server 数量少，不缓存" 策略）。如未来工具数量爆炸，可考虑加缓存层。
- 真实生产场景下 refund server 应调订单服务，本期按 25 讲选择 hardcode mock 演示。