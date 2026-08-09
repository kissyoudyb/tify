# Hify 本地启动日志

> 时间：2026-08-08
> 工具链：JDK 17.0.4.1（`D:\javatools\Java\jdk-17.0.4.1`） + Maven 3.9.9（`D:\javatools\apache-maven-3.9.9`）
> 中间件：K8s 内（`hify` namespace，参见 `scripts/install-log.md`），通过 `kubectl port-forward` 暴露到本地

## 1. 应用最终运行状态

| 端口 | 用途 | 监听 |
|---|---|---|
| **8080** | Spring Boot 主端口（REST API `/api/v1/*`） | `0.0.0.0:8080`（PID 12896） |
| **8081** | Spring Boot Actuator（`/actuator/health` `/actuator/prometheus`） | `0.0.0.0:8081` |

**管理/调用入口**：

| 路径 | 用途 |
|---|---|
| `http://127.0.0.1:8080/api/v1/health` | 业务健康检查（最常用） |
| `http://127.0.0.1:8081/actuator/health` | Spring Boot 健康检查 |
| `http://127.0.0.1:8081/actuator/prometheus` | Prometheus 抓取端点 |
| `http://127.0.0.1:8080/api/v1/providers` | 模型提供商 CRUD |
| `http://127.0.0.1:8080/api/v1/agents` | Agent CRUD |
| `http://127.0.0.1:8080/api/v1/chat/sessions` | 对话会话 + `/messages/stream` 流式 SSE |
| `http://127.0.0.1:8080/api/v1/knowledge-bases` / `/documents` | RAG 知识库 |
| `http://127.0.0.1:8080/api/v1/mcp-servers` | MCP 工具注册 |
| `http://127.0.0.1:8080/api/v1/workflows` | 工作流 CRUD |

完整 42 个接口见 `docs/api-list.md`。

**当前 health**：

```bash
$ curl -sS http://127.0.0.1:8080/api/v1/health
{"status":"UP","components":{"mysql":"UP","redis":"UP","pgvector":"UP"}}
```

---

## 2. 完整启动命令（可复现）

```bash
# 0. 准备（只需一次）
export JAVA_HOME="D:/javatools/Java/jdk-17.0.4.1"
export PATH="$JAVA_HOME/bin:/d/javatools/apache-maven-3.9.9/bin:$PATH"

# 1. 把 K8s 内 3 个中间件映射到本地（详见 scripts/install-log.md）
export KUBECONFIG="C:/Users/HP/Documents/jenkins-secret/kubeconfig-phadagent-wsl"
nohup kubectl port-forward -n hify svc/hify-mysql    13306:3306  >/tmp/pf-mysql.log 2>&1 &
nohup kubectl port-forward -n hify svc/hify-redis    16379:6379  >/tmp/pf-redis.log 2>&1 &
nohup kubectl port-forward -n hify svc/hify-pgvector 15432:5432  >/tmp/pg-pf.log     2>&1 &
disown -a
sleep 3 && netstat -ano | grep -E "13306|16379|15432"   # 应都能 LISTENING

# 2. 编译打 jar
mvn clean install -DskipTests -B
# → BUILD SUCCESS，hify-app/target/hify-app-0.0.1-SNAPSHOT.jar (~58MB)

# 3. 启动应用
DB_HOST=127.0.0.1 DB_PORT=13306 DB_NAME=hify DB_USERNAME=root DB_PASSWORD=hify_root_pw \
REDIS_HOST=127.0.0.1 REDIS_PORT=16379 REDIS_PASSWORD=hify_redis_pw \
PGVECTOR_HOST=127.0.0.1 PGVECTOR_PORT=15432 PGVECTOR_DB=hify PGVECTOR_USERNAME=hify PGVECTOR_PASSWORD=hify_pg_pw \
SERVER_PORT=8080 \
nohup java -Xms256m -Xmx512m -jar hify-app/target/hify-app-0.0.1-SNAPSHOT.jar > /tmp/hify-app.log 2>&1 &
disown
sleep 25 && curl -sS http://127.0.0.1:8080/api/v1/health
# → {"status":"UP","components":{"mysql":"UP","redis":"UP","pgvector":"UP"}}
```

---

## 3. 过程中遇到的问题与修复

### 问题 1 · Maven 3.5.2 太老不识别 JDK 17

**症状**：

```
[ERROR] 不支持源选项 5，请使用 7 或更高版本。
[ERROR] 不支持目标选项 5，请使用 7 或更高版本。
[ERROR] Failed to execute goal ... maven-compiler-plugin:3.1:compile ...
```

**根因**：项目用 `D:\javatools\apache-maven-3.5.2`（2017 年发布），与 README 提示的「Maven 版本太低」一致。它对 `<java.version>17</java.version>` 的 release flag 处理有 bug，编译仍以 source=1.5 触发。

**修复**：升级到 Maven 3.9.9。

- 从阿里云镜像下载：`curl -L -o apache-maven-3.9.9-bin.zip https://maven.aliyun.com/repository/public/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip`
- 解压到 `D:\javatools\apache-maven-3.9.9\`
- 写一份精简的 `conf/settings.xml`：

```xml
<localRepository>D:\maven-repo</localRepository>
<mirrors>
  <mirror>
    <id>aliyun-public</id>
    <mirrorOf>central</mirrorOf>
    <url>https://maven.aliyun.com/repository/public</url>
  </mirror>
</mirrors>
<profiles>
  <profile>
    <id>aliyun</id>
    <repositories>...</repositories>
    <pluginRepositories>...</pluginRepositories>
  </profile>
</profiles>
<activeProfiles><activeProfile>aliyun</activeProfile></activeProfiles>
```

- PATH 把 3.9.9 放前面，确认 `mvn -v` 报 3.9.9 + JDK 17.0.4.1。

**结果**：`mvn clean install -DskipTests` 在 2 分 2 秒内 BUILD SUCCESS，9 个模块全过：

```
[INFO] hify ............................................... SUCCESS
[INFO] hify-common ........................................ SUCCESS
[INFO] hify-provider / hify-mcp / hify-agent / hify-knowledge / hify-workflow / hify-chat
[INFO] hify-app ........................................... SUCCESS
[INFO] BUILD SUCCESS
```

### 问题 2 · `JAVA_HOME` 仍指向 JDK 8

**症状**：第一次切到 JDK 17 后 `mvn -v` 仍显示 `Java version: 1.8.0_181`，因为 `JAVA_HOME=D:\javatools\Java\jdk1.8.0_181`。

**修复**：本次会话 export `JAVA_HOME=D:/javatools/Java/jdk-17.0.4.1` 后 mvn 才认 17。

**长期建议**：在 `.bashrc` 或 Windows 用户环境变量里把 `JAVA_HOME` 改到 17 路径，PATH 把 `%JAVA_HOME%\bin` 放最前。

### 问题 3 · K8s 中间件不是本地端口

**症状**：本地直接启 jar，`/api/v1/health` 返回 503，mysql/redis/pgvector 全部连接失败。

**修复**：用 `kubectl port-forward` 把 K8s service 映射到本地：

| K8s service | 本地端口 |
|---|---|
| `hify-mysql.hify.svc.cluster.local:3306` | `127.0.0.1:13306` |
| `hify-redis.hify.svc.cluster.local:6379` | `127.0.0.1:16379` |
| `hify-pgvector.hify.svc.cluster.local:5432` | `127.0.0.1:15432` |

启动时通过环境变量覆盖 `application.yml` 默认的 `${DB_HOST:localhost}` 等占位符。

### 问题 4 · MySQL 驱动 `characterEncoding=utf8mb4` 不识别

**症状**：

```
Caused by: java.io.UnsupportedEncodingException: utf8mb4
  at java.base/java.lang.String.lookupCharset(String.java:831)
```

`/api/v1/health` 返回：

```json
{"status":"DOWN","components":{"mysql":{"error":"Unsupported character encoding 'utf8mb4'","status":"DOWN"},"redis":"UP","pgvector":"UP"}}
```

**根因**：MySQL Connector/J 的 `characterEncoding` 参数是 **Java charset 名字**（如 `UTF-8`、`GBK`），而不是 MySQL collation 名字（`utf8mb4` 是后者）。`hify-app/src/main/resources/application.yml` 里写错了。

**修复**：把

```
characterEncoding=utf8mb4
```

改为

```
characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci
```

——前者声明 Java 端字符集，后者声明 MySQL 端 collation，与 MySQL 8.0 服务端默认一致。改完重打 jar。

**结果**：重 build + 重启后 `mysqladmin` ping OK，Hikari 连接池 `Added connection ... ConnectionImpl`，health 返回 `UP`。

### 问题 5 · 进程前台 detach 不稳

**症状**：第一次用 `&` 后台起 java，bash background task 退出时应用进程被杀。

**修复**：用 `nohup java -jar ... >/tmp/hify-app.log 2>&1 & disown`，日志落到文件、bash 退出不影响。同样处理三个 `kubectl port-forward` 进程。

---

## 4. 当前中间件连接参数

应用启动时通过环境变量传入：

```
DB_HOST=127.0.0.1 DB_PORT=13306 DB_NAME=hify DB_USERNAME=root DB_PASSWORD=hify_root_pw
REDIS_HOST=127.0.0.1 REDIS_PORT=16379 REDIS_PASSWORD=hify_redis_pw
PGVECTOR_HOST=127.0.0.1 PGVECTOR_PORT=15432 PGVECTOR_DB=hify PGVECTOR_USERNAME=hify PGVECTOR_PASSWORD=hify_pg_pw
```

对应 K8s secret：

- `hify-mysql-secret` → `MYSQL_ROOT_PASSWORD=hify_root_pw` / `MYSQL_USER=hify` / `MYSQL_PASSWORD=hify_pw`
- `hify-redis-secret` → `REDIS_PASSWORD=hify_redis_pw`
- `hify-pgvector-secret` → `POSTGRES_USER=hify` / `POSTGRES_PASSWORD=hify_pg_pw`

---

## 5. 进程与日志

| 资源 | PID / 路径 |
|---|---|
| Spring Boot 主进程 | `java.exe` PID 12896，监听 `0.0.0.0:8080` + `0.0.0.0:8081` |
| MySQL port-forward | `kubectl.exe` PID 11132 → `127.0.0.1:13306` |
| Redis port-forward | `kubectl.exe` PID 5560 → `127.0.0.1:16379` |
| pgvector port-forward | `kubectl.exe` PID 26512 → `127.0.0.1:15432` |
| 应用日志 | `/tmp/hify-app.log`（stdout/stderr 合并） |

停止应用：

```bash
taskkill //F //PID 12896
```

停止所有 port-forward：

```bash
taskkill //F //IM kubectl.exe
```

---

## 6. 已知遗留

1. **MySQL 仅 8 张表**：RAG / Workflow 相关 7 张表仍按 `docs/data-model.md` 末尾说明未在生产 DDL，需业务模块就绪后补。
2. **production secrets 明文**：本次为可复现性把密码写到 K8s Secret 明文 yml，生产前需迁移到 Vault 或 sealed-secrets。
3. **本地 13306/16379/15432 端口占用**：如需重启 pf，先 `taskkill //F //IM kubectl.exe`。
4. **MySQL root 密码不一致**：K8s secret 是 `hify_root_pw`，但 schema.sql 假定的 root 账号（env.template）是其他值；本次本地测试都用 K8s 设的值。生产部署前需统一。