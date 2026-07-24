<#
.SYNOPSIS
  只构建并打包 edp-agent-java 的 Linux Docker 部署上下文。

.DESCRIPTION
  产物只包含 EDP 自己需要的 jar、governance、scenarios/wealth-demo 和 deploy 资源；
  不包含 adapter、mock、deploy-all，也绝不打包 deploy/.env 中的密钥。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File common\agents\edp-agent-java\deploy\pack-for-linux.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File common\agents\edp-agent-java\deploy\pack-for-linux.ps1 -SkipBuild
#>
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string]$OutputDirectory = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = $PSScriptRoot
$ServiceRoot = Split-Path -Parent $ScriptDir
$AgentRoot = Split-Path -Parent $ServiceRoot
$CommonRoot = Split-Path -Parent $AgentRoot
$RepoRoot = Split-Path -Parent $CommonRoot

$EdpPom = Join-Path $ServiceRoot "pom.xml"
$RuntimeExtPom = Join-Path $CommonRoot "agent-runtime-ext-java\pom.xml"
$JarDirectory = Join-Path $ServiceRoot "engine\target"
$GovernanceSource = Join-Path $ServiceRoot "engine\src\main\resources\governance"
$ScenarioSource = Join-Path $ServiceRoot "scenarios\wealth-demo"

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
        throw "未找到 mvn，请安装 Maven 并加入 PATH。"
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "未找到 java，请安装 JDK 17+ 并加入 PATH。"
    }
    if (-not (Test-Path -LiteralPath $RuntimeExtPom)) {
        throw "缺少 agent-runtime-ext-java pom：$RuntimeExtPom"
    }

    Write-Host "[1/2] 安装 EDP 所需 runtime-ext 依赖..." -ForegroundColor Yellow
    & mvn -f $RuntimeExtPom clean install -DskipTests
    Assert-LastExitCode "runtime-ext Maven 构建"

    Write-Host "[2/2] 构建 edp-agent-java..." -ForegroundColor Yellow
    & mvn -f $EdpPom clean package -DskipTests
    Assert-LastExitCode "edp-agent-java Maven 构建"
}

$JarCandidates = @(Get-ChildItem -LiteralPath $JarDirectory -Filter "edp-agent-engine-*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "-(sources|javadoc|tests|plain)\.jar$" } |
    Sort-Object LastWriteTime -Descending)
if ($JarCandidates.Count -eq 0) {
    throw "未找到 $JarDirectory\edp-agent-engine-*.jar。请先构建，或去掉 -SkipBuild。"
}
$Jar = $JarCandidates[0]
if ($JarCandidates.Count -gt 1) {
    Write-Warning "检测到多个匹配 jar，部署包只收入最新文件：$($Jar.Name)"
}
if (-not (Test-Path -LiteralPath $GovernanceSource -PathType Container)) {
    throw "缺少 governance 目录：$GovernanceSource"
}
if (-not (Test-Path -LiteralPath $ScenarioSource -PathType Container)) {
    throw "缺少场景目录：$ScenarioSource"
}
if (-not (Get-Command tar -ErrorAction SilentlyContinue)) {
    throw "未找到 tar。Windows 10 1803+ 通常已内置 tar。"
}

$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$BundleName = "edp-agent-java"
$ArchiveName = "edp-agent-java-deploy-$Timestamp.tar.gz"
$ArchivePath = Join-Path $OutputDirectory $ArchiveName
if (Test-Path -LiteralPath $ArchivePath) {
    throw "输出文件已存在：$ArchivePath"
}

$StageRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("edp-agent-pack-" + [guid]::NewGuid().ToString("N"))
$BundleRoot = Join-Path $StageRoot $BundleName

try {
    New-Item -ItemType Directory -Force -Path $BundleRoot | Out-Null

    # deploy 全量复制，但明确排除可能含密钥的 deploy/.env。
    $DeployDestination = Join-Path $BundleRoot "deploy"
    New-Item -ItemType Directory -Force -Path $DeployDestination | Out-Null
    Get-ChildItem -LiteralPath $ScriptDir -Force |
        Where-Object { $_.Name -ne ".env" } |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $DeployDestination -Recurse -Force
        }

    $JarDestination = Join-Path $BundleRoot "engine\target"
    New-Item -ItemType Directory -Force -Path $JarDestination | Out-Null
    Copy-Item -LiteralPath $Jar.FullName -Destination $JarDestination -Force

    $ResourcesDestination = Join-Path $BundleRoot "engine\src\main\resources"
    New-Item -ItemType Directory -Force -Path $ResourcesDestination | Out-Null
    Copy-Item -LiteralPath $GovernanceSource -Destination $ResourcesDestination -Recurse -Force

    $ScenariosDestination = Join-Path $BundleRoot "scenarios"
    New-Item -ItemType Directory -Force -Path $ScenariosDestination | Out-Null
    Copy-Item -LiteralPath $ScenarioSource -Destination $ScenariosDestination -Recurse -Force

    # 部署包本身已经是最小上下文。生成安全的最小 ignore，避免源码 .dockerignore
    # 排除 deploy/ 父目录后无法反向包含 requirements/config 的问题。
    $DockerIgnorePath = Join-Path $BundleRoot ".dockerignore"
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
    & tar -czf $ArchivePath -C $StageRoot $BundleName
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
Write-Host "EDP 独立部署包已生成：$ArchivePath ($SizeMB MB)" -ForegroundColor Green
Write-Host "上传 Linux 后执行："
Write-Host "  tar xzf $ArchiveName"
Write-Host "  cd edp-agent-java"
Write-Host "  cp deploy/.env.example deploy/.env && chmod 600 deploy/.env"
Write-Host "  vi deploy/.env"
Write-Host "  bash deploy/deploy.sh"
