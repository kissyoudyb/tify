# Hify P0 测试补全计划

> 范围：`docs/test-gaps.md` 中 7 个 P0 缺口
> 顺序原则：改造路径上的 Characterization > 核心链路集成 > 复杂逻辑单元
> 每批 1-3 个测试，单批可独立交付 + 可独立运行

## 批次总览

| 批次 | 测试类型 | 覆盖链路 | 测试项 | 工作量 | 前置依赖 |
|---|---|---|---|---|---|
| **B1** | Characterization Test | 链路 8 | 锁住"Result 永远不进 @Cacheable"约束（#7） | 0.5h | 无 |
| **B2** | Characterization Test | 链路 1 | 锁住 OpenAiAdapter 双 key 兼容（#3） | 0.5h | 无 |
| **B3** | 集成测试 | 链路 1 | ProviderConnectionTestService 成功路径全覆盖（#1） | 2h | Testcontainers MySQL + Redis |
| **B4** | 集成测试 | 链路 1 | ProviderService.list() vs getDetail() 一致性（#4） | 1h | 复用 B3 的 Testcontainers 基建 |
| **B5** | 集成测试 | 链路 7 | Agent 软删穿透 chat_session（#6） | 1.5h | Testcontainers MySQL |
| **B6** | 单元测试 | 链路 1 / 5 | ProviderConnectionTestService 失败状态机（#2）+ 熔断 failCount 不重复计数（#5） | 1.5h | 复用 B3 的 Testcontainers 基建 |

**总计**：7 个测试项 / 6 批 / 约 7 工作小时（一人 1 个工作日）

---

## 批次详情

### B1 · Characterization Test · 0.5h · 链路 8

**目标**：用反射扫所有 `@Cacheable` 方法，断言返回类型不含 `Result<...>` 包装。

**为什么是 Characterization 而非单元**：D3 (Result 反序列化) 是已修 bug，单纯写"返回 Result 报错"的单测会随代码变化失效。要用"约束扫描器"锁住"未来任何人不能把 Result 塞进 @Cacheable"。

**测试项**：

| ID | 名称 | 断言 |
|---|---|---|
| #7 | `NoResultInCacheableScanTest` | 反射扫 `com.hify.*` 包下所有 `@Cacheable` 方法，returnType 不能是 `Result<...>` 或被 `Result` 包装 |

**改造路径位置**：项目早期就该建这种"机制级护栏"，防止 D3 再次发生

---

### B2 · Characterization Test · 0.5h · 链路 1

**目标**：锁定 OpenAiAdapter.getAuth() 同时认 `apiKey` / `api_key` 的现状。

**测试项**：

| ID | 名称 | 断言 |
|---|---|---|
| #3a | `OpenAiAdapter_getAuth_camelCase` | `auth_config={"apiKey":"sk-xxx"}` → 返回 `sk-xxx` |
| #3b | `OpenAiAdapter_getAuth_snake_case` | `auth_config={"api_key":"sk-xxx"}` → 返回 `sk-xxx` |
| #3c | `OpenAiAdapter_getAuth_missing_both` | 抛 `IllegalArgumentException`，消息含"apiKey" |

**为什么 Characterization**：本质是"前端可传两种字段名"的事实约束，CLAUDE.md 没写，将来可能被"统一规范"为单 key，破坏已上线的前端代码

---

### B3 · 集成测试 · 2h · 链路 1

**目标**：测通 ProviderConnectionTestService.test() 的完整成功路径。

**测试项**：

| ID | 名称 | 断言 |
|---|---|---|
| #1a | `ProviderConnectionTestServiceTest_success_writesHealth` | mock Adapter 返 success → provider_health 表新增一行 status=UP, failCount=0, latencyMs 正确 |
| #1b | `ProviderConnectionTestServiceTest_success_syncsModels` | mock Adapter.listModels 返 2 个 modelId → model_config 表先 disabled 旧记录，再 insert 2 行 enabled=1 |
| #1c | `ProviderConnectionTestServiceTest_success_evictsCache` | mock CacheManager → 验证 `provider-cache::{id}` 和 `provider-cache::list` 都被 evict |

**为什么是集成**：本批是 D3 + 缓存不刷新 + model_config 同步策略三个连环 bug 的源头，必须走真 Redis / 真 MyBatis，不能用 mock 替身

**前置依赖**：Testcontainers MySQL 8 + Redis 7（建议封装为 `AbstractIntegrationTest` 共享基类，给后续 B4 B5 B6 复用）

---

### B4 · 集成测试 · 1h · 链路 1

**目标**：锁住 list() vs getDetail() 数据一致性。

**测试项**：

| ID | 名称 | 断言 |
|---|---|---|
| #4a | `ProviderServiceTest_list_detail_model_consistency` | 准备 3 条 model_config（2 enabled=1，1 enabled=0）→ 两条接口返回的 models 都过滤掉 enabled=0 的那一条 |

**为什么独立成批**：这是 B3 修过的 bug 的延伸验证，单独跑便于在 PR 评审时聚焦"list vs detail"diff；如果合到 B3 失败时根因难定位

---

### B5 · 集成测试 · 1.5h · 链路 7

**目标**：暴露并锁住 Agent 软删穿透 chat_session 的当前行为。

**测试项**：

| ID | 名称 | 断言 |
|---|---|---|
| #6a | `AgentServiceTest_softDelete_penetrates_chatSession` | 创建 agent → 创建 chat_session 绑该 agent → 删除 agent → `chatSessionMapper.selectByAgentId(id)` 仍返回旧 session（断言"穿透"，标红这条是已知缺陷） |
| #6b | `AgentServiceTest_hardDelete_cascades` | 同上，但用物理删除 → 期望 chat_session 也消失（这是"应该做的"行为，未来 PR 修复时直接复用此断言） |

**为什么独立成批**：链路 7 是 critical-paths 标红"一旦命中即证明 CLAUDE.md 风险已显化"的链路，**整个项目最高优先级**。test #6b 是"等修的契约"，#6a 是"现状锁住"——两个断言一起写，将来 PR 修了就改 #6a → 改成"不返回"

---

### B6 · 单元测试 · 1.5h · 链路 1 / 5

**目标**：纯算法层面的状态机正确性。

**测试项**：

| ID | 名称 | 断言 |
|---|---|---|
| #2a | `ProviderHealthStateMachineTest_failCount_thresholds` | failCount=1 → DEGRADED，failCount=2 → DEGRADED，failCount=3 → DOWN；reset（success 后）→ UP, failCount=0 |
| #5a | `CircuitBreakerTest_openAfter10_failures_doesNotDoubleCount` | mock Adapter 连续 10 次 timeout → 熔断器打开；调用 #11 收到 fast-fail（不再真发出请求）；failCount 只 +1 不重复累计 |

**为什么放最后**：B3 / B4 / B5 已覆盖大部分真实路径，B6 是纯算法补充；状态机和熔断计数是"已经被打爆过的边界"，补单测足够

**前置依赖**：可复用 B3 的 Testcontainers 基建（#5a 要起真 Redis）

---

## 实施建议

### 顺序

```
B1 (0.5h) → B2 (0.5h) → B3 (2h，建基建) → B4 (1h) → B5 (1.5h) → B6 (1.5h)
```

总耗时 7h = 1 个工作日。

### 基建前置

B1 / B2 不需要任何基建，可以**当天立即开始**，给团队信心。

B3 是基建建立批，建议**先单独立一个 PR**只加 `AbstractIntegrationTest` + Testcontainers 依赖，后续 4 个 PR 都基于它。

### 与重构的关系

按 `docs/test-gaps.md` 末尾的建议："P0 当作重构 ProviderConnectionTestService 的硬性前置项"。建议执行顺序：

1. **先做 B1 + B2**（纯 Characterization，零依赖，1h）→ 立即获得"未来不再复发 D3 + authConfig 命名问题"的兜底
2. **再做 B3 + B4 + B5**（集成测试，3.5h）→ 此时再让任何人改 `ProviderConnectionTestService` / `ProviderService` / `AgentService` 都有护栏
3. **最后做 B6**（状态机补充）→ 锦上添花

### 不进计划的项（理由）

- 简单 CRUD 单表读写测试：MyBatis-Plus 生成代码 + 单表 = 风险低，不占 P0 名额
- 前端测试：前端 Vue 组件测试与"后端关键路径"主题不直接相关，本计划聚焦后端
- E2E（Playwright / cypress）：链路 8（跨模块写一致性）值得 E2E，但属于 P1 范围，不进 P0 计划

### 完成标志

- `mvn test` 在 CI 上从"空跑"变成"跑 7 个测试 + 全绿"
- 每个测试有明确的失败信息（哪个链路、哪个 bug 复发）
- PR 模板加"是否新增/修改了 P0 测试项"的勾选项，强制回归保护