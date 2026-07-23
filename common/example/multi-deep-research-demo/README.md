# Multi Deep Research Demo

基于 `agent-runtime-java` 的多 agent 深度调研示例：root DeepAgent 组织远端 search / verify sub-agent 完成主题调研，产出包含对比矩阵、图表、引用与覆盖判定的报告，并把每一次问答持久化到工作目录，供后续跨会话回顾。

本 demo 展示 solution 层如何在**不侵入 core-java**、**库层无 Spring** 的两层约束下装配一个完整的 DeepAgent 应用：

- **A2A 远端 sub-agent 注入**：通过 `agent-runtime-ext-java` 的 `RemoteA2aToolInstaller`，把远端 A2A agent card 自动注册为 root DeepAgent 的工具（本 demo 内注入 `search-agent` 和 `verify-agent` 两个远端子代理）
- **沙箱可视化**：通过 `SandboxRail` / `UrlVerifyRail` 把 pandas / matplotlib / urllib 代码送到 jiuwenbox 沙箱执行，产出对比表 Markdown、PNG 图表和 URL 可达性验证
- **LLM 覆盖判定**：`verify-agent` 是纯 LLM judge（无工具），在 COMPARISON 模式下由 root 强制调 1 次，检查草稿是否覆盖 对比矩阵 / 引用来源 / 置信度 三项 anchor —— 属"best-effort 质量闸"，失败绝不阻塞交付
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
                    │    ├── search-agent  (A2A remote)    │  RemoteA2aToolInstaller
                    │    └── verify-agent  (A2A remote)    │  RemoteA2aToolInstaller
                    └──────┬──────────────┬─────────┬──────┘
                           │              │         │
             A2A / streaming     A2A / SSE     Python execute
                           │              │         │
                           ▼              ▼         ▼
        ┌────────────────────┐  ┌──────────────┐  ┌──────────────────────┐
        │ search-agent       │  │ verify-agent │  │ jiuwenbox sandbox    │
        │ ReActAgent         │  │ ReActAgent   │  │ (HTTP, pandas /      │
        │   • web_search     │  │ (LLM judge,  │  │  matplotlib /        │
        │   • Tavily / Stub  │  │  no tools)   │  │  urllib)             │
        └────────────────────┘  └──────────────┘  └──────────────────────┘
```

两层约束：

- **库层**（`agent-deep-research`、`agent-search`、`agent-verify`）：仅依赖 `agent-core-java`（`agent-verify` 额外依赖 `react-rails`）。无 Spring、无 `agent-service-*`。切换 runtime 时库代码不变。
- **wrapper 层**（`*-runtime`）：Spring Boot 装配，负责 `@ConfigurationProperties`、SPI 暴露、`SandboxClient → SandboxOps` 适配、A2A 远端注册。

---

## 模块布局

```
multi-deep-research-demo/
├── pom.xml                             ← parent (packaging=pom)
│
├── agent-deep-research/                ← root DeepAgent（SDK + Spring Boot runtime 同模块，按包名分层）
│   ├── src/main/java/com/openjiuwen/example/deepresearch/
│   │   ├── DeepResearchProperties.java     配置 POJO + system prompt
│   │   ├── DeepResearchAgentFactory.java   props + sandboxOpsSupplier → DeepAgent
│   │   ├── rail/
│   │   │   ├── AutoPersistMemoryRail.java  extends MemoryRail；afterInvoke 落盘
│   │   │   ├── SandboxRail.java            render_comparison_table / render_chart
│   │   │   ├── UrlVerifyRail.java          verify_urls
│   │   │   ├── SandboxOps.java             库层窄接口：executeCode / downloadFile
│   │   │   └── ExecResult.java             record: (ok, exitCode, stdout, stderr, message)
│   │   └── runtime/                        ← Spring Boot 层
│   │       ├── DeepResearchRuntimeApplication.java  Spring 装配；SandboxClient → SandboxOps 适配
│   │       └── DeepResearchSpringProperties.java    继承库层 Properties 加 @ConfigurationProperties
│   └── src/main/resources/
│       ├── application.yml                        主配置
│       └── application-redis-checkpointer.yml     可选 profile：把 checkpointer 切到 Redis
│
├── agent-search/                       ← search sub-agent（ReActAgent）（SDK + Spring Boot runtime 同模块，按包名分层）
│   ├── src/main/java/com/openjiuwen/example/deepresearch/search/
│   │   ├── SearchAgentProperties.java
│   │   ├── SearchAgentFactory.java             props → ReActAgent + web_search 工具
│   │   ├── WebSearchProvider.java              pluggable 后端 SPI
│   │   ├── TavilyWebSearchProvider.java        prod 走 https://api.tavily.com
│   │   ├── StubWebSearchProvider.java          fixture 走本地 JSON
│   │   ├── WebSearchTool.java / StubWebSearchTool.java   工具入口
│   │   ├── DomainReranker.java                 official ×2, blog ×0.7
│   │   ├── SourceKindClassifier.java           host → official/blog/news/forum
│   │   ├── WebSearchResultSerializer.java      wire 格式
│   │   └── runtime/                            ← Spring Boot 层
│   │       ├── SearchAgentRuntimeApplication.java
│   │       └── SearchAgentSpringProperties.java
│   └── src/main/resources/application.yml
│
└── agent-verify/                       ← verify sub-agent（ReActAgent LLM judge，无工具）（SDK + runtime 同模块，按包名分层）
    ├── src/main/java/com/openjiuwen/example/deepresearch/verify/
    │   ├── VerifyAgentProperties.java          配置 + criteria = ["对比矩阵已覆盖", "引用来源已覆盖", "置信度已覆盖"]
    │   ├── VerifyAgentFactory.java             props → ReActAgent + react-rails 手动装配
    │   ├── RailStateObserver.java              观测 CriteriaReplanBridgeRail 分支决策
    │   └── runtime/                            ← Spring Boot 层
    │       ├── VerifyAgentRuntimeApplication.java
    │       ├── VerifyAgentSpringProperties.java
    │       └── A2aMetadataLoggingFilter.java   OncePerRequestFilter：wire 层记录 params.metadata / params.message.metadata
    └── src/main/resources/application.yml
```

三个模块的 fat jar 可以彼此独立部署：`agent-deep-research` 是 root（默认端口 18090），`agent-search` 和 `agent-verify` 是被 A2A 调用的 sub-agent（默认端口 18091 / 18093）。

---

## 能力矩阵

| 能力 | 类型 | 实现位置 | 触发方式 |
|---|---|---|---|
| 主题拆解 + 任务循环 | DeepAgent task loop | core-java（`DeepAgent`）+ solution 侧 `system-prompt` | `enableTaskLoop=true`、`maxIterations` |
| Web 搜索（子 agent） | ReActAgent + tool | `agent-search` | root 通过 A2A 调 `search-agent`；`RemoteA2aToolInstaller` 每轮把远端 card 注入为工具 |
| 报告覆盖判定（子 agent） | ReActAgent LLM judge（无工具） | `agent-verify` | root 通过 A2A 调 `verify-agent`；COMPARISON 模式下 **强制调用 1 次**（草稿渲染完 → 写正文前），SINGLE 模式跳过。react-rails 内部走 `CriteriaReplanBridgeRail` + `RootCauseRail` 保底 |
| 对比表 + 图表可视化 | Harness tool | `SandboxRail` → Python + pandas/matplotlib，在 jiuwenbox 沙箱执行 | LLM 调 `render_comparison_table` / `render_chart` |
| URL 可达性验证 | Harness tool | `UrlVerifyRail` → Python urllib，在沙箱执行 | LLM 调 `verify_urls` |
| 长期记忆读写 | MemoryRail tools | core-java 提供 `write_memory` / `read_memory` / `memory_search` / `memory_get` / `edit_memory` | LLM 显式调用或 rail 自动写 |
| **确定性落盘** | Rail 生命周期钩子 | `AutoPersistMemoryRail.afterInvoke` | 每次 `result_type=="answer"` 自动写 `memory/answer-*.md` + `reports/answer-*.md` |
| 多轮上下文 | Checkpointer | in-memory（默认）或 Redis（`application-redis-checkpointer.yml`，支持 standalone / cluster） | 同 `conversationId` 请求走同一状态 |
| 中文字体 | 沙箱代码内置 | `SandboxRail` Python 头部 | Noto Sans CJK SC → Microsoft YaHei → DejaVu Sans 降级 |
| Wire 层 metadata 观测 | Servlet filter | `agent-verify` 的 `A2aMetadataLoggingFilter`（`OncePerRequestFilter` + `ContentCachingRequestWrapper`） | 每次 `/a2a` POST 打一行 `[A2A wire] verify-agent received: {method, contextId, params.metadata, params.message.metadata}`，用于 FEAT-004 §Metadata 转发验收 |

`search-agent` 支持 `stub` profile 用本地 fixture 演示，无需 Tavily key；prod profile 需要 `TAVILY_API_KEY`。`verify-agent` 是纯 LLM judge，只需 LLM 环境变量，无外部依赖。

### Sub-agent 路由约束（root prompt 硬规则）

Root DeepAgent 面对 A2A remote tool（`search-agent`）时，`system-prompt` 里有两条硬约束，用来避免"父 agent 越权改写用户语义"和"子 agent 在不该问的时候乱搜"两类失败：

**（1）`remoteInput` 必须 byte-for-byte 等于用户原句** —— root 是路由器，不是改写器。以下操作被 prompt 明确列为**禁止**：

- 追加用户没说的限定词（"API"、"官方"、"官网文档"��"SDK"、"文档"）
- 追加年份 / 季度 / "最新"（用户没说就不加）
- 追加语言提示或翻译任何片段
- 把一个用户请求拆成多条关键词子查询，或把两个用户请求合并成一条
- 删掉礼貌用语 / 招呼语（"你好,"、"请"）
- 自己解决歧义（比如把 "DeepSeek 定价" 补成 "DeepSeek V3 定价"）—— 应原样透传，交给 sub-agent 触发 `ask_user`

对应源码：`agent-deep-research/src/main/java/com/openjiuwen/example/deepresearch/DeepResearchProperties.java` 的 `system-prompt` "HARD CONSTRAINT on remoteInput" 段。

**（2）`search-agent` 在明确歧义模式下必须先 `ask_user`，不许先 `web_search`**：

- 供应商 + 产品家族但缺 SKU（`DeepSeek 官网报价`、`DeepSeek API 定价`、`DeepSeek 模型价格`）
- 同名多实体（`Claude` 可能指 API tier 或消费端订阅）
- 时敏词但缺时间限定（`最新价格` 但没写年份/季度）

**关键点**：query 里带 "API"、"官方"、"官网文档"、"2025" 这种限定词**不算解决了歧义**——只有具体的 model / SKU / version 才算。这块规则在 `agent-search/src/main/java/com/openjiuwen/example/deepresearch/search/SearchAgentProperties.java` 的 "Ambiguity rules (HARD)" 段。

除以上明列的三种模式，`search-agent` 一律**优先 `web_search`**，不许自造新歧义。

**（3）verify-agent 调用规则（COMPARISON 模式硬约束）**：

Root 系统提示词里额外规定：如果 tool list 里存在 `verify-agent` 且当前是 COMPARISON 模式（多值对比），root **必须**在 `render_comparison_table` 返回后、写自然语言答复前**恰好调用 1 次** `verify-agent`，`remoteInput` 传草稿报告 markdown（含对比矩阵 + 引用 + 置信度），把 verdict 附在答复的"Report verification (best-effort judge)"小节。若 verify-agent 不可达 / 超时，记 `verify-agent unavailable — proceeding without external judgement` 后立即写正文 —— 这是**质量闸而非交付闸，绝不能阻塞最终答复**。SINGLE 模式（单值查询）直接跳过（没有 draft 可判）。

对应源码：`DeepResearchProperties.system-prompt` 的 "Verification pass via verify-agent" 段 + `agent-verify` 的 `VerifyAgentProperties.criteria = ["对比矩阵已覆盖", "引用来源已覆盖", "置信度已覆盖"]`（happy-path 短语作 substring 匹配 anchor）。

**verify-agent 内部：react-rails 承担的判定循环与容错**：

verify-agent 是一个纯 LLM judge，本身没有工具，其"判定 → 兜底 → 观测"三件事完全由 `agent-runtime-ext-java` 的 **react-rails** 组件承担。`VerifyAgentFactory.build()` 手动挂 3 条 rail + 1 个观测钩子（避免 BeanPostProcessor 隐式装配，保持 SDK 层无 Spring）：

| Rail | 类别 | 职责 |
|---|---|---|
| `CriteriaReplanBridgeRail` + `RuleBasedCriteriaVerifier` | 判定 | 每轮 LLM 输出交给 verifier 按 `criteria` substring 匹配打分：**PASS** → `forceFinish(verified)` 立即出结论；**FAIL** → 推送 steering hint（"缺哪几个 anchor"）→ 触发下一轮 replan；**replan 次数耗尽** → `forceFinish(degraded)` 也不阻塞交付 |
| `ReplanRail(maxReplan)` | 判定预算 | 全局共享计数器，`max-replan=1` 默认允许 1 次 steer 重试（配合 `max-iterations=2` 总共最多跑 2 轮 ReAct） |
| `RootCauseRail` | 兜底容错 | 底层 model client 抛异常（网络 / gateway 5xx）时**直接降级**，不做 `maxIterations` 空转，配合 root 的 "verify-agent unavailable" 兜底路径 |
| `ReactRailsObservability.install(agent)` | 观测 | 挂 rail 事件监听，把每轮 decision（PASS/FAIL/DEGRADED、steering hint 内容、violation 明细）打到 slf4j，用于事后回放 |

组合效果：verify-agent 不需要在 SDK 层自己写循环 / 重试 / 熔断代码，全靠 rail 装配。判定策略要变（多加 criteria / 关闭 replan / 换 verifier 实现）只改 [VerifyAgentFactory.java](common/example/multi-deep-research-demo/agent-verify/src/main/java/com/openjiuwen/example/deepresearch/verify/VerifyAgentFactory.java) 装配层，`ReActAgent` 本体不动 —— 这也是 react-rails "把认知增强能力独立成可组合 rail" 的核心价值。

对应源码：[VerifyAgentFactory.build()](common/example/multi-deep-research-demo/agent-verify/src/main/java/com/openjiuwen/example/deepresearch/verify/VerifyAgentFactory.java#L49-L94) 的 rail 装配段；rail 实现在 `agent-runtime-ext-java` 的 `com.openjiuwen.agents.reactrails.*` 包。

**（4）`web_search` 工具的注册路径**：`SearchAgentFactory` 用 `Runner.resourceMgr().addTool(webSearchTool, agent.getCard().getId())` 把工具注册到 agent-scoped 资源管理器（而不是全局），随后向 `agent.getAbilityManager().add(toolCard)` 补挂 card。这样多个 agent 各自持有自己的工具实例，jsonrpc getTask 也能正确回显 tool card。

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
agent-deep-research/target/agent-deep-research-0.1.0.jar   ← root（默认端口 18090）
agent-search/target/agent-search-0.1.0.jar                 ← search sub-agent（默认端口 18091）
agent-verify/target/agent-verify-0.1.0.jar                 ← verify sub-agent（默认端口 18093）
```

---

## 服务器部署（fat jar）

### 依赖

- **Java 17+**（字节码 `--release 17`，JDK 26 启动也兼容）
- **jiuwenbox 沙箱**（可选，用于表图 + URL 验证）：需先启动 `jiuwenbox-server`，暴露 `http://<host>:8321`。若不启用，`SANDBOX_ENABLED=false` 时 rail 不注册对应工具，报告改为"仅文本 + 未验证 URL"格式。
- **Redis**（可选，用于多轮对话跨进程复用）：启用 `--spring.profiles.active=redis-checkpointer` 时需要
- **网络出站**：LLM base URL 可达；`agent-search` 走 Tavily 时需要访问 `https://api.tavily.com`；`agent-verify` 只需 LLM base URL 可达（无第三方 API 依赖）

### 环境变量

deep-research、search-agent、verify-agent 三个进程共享同一组 LLM 环境变量：

```bash
# LLM（三个进程都用）
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

# 远端 sub-agent 地址（deep-research 用来找 search / verify）
export SEARCH_AGENT_URL=http://127.0.0.1:18091
export VERIFY_AGENT_URL=http://127.0.0.1:18093
```

### 启动顺序

推荐顺序：先起两个 sub-agent（search-agent、verify-agent），再起 root（deep-research）。root 先起也不会崩，`A2AAgentCardDiscovery` 会每 30s 重试拉 card，但 card 拿到前 root 看不到对应工具（tool list 里没有 `search-agent` / `verify-agent`，COMPARISON 模式的 verify 硬约束会走 `verify-agent unavailable` 兜底分支）。

JDK 17+ 上启用 `redis-checkpointer` 时必须加 `--add-opens java.base/java.time=ALL-UNNAMED`：`RedisTaskStore` 通过 A2A SDK 的 Gson 反射序列化 `Task`（含 `OffsetDateTime` 字段），需要打开 `java.time` 模块。runtime-java `RedisTaskStore.java` 源码注释已说明此约束。in-memory 模式不受影响。

```bash
# 1. 启动 search-agent（端口 18091）
nohup java \
  --add-opens java.base/java.time=ALL-UNNAMED \
  -jar agent-search-0.1.0.jar \
  > search-agent.log 2>&1 &
echo $! > search-agent.pid

# 2. 启动 verify-agent（端口 18093，纯 LLM judge，无外部依赖）
nohup java \
  --add-opens java.base/java.time=ALL-UNNAMED \
  -jar agent-verify-0.1.0.jar \
  > verify-agent.log 2>&1 &
echo $! > verify-agent.pid

# 等 Ready 后确认两个 sub-agent 的 agent card 可达
tail -n 20 search-agent.log verify-agent.log     # 分别看到 "Started SearchAgentRuntimeApplication" / "Started VerifyAgentRuntimeApplication"
curl -s http://127.0.0.1:18091/.well-known/agent-card.json | head -20
curl -s http://127.0.0.1:18093/.well-known/agent-card.json | head -20

# 3. 启动 deep-research-agent（端口 18090）
export SEARCH_AGENT_URL=http://127.0.0.1:18091
export VERIFY_AGENT_URL=http://127.0.0.1:18093
nohup java \
  --add-opens java.base/java.time=ALL-UNNAMED \
  -jar agent-deep-research-0.1.0.jar \
  > deep-research.log 2>&1 &
echo $! > deep-research.pid

# 4. 确认 deep-research 启动 + 两个远端 card 都发现成功
grep -E "Started DeepResearchRuntimeApplication|Discovered remote agent" deep-research.log
# 应看到两行 "Discovered remote agent 'search-agent'" / "Discovered remote agent 'verify-agent'"
```

启用 Redis checkpointer：

Runtime 侧的 Redis 中间件（`agent-runtime-java` 提供的 `RuntimeRedisClient` SPI）同时支持 **Redis 单节点（standalone）** 和 **Redis Cluster** 两种部署形态，通过 `REDIS_TYPE` 环境变量切换；两种形态共享同一份 yml/env 骨架，只在 endpoint 描述上有差异：

- **standalone**：`REDIS_TYPE=standalone` + `REDIS_HOST` + `REDIS_PORT`（+ 可选 `REDIS_DB` / `REDIS_PASSWORD`）
- **cluster**：`REDIS_TYPE=cluster` + `REDIS_NODES=host1:port1,host2:port2,...`（`database` 字段在集群下会被自动忽略并打出 `databaseIgnored=` 警告）

两个 runtime 都激活 `redis-checkpointer` profile 即可共享同一 Redis 后端；库层代码只通过 SPI 访问 Redis，切换 standalone/cluster 不涉及任何 solution 侧代码改动。

```bash
# 两个 runtime 共享同一个 Redis 实例；env 一次设置，进程分别激活 profile
export REDIS_TYPE=${REDIS_TYPE:-standalone}       # standalone | cluster（cluster 需再配 REDIS_NODES）
export REDIS_HOST=<host>
export REDIS_PORT=<port>
export REDIS_PASSWORD=<plaintext-or-blank>
export CHECKPOINTER_TTL_SECONDS=86400              # 1 天；yml 默认同值

# search-runtime（18091）
java --add-opens java.base/java.time=ALL-UNNAMED \
  -jar agent-search-*.jar \
  --spring.profiles.active=redis-checkpointer

# verify-runtime（18093）
java --add-opens java.base/java.time=ALL-UNNAMED \
  -jar agent-verify-*.jar \
  --spring.profiles.active=redis-checkpointer

# deep-research-runtime（18090）
java --add-opens java.base/java.time=ALL-UNNAMED \
  -jar agent-deep-research-0.1.0.jar \
  --spring.profiles.active=redis-checkpointer
```

> 注：`agent-verify` 目前不携带 `application-redis-checkpointer.yml` profile —— verify-agent 本身是无状态 LLM judge，一次调用即完成判定，不依赖 checkpointer 状态。上面一行 `--spring.profiles.active=redis-checkpointer` 对 verify-runtime 是空操作，可省略。写在这里只是为了三个 runtime 命令并列易读。

Redis Cluster 部署时把 `REDIS_TYPE` 切成 `cluster`，并用 Spring Boot 的 indexed 语法追加 `nodes[N]` 参数（每个节点一条 `host:port`）：

```bash
export REDIS_TYPE=cluster
export REDIS_PASSWORD=              # 集群无密码时留空

java --add-opens java.base/java.time=ALL-UNNAMED \
  -jar agent-search-*.jar \
  --spring.profiles.active=redis-checkpointer \
  --openjiuwen.service.middleware.redis.default.nodes[0]=<host>:7001 \
  --openjiuwen.service.middleware.redis.default.nodes[1]=<host>:7002 \
  --openjiuwen.service.middleware.redis.default.nodes[2]=<host>:7003 \
  --openjiuwen.service.middleware.redis.default.nodes[3]=<host>:7004 \
  --openjiuwen.service.middleware.redis.default.nodes[4]=<host>:7005 \
  --openjiuwen.service.middleware.redis.default.nodes[5]=<host>:7006
```

`deep-research-runtime` 同理。集群模式下 `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` 无效（`database` 会打出 `databaseIgnored=` 警告并被忽略），nodes 列表才是权威来源；节点数量、host:port 格式由 runtime 侧 `RedisConnectionAssembler` 校验，任一节点缺失或格式非法都会 fail-fast 阻止 Spring Boot 启动。

启动后每个进程的日志里应能 grep 到一行 `RedisDatasourceDiagnostics` 输出，形如：

```
Runtime Redis datasource selected: redis-ref=default, endpoint-type=standalone,
  RuntimeRedisClient=JedisPooledRuntimeRedisClient, ttl-seconds=86400,
  ref=default, type=standalone, host=<host>, port=<port>, database=0,
  timeoutMs=3000, passwordConfigured=true
```

`passwordConfigured` 只暴露 `true/false`，不会打印明文口令。
Cluster 模式下 `database` 会被自动忽略，日志会额外多一行 `databaseIgnored=` 警告。

### 停止

```bash
kill $(cat deep-research.pid) $(cat search-agent.pid) $(cat verify-agent.pid)
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
  -f "common\example\multi-deep-research-demo\agent-search\pom.xml" `
  spring-boot:run "-Dspring-boot.run.profiles=stub" `
  "-Dspring-boot.run.arguments=--openjiuwen.demo.search-agent.api-key=any-dummy"

# prod profile（真 Tavily）
$env:TAVILY_API_KEY = "<your-tavily-key>"
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\multi-deep-research-demo\agent-search\pom.xml" `
  spring-boot:run
```

启动 verify-agent（新开一个终端；LLM env 沿用上面）：

```powershell
$env:VERIFY_AGENT_PORT = "18093"
mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\multi-deep-research-demo\agent-verify\pom.xml" `
  spring-boot:run
```

启动 deep-research：

```powershell
$env:DEEP_RESEARCH_PORT = "18090"
$env:SEARCH_AGENT_URL   = "http://127.0.0.1:18091"
$env:VERIFY_AGENT_URL   = "http://127.0.0.1:18093"
# 可选：启用沙箱能力
# $env:SANDBOX_ENABLED = "true"
# $env:SANDBOX_URL     = "http://127.0.0.1:8321"

mvn "-Dmaven.repo.local=.m2\repository" `
  -f "common\example\multi-deep-research-demo\agent-deep-research\pom.xml" `
  spring-boot:run
```

A2A 入口地址：`http://127.0.0.1:18090/a2a/`（root）、`http://127.0.0.1:18091/a2a/`（search 直连）、`http://127.0.0.1:18093/a2a/`（verify 直连）

---

## 端到端调用

所有调用都走标准 A2A JSON-RPC 协议 —— endpoint `http://<host>:18090/a2a/`，`method` 用 `SendStreamingMessage`（流式）或 `SendMessage`（非流），`params.message.parts[0].text` 放调研主题，`params.message.contextId` 是多轮对话的会话键（跟 core-java checkpointer 的 conversationId 一一对应）。

请求示例里的对比类问题（"对比 A 和 B 的定价……"）走 COMPARISON 模式，root 会依次触发：
1. `search-agent` 抓官方定价
2. `render_comparison_table` 生成对比矩阵图 + Markdown
3. `verify_urls` 沙箱侧验证引用 URL 可达性
4. **`verify-agent`** 判定草稿是否覆盖 对比矩阵 / 引用来源 / 置信度（COMPARISON 模式硬约束，1 次；失败/超时不阻塞交付）
5. 写自然语言最终答复

单值查询（"DeepSeek V3 上下文长度多少"）走 SINGLE 模式，只调 `search-agent`，跳过 render / verify-agent（没有 draft 可判）。

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

`agent-deep-research/src/main/resources/application.yml` 关键字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `18090` | HTTP 端口 |
| `openjiuwen.service.handler` | `agentcore-ext` | 走 `JiuwenCoreAgentExtHandler`，激活 A2A 远端注入 |
| `openjiuwen.service.a2a.skills[0].id` | `deep_research` | A2A agent card 声明的技能 |
| `openjiuwen.service.a2a.remote-agents[0].name` | `search-agent` | 远端搜索 sub-agent 名，`RemoteA2aToolInstaller` 用它把 card 注入为工具 |
| `openjiuwen.service.a2a.remote-agents[0].url` | `${SEARCH_AGENT_URL}` | 远端搜索 sub-agent HTTP 地址 |
| `openjiuwen.service.a2a.remote-agents[1].name` | `verify-agent` | 远端 verify sub-agent 名（COMPARISON 模式强制调 1 次） |
| `openjiuwen.service.a2a.remote-agents[1].url` | `${VERIFY_AGENT_URL:http://127.0.0.1:18093}` | 远端 verify sub-agent HTTP 地址 |
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

`application-redis-checkpointer.yml` 里的 Redis 字段（`openjiuwen.service.middleware.redis.default.*`、`openjiuwen.service.middleware.checkpointer.ttl-seconds`）通过 `--spring.profiles.active=redis-checkpointer` 激活。`agent-search` 有一份镜像 profile，env 变量同名，两个 runtime 共享同一个 Redis 实例（同 host/port/db/password）。`agent-verify` 目前不带 redis profile（无状态 judge，不需要 checkpointer）。

`agent-verify/src/main/resources/application.yml` 关键字段：

| 字段 | 默认 | 说明 |
|---|---|---|
| `server.port` | `18093` | HTTP 端口 |
| `openjiuwen.service.handler` | `agentcore` | 走 `JiuwenCoreAgentHandler`（无需 A2A 远端注入） |
| `openjiuwen.service.a2a.skills[0].id` | `verify_report` | agent card 声明的技能 |
| `openjiuwen.demo.verify-agent.max-iterations` | `${VERIFY_AGENT_MAX_ITERATIONS:2}` | ReAct 循环上限（judge 通常 1-2 轮即出结论） |
| `openjiuwen.demo.verify-agent.max-replan` | `${VERIFY_AGENT_MAX_REPLAN:1}` | react-rails `CriteriaReplanBridgeRail` 允许的 steer 次数；1 = 允许 1 次判定失败后 push steering hint 再试 1 轮 |
| `openjiuwen.demo.verify-agent.criteria` | `["对比矩阵已覆盖", "引用来源已覆盖", "置信度已覆盖"]` | happy-path 短语：`RuleBasedCriteriaVerifier` 用 substring 匹配决定 PASS/FAIL |
| `openjiuwen.demo.verify-agent.system-prompt` | 内置 | 严格输出格式（`判定通过`/`判定不通过` + `<anchor>已覆盖`/`<anchor>缺失`），配合 criteria 做规则匹配 |

### Redis key 命名与多 runtime 共用同一 Redis 的隔离

启用 Redis checkpointer 后，两类 key 会被写入 Redis：

**（1）A2A 任务 key**（`RedisTaskStore`，任务生命周期）

- 格式：`a2a:task:<taskId>`
- `taskId` 由 A2A SDK 生成，是全局唯一的 UUID（形如 `c013a48b-d9a8-46d2-8774-d6f2dd861246`）
- **多 runtime 共用同一 Redis 时不会冲突**：UUID 空间足够大

**（2）Checkpointer session state key**（core-java `Checkpointer.buildKeyWithNamespace`，会话状态）

- 格式：`<sessionId>:<namespace>:<entityId>[:<suffix>...]`
- `sessionId` = A2A `contextId` = 请求 body 里的 `params.message.contextId`（**由客户端传入的字符串**）
- `namespace` 由 core-java 定死：`agent` / `workflow` / `workflow-graph`
- 典型形状：
  - `<sessionId>:agent:<entityId>:<suffix>`（`AgentStorage`）
  - `<sessionId>:workflow:<workflowId>:<state|update>_blobs[_dump_type]`（`WorkflowStorage`）
  - `<sessionId>:workflow-graph:<ns>:{DATA_TYPE|DATA_VALUE}`（`GraphStore`）

**是否会跨 runtime 冲突取决于业务方怎么用 `contextId`**：

| 场景 | 行为 |
|---|---|
| 同 `contextId` 的 A2A 父子派单（本 demo：deep-research → search-agent / verify-agent） | 故意让父子共享 sessionId 前缀，实现状态共享；子 agent 由 core-java 追加 `_1_1` 之类后缀落到 entityId 上，不会撞车 |
| 同一应用的不同会话/不同用户 | 只要各自 `contextId` 不同即不冲突（正常业务方生成的 `contextId` 通常带 UUID 或时间戳） |
| 两个**不相干应用**恰好用了相同 `contextId`（如都手写 `ctx-test-001`） | 会互相踩，checkpointer 数据互相覆盖 |

**跨 runtime 严格隔离的三种做法**（按代价从低到高）：

1. **约定 `contextId` 前缀**：每个应用带自己的前缀（`app-a:ctx-xxx` / `app-b:ctx-xxx`）。零改动，推荐首选。
2. **每 runtime 独占 Redis database**（只对 standalone 有效；cluster 下 `database` 被自动忽略，启动日志的 `databaseIgnored=N` 就是这事）。
3. **每 runtime 独占 Redis 实例**（最彻底，但需要多实例部署）。

本 demo 里 deep-research + search-agent 共用同一 Redis 是**必要设计**（父子共享 sessionId 才能跨进程 recall）；verify-agent 无状态、不写 checkpointer，共不共享 Redis 都无影响。如果以后要跟别的应用共用同一 Redis，做法 1 就够了。

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
