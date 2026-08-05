<#
.SYNOPSIS
  只构建并打包 agent-client-demo 的 Linux Docker 部署上下文。

.DESCRIPTION
  产物只包含 agent-client-demo 自己需要的源码、SDK 源码与 deploy 资源；
  不包含 deploy-all、其他 example 或 deploy/.env 中的密钥。
  适合在 Windows 上构建后打 tar 包上传 Linux 部署（纯 Linux 流程无需此脚本，
  直接在 Linux 代码仓内 bash deploy/build-jar.sh 即可）。

  打包后的目录结构（解压后进入 agent-client-demo 目录即可部署）：
    agent-client-demo/
      deploy/                 (全量 deploy 资源，排除 .env)
      mock-gateway/
      verification-app/
      pom.xml
      ../agent-client/agent-client-sdk-for-jvm/   (SDK 源码，通过相对路径纳入 reactor)

  注意：docker build 上下文需要 common/ 目录。pack 产物保持了 common/ 下的相对结构，
  解压后在 common/ 层执行 docker build 即可。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File common\example\agent-client-demo\deploy\pack-for-linux.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File common\example\agent-client-demo\deploy\pack-for-linux.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string]$OutputDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = $PSScriptRoot
$ServiceRoot = Split-Path -Parent $ScriptDir          # agent-client-demo/
$ExampleRoot = Split-Path -Parent $ServiceRoot         # example/
$CommonRoot = Split-Path -Parent $ExampleRoot          # common/
$RepoRoot = Split-Path -Parent $CommonRoot             # agent-solution/

$DemoPom = Join-Path $ServiceRoot "pom.xml"
$SdkRoot = Join-Path $CommonRoot "agent-client\agent-client-sdk-for-jvm"

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = $RepoRoot
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)

function Assert-LastExitCode {
    param([Parameter(Mandatory = $true)][string]$Action)
    if ($LASTEXITCODE -ne 0) {
        throw "$Action 失败，退出码：$LASTEXITCODE"
    }
}

if (-not $SkipBuild) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw "未找到 mvn，请安装 Maven 3.9+ 并加入 PATH。"
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "未找到 java，请安装 JDK 17+ 并加入 PATH。"
    }
    if (-not (Test-Path -LiteralPath $DemoPom)) {
        throw "缺少 agent-client-demo 父 pom：$DemoPom"
    }
    if (-not (Test-Path -LiteralPath $SdkRoot -PathType Container)) {
        throw "缺少 SDK 模块目录：$SdkRoot"
    }

    Write-Host "[1/1] 构建 agent-client-demo 多模块 reactor（SDK + mock-gateway + verification-app）..." -ForegroundColor Yellow
    & mvn -f $DemoPom clean package -DskipTests
    Assert-LastExitCode "agent-client-demo Maven 构建"
}

# 校验产物存在
$SdkJar = Join-Path $SdkRoot "target\agent-client-sdk-for-jvm.jar"
$MockJar = Join-Path $ServiceRoot "mock-gateway\target\mock-gateway.jar"
$VerifyJar = Join-Path $ServiceRoot "verification-app\target\verification-app.jar"
foreach ($jar in @($SdkJar, $MockJar, $VerifyJar)) {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "未找到构建产物：$jar。请先构建，或去掉 -SkipBuild。"
    }
}

if (-not (Get-Command tar -ErrorAction SilentlyContinue)) {
    throw "未找到 tar。Windows 10 1803+ 通常已内置 tar。"
}

$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BundleName = "agent-client-demo"
$ArchiveName = "agent-client-demo-deploy-$Timestamp.tar.gz"
$ArchivePath = Join-Path $OutputDirectory $ArchiveName
if (Test-Path -LiteralPath $ArchivePath) {
    throw "输出文件已存在：$ArchivePath"
}

# 暂存目录结构需要保持 common/ 下的相对布局，以便 docker build 上下文 = 解压后的 common 目录。
# 因此 stage 根模拟 common/，下面放 example/agent-client-demo/ 与 agent-client/。
$StageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("acd-pack-" + [guid]::NewGuid().ToString("N"))
$StageCommon = Join-Path $StageRoot "common"

try {
    # 复制 agent-client-demo 全量（排除 deploy/.env 密钥）
    $DemoDestination = Join-Path $StageCommon "example\agent-client-demo"
    New-Item -ItemType Directory -Force -Path $DemoDestination | Out-Null
    Get-ChildItem -LiteralPath $ServiceRoot -Force |
        Where-Object { $_.Name -ne ".env" } |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $DemoDestination -Recurse -Force
        }

    # 复制 SDK 模块（含已构建的 target/，Linux 上可直接用本地 Maven 缓存或重新构建）
    $SdkDestination = Join-Path $StageCommon "agent-client\agent-client-sdk-for-jvm"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $SdkDestination) | Out-Null
    Copy-Item -LiteralPath $SdkRoot -Destination $SdkDestination -Recurse -Force

    # 生成安全的最小 .dockerignore
    $DockerIgnorePath = Join-Path $StageCommon ".dockerignore"
    $DockerIgnoreLines = @(
        ".git/",
        "deploy/.env",
        "*.tar.gz",
        "**/logs/"
    )
    [System.IO.File]::WriteAllLines(
        $DockerIgnorePath,
        $DockerIgnoreLines,
        (New-Object System.Text.UTF8Encoding($false))
    )

    Write-Host "正在生成 $ArchivePath ..." -ForegroundColor Yellow
    & tar -czf $ArchivePath -C $StageRoot common
    Assert-LastExitCode "tar 打包"
}
finally {
    if (Test-Path -LiteralPath $StageRoot) {
        Remove-Item -LiteralPath $StageRoot -Recurse -Force
    }
}

$Archive = Get-Item -LiteralPath $ArchivePath
$SizeMB = [math]::Round($Archive.Length / 1MB, 1)
Write-Host ""
Write-Host "agent-client-demo 独立部署包已生成：$ArchivePath ($SizeMB MB)" -ForegroundColor Green
Write-Host "上传 Linux 后执行："
Write-Host "  tar xzf $ArchiveName"
Write-Host "  cd common/example/agent-client-demo"
Write-Host "  cp deploy/.env.example deploy/.env && chmod 600 deploy/.env"
Write-Host "  vi deploy/.env"
Write-Host "  bash deploy/deploy.sh"
