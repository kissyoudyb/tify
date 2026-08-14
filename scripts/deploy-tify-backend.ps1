<#
.SYNOPSIS
    一键打包 → 构建镜像 → 推送到 Harbor → 部署到 K8s（tify 命名空间）

.DESCRIPTION
    本脚本面向 Windows PowerShell 环境，按顺序完成：
      1. Maven 打包 tify-app（-DskipTests，输出 target/*.jar）
      2. Docker 构建 tify-backend 镜像（参考项目根 Dockerfile）
      3. 登录 Harbor（admin / Harbor12345）
      4. 推送镜像到 Harbor 项目 phadagent
      5. 用指定 kubeconfig 把 K8s manifests apply 到 tify 命名空间
         （部署对象：tify-backend；命名空间不存在则自动创建）

    止血版本说明：
      本脚本会在打包前把 application.yml 的 tify.health-check.enabled 置为 false，
      并在脚本结束后回滚到 git 原始值。如果你已经手动改过，请自行处理或忽略该步骤。

.PARAMETER ImageTag
    镜像 tag，默认 git 短 SHA + 本地时间，例：rename-tify-a1b2c3d-20260813-1530

.PARAMETER SkipBuild
    跳过 Maven 打包，直接用已有的 target jar 构建镜像

.PARAMETER SkipPush
    只构建镜像，不推送 Harbor

.PARAMETER SkipDeploy
    只构建并推送镜像，不 apply K8s

.PARAMETER HarborHost
    Harbor 地址，默认 registry.tjzs.com

.PARAMETER HarborProject
    Harbor 项目名，默认 phadagent

.PARAMETER ImageName
    镜像名，默认 tify-backend

.PARAMETER Kubeconfig
    kubeconfig 文件绝对路径，必须存在

.PARAMETER Namespace
    K8s 命名空间，默认 tify

.PARAMETER ContainerTool
    容器工具，默认 podman（Windows 本地开发环境）。可选 docker / podman

.PARAMETER JavaHome
    覆盖 JAVA_HOME，默认自动从环境变量 / 常见路径探测

.PARAMETER MavenHome
    覆盖 M2_HOME，默认自动从环境变量 / 常见路径探测

.EXAMPLE
    .\scripts\deploy-tify-backend.ps1 `
        -Kubeconfig "D:\tianjisuan\05-技术方案\开发环境部署\kubeconfig-phadagent-dev" `
        -Namespace tify

.NOTES
    前置依赖：
      - PowerShell 7（pwsh）
      - JDK 17（自动从 $env:JAVA_HOME / D:\javatools\Java\jdk-17* 探测）
      - Maven 3.9+（自动从 $env:M2_HOME / D:\javatools\apache-maven-3.9* 探测）
      - Podman（Windows 推荐 podman 5.x；也可改用 docker）
      - kubectl，且能访问目标 K8s
#>
[CmdletBinding()]
param(
    [string]$ImageTag = "",
    [switch]$SkipBuild,
    [switch]$SkipPush,
    [switch]$SkipDeploy,
    [string]$HarborHost = "registry.tjzs.com",
    [string]$HarborProject = "phadagent",
    [string]$ImageName = "tify-backend",
    [string]$HarborUser = "admin",
    [string]$HarborPassword = "Harbor12345",
    [string]$Kubeconfig = "D:\tianjisuan\05-技术方案\开发环境部署\kubeconfig-phadagent-dev",
    [string]$Namespace = "tify",
    [string]$ContainerTool = "podman",
    [string]$JavaHome  = "D:\javatools\Java\jdk-17.0.4.1",
    [string]$MavenHome = "D:\javatools\apache-maven-3.9.9",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$ProgressPreference   = "Continue"

# ── 强制 UTF-8 输出（避免中文抛异常的 GBK 乱码）─────────────
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
    $PSDefaultParameterValues['Out-File:Encoding'] = 'utf8'
} catch { }

# ── 路径与常量 ──────────────────────────────────────────────
$ScriptDir      = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot    = Resolve-Path (Join-Path $ScriptDir "..")
$AppModuleDir   = Join-Path $ProjectRoot "tify-app"
$Dockerfile     = Join-Path $ProjectRoot "Dockerfile.local"
$BackendYaml    = Join-Path $ProjectRoot "deploy\k8s\backend-deployment.yml"
$BackendSvcYaml = Join-Path $ProjectRoot "deploy\k8s\backend-service.yml"
$BackendCmYaml  = Join-Path $ProjectRoot "deploy\k8s\backend-configmap.yml"
$AppYml         = Join-Path $AppModuleDir  "src\main\resources\application.yml"
$BuildJarPath   = ""   # 由打包阶段填充，或通过 -SkipBuild + 手工放置

if (-not $ImageTag) {
    $sha = (& git -C $ProjectRoot rev-parse --short HEAD 2>$null)
    if (-not $sha) { $sha = "local" }
    $stamp = Get-Date -Format "yyyyMMdd-HHmm"
    $ImageTag = "rename-tify-$sha-$stamp"
}
$FullImage = "${HarborHost}/${HarborProject}/${ImageName}:${ImageTag}"

# ── JDK / Maven 写死路径（直接覆盖，不探测环境变量）─────────────────
$JavaHome  = 'D:\javatools\Java\jdk-17.0.4.1'
$MavenHome = 'D:\javatools\apache-maven-3.9.9'

# ── 颜色输出 ────────────────────────────────────────────────
function Write-Phase($msg) {
    Write-Host ""
    Write-Host "==> $msg" -ForegroundColor Cyan
}
function Write-Ok($msg)   { Write-Host "    [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "    [WARN] $msg" -ForegroundColor Yellow }
function Write-Err($msg)  { Write-Host "    [ERR]  $msg" -ForegroundColor Red }

# ── JDK / Maven 写死路径覆盖（依赖 Write-Ok，必须在颜色函数之后）──
if ($JavaHome)  {
    Remove-Item Env:\JAVA_HOME  -ErrorAction SilentlyContinue
    Remove-Item Env:\EXE4J_JAVA_HOME -ErrorAction SilentlyContinue
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$JavaHome\bin;$env:Path"
}
if ($MavenHome) {
    Remove-Item Env:\M2_HOME    -ErrorAction SilentlyContinue
    Remove-Item Env:\MAVEN_HOME -ErrorAction SilentlyContinue
    $env:M2_HOME = $MavenHome
    $env:Path = "$MavenHome\bin;$env:Path"
}
Write-Ok "JAVA_HOME = $JavaHome"
Write-Ok "M2_HOME   = $MavenHome"

# 把探测结果写到日志（方便排错，绕过控制台编码问题）
$LogFile = Join-Path $ScriptDir ".deploy-tify-backend.log"
"$(Get-Date -Format 'o') JAVA_HOME=$JavaHome M2_HOME=$MavenHome" | Out-File -Append $LogFile -Encoding utf8

# ── 工具函数 ────────────────────────────────────────────────
function Assert-Command($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        Write-Err "未检测到 $name，请先安装并加入 PATH"
        throw "Missing dependency: $name"
    }
}

function Invoke-Step($description, [scriptblock]$action) {
    Write-Phase $description
    if ($DryRun) {
        Write-Host "    [DRY-RUN] 跳过实际执行" -ForegroundColor Magenta
        return
    }
    $LASTEXITCODE = 0
    & $action
    if ($LASTEXITCODE -ne 0) {
        throw "步骤失败：$description (exit=$LASTEXITCODE)"
    }
}

# ── 前置检查（直接展开，不用 Invoke-Step，保留原始错误）────
Write-Phase "前置依赖检查"
foreach ($name in @("git","mvn",$ContainerTool)) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "[$name] 命令不存在，请确认已安装并加入 PATH"
    }
    Write-Ok "$name 已找到"
}
if (-not $SkipDeploy) {
    if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
        throw "[kubectl] 命令不存在，请确认已安装并加入 PATH"
    }
    Write-Ok "kubectl 已找到"
    if (-not (Test-Path $Kubeconfig)) { throw "找不到 kubeconfig：$Kubeconfig" }
    Write-Ok "kubeconfig 已找到"
}
if (-not $JavaHome)  { throw "未探测到 JDK 17，请 -JavaHome 或设置 JAVA_HOME" }
if (-not $MavenHome) { throw "未探测到 Maven 3.9，请 -MavenHome 或设置 M2_HOME" }
foreach ($f in @(
    @{Path=$Dockerfile;     Name="Dockerfile.local"},
    @{Path=$AppYml;         Name="application.yml"},
    @{Path=$BackendYaml;    Name="backend-deployment.yml"},
    @{Path=$BackendSvcYaml; Name="backend-service.yml"},
    @{Path=$BackendCmYaml;  Name="backend-configmap.yml"}
)) {
    if (-not (Test-Path $f.Path)) { throw "找不到 $($f.Name)：$($f.Path)" }
    Write-Ok "$($f.Name) 已找到"
}

# ── 1. Maven 打包 ───────────────────────────────────────────
if (-not $SkipBuild) {
    Invoke-Step "Maven 打包 tify-app（-DskipTests）" {
        Push-Location $ProjectRoot
        try {
            & mvn -q -pl tify-app -am -DskipTests package
        } finally {
            Pop-Location
        }
        $jar = Get-ChildItem -Path (Join-Path $AppModuleDir "target") -Filter "tify-app-*.jar" -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notmatch "\.original$" } | Select-Object -First 1
        if (-not $jar) { throw "未找到 tify-app 打包产物" }
        $script:BuildJarPath = $jar.FullName
        Write-Ok "产物：$($jar.FullName)  ($([math]::Round($jar.Length/1MB,2)) MB)"
    }
}

# ── 2. 写入止血 yml（幂等）───────────────────────────────────
$ymlBackup = "$AppYml.bak.deploy"
Invoke-Step "注入 tify.health-check.enabled=false（止血）" {
    $content = Get-Content $AppYml -Raw
    if ($content -notmatch "tify:\s*\r?\n\s*health-check:\s*\r?\n\s*enabled:\s*false") {
        Copy-Item $AppYml $ymlBackup -Force
        # 注入到 mybatis-plus 之前
        $patch = @"
# ── Tify 自定义配置（deploy 脚本注入）────────────────
tify:
  health-check:
    enabled: false

"@
        $content = $content -replace "(mybatis-plus:\s*)", ($patch + '$1')
        Set-Content -Path $AppYml -Value $content -NoNewline
        Write-Ok "已注入；备份：$ymlBackup"
    } else {
        Write-Ok "已存在 health-check.enabled=false，跳过"
    }
}

# ── 2.5 自动发现 jar（-SkipBuild 时）────────────────────────
if ($SkipBuild) {
    $jar = Get-ChildItem -Path (Join-Path $AppModuleDir "target") -Filter "tify-app-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch "\.original$" } | Select-Object -First 1
    if (-not $jar) { throw "-SkipBuild=true 但未找到 tify-app/target/tify-app-*.jar，请先 mvn package" }
    $script:BuildJarPath = $jar.FullName
    Write-Ok "复用现有 jar：$($jar.FullName)"
}

# ── 3. Docker 构建（复用本地 jar）────────────────────────────
Invoke-Step "Docker 构建镜像 $FullImage（Dockerfile.local）" {
    if (-not (Test-Path $BuildJarPath)) {
        throw "找不到本地 jar：$BuildJarPath（请先执行 mvn package，或使用 -SkipBuild=false）"
    }
    $jarRel = $BuildJarPath.Substring($ProjectRoot.Path.Length).TrimStart('\','/') -replace '\\','/'
    Write-Ok "使用 jar：$BuildJarPath"
    & $ContainerTool build -t $FullImage -f $Dockerfile `
        --build-arg "JAR_FILE=$jarRel" `
        --tls-verify=false `
        $ProjectRoot
}

# ── 4. 登录 Harbor + 推送 ───────────────────────────────────
if (-not $SkipPush) {
    Invoke-Step "登录 Harbor $HarborHost（容器工具：$ContainerTool）" {
        $env:CRYPTOGRAPHY_OPENSSL_NO_LEGACY = "1"
        $pw = $HarborPassword | ConvertTo-SecureString -AsPlainText -Force
        $cred = New-Object System.Management.Automation.PSCredential($HarborUser, $pw)
        # 容器工具 login 不会回显密码
        $cred.GetNetworkCredential().Password | & $ContainerTool login --tls-verify=false $HarborHost -u $HarborUser --password-stdin | Out-Host
    }
    Invoke-Step "推送镜像 $FullImage" {
        & $ContainerTool push --tls-verify=false $FullImage
    }
} else {
    Write-Warn "已跳过 Harbor 推送（-SkipPush）"
}

# ── 5. K8s 部署 ─────────────────────────────────────────────
if (-not $SkipDeploy) {
    Invoke-Step "确保命名空间 $Namespace 存在" {
        $nsYaml = & kubectl --kubeconfig $Kubeconfig create namespace $Namespace --dry-run=client -o yaml
        $nsYaml | & kubectl --kubeconfig $Kubeconfig apply -f - | Out-Host
    }
    Invoke-Step "应用 ConfigMap / Service" {
        # 替换 namespace（manifest 默认 tify，可参数化为 $Namespace）
        foreach ($f in @($BackendCmYaml, $BackendSvcYaml)) {
            $tmp = [IO.Path]::GetTempFileName()
            (Get-Content $f -Raw) -replace "namespace:\s*tify", "namespace: $Namespace" | Set-Content $tmp
            & kubectl --kubeconfig $Kubeconfig apply -f $tmp | Out-Host
            Remove-Item $tmp
        }
    }
    Invoke-Step "应用 Deployment（image=$FullImage）" {
        $tmp = [IO.Path]::GetTempFileName()
        $dep = Get-Content $BackendYaml -Raw
        $dep = $dep -replace "namespace:\s*tify", "namespace: $Namespace"
        # 替换镜像 tag（保留 registry+project+name）
        $dep = $dep -replace "(registry\.tjzs\.com/phadagent/tify-backend):[^\s`"]+", "`$1:${ImageTag}"
        Set-Content $tmp -Value $dep -NoNewline
        & kubectl --kubeconfig $Kubeconfig apply -f $tmp | Out-Host
        Remove-Item $tmp
    }
    Invoke-Step "等待滚动更新完成" {
        & kubectl --kubeconfig $Kubeconfig -n $Namespace rollout status deployment/tify-backend --timeout=180s | Out-Host
    }
    Invoke-Step "查看 Pod 状态" {
        & kubectl --kubeconfig $Kubeconfig -n $Namespace get pods -l app=tify-backend -o wide
    }
} else {
    Write-Warn "已跳过 K8s 部署（-SkipDeploy）"
}

# ── 6. 回滚 application.yml ────────────────────────────────
Invoke-Step "回滚 application.yml（恢复 git 原状）" {
    if (Test-Path $ymlBackup) {
        Copy-Item $ymlBackup $AppYml -Force
        Remove-Item $ymlBackup
        Write-Ok "已恢复；如需保留止血 yml，请手动取消恢复"
    } else {
        Write-Ok "无需回滚（备份不存在）"
    }
}

Write-Host ""
Write-Host "==> 全部完成。镜像：$FullImage" -ForegroundColor Green
if (-not $SkipDeploy) {
    Write-Host "    查看日志：kubectl --kubeconfig `"$Kubeconfig`" -n $Namespace logs -f deployment/tify-backend" -ForegroundColor Gray
}