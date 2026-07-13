# Multi Deep Research Demo

基于 `agent-runtime-java` 的多 agent 深度调研示例：root DeepAgent 组织远端 search sub-agent 完成主题调研，产出包含对比矩阵、图表和引用的报告，并把每一次问答持久化到工作目录，供后续跨会话回顾。

本 demo 展示 solution 层如何在**不侵入 core-java**、**库层无 Spring** 的两层约束下装配一个完整的 DeepAgent 应用：

- **A2A 远端 sub-agent 注入**：通过 `agent-runtime-ext-java` 的 `RemoteA2aToolInstaller`，把远端 A2A agent card 自动注册为 root DeepAgent 的工具
- **沙箱可视化**：通过 `SandboxRail` / `UrlVerifyRail` 把 pandas / matplotlib / urllib 代码送到 jiuwenbox 沙箱执行，产出对比表 Markdown、PNG 图表和 URL 可达性验证
- **长期记忆**：通过 `AutoPersistMemoryRail` 的 `afterInvoke` 钩子确定性落盘问答对，支持同会话消息历史回顾和跨会话 `memory_search` 召回

---

## 目录

- [拓扑与架构](#拓扑与架构)
- [模块布局](#模块布局)
- [能力矩阵](#能力矩阵)
- [构建](#构建)
- [服务器部署（fat jar）](#服务器部署fat-jar)
- [本地开发（mvn spring-boot:run）](#本地开发mvn-spring-bootrun)
- [端到端调用](#端到端调用)
- [配置字段速查](#配置字段速查)
- [工作目录产物](#工作目录产物)

---

## 拓扑与架构

```
                          user query (A2A JSON-RPC)
                                        │
                                        ▼
                    ┌──────────────────────────────────────┐
                    │      deep-research-agent (root)      │
                    │      DeepAgent (task loop)           │
                    │                                      │
                    │   Rails                              │
                    │    ├── AutoPersistMemoryRail         │  afterInvoke → memory/ + reports/
                    │    ├── SandboxRail                   │  render_comparison_table / render_chart
                    │    └── UrlVerifyRail                 │  verify_urls
                    │                                      │
                    │   Tools（运行时注入）                 │
                    │    └── search-agent (A2A remote)     │  RemoteA2aToolInstaller
                    └──────┬────────────────────────┬──────┘
                           │                        │
             A2A / streaming                Python execute
                           │                        │
                           ▼                        ▼
                ┌────────────────────┐   ┌──────────────────────┐
                │ search-agent       │   │ jiuwenbox sandbox    │
                │ ReActAgent         │   │ (HTTP, pandas /      │
                │   • web_search     │   │  matplotlib /        │
                │   • Tavily / Stub  │   │  urllib)             │
                └────────────────────┘   └──────────────────────┘
```

两层约束：

- **库层**（`agent-deep-research`、`agent-search`）：仅依赖 `agent-core-java`。无 Spring、无 `agent-service-*`。切换 runtime 时库代码不变。
- **wrapper 层**（`*-runtime`）：Spring Boot 装配，负责 `@ConfigurationProperties`、SPI 暴露、`SandboxClient → SandboxOps` 适配、A2A 远端注册。

---

## 模块布局

```
multi-deep-research-demo/
├── pom.xml                             ← parent (packaging=pom)
│
├── agent-deep-research/                ← 库层：root DeepAgent
│   └── src/main/java/com/openjiuwen/example/deepresearch/
│       ├── DeepResearchProperties.java     配置 POJO + system prompt
│       ├── DeepResearchAgentFactory.java   props + sandboxOpsSupplier → DeepAgent
│       └── rail/
│           ├── AutoPersistMemoryRail.java  extends MemoryRail；afterInvoke 落盘
│           ├── SandboxRail.java            render_comparison_table / render_chart
│           ├── UrlVerifyRail.java          verify_urls
│           ├── SandboxOps.java             库层窄接口：executeCode / downloadFile
│           └── ExecResult.java             record: (ok, exitCode, stdout, stderr, message)
│
├── agent-deep-research-runtime/        ← wrapper：Spring Boot 应用
│   ├── src/main/java/.../runtime/
│   │   ├── DeepResearchRuntimeApplication.java  Spring 装配；SandboxClient → SandboxOps 适配
│   │   └── DeepResearchSpringProperties.java    继承库层 Properties 加 @ConfigurationProperties
│   └── src/main/resources/
│       ├── application.yml                        主配置
│       └── application-redis-checkpointer.yml     可选 profile：把 checkpointer 切到 Redis
│
├── agent-search/                       ← 库层：search sub-agent（ReActAgent）
│   └── src/main/java/.../search/
│       ├── SearchAgentProperties.java
│       ├── SearchAgentFactory.java             props → ReActAgent + web_search 工具
│       ├── WebSearchProvider.java              pluggable 后端 SPI
│       ├── TavilyWebSearchProvider.java        prod 走 https://api.tavily.com
│       ├── StubWebSearchProvider.java          fixture 走本地 JSON
│       ├── WebSearchTool.java / StubWebSearchTool.java   工具入口
│       ├── DomainReranker.java                 official ×2, blog ×0.7
│       ├── SourceKindClassifier.java           host → official/blog/news/forum
│       └── WebSearchResultSerializer.java      wire 格式
│
└── agent-search-runtime/               ← wrapper：search agent Spring Boot 应用
    ├── src/main/java/.../search/runtime/
    │   ├── SearchAgentRuntimeApplication.java
    │   └── SearchAgentSpringProperties.java
    └── src/main/resources/application.yml
```

---

## 能力矩阵

| 能力 | 类型 | 实现位置 | 触发方式 |
|---|---|---|---|
| 主题拆解 + 任务循环 | DeepAgent task loop | core-java（`DeepAgent`）+ solution 侧 `system-prompt` | `enableTaskLoop=true`、`maxIterations` |
| Web 搜索（子 agent） | ReActAgent + tool | `agent-search` | root 通过 A2A 调 `search-agent`；`RemoteA2aToolInstaller` 每轮把远端 card 注入为工具 |
| 对比表 + 图表可视化 | Harness tool | `SandboxRail` → Python + pandas/matplotlib，在 jiuwenbox 沙箱执行 | LLM 调 `render_comparison_table` / `render_chart` |
| URL 可达性验证 | Harness tool | `UrlVerifyRail` → Python urllib，在沙箱执行 | LLM 调 `verify_urls` |
| 长期记忆读写 | MemoryRail tools | core-java 提供 `write_memory` / `read_memory` / `memory_search` / `memory_get` / `edit_memory` | LLM 显式调用或 rail 自动写 |
| **确定性落盘** | Rail 生命周期钩子 | `AutoPersistMemoryRail.afterInvoke` | 每次 `result_type=="answer"` 自动写 `memory/answer-*.md` + `reports/answer-*.md` |
| 多轮上下文 | Checkpointer | in-memory（默认）或 Redis（`application-redis-checkpointer.yml`） | 同 `conversationId` 请求走同一状态 |
| 中文字体 | 沙箱代码内置 | `SandboxRail` Python 头部 | Noto Sans CJK SC → Microsoft YaHei → DejaVu Sans 降级 |

`search-agent` 支持 `stub` profile 用本地 fixture 演示，无需 Tavily key；prod profile 需要 `TAVILY_API_KEY`。

---

## 构建

在仓库根目录下执行（用 repo 内 `.m2`，避免污染全局）：

```powershell
# 1. 先装 agent-runtime-ext-java 的两个 SPI 适配（agentcore-ext 提供
#    JiuwenCoreAgentExtHandler + RemoteA2aToolInstaller；agentcore 提供
#    AgentCoreSandboxClientFactory）
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\agent-runtime-ext-java\pom.xml" `
  -pl agent-service-adapters/agent-service-adapters-agentcore-ext,agent-service-adapters/agent-service-adapters-agentcore `
  -am clean install -DskipTests

# 2. 构建本 demo
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\multi-deep-research-demo\pom.xml" `
  clean package -DskipTests
```

产物（可独立运行的 Spring Boot fat jar，各约 180 MB）：

```
agent-deep-research-runtime/target/agent-deep-research-runtime-0.1.0-SNAPSHOT.jar
agent-search-runtime/target/agent-search-runtime-0.1.0-SNAPSHOT.jar
```

---

## 服务器部署（fat jar）

### 依赖

- **Java 17+**（字节码 `--release 17`，JDK 26 启动也兼容）
- **jiuwenbox 沙箱**（可选，用于表图 + URL 验证）：需先启动 `jiuwenbox-server`，暴露 `http://<host>:8321`。若不启用，`SANDBOX_ENABLED=false` 时 rail 不注册对应工具，报告改为"仅文本 + 未验证 URL"格式。
- **Redis**（可选，用于多轮对话跨进程复用）：启用 `--spring.profiles.active=redis-checkpointer` 时需要
- **网络出站**：LLM base URL 可达；`agent-search` 走 Tavily 时需要访问 `https://api.tavily.com`

### 环境变量

deep-research 和 search-agent 共享同一组 LLM 环境变量：

```bash
# LLM（两个进程都用）
export LLM_PROVIDER=OpenAI            # OpenAI 兼容 provider 名
export LLM_API_KEY=<your-llm-key>
export LLM_API_BASE=https://api.deepseek.com
export LLM_MODEL=deepseek-chat
# 可选：LLM_SSL_VERIFY=true / LLM_TEMPERATURE=0.2 / LLM_TOP_P=0.8 / LLM_TIMEOUT=120s

# search-agent 专属（prod 模式必需；stub profile 不需要）
export TAVILY_API_KEY=<your-tavily-key>

# 沙箱（可选，用于表图 + URL 验证；未设则 rail 不注册）
export SANDBOX_ENABLED=true
export SANDBOX_URL=http://127.0.0.1:8321
```

### 启动顺序

推荐先起 search-agent 再起 deep-research（先起 deep-research 也不会崩，`A2AAgentCardDiscovery` 会每 30s 重试拉 card，但 card 拿到前 root 看不到 search 工具）。

```bash
# 1. 启动 search-agent（端口 18091）
nohup java -jar agent-search-runtime-0.1.0-SNAPSHOT.jar \
  > search-agent.log 2>&1 &
echo $! > search-agent.pid

# 等 Ready 后确认 agent card 可达
tail -f search-agent.log     # 看到 "Started SearchAgentRuntimeApplication"
curl -s http://127.0.0.1:18091/.well-known/agent-card.json | head -20

# 2. 启动 deep-research-agent（端口 18090）
export SEARCH_AGENT_URL=http://127.0.0.1:18091
nohup java -jar agent-deep-research-runtime-0.1.0-SNAPSHOT.jar \
  > deep-research.log 2>&1 &
echo $! > deep-research.pid

# 3. 确认 deep-research 启动 + 远端 card 发现成功
grep -E "Started DeepResearchRuntimeApplication|Discovered remote agent" deep-research.log
```

启用 Redis checkpointer：

```bash
export REDIS_HOST=<host>
export REDIS_PORT=<port>
export REDIS_PASSWORD=<plaintext-or-blank>
java -jar agent-deep-research-runtime-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=redis-checkpointer
```

### 停止

```bash
kill $(cat deep-research.pid) $(cat search-agent.pid)
```

---

## 本地开发（mvn spring-boot:run）

PowerShell 需要先固定 UTF-8 编码，中文查询才不会串码：

```powershell
Set-Location <repo-root>
chcp.com 65001 > $null
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"

# LLM
$env:LLM_API_KEY  = "<your-key>"
$env:LLM_API_BASE = "https://api.deepseek.com"
$env:LLM_MODEL    = "deepseek-chat"
```

启动 search-agent（stub profile 不需要 Tavily key，走本地 fixture）：

```powershell
$env:SEARCH_AGENT_PORT = "18091"
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\multi-deep-research-demo\agent-search-runtime\pom.xml" `
  spring-boot:run "-Dspring-boot.run.profiles=stub" `
  "-Dspring-boot.run.arguments=--openjiuwen.demo.search-agent.api-key=any-dummy"

# prod profile（真 Tavily）
$env:TAVILY_API_KEY = "<your-tavily-key>"
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\multi-deep-research-demo\agent-search-runtime\pom.xml" `
  spring-boot:run
```

启动 deep-research：

```powershell
$env:DEEP_RESEARCH_PORT = "18090"
# 可选：启用沙箱能力
# $env:SANDBOX_ENABLED = "true"
# $env:SANDBOX_URL     = "http://127.0.0.1:8321"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\multi-deep-research-demo\agent-deep-research-runtime\pom.xml" `
  spring-boot:run
```

A2A 入口地址：`http://127.0.0.1:18090/a2a/`

---

## 端到端调用

所有调用都走标准 A2A JSON-RPC 协议 —— endpoint `http://<host>:18090/a2a/`，`method` 用 `SendStreamingMessage`（流式）或 `SendMessage`（非流），`params.message.parts[0].text` 放调研主题，`params.message.contextId` 是多轮对话的会话键（跟 core-java checkpointer 的 conversationId 一一对应）。

### 流式调用（curl）

```bash
curl -N -X POST http://127.0.0.1:18090/a2a/ \
  -H 'Content-Type: application/json; charset=utf-8' \
  -H 'Accept: text/event-stream' \
  -d '{
    "jsonrpc": "2.0",
    "id": "deep-research-001",
    "method": "SendStreamingMessage",
    "params": {
      "message": {
        "role": "ROLE_USER",
        "contextId": "deep-research-001",
        "parts": [
          { "text": "对比 DeepSeek V4 Pro 和 GLM-5.2 两款旗舰大模型 API 的最新 token 定价，统一换算为 USD/百万 token，产出对比表和柱状图，每个数据点给出出处 URL，并验证 URL 可达性。" }
        ]
      }
    }
  }'
```

### 流式调用（PowerShell）

```powershell
chcp.com 65001 > $null
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$a2aUrl = "http://127.0.0.1:18090/a2a/"

$request = [ordered]@{
  jsonrpc = "2.0"
  id      = "deep-research-001"
  method  = "SendStreamingMessage"
  params  = [ordered]@{
    message = [ordered]@{
      role      = "ROLE_USER"
      contextId = "deep-research-001"
      parts     = @(
        [ordered]@{
          text = "对比 DeepSeek V4 Pro 和 GLM-5.2 两款旗舰大模型 API 的最新 token 定价，统一换算为 USD/百万 token，产出对比表和柱状图，每个数据点给出出处 URL，并验证 URL 可达性。"
        }
      )
    }
  }
}
$body = $request | ConvertTo-Json -Depth 100

$response = Invoke-WebRequest -UseBasicParsing `
    -Uri $a2aUrl `
    -Method Post `
    -ContentType "application/json; charset=utf-8" `
    -Headers @{ Accept = "text/event-stream" } `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body))

$response.RawContentStream.Position = 0
$reader = New-Object System.IO.StreamReader($response.RawContentStream, [System.Text.Encoding]::UTF8)
$reader.ReadToEnd()
```

### 非流式调用

把 `method` 改为 `SendMessage`，请求 header 换成 `Accept: application/json`，其余字段不变。返回是聚合后的一次性 JSON，`result` 里携带最终答案。

### 跨会话回顾

用**同一个 `contextId`** 追问 "我上次问了你什么？"，DeepAgent 应直接从 checkpointer 恢复的消息历史复述，**不走** `memory_search`（对应 system prompt 的 recall routing (a) 分支）。

换新 `contextId` 后追问 "上周关于大模型定价的调研，结论是什么？"，DeepAgent 应先调 `memory_search` 命中 `AutoPersistMemoryRail` 落盘的 `answer-YYYY-MM-DD-<slug>.md`，再复述（对应 recall routing (b) 分支）。

---

## 配置字段速查

`agent-deep-research-runtime/src/main/resources/application.yml` 关键字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `18090` | HTTP 端口 |
| `openjiuwen.service.handler` | `agentcore-ext` | 走 `JiuwenCoreAgentExtHandler`，激活 A2A 远端注入 |
| `openjiuwen.service.a2a.skills[0].id` | `deep_research` | A2A agent card 声明的技能 |
| `openjiuwen.service.a2a.remote-agents[].name` | `search-agent` | 远端 sub-agent 名，`RemoteA2aToolInstaller` 用它把 card 注入为工具 |
| `openjiuwen.service.a2a.remote-agents[].url` | `${SEARCH_AGENT_URL}` | 远端 sub-agent HTTP 地址 |
| `openjiuwen.service.external.sandbox.enabled` | `${SANDBOX_ENABLED:false}` | 是否启用沙箱工具（关掉则 `SandboxRail` / `UrlVerifyRail` 不注册） |
| `openjiuwen.service.external.sandbox.servers[0].service-url` | `${SANDBOX_URL:http://127.0.0.1:8321}` | jiuwenbox 服务地址 |
| `openjiuwen.service.external.sandbox.servers[0].idle-ttl-seconds` | `300` | 沙箱空闲回收秒数 |
| `openjiuwen.demo.deep-research.sandbox.smoke-test` | `false` | 启动阶段跑一次 `echo hi_from_sandbox` 验证 jiuwenbox 连通性（`SandboxSmokeTest`）；仅调试时打开 |
| `openjiuwen.demo.deep-research.provider` | `${LLM_PROVIDER:OpenAI}` | LLM provider（OpenAI 兼容） |
| `openjiuwen.demo.deep-research.api-base` | `${LLM_API_BASE:https://api.deepseek.com}` | LLM base URL |
| `openjiuwen.demo.deep-research.model-name` | `${LLM_MODEL:deepseek-chat}` | 模型名 |
| `openjiuwen.demo.deep-research.max-iterations` | `${DEEP_RESEARCH_MAX_ITERATIONS:10}` | DeepAgent task loop 最大轮次 |
| `openjiuwen.demo.deep-research.completion-timeout` | `${DEEP_RESEARCH_COMPLETION_TIMEOUT:600s}` | 单轮 invoke 总超时 |
| `openjiuwen.demo.deep-research.workspace-path` | `target/deep-research-workspace` | 记忆和报告落盘根目录 |
| `openjiuwen.demo.deep-research.system-prompt` | 内置 | 含 A2A 调用规范、memory 工具文档、sandbox 工具契约、迭代预算硬规则 |

`application-redis-checkpointer.yml` 里的 Redis 字段（`openjiuwen.service.middleware.redis.default.*`）通过 `--spring.profiles.active=redis-checkpointer` 激活。

---

## 工作目录产物

`DEEP_RESEARCH_WORKSPACE`（默认 `target/deep-research-workspace/`）目录布局：

```
deep-research-workspace/
├── memory/                       ← core-java MemoryRail 管理，会被 MemoryIndexManager 索引供 memory_search
│   ├── answer-YYYY-MM-DD-<slug>.md   AutoPersistMemoryRail.afterInvoke 自动写；含用户问题 + agent 回答 + 会话 ID
│   └── notes-YYYY-MM-DD-<topic>.md   LLM 主动写的中间 scratchpad（可选）
└── reports/                      ← 人类可读交付物
    ├── answer-YYYY-MM-DD-<slug>.md   AutoPersistMemoryRail 同步写的答案正文副本（无 wrap）
    ├── render_table_*.png            SandboxRail 下载的对比表 PNG
    └── render_chart_*.png            SandboxRail 下载的图表 PNG
```

`answer-` 前缀由 `AutoPersistMemoryRail` 保留，用于和 LLM 自己写的 `notes-` scratchpad 区分。core-java 的 `write_memory` 契约按 basename 扁平化目标路径，因此文件名不带子目录。
