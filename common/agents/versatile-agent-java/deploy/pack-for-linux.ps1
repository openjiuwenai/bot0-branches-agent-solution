param(
    [switch]$SkipBuild,
    [string]$OutputDirectory = ""
)

# 只构建并打包 adapter 自己；不构建 edp-agent、Redis、mock 或 deploy-all。
$ErrorActionPreference = "Stop"

$DeployDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Split-Path -Parent $DeployDir
$PomFile = Join-Path $ProjectDir "pom.xml"
$RuntimeExtPom = Join-Path (Split-Path -Parent (Split-Path -Parent $ProjectDir)) "agent-runtime-ext-java\pom.xml"
$DockerIgnore = Join-Path $ProjectDir ".dockerignore"

function Get-AdapterRuntimeJar {
    $jars = @(
        Get-ChildItem -LiteralPath (Join-Path $ProjectDir "target") -Filter "adapter-versatile-agent-java-*.jar" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch "-(sources|javadoc|tests|plain)\.jar$" }
    )
    if ($jars.Count -ne 1) {
        throw "期望 target 下恰好有一个 adapter 运行 jar，实际找到 $($jars.Count) 个。"
    }
    return $jars[0]
}

if (-not (Test-Path -LiteralPath $PomFile -PathType Leaf)) {
    throw "未找到 adapter pom.xml：$PomFile"
}
if (-not (Test-Path -LiteralPath $DockerIgnore -PathType Leaf)) {
    throw "未找到 adapter .dockerignore：$DockerIgnore"
}

if (-not $SkipBuild) {
    $maven = Get-Command mvn -ErrorAction Stop
    if (-not (Test-Path -LiteralPath $RuntimeExtPom -PathType Leaf)) {
        throw "未找到 adapter 所需的 runtime-ext pom：$RuntimeExtPom"
    }
    Write-Host "[1/2] 安装 adapter 所需 runtime-ext 依赖..."
    & $maven.Source -f $RuntimeExtPom clean install "-DskipTests"
    if ($LASTEXITCODE -ne 0) {
        throw "runtime-ext Maven 构建失败，退出码：$LASTEXITCODE"
    }
    Write-Host "[2/2] 构建 adapter jar..."
    & $maven.Source -f $PomFile clean package "-DskipTests"
    if ($LASTEXITCODE -ne 0) {
        throw "adapter Maven 构建失败，退出码：$LASTEXITCODE"
    }
}
else {
    Write-Host "已指定 -SkipBuild，复用 target 下现有 jar。"
}

$runtimeJar = Get-AdapterRuntimeJar

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = $ProjectDir
}
elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $ProjectDir $OutputDirectory
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$stamp = Get-Date -Format "yyyyMMdd-HHmmss-fff"
$bundleName = "adapter-versatile-agent-java"
$archivePath = Join-Path $OutputDirectory "adapter-versatile-agent-java-deploy-$stamp.tar.gz"
$stagingParent = Join-Path (Join-Path $ProjectDir "target") "adapter-deploy-staging-$stamp-$PID"
$bundleRoot = Join-Path $stagingParent $bundleName
$success = $false

try {
    New-Item -ItemType Directory -Force -Path (Join-Path $bundleRoot "target") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $bundleRoot "deploy") | Out-Null

    Copy-Item -LiteralPath $runtimeJar.FullName -Destination (Join-Path $bundleRoot "target")
    Copy-Item -LiteralPath $DockerIgnore -Destination $bundleRoot

    # 带上 Dockerfile、独立部署脚本、配置模板与手册；明确排除可能含真实地址的 deploy/.env。
    foreach ($item in Get-ChildItem -Force -LiteralPath $DeployDir) {
        if ($item.Name -eq ".env") {
            continue
        }
        Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $bundleRoot "deploy") -Recurse -Force
    }

    $tar = Get-Command tar -ErrorAction Stop
    Write-Host "生成 Linux 部署包：$archivePath"
    & $tar.Source -czf $archivePath -C $stagingParent $bundleName
    if ($LASTEXITCODE -ne 0) {
        throw "tar 打包失败，退出码：$LASTEXITCODE"
    }
    $success = $true
}
finally {
    if (Test-Path -LiteralPath $stagingParent) {
        Remove-Item -LiteralPath $stagingParent -Recurse -Force
    }
    if (-not $success -and (Test-Path -LiteralPath $archivePath)) {
        Remove-Item -LiteralPath $archivePath -Force
    }
}

Write-Host ""
Write-Host "[完成] $archivePath"
Write-Host "部署包只包含 adapter 的 jar、.dockerignore 和 deploy 资源。"
Write-Host "上传 Linux 后解压，进入 $bundleName，再阅读 deploy/README.md。"
