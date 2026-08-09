# GitHub CI 跑通指南

> 适用：仓库 `kissyoudyb/tify`（默认分支 `main`），CI 配置 `.github/workflows/ci.yml`
> 状态：已推送（commit `a80ad30`），首次 push 触发后 unit job 跑通；integration job 等待 self-hosted runner 上线

## 1. 现状

| 部分 | 状态 |
|---|---|
| Workflow 文件 | 已 push 到 `.github/workflows/ci.yml` |
| unit job (B1-B2 单元测试) | 配置已就绪，GitHub-hosted runner，不需要本地端口 |
| integration job (B3-B6 集成测试) | 等待 self-hosted runner 接入 + 中间件可达 |

CI 流程：

```
Git push to main
  ↓
unit job (ubuntu-latest runner)
  ├─ checkout
  ├─ setup JDK 17
  ├─ cache Maven (key 基于 pom.xml hash)
  └─ mvn test in hify-provider/  → 期望 9 tests passed
  ↓
integration job (self-hosted runner)
  ├─ 同 setup + cache
  └─ mvn test in hify-app/  → 期望 13 tests passed
```

## 2. 前置条件

### 2.1 工具链

self-hosted runner 所在机器需要：
- JDK 17（推荐 Temurin）
- Maven 3.9.9（项目当前使用版本）
- kubectl + K8s 集群访问凭证（当前 `C:\Users\HP\Documents\jenkins-secret\kubeconfig-phadagent-wsl`）
- Git
- Bash / Linux shell

Windows 机器用 Git Bash 即可。WSL 节点 `desktop-cdh1or2` 已经是 Linux。

### 2.2 中间件端口可达

runner 上必须能验证：

```bash
mysql -h 127.0.0.1 -P 13306 -uroot -phify_root_pw hify -e 'SELECT 1'
redis-cli -h 127.0.0.1 -p 16379 PING
PGPASSWORD=hify_pg_pw psql -h 127.0.0.1 -p 15432 -U hify -d hify -c '\q'
```

三个端口 13306 / 16379 / 15432 是 K8s 中间件（namespace `hify`）通过 port-forward 暴露到 runner 本机。

## 3. 注册 self-hosted runner

### 3.1 GitHub UI 注册

1. 打开 https://github.com/kissyoudyb/tify/settings/actions/runners
2. 点击 **New self-hosted runner**
3. 选择 OS（Linux 或 Windows）
4. GitHub 显示一段 token + config.sh 命令

### 3.2 安装（以 WSL 节点 `desktop-cdh1or2` 为例）

```bash
# 1. 准备目录
mkdir -p ~/actions-runner && cd ~/actions-runner

# 2. 下载 runner（版本号与 GitHub UI 一致）
curl -o actions-runner-linux-x64-2.319.1.tar.gz -L \
  https://github.com/actions/runner/releases/download/v2.319.1/actions-runner-linux-x64-2.319.1.tar.gz
tar xzf actions-runner-linux-x64-2.319.1.tar.gz

# 3. 配置（粘 GitHub UI 给的 token）
./config.sh --url https://github.com/kissyoudyb/tify --token <TOKEN>
# 提示：
#   - labels 输入：self-hosted（默认），可加 linux,x64,hify
#   - runner group：默认 Default

# 4. 手动运行（测试）
./run.sh
# 期望：Listening for Jobs

# 5. 安装 systemd service（持久）
sudo ./svc.sh install
sudo ./svc.sh start

# 6. 验证
sudo systemctl status actions.runner.<name>.service
# 期望：active (running)
```

Windows runner 安装步骤类似，[参考 GitHub 官方文档](https://docs.github.com/en/actions/hosting-your-own-runners/managing-self-hosted-runners/adding-self-hosted-runners)。

## 4. 端口转发开机自启

runner 启动后必须把三个端口转发保持住。K8s pod 重启、runner 机器重启都要能恢复。

### 4.1 Linux (systemd) 推荐做法

建 `/etc/systemd/system/hify-pf-mysql.service`：

```ini
[Unit]
Description=Port-forward hify-mysql to 13306
After=network-online.target
Wants=network-online.target

[Service]
Environment=KUBECONFIG=/home/<user>/.kube/config
ExecStart=/usr/local/bin/kubectl port-forward -n hify svc/hify-mysql 13306:3306
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

类似建 `hify-pf-redis.service`（16379:6379）和 `hify-pf-pgvector.service`（15432:5432）。然后：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now hify-pf-mysql hify-pf-redis hify-pf-pgvector
sudo systemctl status hify-pf-*
# 期望：active (running)
```

### 4.2 临时方案（Linux）

runner 启动脚本里加：

```bash
nohup kubectl port-forward -n hify svc/hify-mysql    13306:3306 >/var/log/pf-mysql.log    2>&1 &
nohup kubectl port-forward -n hify svc/hify-redis    16379:6379 >/var/log/pf-redis.log    2>&1 &
nohup kubectl port-forward -n hify svc/hify-pgvector 15432:5432 >/var/log/pf-pgvector.log 2>&1 &
disown -a
```

### 4.3 Windows runner

用 `nssm` 或 Task Scheduler 把 3 个端口转发做成开机启动：

```powershell
kubectl port-forward -n hify svc/hify-mysql    13306:3306
kubectl port-forward -n hify svc/hify-redis    16379:6379
kubectl port-forward -n hify svc/hify-pgvector 15432:5432
```

在 Task Scheduler 里建 3 个 task，触发器选 "At startup"。

## 5. 验证 runner 配置

按下面清单一项项勾：

- [ ] `sudo systemctl status actions.runner.<name>.service` 显示 `active (running)`
- [ ] repo → Settings → Actions → Runners 列表里出现 runner，状态 `Idle`
- [ ] runner 上 `mvn -v` 输出 Maven 3.9.9 + Java 17
- [ ] runner 上 `kubectl get pod -n hify` 看到 3 个 pod Running
- [ ] runner 上 `nc -zv 127.0.0.1 13306 / 16379 / 15432` 三个都通
- [ ] runner 上 `mysql -h 127.0.0.1 -P 13306 -uroot -phify_root_pw hify -e 'SELECT 1'` 成功
- [ ] `~/.m2/repository` 目录存在（runner 用户的）

## 6. 触发 CI 跑通

```bash
cd D:/github-code/hify
git commit --allow-empty -m "ci: trigger first run"
git push origin main
```

打开 https://github.com/kissyoudyb/tify/actions 看 workflow run：

1. **unit job** —— GitHub-hosted runner，30 秒内出结果
   - 期望绿：`Tests run: 9 in hify-provider`
2. **integration job** —— self-hosted runner
   - 期望绿：`Tests run: 13 in hify-app`

## 7. 失败排查

### 7.1 integration job 一直 `queued`

原因：没有 self-hosted runner，或 runner label 与 `runs-on: self-hosted` 不匹配。

排查：
```bash
# 在 runner 机器上
sudo systemctl status actions.runner.*
# 看 GitHub repo Settings → Actions → Runners 列表
# 状态应为 Idle，不是 Offline
```

如果 runner 离线：
```bash
sudo ./svc.sh restart
sudo journalctl -u actions.runner.<name>.service -n 200
```

### 7.2 测试报 `Connection refused: 13306`

原因：port-forward 没起来 / K8s pod 重启。

排查：
```bash
kubectl get pod -n hify
ps aux | grep "kubectl port-forward"
ss -tlnp | grep 13306
```

修复：
```bash
sudo systemctl restart hify-pf-mysql hify-pf-redis hify-pf-pgvector
```

### 7.3 测试报 `Table 'hify.X' doesn't exist`

原因：集成测试启动时 DDL 缺失。检查 `hify-app/src/main/resources/db/schema.sql`。

## 8. CI 跑通后

1. 删掉 `pom.xml` 里的本地测试引用（如果有）
2. 任何 PR 都会自动跑 unit + integration
3. PR 必须集成测试绿才能 merge（如要加 branch protection）
4. 后续 P1/P2 测试（critical-paths.md 链路 4-8）按相同模板加新 Stage

## 9. 进阶优化

### 9.1 缓存粒度更细

当前 `key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}` 任意 pom 改动都失效。改成：

```yaml
key: ${{ runner.os }}-m2-${{ hashFiles('hify-provider/pom.xml', 'hify-app/pom.xml', 'hify-common/pom.xml', '**/pom.xml') }}
```

### 9.2 PR 跳过 integration

如果想 PR 阶段只跑 unit，加快反馈：

```yaml
integration:
  needs: unit
  if: github.event_name == 'push'  # 只在 push 触发，PR 跳过
  runs-on: self-hosted
  steps: [...]
```

### 9.3 矩阵化

如果以后想多 JDK / 多 OS 矩阵：

```yaml
strategy:
  matrix:
    java: [17, 21]
steps:
- uses: actions/setup-java@v4
  with:
    java-version: ${{ matrix.java }}
```

### 9.4 状态徽章

在 README.md 顶部加：

```markdown
![CI](https://github.com/kissyoudyb/tify/actions/workflows/ci.yml/badge.svg)
```

## 10. 关键路径

| 资源 | 路径 |
|---|---|
| Workflow 定义 | `.github/workflows/ci.yml` |
| Runner service | `actions.runner.<name>.service` |
| Runner 配置 | `~/.runner`（Linux）/ `.runner`（Windows） |
| K8s namespace | `hify` |
| K8s 中间件 | svc/hify-mysql, svc/hify-redis, svc/hify-pgvector |
| Port-forward | 13306 (MySQL), 16379 (Redis), 15432 (pgvector) |
| 测试 DB 凭证 | `hify-app/src/test/resources/application-test.yml` |
| Maven 缓存 | `~/.m2/repository` |

## 11. 完成检查清单

- [ ] repo Settings → Actions → Runner 创建
- [ ] runner 机器装好 JDK 17 + Maven 3.9.9
- [ ] runner 装好 `config.sh --token <token>`
- [ ] `sudo ./svc.sh install && sudo ./svc.sh start`
- [ ] `systemctl status actions.runner.<name>.service` 看见 `active (running)`
- [ ] runner 列出在 repo Settings → Actions → Runners 列表中（Idle）
- [ ] runner 上中间件端口都通（13306/16379/15432）
- [ ] runner 上 MySQL/Redis/pgvector 客户端都能登录
- [ ] port-forward 写成 systemd service / Task Scheduler 持久
- [ ] 触发 push → Actions 列表现新 run
- [ ] unit job 绿（9 tests）
- [ ] integration job 绿（13 tests）

全部勾上后 CI 跑通。

## 12. 联系方式

卡住先看 §7 排错。
- Runner 日志：`~/actions-runner/_diag/Worker_*.log`
- CI 工件：Actions UI → run → Artifacts → surefire-reports
- GitHub status: https://www.githubstatus.com

## 13. 常见误判

1. **runner 标签即使叫 `self-hosted`，GitHub 默认会匹配**。如果 workflow 写了 `runs-on: [self-hosted, linux]`，runner 没有 `linux` label 就不会拉。
2. **`work-dir` 不能改 `actions/runner` 目录**。runner 启动时 cd 到 `_work/<repo-name>/`，workflow 自己的 `working-directory` 才能生效。
3. **`secrets` 不能跨 fork 传**。如果以后开 GitHub fork 跑，secrets 不会给。
4. **unit 阶段和 integration 阶段用同一个 `mvn` 缓存 key**。如果只想 unit 缓存命中，要分 key。
5. **port-forward 进程意外退出**。`Restart=always` + `RestartSec=5` 配好后，进程崩了会自动重启。

## 14. 进阶：分流 test 报告

测试工件会自动上传。Artifacts 路径：
```
Actions → run → 底部 'Artifacts' → surefire-reports.zip
```

## 15. 清理

不用了想删除 runner：

```bash
sudo ./svc.sh stop
sudo ./svc.sh uninstall
./config.sh remove --token <token>
```

GitHub UI 同步反射状态变 Offline。

---

文档维护人：项目 Owner。最后更新：CI 初次接入。