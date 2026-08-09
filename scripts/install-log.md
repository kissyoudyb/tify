# Hify 中间件 K8s 部署安装日志

> 时间：2026-08-07 / 2026-08-08
> 集群：`https://172.17.82.209:6443`（k3s v1.34.9，单节点 desktop-cdh1or2，containerd 2.2.5-k3s2）
> Kubeconfig：`C:\Users\HP\Documents\jenkins-secret\kubeconfig-phadagent-wsl`
> Namespace：`hify`

## 1. 目标

按 `docs/env-checklist.md` 第 1 节中间件清单在 K8s 部署并初始化：MySQL 8 / Redis 7 / pgvector（向量扩展）。

## 2. 准备的 K8s 资源文件

| 中间件 | 文件 |
|---|---|
| MySQL | `deploy/k8s/mysql/{secret,pvc,service,statefulset,init-job}.yml` |
| Redis | `deploy/k8s/redis/{secret,pvc,service,statefulset}.yml` |
| pgvector | `deploy/k8s/pgvector/{secret,pvc,service,deployment,init-job}.yml` |

镜像选型：

- MySQL：`mysql:8.0`（官方，稳定 + 与 schema.sql 的 utf8mb4 / JSON / 逻辑删除字段匹配）
- Redis：`redis:7`（官方 AOF 配置最简）
- pgvector：`pgvector/pgvector:pg16`（自带 vector extension 0.8.x，无需额外构建）

## 3. 实际执行的命令

```bash
export KUBECONFIG="C:/Users/HP/Documents/jenkins-secret/kubeconfig-phadagent-wsl"

# 1. 建 namespace
kubectl create namespace hify

# 2. MySQL 全套
kubectl apply -f deploy/k8s/mysql/secret.yml \
               -f deploy/k8s/mysql/pvc.yml \
               -f deploy/k8s/mysql/service.yml \
               -f deploy/k8s/mysql/statefulset.yml \
               -f deploy/k8s/mysql/init-job.yml

# 3. Redis 全套
kubectl apply -f deploy/k8s/redis/secret.yml \
               -f deploy/k8s/redis/pvc.yml \
               -f deploy/k8s/redis/service.yml \
               -f deploy/k8s/redis/statefulset.yml

# 4. pgvector 全套
kubectl apply -f deploy/k8s/pgvector/secret.yml \
               -f deploy/k8s/pgvector/pvc.yml \
               -f deploy/k8s/pgvector/service.yml \
               -f deploy/k8s/pgvector/deployment.yml \
               -f deploy/k8s/pgvector/init-job.yml
```

## 4. 过程中遇到的问题与修复

### 问题 1 · 镜像拉取失败（Redis / pgvector）

**症状**：

```
Failed to pull image "redis:7":
  failed to do request: Head "https://docker.1ms.run/v2/library/redis/manifests/7":
  dial tcp: lookup docker.1ms.run: Try again
```

**根因**：k3s 节点的 `/etc/rancher/k3s/registries.yaml` 把所有镜像源（含 `docker.io`）重定向到 `docker.1ms.run`，而该镜像加速器 DNS 在此环境下不可达（`lookup docker.1ms.run: Try again`）。busybox/alpine 等小镜像同样受影响。

**尝试修复**：写 debug pod 进 hostPID + 挂 `/etc/rancher` 准备读 registries.yaml，但 debug pod 自身拉镜像也失败，未能拿到 root cause 详情。

**最终方案**：经用户手动干预后镜像拉取恢复正常（用户在节点层调整了 registry 加速器或临时切换为可访问源）。从此刻起 Redis 与 pgvector pod 进入 `Running`。

### 问题 2 · `hify-mysql-init` Job Failed

**症状**：`hify-mysql-init` Job 在 apply 后 8h 仍 `Failed`，MySQL pod 已 Running。

**排查**：

```bash
kubectl logs -n hify job/hify-mysql-init
# → "error: timed out waiting for the condition"
```

进一步查 pod 不存在（Job 失败清理），但 MySQL 主 pod 里有表。最终直接在 mysql pod 内查：

```
Tables_in_hify: mcp_server / model_config / provider / provider_health  ← 仅 4 张
```

**根因**：apply 之前 Job 的 init container `wait-mysql` 在镜像拉取阶段就被卡住，schema.sql 注入从未成功触发。`docker.1ms.run` 加速器不可达期间任何镜像都拉不下来。

**修复**：手动在 MySQL pod 内执行剩余 4 张表（agent / agent_tool / chat_session / chat_message）的 DDL。

### 问题 3 · agent 表 `system_prompt TEXT` 不能 default

**症状**：

```
ERROR 1101 (42000): BLOB, TEXT, GEOMETRY or JSON column 'system_prompt' can't have a default value
```

**根因**：MySQL 8 严格模式下 TEXT/BLOB/JSON/GEOMETRY 字段禁止 `DEFAULT ''`。

**修复**：把 `system_prompt TEXT DEFAULT ''` 改为 `system_prompt TEXT`（保留可空，与 schema.sql 注释一致）。其余 3 张表 CREATE 成功。

**最终结果**：

```
Tables_in_hify: agent / agent_tool / chat_message / chat_session /
                mcp_server / model_config / provider / provider_health  ✓ 8 张
```

### 问题 4 · `hify-pgvector-init` Job Failed

**症状**：同上，pgvector 镜像拉不下来时 init Job 失败，没执行 `CREATE EXTENSION`。

**修复**：手工在 pgvector pod 内补执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1536),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_document_chunk_doc_id ON document_chunk(document_id);
```

**最终结果**：

```
\dx: plpgsql 1.0 / vector 0.8.6                          ✓ vector 已启用
\dt: document_chunk                                         ✓ 占位表就绪
```

## 5. 最终验证状态

| 中间件 | Service | Pod | 健康检查 | 表/数据 |
|---|---|---|---|---|
| MySQL 8 | `hify-mysql:3306` | `hify-mysql-0` Running | `mysqladmin ping` OK | `hify` 库含 8 张表 |
| Redis 7 | `hify-redis:6379` | `hify-redis-0` Running | `redis-cli ping` → `PONG` | AOF on，密码 `hify_redis_pw` |
| pgvector 16 | `hify-pgvector:5432` | `hify-pgvector-57df97c66d-5lplc` Running | `pg_isready` OK | `vector` 扩展 + `document_chunk` 表 |

后端 Spring Boot 应用所需连接参数：

```yaml
DB_HOST:        hify-mysql.hify.svc.cluster.local     # 3306 / hify / hify_pw
REDIS_HOST:     hify-redis.hify.svc.cluster.local     # 6379 / hify_redis_pw
PGVECTOR_HOST:  hify-pgvector.hify.svc.cluster.local  # 5432 / hify / hify_pg_pw
```

> ⚠ **生产密钥**：本次为可复现性写明文 secret 到 yml。生产部署前必须从环境变量 / Vault 注入，参见 `deploy/k8s/backend-secret.yml` 的 CI/CD 注入示例。

## 6. 已知遗留与后续工作

1. **Job 失败未清理**：两个 init Job 在 cluster 里留 Failed 记录，运行时无害，但 `kubectl get job` 仍能看到。`kubectl delete job -n hify hify-mysql-init hify-pgvector-init` 可清理。
2. **生产 DDL 缺口**：MySQL 仍只 8 张表，knowledge_base / document / workflow*（7 张）按 `docs/data-model.md` 末尾说明需要补。
3. **vector 扩展是占位**：`document_chunk` 表是为 `KnowledgeService` 占位设计（embedding vector(1536) 与主流 OpenAI text-embedding-3-small 维度对齐），业务字段（document_id 外键约束、metadata JSON 等）应在 `Knowledge` 模块就绪后细化。
4. **registry 加速器**：k3s 节点的 `/etc/rancher/k3s/registries.yaml` 配置由用户手工修复，所有未来镜像拉取应已恢复。如再次发生 `ImagePullBackOff`，优先检查该文件。