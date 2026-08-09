# Hify 测试缺口

> 来源对照：`docs/critical-paths.md`（应测什么）× `docs/test-status.md`（现状 0 测试）
> 原则：≤20 项，只列主链路上的缺口；追求"关键路径有兜底"，不追求覆盖率

## 测试缺口清单（16 项）

| # | 优先级 | 链路 | 场景描述 | 为什么必须 | 建议测试类型 |
|---|---|---|---|---|---|
| 1 | **P0** | 1 | ProviderConnectionTestService.test() 成功路径：写 provider_health + 刷 model_config + evict cache | 本会话连续 3 次 bug（D3 反序列化 + 缓存不刷新 + list/detail 不一致）的源头，改动极易回归 | **集成测试**（mock OkHttp + 真实 MyBatis） |
| 2 | **P0** | 1 | ProviderConnectionTestService.test() 失败路径：failCount 累加 → DEGRADED → DOWN 状态机 | 熔断触发逻辑改 1 行就可能让 failCount 跳级或重置 | **单元测试**（纯状态机） |
| 3 | **P0** | 1 | OpenAiAdapter.getAuth() 同时支持 `apiKey` (camel) 和 `api_key` (snake) | 本会话刚暴露的"前端传错字段名"坑，CLAUDE.md 没约束字段命名风格 | **单元测试** + **Characterization Test**（锁住两套 key 都通） |
| 4 | **P0** | 1 | ProviderService.list() vs getDetail() 返回的 models 数量一致（都过滤 enabled=1） | 本会话刚修过的"外面 8 个 / 里面 0 个"问题，重构 Service 时极易复发 | **集成测试**（写两条 enabled=1 / 一条 enabled=0，断言两条接口都返回2） |
| 5 | **P0** | 5 | Resilience4j 熔断器打开后，ProviderConnectionTestService 收到 fast-fail，failCount 不重复计数 | 熔断共享可变状态（failCount），多线程下竞态可能让计数错乱 | **集成测试**（mock OkHttp 抛 timeout，连续 10 次） |
| 6 | **P0** | 7 | DELETE Agent 后，GET /chat/sessions?agentId={id} 仍返回历史 session | "逻辑删除穿透"是 critical-paths 标红的链路；改应用层外键校验逻辑时无回归保护 | **集成测试**（删 agent → 立即查 chat_session，断言返回旧 session 即视为暴露 bug） |
| 7 | **P0** | 8 | `Result<PageResult<...>>` 永远不能进 `@Cacheable`（防御性约束） | 本会话 D3 直接原因；任何重构都可能把缓存的返回值改回去 | **Characterization Test**（反射扫所有 `@Cacheable` 方法，断言返回类型不含 `Result`） |
| 8 | **P1** | 2 | ChatService.sendMessage() 多轮上下文窗口截断：history.length > agent.max_context_turns 时只取最近 N 轮 | max_context_turns 是 CLAUDE.md 重点策略，无测试守门易被改坏 | **单元测试**（mock chatMessageMapper，断言拼装的 prompt 长度） |
| 9 | **P1** | 2 | ChatService.streamChat() 首 token latency + 流结束写 chat_message 一行 | 流式 SSE 是 hify-chat 唯一对外能力，没测试则 N+1 / 丢包都查不出 | **集成测试**（mock SSE emitter + 真实 MyBatis） |
| 10 | **P1** | 3 | POST /agents/{id}/tools 绑定：UK (agent_id, mcp_server_id) 重复绑定返 409 | M:N 关联表唯一约束是数据完整性核心 | **集成测试**（绑两次相同 tool，第二次应业务异常） |
| 11 | **P1** | 3 | McpService.debugTool() 调用外部 MCP server 的 JSON-RPC 请求体格式 | MCP 协议细节由 SDK 处理，但请求体改造时易破协议 | **集成测试**（mock HTTP server，断言请求体含 `jsonrpc: "2.0"`、`method`、`params`） |
| 12 | **P1** | 4 | KnowledgeService.uploadDocument() 异步分块 → document_chunk 写入 pgvector | RAG 全链路最薄弱环节，没人测意味着 vector 数据格式错了也没人发现 | **集成测试**（真实 pgvector，上传固定文本，断言 chunks 行数 + vector 维度） |
| 13 | **P1** | 6 | max_tokens 超 model_config.context_size 时 LLM 调用截断而非崩溃 | CLAUDE.md 强调的 token 预算边界 | **单元测试**（mock ProviderAdapter，断言请求 max_tokens ≤ context_size） |
| 14 | **P1** | 8 | DB 写成功 + Redis 写失败的回滚策略：DB 不应回滚，下次读应能 rehydrate | Redis `session:{sessionId}` 是会话上下文唯一来源（CLAUDE.md 缓存策略） | **集成测试**（mock RedisTemplate 抛异常，断言 DB 仍成功 + 接口返回非 500） |
| 15 | **P2** | 5 | ProviderHealthCheckTask 每 60s 周期 + failCount 阈值切换 DOWN | 周期任务最难调试，有测试可在 CI 内加快时间戳模拟 | **集成测试**（用 Spring `@Scheduled` 测试支持，加速时钟） |
| 16 | **P2** | 4 | embedding 模型配置变化时，document_chunk embedding 维度是否一致 | 切 OpenAI text-embedding-3 → 4 后旧数据可能维度不兼容 | **Characterization Test**（断言所有 chunk.embedding 维度相同） |

## 优先级判读

**P0（7 项）**：本会话刚踩过的 bug，或链路 7 标红的"应用层外键"风险。任何一项缺失，下次重构就可能原地爆炸。

**P1（7 项）**：CLAUDE.md / critical-paths 明确点名的核心策略（多轮上下文、token 预算、RAG、跨模块一致性），缺了 = CLAUDE.md 等于一纸空文。

**P2（2 项）**：周期任务、维度兼容性 — 工程完备性补充，**不补也没人骂**。

## 测试类型说明

- **单元测试**：纯 JUnit + Mockito，无 Spring 容器，跑得快（< 1s/个），适合纯逻辑
- **集成测试**：`@SpringBootTest` + Testcontainers 起真实 MySQL/Redis/pgvector，**本项目补测主力**（无容器基建就先 in-memory H2 / embedded-redis）
- **Characterization Test**：用断言"锁住现状"——不是验证正确行为，而是防止以后回归到已知坏状态（D3 Result 进缓存就是典型的需要被锁住的"错误行为已修"）

## 推算落地成本

| 类型 | 平均单测成本 | 16 项合计 |
|---|---|---|
| 单元测试 | ~20 分钟 | ~2 小时 |
| 集成测试（Testcontainers） | ~60 分钟（首项含基建） | ~10 小时 |
| Characterization Test | ~30 分钟 | ~3 小时 |

**总计**：一个人半天到 1 天能完成全部 P0（7 项），P1 再加 1 天。建议先把 P0 当作"重构 ProviderConnectionTestService 之前的硬性前置项"。