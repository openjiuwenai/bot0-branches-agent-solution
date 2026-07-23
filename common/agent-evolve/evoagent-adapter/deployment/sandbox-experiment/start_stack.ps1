# Start redis + jiuwenbox + edpagent + adapter on evo-sandbox-net
# Usage (PowerShell):
#   $env:PLANNING_AGENT_MODEL_API_KEY = "sk-..."
#   .\deployment\sandbox-experiment\start_stack.ps1

$ErrorActionPreference = "Continue"

$Exp = Split-Path -Parent $MyInvocation.MyCommand.Path
$AdapterRoot = Resolve-Path (Join-Path $Exp "../..")
$EdpDeploy = Resolve-Path (Join-Path $AdapterRoot "../EDPAgent/deployment")

$ApiKey = $env:PLANNING_AGENT_MODEL_API_KEY
if (-not $ApiKey) {
  Write-Error "Set PLANNING_AGENT_MODEL_API_KEY before starting (do not commit the key)."
  exit 1
}

$ApiBase = if ($env:PLANNING_AGENT_MODEL_BASE_URL) { $env:PLANNING_AGENT_MODEL_BASE_URL } else { "https://api.example-llm.com/v1" }
$ModelName = if ($env:PLANNING_AGENT_MODEL_NAME) { $env:PLANNING_AGENT_MODEL_NAME } else { "example-model" }

New-Item -ItemType Directory -Force -Path (Join-Path $Exp "data") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Exp "skills-meta/edp_agent") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Exp "logs/edp_agent") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $EdpDeploy "logs/a2a_service") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $EdpDeploy "logs/versatile_adapter") | Out-Null

$HostLogRoot = Join-Path $Exp "logs"
$HostEdpLog = Join-Path $HostLogRoot "edp_agent"

$netExists = docker network ls --format "{{.Name}}" | Where-Object { $_ -eq "evo-sandbox-net" }
if (-not $netExists) {
  docker network create evo-sandbox-net | Out-Null
  Write-Host "created network evo-sandbox-net"
}

function Remove-Container([string]$name) {
  cmd /c "docker rm -f $name >nul 2>&1"
}

Write-Host "== redis =="
Remove-Container redis
docker run -d --name redis --network evo-sandbox-net --restart unless-stopped redis:7-alpine
if ($LASTEXITCODE -ne 0) { throw "failed to start redis" }

Write-Host "== jiuwenbox =="
Remove-Container jiuwenbox
docker run -d --name jiuwenbox --privileged --network evo-sandbox-net --restart unless-stopped `
  --sysctl net.ipv4.ip_forward=1 `
  --cap-add=SYS_ADMIN --cap-add=NET_ADMIN `
  --security-opt seccomp=unconfined --security-opt apparmor=unconfined `
  --cgroupns=host -v "/sys/fs/cgroup:/sys/fs/cgroup:rw" `
  -p 8321:8321 -p 8322:8322 `
  -e JIUWENBOX_LISTEN=http://0.0.0.0:8321 `
  jiuwenbox:latest
if ($LASTEXITCODE -ne 0) { throw "failed to start jiuwenbox" }

Write-Host "== rebuild agent-adapter =="
Push-Location $AdapterRoot
docker build -t agent-adapter:latest -f deployment/Dockerfile .
if ($LASTEXITCODE -ne 0) { Pop-Location; throw "adapter build failed" }
Pop-Location

Write-Host "== edpagent =="
Remove-Container edpagent
docker run -d --name edpagent --init --network evo-sandbox-net --restart unless-stopped `
  --add-host "host.docker.internal:host-gateway" `
  -p 18001:8090 -p 18091:8091 `
  --env-file (Join-Path $Exp "edp_va.env") `
  --env-file (Join-Path $Exp "edp_a2a.env") `
  -e "PLANNING_AGENT_MODEL_API_KEY=$ApiKey" `
  -e "PLANNING_AGENT_MODEL_BASE_URL=$ApiBase" `
  -e "PLANNING_AGENT_MODEL_NAME=$ModelName" `
  -e "SANDBOX_URL=http://jiuwenbox:8321" `
  -e "SKILL_TARGET_PATH=/tmp" `
  -e "REDIS_HOST=redis" `
  -e "LOG_DIR=/app/a2a_service/logs" `
  -v "${HostEdpLog}:/app/a2a_service/logs" `
  -v "$(Join-Path $EdpDeploy 'logs/versatile_adapter'):/app/versatile_adapter/logs" `
  edpagent:local
if ($LASTEXITCODE -ne 0) { throw "failed to start edpagent" }

Write-Host "== adapter =="
Remove-Container evo-adapter
docker run -d --name evo-adapter --network evo-sandbox-net --restart unless-stopped `
  -p 18900:8900 `
  -e ADAPTER_HOST=0.0.0.0 `
  -e ADAPTER_PORT=8900 `
  -e ADAPTER_POLL_INTERVAL=5 `
  -e ADAPTER_START_FROM=head `
  -e ADAPTER_LOG_DIR=/data/logs `
  -e ADAPTER_SKILLS_ROOT=/data/skills `
  -v "$(Join-Path $Exp 'data'):/app/data" `
  -v "$(Join-Path $Exp 'agent_adapter_config.yaml'):/app/agent_adapter_config.yaml" `
  -v "${HostLogRoot}:/data/logs:ro" `
  -v "$(Join-Path $Exp 'skills-meta'):/data/skills" `
  agent-adapter:latest
if ($LASTEXITCODE -ne 0) { throw "failed to start adapter" }

Write-Host "Waiting for health..."
$ok = $false
for ($i = 0; $i -lt 90; $i++) {
  try {
    $h1 = Invoke-WebRequest -Uri http://127.0.0.1:8321/health -UseBasicParsing -TimeoutSec 3
    $h2 = Invoke-WebRequest -Uri http://127.0.0.1:18001/health -UseBasicParsing -TimeoutSec 3
    $h3 = Invoke-WebRequest -Uri http://127.0.0.1:18900/health -UseBasicParsing -TimeoutSec 3
    if ($h1.StatusCode -eq 200 -and $h2.StatusCode -eq 200 -and $h3.StatusCode -eq 200) {
      $ok = $true
      break
    }
  } catch {
    # keep waiting
  }
  Write-Host -NoNewline "."
  Start-Sleep -Seconds 2
}
Write-Host ""
if (-not $ok) {
  Write-Host "Health wait timed out. Dumping status:"
  docker ps -a --filter name=redis --filter name=jiuwenbox --filter name=edpagent --filter name=evo-adapter
  Write-Host "---- edpagent logs ----"
  docker logs edpagent 2>&1 | Select-Object -Last 50
  Write-Host "---- evo-adapter logs ----"
  docker logs evo-adapter 2>&1 | Select-Object -Last 50
  exit 1
}

Write-Host "Stack is healthy."
docker ps --filter name=redis --filter name=jiuwenbox --filter name=edpagent --filter name=evo-adapter --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
