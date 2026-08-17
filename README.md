# Tify

简版 AI Agent 开发平台（参考 Dify），面向 20-50 人团队内部使用。
技术栈：Spring Boot 3.x + MyBatis-Plus + MySQL 8.x + Redis 7.x + pgvector / Vue 3 + TypeScript + Element Plus + Vite / Docker + K8s。

详细模块架构、接口、数据模型在 `docs/` 下，启动前请先阅读 `CLAUDE.md`，运行期问题先看 `docs/`。

---

## 1. 目录速览

```
tify/
├── tify-app/          # Spring Boot 启动模块
├── tify-provider/     # 模型提供商
├── tify-agent/        # Agent 配置
├── tify-chat/         # 对话引擎
├── tify-mcp/          # MCP 工具
├── tify-workflow/     # 工作流
├── tify-knowledge/    # RAG 知识库
├── tify-common/       # 公共模块
├── tify-web/          # Vue 3 前端
├── deploy/            # K8s / Docker 部署配置
└── docs/              # api-list.md / data-model.md / architecture...
```

---

## 2. 前端本地开发（推荐方式）

前端通过 `localhost:5173` 启动，Vite proxy 把 `/api` 转发到 `localhost:8080`，**8080 由 kubectl port-forward 把 K8s backend Pod 端口转发过来**。这样前端不需要改任何代码就能调通整套栈。

### 2.1 配置 npm 国内加速源（首次必做）

把下面文件放到 `tify-web/.npmrc`（也可放到用户目录 `~/.npmrc` 作用全局）：

```ini
# tify-web/.npmrc
registry=https://registry.npmmirror.com/

# 可选：electron 二进制走淘宝镜像（如本项目后续引入）
# electron_mirror=https://npmmirror.com/mirrors/electron/
# sass_binary_site=https://npmmirror.com/mirrors/node-sass

# 切换回官方
# registry=https://registry.npmjs.org/
```

安装完后可以用 `npm config get registry` 验证。

> 说明：国内加速源只是镜像，install 出来的 hash 不变，对 lockfile 兼容。如果团队固定要走镜像，把 `.npmrc` 提交进 git 更稳。

### 2.2 启动方式（一键脚本）

```bash
# 终端 A：起后端端口转发（保持运行）
kubectl --kubeconfig "D:\tianjisuan\05-技术方案\开发环境部署\kubeconfig-phadagent-dev" -n tify port-forward svc/tify-backend 8080:8080

# 终端 B：验证后端可达
curl http://localhost:8080/api/v1/health
# 期望返回 {"status":"UP", ...}

# 终端 C：起前端
cd tify-web
npm install --registry=https://registry.npmmirror.com   # 第一次或 lockfile 变更时
npm run dev
```

浏览器打开 `http://localhost:5173` 即可。

### 2.3 常见踩坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `Failed to resolve entry for package "element-plus"` | `node_modules/element-plus/es/index.mjs` 缺失（之前 install 中断） | 删除 `node_modules/element-plus` 后 `npm install element-plus@^2.13.6 --no-audit` |
| 前端请求 502 | port-forward 进程没起或被关 | 重新跑终端 A 的命令 |
| `/api/v1/chat/sessions/.../messages` 返回 406 | 前端打了同步接口但带 `Accept: text/event-stream` | 确认 `tify-web/src/api/chat.ts` 里 stream 调用的是 `/messages/stream`（不要 `/messages`） |
| SSE 聊天一直没收到 chunk | Vite proxy 转发 SSE 偶发 buffer | 优先用 port-forward + Vite 5 默认 proxy；不要中途换 Nginx 中转 |
| `curl` 健康检查 502 | K8s Pod 还在重启 / 镜像拉取中 | `kubectl -n tify get pods` 看 `tify-backend-...` 状态；未 Ready 时 etcd 滚动日志用 `kubectl -n tify logs -f tify-backend-84ff746dc6-kbx4d` |

### 2.4 切换 backend 后端源

`vite.config.ts` 里 proxy 默认指向 `http://localhost:8080`。如果你想直连某个 NodePort 或本地 `java -jar`：

```ts
// tify-web/vite.config.ts
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:33081',  // 比如 NodePort 33081
      changeOrigin: true,
    },
  },
},
```

---

## 3. 后端本地直接跑（不用 K8s）

需要本地有 MySQL 8 / Redis 7 / pgvector。后端配置走 `application.yml` + profile：

```bash
# 第一次或改动了依赖，需要先 install
mvn clean install -DskipTests

# 跑 mock 模式（不依赖任何外部存储，纯前端联调用）
mvn spring-boot:run -pl tify-app -Dspring-boot.run.profiles=mock

# 跑真实环境（连本机 MySQL/Redis/pgvector）
mvn spring-boot:run -pl tify-app -Dspring-boot.run.profiles=local
```

> 注意：`mvn spring-boot:run` 默认占用 8080。如果同时要跑 port-forward，会冲突 —— 二选一。

---

## 4. Docker / K8s 一键部署

```bash
# 构建镜像（参考 Dockerfile / Dockerfile.local）
docker build -t tify-backend:latest -f Dockerfile.local .
docker build -t tify-web:latest -f tify-web/Dockerfile tify-web/

# K8s 部署（需要先有 tify namespace 和 secrets）
kubectl apply -f deploy/k8s/ -n tify
```

详见 `deploy/k8s/` 和 `docs/` 下的部署文档。

---

## 5. 项目规范速查

- **代码规范**：见 `CLAUDE.md`（强制条目，主要约束：分层、错误码、缓存、线程池、Lombok、绝不引入计划外依赖）。
- **接口清单**：`docs/api-list.md`。
- **数据模型**：`docs/data-model.md`。
- **架构图**：`docs/architecture.svg` / `docs/module-deps.svg` / `docs/external-deps.svg`。

---

## 6. 常见 TIPS

```bash
# maven 依赖找不到？先清装
mvn clean install -DskipTests
mvn clean package -DskipTests

# 切换 npm 镜像
npm config set registry https://registry.npmmirror.com
npm config get registry

# K8s 排查
kubectl -n tify get pods
kubectl -n tify logs -f <pod-name>
kubectl -n tify describe pod <pod-name>
```
