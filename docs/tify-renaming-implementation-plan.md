# Tify 命名改造实施计划

## 1. 实施目标

在 `rename-tify` 分支完成 Tify 工程命名统一，按阶段提交并验证每个可构建节点。最终镜像以完成改造后的 Git 短 SHA 标记，部署到开发集群的 `tify` namespace。

## 2. 前置门禁

1. 使用 JDK 17 和 Maven 3.9.9 执行后端构建。
2. 使用 Node.js 22、npm 10 执行前端构建。
3. 使用本机 Podman machine 构建并推送应用镜像，Harbor 凭据只通过 Podman 登录使用。
4. 使用指定 kubeconfig 检查集群、`nfs-csi` 存储类和 `30081` 端口占用。
5. kubeconfig 和 Registry 凭据只在本机使用，不写入仓库。

## 3. 代码与工程实施

### 阶段 A：清理构建产物

- 删除根 `dist/`、前端 `dist/` 和 Maven `target/` 中的过期内容。
- 后续只通过标准构建重新生成发布产物。
- 提交信息：`chore: remove obsolete generated artifacts`。

### 阶段 B：后端命名空间

- 将 8 个 Maven 模块统一为 `tify-*`。
- 将 Java 主代码与测试代码统一迁移到 `com.phadcalc.llm.tify`。
- 更新 POM、package、import、扫描路径、启动类、指标类及全限定配置引用。
- 使用 Maven reactor 编译验证模块依赖闭环。
- 提交信息：`refactor: rename backend modules and packages to tify`。

### 阶段 C：前端与代理

- 将前端模块统一为 `tify-web`，更新 npm 包名、组件文件名和 CSS 前缀。
- 更新页面品牌、设计令牌和所有组件引用。
- Nginx `/api/` 代理固定为 `http://tify-backend:8080`。
- 运行 `npm --prefix tify-web run build`。
- 提交信息：`refactor: rename frontend application to tify`。

### 阶段 D：配置与文档

- 更新 Docker、Compose、Makefile、Shell、CI、应用配置和发布路径。
- JVM、容器和 Spring JSON 统一设置 `Asia/Shanghai`。
- 更新 README、AGENTS、CLAUDE、Markdown、SVG、脚本和监控配置。
- 运行全工作树命名扫描和 `git diff --check`。
- 提交信息：`docs: align configuration and documentation with tify`。

## 4. Kubernetes 实施

### 4.1 清单准备

- 资源统一使用 `namespace: tify`、`tify-*` 名称和标签。
- MySQL、Redis、pgvector 使用 `nfs-csi` PVC 和内部 Service DNS。
- 前端 Service 使用 `NodePort 30081`，后端使用 `ClusterIP:8080`。
- 后端与前端镜像固定到 `registry.tjzs.com/phadagent/*:rename-tify-<short-sha>`。
- pgvector 使用 `registry.tjzs.com/library/pgvector:pg16`。
- 监控文件只改名，不在本轮 apply。

### 4.2 Secret

执行 `deploy/k8s/create-secrets.ps1`：

- 使用加密安全随机数生成 MySQL root、MySQL 应用账号、Redis 和 pgvector 密码。
- 创建 `tify-mysql-secret`、`tify-redis-secret`、`tify-pgvector-secret` 和 `tify-backend-secret`。
- 不输出 Secret 值，不生成包含真实值的本地文件。
- `OPENAI_API_KEY` 仅在环境变量存在时注入。

### 4.3 部署顺序

1. 创建 namespace 和 Secret。
2. apply MySQL、Redis、pgvector 的 PVC、Service 和工作负载。
3. 等待三个中间件 Ready。
4. apply MySQL schema Job 和 pgvector extension Job，并等待完成。
5. apply 后端 ConfigMap、Service 和 Deployment，等待 rollout。
6. apply 前端 Service 和 Deployment，等待 rollout。

所有命令必须显式使用：

```powershell
$kubeconfig = "C:\Users\HP\Documents\jenkins-secret\kubeconfig-phadagent-dev"
kubectl --kubeconfig $kubeconfig ...
```

## 5. 构建、镜像与发布

1. 使用 Maven 3.9.9 + JDK 17 执行 `mvn clean install`。
2. 执行前端生产构建和 mock profile 健康检查。
3. 重新生成 `tify-app.jar`、`tify-web/dist` 和 `dist/tify-<version>.tar.gz`。
4. 检查 JAR、tar.gz 和静态资源内部命名。
5. 使用构建提交短 SHA `55ebb0e`，镜像标签为 `rename-tify-55ebb0e`。
6. 在具备 Harbor 权限的构建机使用 Docker/Podman 构建并推送：
   - `registry.tjzs.com/phadagent/tify-backend:<tag>`
   - `registry.tjzs.com/phadagent/tify-frontend:<tag>`
7. Deployment 已固定到实际标签；镜像推送完成后重新触发 rollout。

## 6. 验收与收尾

- 后端 reactor、单元测试和集成测试通过。
- 前端类型检查、生产构建和 Nginx 代理通过。
- Docker Compose 配置及 Kubernetes server dry-run 通过。
- `tify` namespace 中间件、初始化 Job、后端和前端状态正常。
- 访问 `http://192.168.130.220:30081/`，验证首页和 `/api/v1/health`。
- 在 Pod 中检查 `TZ`、系统时间和 JVM `user.timezone`。
- 在 MySQL 和 pgvector 中检查当前时间与时区行为。
- 不部署或验收 Grafana/Prometheus，不删除集群其他资源和历史镜像。
- 本地进程停止后，将工作目录调整为 `D:\github-code\tify`。
