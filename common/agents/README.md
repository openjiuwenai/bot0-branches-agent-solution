# Common Java Agent 开发与部署指南

本文面向第一次接触本项目的 Java 基础较弱的开发者，告诉你如何在 Windows 电脑上把 `edp-agent-java`（默认端口 `8190`）和 `adapter-versatile-agent-java`（默认端口 `8191`）这两个服务跑起来，并给部署人员一个独立入口。

全文分三大块：

1. **第一次装环境**：从没有 Java、没有 Redis 到把两个服务第一次跑起来。
2. **日常调试与重启**：环境装好后，平时改代码、改配置、重启服务的最小操作。
3. **部署人员入口**：指向两个 `deploy` 目录，并解释部署环境与本地环境的本质区别。

所有 Windows 命令默认使用 **PowerShell**，且都从仓库根目录 `agent-solution` 执行。命令块都写成可以直接整段复制的形式。

---

## 0. 先认识两个服务

| 服务 | 目录 | 默认端口 | 作用 |
|---|---|---:|---|
| EDP Agent | `common\agents\edp-agent-java` | 8190 | 大模型推理、任务规划、场景治理、Redis 状态管理 |
| Adapter Versatile Agent | `common\agents\adapter-versatile-agent-java` | 8191 | 把 A2A 请求转成 Versatile HTTP/SSE 请求并回传给 EDP |

调用链简图：

```text
客户端 ──A2A──▶ EDP Agent:8190 ──┬──▶ 大模型 API
                                 ├──▶ Redis/Memurai:6379
                                 └──A2A──▶ Adapter:8191 ──HTTP/SSE──▶ Versatile:30001
```

启动顺序固定为：**Memurai → Versatile 上游 → Adapter → EDP**。

---

## 1. 第一次装环境（从零到跑起来）

适用对象：这台 Windows 上还没有 JDK 17、没有 Maven、没有 Memurai，也没有构建过本项目。请按下面 4 个小节顺序执行。

### 1.1 进入仓库根目录

打开 PowerShell：

```powershell
Set-Location "<你的代码目录>\agent-solution"
Get-Location
Test-Path ".\common\agents"
```

最后一条应返回 `True`。后续所有命令都假设你停在这个根目录。

### 1.2 安装并校验 JDK 17 和 Maven

项目要求 **JDK 17**（不要用 JDK 8、11；首次也不要主动用 21）和 **Maven 3.8+**。整段复制执行：

```powershell
# 安装 JDK 17（Eclipse Temurin 为例，也可用公司软件中心）
winget install --exact --id EclipseAdoptium.Temurin.17.JDK

# 安装 Maven
winget install --exact --id Apache.Maven

# 关闭当前 PowerShell，重新打开一个新的，再继续执行下面校验
java -version
javac -version
mvn -version
where.exe java
$env:JAVA_HOME
```

校验要点：

- `java -version` 和 `javac -version` 都应显示 `17.0.x`。如果只有 `java` 没有 `javac`，说明装的是 JRE 而不是完整 JDK。
- `mvn -version` 输出里的 `Java version` 必须是 17 —— 这才是 Maven 编译时真正使用的 Java，比 `java -version` 更关键。
- `$env:JAVA_HOME` 应指向 JDK 根目录（例如 `C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot`），而不是它的 `bin` 子目录。

### 1.3 切换 JDK（仅当电脑上有多个 JDK 时）

如果 `mvn -version` 显示的不是 17，说明系统里有更靠前的旧 JDK。最简单的做法是只在当前 PowerShell 里临时切换，关掉窗口就失效：

```powershell
# 把路径换成你本机真实的 JDK 17 根目录
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot"
$env:Path      = "$env:JAVA_HOME\bin;$env:Path"

# 立即校验
java -version
mvn -version
where.exe java
```

如果想让切换永久生效（关掉 PowerShell 再开仍是 17），用用户级变量：

```powershell
$jdk17 = "C:\Program Files\Eclipse Adoptium\jdk-17.0.15.6-hotspot"
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk17, "User")

$userPath  = [Environment]::GetEnvironmentVariable("Path", "User")
$javaEntry = "%JAVA_HOME%\bin"
$entries   = @($userPath -split ";" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -ne $javaEntry })
$newPath   = ($javaEntry + ";" + ($entries -join ";")).TrimEnd(";")
[Environment]::SetEnvironmentVariable("Path", $newPath, "User")
```

设完用户级变量后必须**重新打开** PowerShell 才会生效。不要用 `setx PATH ...` 重写整个 Path，容易把路径截断丢失。

### 1.4 安装并启动 Memurai（本地 Redis）

EDP Agent 用 Redis 存会话检查点和待办状态。Windows 上用 Memurai（Redis 兼容）即可，**不需要装 Docker Desktop**。从 [https://www.memurai.com](https://www.memurai.com) 下载安装包，装好后用管理员 PowerShell 启动服务并校验：

```powershell
# 查找服务名（通常叫 Memurai）
Get-Service | Where-Object { $_.Name -match "Memurai" -or $_.DisplayName -match "Memurai" }

# 启动（管理员 PowerShell）
Start-Service -Name Memurai

# 校验端口
Test-NetConnection 127.0.0.1 -Port 6379
# 期望看到：TcpTestSucceeded : True

# 如果 memurai-cli 在 Path 里，直接 ping
memurai-cli ping
# 期望返回：PONG
```

本地默认连接参数固定为 `127.0.0.1:6379`、无密码、DB 0，与 EDP Agent 的默认配置一致，不用额外改。

### 1.5 一次性构建所有模块

两个服务都依赖仓库里的共享扩展 `agent-runtime-ext-java`，所以**第一次必须先构建它**，再构建两个服务。整段复制执行：

```powershell
# 第 1 步：构建共享扩展（必须最先，只做一次；之后改它才需要重建）
mvn -f .\common\agent-runtime-ext-java\pom.xml clean install "-DskipTests"

# 第 2 步：构建 EDP Agent
mvn -f .\common\agents\edp-agent-java\pom.xml clean install "-DskipTests"

# 第 3 步：构建 Adapter
mvn -f .\common\agents\adapter-versatile-agent-java\pom.xml clean install "-DskipTests"

# 校验产物
Get-ChildItem .\common\agents\edp-agent-java\engine\target\edp-agent-engine-*.jar
Get-ChildItem .\common\agents\adapter-versatile-agent-java\target\adapter-versatile-agent-java-*.jar
```

三个命令都应看到 `BUILD SUCCESS`。共享扩展安装后会被放进本机 Maven 仓库 `%USERPROFILE%\.m2\repository`，两个服务后续编译时才能解析到这些 `SNAPSHOT` 依赖 —— 这是为什么第一次必须先 `install` 它。

> 之后日常改 EDP 或 Adapter 代码时，不必再手动跑这三条；用第 2 节的启动脚本即可，它会自动重新编译当前模块。

### 1.6 准备 Versatile 上游

Adapter 需要把请求转发给一个 Versatile 上游。两种方式二选一：

- **真实 Versatile**：从 Versatile 负责人处拿 URL，URL 里必须保留 `{conversation_id}` 占位符，例如
  `https://versatile.example.com/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id}`。
- **本地 Mock**（没有真实 Versatile 时用）：一次性准备 Python 虚拟环境并启动。

```powershell
# 第一次准备 Mock 环境
py -3.10 -m venv .venv-mock
& .\.venv-mock\Scripts\python.exe -m pip install fastapi uvicorn python-dotenv loguru

# 启动 Mock（需要保持这个窗口开着）
$RepoRoot = (Get-Location).Path
Set-Location .\common\agents\edp-agent-java\mock
& "$RepoRoot\.venv-mock\Scripts\python.exe" .\versatile_main.py
# 默认地址：http://127.0.0.1:30001
```

另开一个 PowerShell 校验 Mock：

```powershell
Invoke-RestMethod http://127.0.0.1:30001/health
```

### 1.7 配置 EDP 的 `.env` 并加载它

EDP Agent 的本地配置放在 `common\agents\edp-agent-java\.env`。先从示例复制，再编辑：

```powershell
Copy-Item .\common\agents\edp-agent-java\.env.example .\common\agents\edp-agent-java\.env
notepad .\common\agents\edp-agent-java\.env
```

至少要改这几项（API Key 必填）：

```dotenv
EDP_AGENT_MODEL_PROVIDER=OpenAI
EDP_AGENT_MODEL_NAME=deepseek-v4-pro
EDP_AGENT_MODEL_BASE_URL=https://api.deepseek.com/v1
EDP_AGENT_MODEL_API_KEY=替换为真实API密钥

EDP_AGENT_VERSATILE_A2A_URL=http://127.0.0.1:8191/a2a
EDP_AGENT_VERSATILE_URL=http://127.0.0.1:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
EDP_AGENT_SCENARIO_HOME=../scenarios/wealth-demo

EDPA_REDIS_HOST=127.0.0.1
EDPA_REDIS_PORT=6379
EDPA_REDIS_PASSWORD=
EDPA_REDIS_DB=0
```

> **为什么必须手动加载 `.env`**：Spring Boot 不会自动读取项目目录里的 `.env` 文件。它只认进程环境变量、Java 系统属性、`application.yml` 等。所以 `.env` 只是一张"变量清单"，必须有人把清单里的变量搬进当前 PowerShell 进程，Spring Boot 才能通过 `${EDP_AGENT_MODEL_API_KEY:默认值}` 这种写法读到。这个"搬运者"在本地就是你手动执行下面这段脚本。

**关键步骤：在准备启动 EDP 的那个 PowerShell 窗口里，执行下面这段加载脚本**（只影响当前窗口，关掉就失效）：

```powershell
$EdpEnv = ".\common\agents\edp-agent-java\.env"
Get-Content -Encoding UTF8 $EdpEnv | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $parts = $line -split "=", 2
        if ($parts.Count -ne 2) { throw "非法 .env 配置行：$line" }
        $name  = $parts[0].Trim()
        $value = $parts[1].Trim()
        if ($name -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") { throw "非法环境变量名：$name" }
        Set-Item -Path "Env:$name" -Value $value
    }
}

# 校验 API Key 是否存在（不打印具体值）
if ([string]::IsNullOrWhiteSpace($env:EDP_AGENT_MODEL_API_KEY)) {
    throw "EDP_AGENT_MODEL_API_KEY 尚未配置"
} else { "EDP_AGENT_MODEL_API_KEY 已加载" }
```

API Key 是敏感信息：不要提交到 Git、不要贴到工单或聊天记录、不要硬编码进 `application.yml`。仓库根 `.gitignore` 已忽略 `.env`，提交前仍建议 `git status --short` 自查。

### 1.8 启动 Adapter 和 EDP

需要**两个独立**的 PowerShell 窗口，分别停在仓库根目录。

**窗口 A —— 启动 Adapter（先启动）：**

```powershell
Set-Location "<你的代码目录>\agent-solution"

# 配置上游 Versatile URL（二选一）
# 真实 Versatile：
$env:VERSATILE_URL = "https://versatile.example.com/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id}"
# 或本地 Mock：
$env:VERSATILE_URL = "http://127.0.0.1:30001/v1/0/agent-manager/workflows/wealth-invest/conversations/{conversation_id}"

# 其他常用配置（可选）
$env:VERSATILE_TIMEOUT        = "600s"
$env:VERSATILE_RESULT_NODE    = "GXZQAResponseNode"
$env:VERSATILE_AGENT_TENANT_ID = "edp-tenant"

# 启动
.\common\agents\start-versatile-agent.bat
```

看到 `Started VersatileAgentApplication` 即启动完成，**不要关这个窗口**。

**窗口 B —— 启动 EDP（后启动）：**

```powershell
Set-Location "<你的代码目录>\agent-solution"

# 先加载 .env（执行 1.7 中的加载脚本）
# ... 此处粘贴 1.7 的加载脚本 ...

# 再启动
.\common\agents\start-edp-agent.bat
```

看到 `Started EdpApplication` 即启动完成，**不要关这个窗口**。

### 1.9 验证

新开第三个 PowerShell 做验证：

```powershell
# 端口
Test-NetConnection 127.0.0.1 -Port 8191
Test-NetConnection 127.0.0.1 -Port 8190

# 健康检查
Invoke-RestMethod http://127.0.0.1:8191/actuator/health
Invoke-RestMethod http://127.0.0.1:8190/actuator/health

# Agent Card
Invoke-RestMethod http://127.0.0.1:8191/.well-known/agent-card.json
Invoke-RestMethod http://127.0.0.1:8190/.well-known/agent-card.json
```

健康检查返回 `UP` 只能证明 Java 进程活着，**不能**证明大模型、Redis、Versatile 之间的业务链路打通。要真正验证业务，发一个 A2A 流式请求（注意 PowerShell 里要用 `curl.exe` 才能看到 SSE）：

```powershell
$body = @{
    jsonrpc = "2.0"
    id      = "local-test-001"
    method  = "sendStreamingMessage"
    params  = @{
        message = @{
            messageId = "message-001"
            role      = "user"
            parts     = @(@{ kind = "text"; text = "帮我推荐一些理财产品" })
        }
    }
} | ConvertTo-Json -Depth 10

curl.exe -N -X POST "http://127.0.0.1:8190/a2a" `
    -H "Content-Type: application/json" `
    -H "Accept: text/event-stream" `
    --data-raw $body
```

---

## 2. 日常调试与重启

环境装好之后，日常你只会反复做这几件事：改代码/配置 → 停服务 → 重启服务。

### 2.1 停止服务

在对应服务窗口按 `Ctrl+C` 即可。如果批处理提示是否终止批处理任务，按提示确认。Memurai 作为 Windows 服务常驻，平时不用停。

### 2.2 什么时候需要重启

下列任一改动都需要重启对应服务（本项目不支持热加载）：

- 改了 `.env`、PowerShell 环境变量；
- 改了 `application.yml` / `application.yaml`；
- 改了场景目录下的 `governance\*.yaml` 或其他场景配置；
- 改了 Java 代码；
- 改了 Maven 依赖版本。

### 2.3 改了 `.env` 后正确重启 EDP

最常见的坑：只改了文件没重新加载。正确做法：

```powershell
# 1) 在 EDP 窗口按 Ctrl+C 停掉服务
# 2) 编辑 .env
notepad .\common\agents\edp-agent-java\.env

# 3) 在同一个 EDP 窗口里，重新执行 1.7 的加载脚本
# ... 粘贴 1.7 的加载脚本 ...

# 4) 在同一个窗口里重启
.\common\agents\start-edp-agent.bat
```

> 环境变量属于进程：窗口 A 里设的变量不会自动出现在窗口 B。所以加载 `.env` 和启动 EDP 必须在**同一个**窗口里。

### 2.4 重新构建模块

日常只改 EDP 或 Adapter 自己的代码，直接用 `start-*.bat` 启动即可，脚本会触发 Maven 编译。只有以下情况才需要重新 `mvn install`：

```powershell
# 改了共享扩展 agent-runtime-ext-java 本身
mvn -f .\common\agent-runtime-ext-java\pom.xml clean install "-DskipTests"

# 想一次重建 EDP + Adapter（不会重建共享扩展）
.\common\agents\build-install.bat
```

### 2.5 日常环境变量速查

Adapter 常用：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VERSATILE_AGENT_PORT` | 8191 | Adapter 端口 |
| `VERSATILE_URL` | 本地 30001 | Versatile URL 模板，必须含 `{conversation_id}` |
| `VERSATILE_TIMEOUT` | 600s | 上游超时 |
| `VERSATILE_RESULT_NODE` | GXZQAResponseNode | 工作流结果节点名 |
| `VERSATILE_AGENT_TENANT_ID` | edp-tenant | A2A 默认租户 |

EDP 常用：

| 变量 | 说明 |
|---|---|
| `SERVER_PORT` | EDP 端口，默认 8190 |
| `EDP_AGENT_MODEL_PROVIDER` / `EDP_AGENT_MODEL_NAME` / `EDP_AGENT_MODEL_BASE_URL` / `EDP_AGENT_MODEL_API_KEY` | 模型配置，API Key 必填 |
| `EDP_AGENT_VERSATILE_A2A_URL` | Adapter A2A 地址 |
| `EDP_AGENT_VERSATILE_URL` | Versatile REST 兼容地址，必须合法 http(s) |
| `EDP_AGENT_SCENARIO_HOME` | 场景目录，默认 `../scenarios/wealth-demo`（相对 `engine` 目录） |
| `EDPA_REDIS_HOST` / `EDPA_REDIS_PORT` / `EDPA_REDIS_PASSWORD` / `EDPA_REDIS_DB` | Redis/Memurai 连接 |
| `EDP_AGENT_LOG_LEVEL` | 日志级别 |

配置优先级从高到低：命令行参数 > 进程环境变量 > `application.yml` 默认值。`.env` 本身不在这个优先级里，只有被加载成进程环境变量后才参与覆盖。

---

## 3. 部署人员入口与 `.env` 机制说明

本节给部署人员看。本地开发不用读本节。

### 3.1 两个独立的部署入口

部署面向 **Linux Docker**，与 Windows 本地开发完全是两套流程。两个服务各自有独立的 `deploy` 目录和部署手册，互不依赖：

| 服务 | 部署目录 | 部署手册 |
|---|---|---|
| EDP Agent | `common\agents\edp-agent-java\deploy` | `common\agents\edp-agent-java\deploy\README.md` |
| Adapter | `common\agents\adapter-versatile-agent-java\deploy` | `common\agents\adapter-versatile-agent-java\deploy\README.md` |

每个 `deploy` 目录里都有 `deploy.sh`（一键构建 jar → 构建镜像 → 起容器 → 验证）、`.env.example`、`Dockerfile` 和若干 `.sh` 脚本。具体操作步骤以各自 `README.md` 为准，本文不重复。

同机部署时的共享网络契约是：

```text
Docker 网络：agent-net
Adapter 网络别名：adapter-versatile
Adapter 容器端口：8191
EDP 访问地址：http://adapter-versatile:8191/a2a
```

推荐部署顺序：准备真实 Versatile → 部署 Adapter → 验证 Adapter Agent Card → 准备 Redis → 部署 EDP → 验证 EDP Agent Card → 发真实业务请求。

### 3.2 三处 `.env` 的作用机制对比

仓库里一共会出现三处 `.env`，文件名相同但作用范围完全不同，**不要互相复制**：

| 文件 | 使用场景 | 谁读取 | 是否自动生效 |
|---|---|---|---|
| `common\agents\edp-agent-java\.env` | Windows 本地开发 | 开发者手动用 PowerShell 脚本加载 | **否**，必须手动加载 |
| `common\agents\edp-agent-java\deploy\.env` | Linux Docker 部署 EDP | EDP 部署脚本 + Docker `--env-file` | **是**，部署脚本自动处理 |
| `common\agents\adapter-versatile-agent-java\deploy\.env` | Linux Docker 部署 Adapter | Adapter 部署脚本 + `docker run -e` | **是**，部署脚本自动处理 |

**本质区别——为什么本地要手动加载、部署却能自动生效：**

- **本地**：Spring Boot 不认识 `.env` 文件。`.env` 只是一张变量清单，必须由开发者的 PowerShell 脚本把每一行 `KEY=value` 读出来、`Set-Item` 写进当前 PowerShell 进程的环境变量，然后从这个进程启动 `mvn spring-boot:run`，Spring Boot 才能通过 `${KEY:默认值}` 读到。关掉窗口就失效。机制：

  ```text
  本地 .env  ──PowerShell 逐行读取──▶  当前 PowerShell 环境变量  ──启动子进程──▶  Spring Boot
  ```

- **部署**：Bash 部署脚本（`_lib.sh`）会安全地逐行解析 `deploy\.env`（只解析 `KEY=value`，不会 `source` 或 `eval` 执行内容），校验后用 Docker 的 `--env-file` 或多个 `-e KEY=value` 把变量**显式注入容器**。容器一启动，里面的 Spring Boot 直接就能读到。机制：

  ```text
  deploy\.env  ──Bash 脚本解析校验──▶  docker run --env-file / -e  ──▶  容器环境变量  ──▶  容器内 Spring Boot
  ```

三类 `.env` 互不继承：

- `edp-agent-java\.env` 不会被 EDP 部署脚本读取；
- `edp-agent-java\deploy\.env` 不会被本地 `start-edp-agent.bat` 读取；
- Adapter 的 `deploy\.env` 不会被本地 `start-versatile-agent.bat` 读取。

文件名都叫 `.env` 只代表它们都用 `KEY=value` 格式；真正决定作用范围的是"谁读取它、把变量交给谁"。

### 3.3 本地地址与容器地址为什么不同

这是部署人员最容易踩的坑。

**本地开发**时，两个 Java 服务都直接跑在 Windows 上，所有东西都共享 Windows 的网络栈，所以都用 `127.0.0.1`：

```text
EDP ──▶ http://127.0.0.1:8191/a2a   (Adapter)
EDP ──▶ 127.0.0.1:6379              (Memurai)
Adapter ──▶ http://127.0.0.1:30001  (Versatile/Mock)
```

**Docker 部署**时，每个容器都是一台"独立的小电脑"，容器里的 `localhost` / `127.0.0.1` 只代表它自己，既不代表 Linux 宿主机，也不代表旁边的另一个容器。所以同机容器之间必须走共享网络 `agent-net` 里的 DNS 别名：

```text
EDP 容器 ──▶ http://adapter-versatile:8191/a2a   (Adapter 容器别名)
EDP 容器 ──▶ edp-redis:6379                      (Redis 容器别名)
```

两套地址不同是正常的。**不要把本地 `.env` 原样复制到 `deploy\.env`**：本地的 `127.0.0.1` 在容器里会指向容器自己，导致 EDP 找不到 Adapter 和 Redis。

### 3.4 配置安全

所有 `.env` 都可能含密钥。规则：

1. 不提交真实 `.env`，只提交 `.env.example`；
2. 不在截图、日志、工单里暴露密钥；
3. 不把密钥硬编码进 Java 或 YAML；
4. Linux 部署建议 `chmod 600 deploy/.env`；
5. 提交前 `git status --short` 自查；
6. 怀疑泄漏时立即在对应平台吊销并轮换密钥，不要只删文件。

---

## 4. 进一步阅读

- EDP Agent 文档入口：`common\agents\edp-agent-java\docs\index.md`
- Adapter 文档入口：`common\agents\adapter-versatile-agent-java\docs\index.md`
- EDP 部署手册：`common\agents\edp-agent-java\deploy\README.md`
- Adapter 部署手册：`common\agents\adapter-versatile-agent-java\deploy\README.md`