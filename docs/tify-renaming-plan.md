# Tify 命名规范

## 1. 目标

Tify 是 Tianjisuan 内部学习型 AI Agent 平台。本规范约束代码、模块、构建产物和部署资源的统一命名，不改变 REST 接口、业务表结构和业务逻辑。

## 2. 统一命名

| 范围 | 规范 |
|---|---|
| 品牌 | `Tify` |
| 项目及模块前缀 | `tify`、`tify-*` |
| Java 根包及 Maven `groupId` | `com.phadcalc.llm.tify` |
| Spring Boot 启动类 | `TifyApplication` |
| 指标管理类 | `TifyMetrics` |
| 前端公共组件 | `TifyTable`、`TifyFormDialog` |
| CSS 和配置前缀 | `tify-*`、`tify.*` |
| Prometheus 指标前缀 | `tify_*` |
| Docker 镜像 | `tify-backend`、`tify-frontend` |
| Docker 网络 | `tify-net` |
| Kubernetes namespace | `tify` |
| 数据库及账号示例 | `tify` |
| 时区 | `Asia/Shanghai` |

后端模块统一为 `tify-common`、`tify-provider`、`tify-mcp`、`tify-agent`、`tify-workflow`、`tify-knowledge`、`tify-chat` 和 `tify-app`；前端模块为 `tify-web`。

## 3. 工程约束

- Java 主代码和测试代码均位于 `com/phadcalc/llm/tify`。
- Maven 内部依赖统一使用 `com.phadcalc.llm.tify:tify-*`。
- Spring、MyBatis、Resilience4j 和反射测试必须引用完整新包名。
- PID、日志、发布包、缓存目录和界面样式统一使用 `tify` 前缀。
- REST 路径、业务表名、错误码及 Redis 会话 Key 保持原有契约。
- 构建产物必须由当前源码重新生成，不允许编辑 JAR、tar.gz 或压缩静态资源。

## 4. 部署约束

- 使用 `C:\Users\HP\Documents\jenkins-secret\kubeconfig-phadagent-dev` 访问开发集群，所有命令显式传入 `--kubeconfig`。
- 应用部署到 `tify` namespace；前端通过 `NodePort 30081` 暴露。
- 镜像使用 `registry.tjzs.com/phadagent/tify-{backend|frontend}:rename-tify-<short-sha>`。
- pgvector 镜像使用 `registry.tjzs.com/library/pgvector:pg16`。
- MySQL、Redis、pgvector 使用全新 `nfs-csi` PVC，不执行历史数据备份或迁移。
- Secret 在部署时随机生成，不向仓库提交真实凭据。
- Pod、JVM、Spring JSON、JDBC 和数据库统一使用 `Asia/Shanghai`。
- 监控配置保持命名一致，但本轮不部署、不作为验收门禁。
- 不删除其他 namespace、PVC、工作负载或历史镜像。

## 5. 验收边界

- Maven 全量构建及测试通过，前端类型检查和生产构建通过。
- mock profile 启动并通过 `/api/v1/health`。
- 源码、跟踪路径、文档、JAR、tar.gz、静态资源和 Kubernetes 清单符合本规范。
- `tify` namespace 的中间件和应用正常运行，Pod 与数据库时区均为东八区。
- Git 历史和 `.git` 对象不属于当前工作树验收范围。
