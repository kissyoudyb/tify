# Tify Backend 部署手册（一键脚本版）

> 适用版本：`rename-tify` 分支及之后。
> 目标环境：开发环境集群 `phadagent-dev`、命名空间 `tify`、镜像仓库 Harbor (`registry.tjzs.com`)。

---

## 1. 背景：本次构建的"止血"目的

`tify-provider` 的 `ProviderHealthCheckTask` 每 60 秒触发一次 `refreshModels`，
会把当前 enabled 的 `model_config` 全部置 0 再重新 INSERT，导致：

1. `model_config` 表行数暴涨（已观察到 100+ 行）
2. 已绑定到 Agent 的 `model_config_id` 被悄悄置为 `enabled=0`，新增/更新 Agent 时报 `MODEL_CONFIG_DISABLED`

**根治方案**在 `ProviderConnectionTestService.refreshModels` 改为按 `(provider_id, model_id)` 的幂等 upsert，并给 `model_config` 加 `UNIQUE KEY`——本次先做最小止血：关闭定时任务，让数据不再涨。详见根因分析章节（`docs/bug-model-config-grows.md` 待补）。

---

## 2. 脚本做了什么

`scripts/deploy-tify-backend.ps1` 顺序执行：

| # | 步骤 | 说明 |
|---|---|---|
| 1 | 前置依赖检查 | `git / mvn / docker / kubectl` 是否就绪；yaml/yml 是否存在 |
| 2 | Maven 打包 | `mvn -pl tify-app -am -DskipTests package`，产出 `tify-app/target/tify-app-*.jar` |
| 3 | 注入止血 yml | 在 `application.yml` 加入 `tify.health-check.enabled=false`，备份到 `application.yml.bak.deploy` |
| 4 | Docker 构建 | 使用项目根 `Dockerfile.local`（**不复用**现有多阶段 `Dockerfile`），参数 `--build-arg JAR_FILE=...` 直接吃本地 jar |
| 5 | 登录 Harbor | `admin` / `Harbor12345`（默认参数，可在调用时覆盖） |
| 6 | 推送镜像 | `registry.tjzs.com/phadagent/tify-backend:<tag>` |
| 7 | K8s 部署 | 用给定 kubeconfig，把 ConfigMap / Service / Deployment apply 到 `tify` 命名空间，滚动更新 |
| 8 | 回滚 yml | 把 `application.yml` 还原成 git 原状，备份删除 |

镜像 tag 默认值：`rename-tify-<gitsha>-<yyyyMMdd-HHmm>`，例：`rename-tify-55ebb0e-20260813-1530`。

---

## 3. 前置依赖

| 工具 | 版本 | 备注 |
|---|---|---|
| Windows PowerShell | 5.1 或 PowerShell 7+ | 推荐 7（`pwsh`），Linux 语法兼容 |
| JDK | 17 | `mvn -v` 能看到 Java 17 |
| Maven | 3.8+ | `mvn -version` |
| Docker Desktop | 最新版 | Windows 需开启 WSL2 后端，且能 `docker run hello-world` |
| kubectl | 与集群版本匹配 | `kubectl version --client` |
| 网络 | 可访问 `registry.tjzs.com` | 公司内网或 VPN |

---

## 4. 一键运行

打开 PowerShell，切到项目根：

```powershell
cd D:\github-code\tify

# 默认参数（Harbor=registry.tjzs.com, namespace=tify, 用默认 kubeconfig）
.\scripts\deploy-tify-backend.ps1 `
    -Kubeconfig "D:\tianjisuan\05-技术方案\开发环境部署\kubeconfig-phadagent-dev" `
    -Namespace tify
```

### 4.1 常用参数

| 参数 | 默认 | 含义 |
|---|---|---|
| `-ImageTag` | 自动生成（git SHA + 时间戳） | 自定义 tag 时使用 |
| `-SkipBuild` | false | 跳过 mvn package，直接复用 target/ 下 jar |
| `-SkipPush` | false | 只构建镜像，不推 Harbor |
| `-SkipDeploy` | false | 只构建 + 推送，不 apply K8s |
| `-HarborHost` | `registry.tjzs.com` | Harbor 地址 |
| `-HarborProject` | `phadagent` | Harbor 项目名 |
| `-ImageName` | `tify-backend` | 镜像名 |
| `-HarborUser` | `admin` | Harbor 用户 |
| `-HarborPassword` | `Harbor12345` | Harbor 密码 |
| `-Kubeconfig` | 用户给出的路径 | kubeconfig 文件绝对路径 |
| `-Namespace` | `tify` | K8s 命名空间 |
| `-DryRun` | false | 只打印步骤，不实际执行 |

### 4.2 典型用法

只构建并推送，不部署：
```powershell
.\scripts\deploy-tify-backend.ps1 `
    -Kubeconfig "..." -SkipDeploy
```

不重新打包，只重打镜像（已本地 jar 在 `tify-app/target/`）：
```powershell
.\scripts\deploy-tify-backend.ps1 `
    -Kubeconfig "..." -SkipBuild
```

跑通流程校验（不真打包/部署）：
```powershell
.\scripts\deploy-tify-backend.ps1 `
    -Kubeconfig "..." -DryRun
```

---

## 5. 部署对象说明

脚本会 apply 以下三个 manifest（来自 `deploy/k8s/`）：

| 文件 | 资源 | 是否被脚本改 namespace |
|---|---|---|
| `backend-configmap.yml` | ConfigMap `tify-backend-config` | 是（替换为 `$Namespace`） |
| `backend-service.yml` | Service `tify-backend` | 是 |
| `backend-deployment.yml` | Deployment `tify-backend` | 是 + 替换镜像 tag |

其他依赖（MySQL / Redis / pgvector / Secret）**不在本脚本范围内**。如果环境是新集群，先按 `docs/deploy-k8s.md` 跑一次基础组件初始化（`create-secrets.ps1` 等）。

---

## 6. 为什么用 `Dockerfile.local`，不用项目根 `Dockerfile`

项目根 `Dockerfile` 在容器内执行 `mvn package`，从源代码全量编译。
**问题**：
- 单次构建 5-10 分钟
- 依赖没改也会重跑下载/编译
- 受容器内 Maven 镜像体积与网络限制

**`Dockerfile.local` 优化**：
- 假设宿主机已经 `mvn package` 完成，docker build 只做 `layertools extract` + 复制层
- 单次构建秒级
- 镜像大小、JVM 参数、启动方式与原 `Dockerfile` 完全一致（运行时无差别）

详细差异见 `Dockerfile.local` 头部注释。

---

## 7. 验证部署

脚本结束后会自动滚动更新并打印 Pod 状态：

```powershell
# 查看 deployment 状态
kubectl --kubeconfig "..." -n tify get deploy tify-backend

# 看滚动进度
kubectl --kubeconfig "..." -n tify rollout status deployment/tify-backend

# 拉日志
kubectl --kubeconfig "..." -n tify logs -f deployment/tify-backend

# 健康检查
curl http://<node-ip>:30080/api/v1/health
```

如果 health 接口返回 `{status:UP}` 且 components 三项都 UP，说明本次部署成功。

---

## 8. 回滚

### 8.1 回滚镜像（K8s 侧）

```powershell
kubectl --kubeconfig "..." -n tify rollout undo deployment/tify-backend
```

或者指定上一个 tag：
```powershell
kubectl --kubeconfig "..." -n tify set image deployment/tify-backend `
    backend=registry.tjzs.com/phadagent/tify-backend:<上一个tag>
```

### 8.2 回滚 application.yml

脚本已经在结束时把 yml 还原成 git 原状。
如果你想保留止血 yml（脚本失败中断、yml 改了没回滚），手动恢复：

```powershell
cd D:\github-code\tify
git checkout -- tify-app/src/main/resources/application.yml
```

### 8.3 完整重置 model_config 数据（下次部署前）

止血只是停止继续涨，**已存在的脏数据还在**。下次部署后要做：

```sql
-- 1. 把每组 (provider_id, model_id) 中非最大 id 的全部 enabled=0
UPDATE model_config m
JOIN (
  SELECT provider_id, model_id, MAX(id) AS keep_id
  FROM model_config
  GROUP BY provider_id, model_id
) k ON m.provider_id = k.provider_id AND m.model_id = k.model_id
SET m.enabled = 0
WHERE m.id <> k.keep_id;

-- 2. 物理删除已禁用的重复行
DELETE FROM model_config WHERE enabled = 0;

-- 3. 加 UK 防再次重复
ALTER TABLE model_config
  ADD UNIQUE KEY uk_model_config_provider_model (provider_id, model_id);
```

---

## 9. 根治 Bug（建议下次合并时做）

止血 yml 只是临时方案，根本修复在 `tify-provider/.../ProviderConnectionTestService.java`：

1. `refreshModels` 改为**幂等 upsert**：
   - `selectList` 当前 enabled=1 的 model_id 集合 → 不在远端列表里的置 enabled=0
   - 远端列表里存在的：先 `selectById(provider_id, model_id)`，存在 `update enabled=1`，不存在才 `insert`
2. 给 `model_config` 加 `UNIQUE KEY (provider_id, model_id)`，数据库兜底
3. 重新打开 `tify.health-check.enabled=true`

建议另起一个 PR，单独走测试和评审。

---

## 10. 常见问题

**Q1：脚本提示 "找不到本地 jar"**
答：先执行 `mvn -pl tify-app -am -DskipTests package`，或在调用时加 `-SkipBuild=false`（默认值）。

**Q2：docker push 报 "denied: requested access to the resource is denied"**
答：Harbor 凭据错了，或 `admin` 没有 `phadagent` 项目的 push 权限。联系 Harbor 管理员。

**Q3：滚动更新卡住 / 新 Pod 起不来**
答：`kubectl -n tify describe pod <pod-name>` 看 Events；通常是健康检查失败 → 看应用日志。常见原因是 DB 连接不上，先确认 ConfigMap / Secret 已 apply。

**Q4：脚本结束后 yml 没还原**
答：检查 `tify-app/src/main/resources/application.yml.bak.deploy` 是否存在，手动 `git checkout --` 还原即可。

**Q5：需要部署到其他命名空间**
答：传 `-Namespace <your-ns>`，脚本会把所有 manifest 的 namespace 改写。注意 Secret 也得在新命名空间创建（`create-secrets.ps1 -Namespace <your-ns>`）。

---

## 11. 文件清单

| 路径 | 作用 |
|---|---|
| `scripts/deploy-tify-backend.ps1` | 一键部署脚本 |
| `Dockerfile.local` | 复用本地 jar 的轻量 Dockerfile |
| `Dockerfile` | 原多阶段 Dockerfile（容器内全量 mvn），本脚本不用 |
| `deploy/k8s/backend-deployment.yml` | Deployment 模板 |
| `deploy/k8s/backend-service.yml` | Service 模板 |
| `deploy/k8s/backend-configmap.yml` | ConfigMap 模板 |
| `deploy/k8s/create-secrets.ps1` | Secret 初始化（不在本脚本范围） |