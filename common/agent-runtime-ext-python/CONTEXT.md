# CONTEXT —— 本仓的统一语言

写给**人和 agent 共用**：新写一段代码、一份设计、一条测试名之前，先在这里对一次词。
目的只有一个——**同一个东西，全仓只有一个叫法**。设计、规格、代码各译一套，是本项目
已经付过学费的错误（一处旧名收口漏改，曾使全部部署级 harness 同时 ImportError）。

> 方法出处：`CONTEXT.md` 这一约定取自 `github.com/mattpocock/skills`（MIT）。
> 本文是按本项目实况重写的，不是移植。

## 权威链（本文不复制上游，只指过去）

| 层 | 位置 | 本文立场 |
|---|---|---|
| L0 术语表与「禁止混淆」 | `Technical-AF/docs/develop/03-Technical-AF/docs/develop/03-architecture/L0-Top-Level-Design/glossary.md` | **遵从**，不重定义。跨模块术语一律以它为准 |
| L1 领域对象与 SPI | 上游仓 `Technical-AF/docs/develop/03-architecture/L1-High-Level-Design/agent-runtime/logical.md` 与同目录 `spi-appendix.md` | 遵从 |
| version-scope FEAT | 上游仓 `Technical-AF/docs/develop/02-features/FEAT-*.md` | 遵从 |
| java 代码 | `agent-runtime-java@develop` 的 `spec/spi` + `spec/dto` | 遵从 |

本文只记两样上游没有的：**Python 版的本地化决定**，以及**本仓已知的错名**。

## 一、领域与 SPI 权威名（三方逐字一致，不得本地化）

L1 逻辑视图 §2、FEAT-002、java `spec/spi`+`spec/dto` 三方逐字相同的名，**只此六个**：

| 名 | 是什么 | 落位 |
|---|---|---|
| `ServeRequest` | 协议中立执行请求 | `domain/context.py` |
| `QueryResponse` | 非流式聚合响应 | `domain/result.py` |
| `QueryChunk` | 流式 chunk | `domain/result.py` |
| `AgentHandler` | 执行入口 SPI | `ports/handler.py` |
| `QueryStreamObserver` | 流式输出契约（Python 以 `AsyncIterator` 实现同一契约） | 不单列 port |
| `ServeOrchestrator` | 编排入口 | `application/serve.py` |

**对象名逐字一致，方法名按语言习惯映射**：

| java | python |
|---|---|
| `streamQuery` | `stream_query` |
| `clearSession` | `clear_session` |
| `cancelActive` | `cancel_active` |
| `resetConversation` | `reset_conversation` |

## 二、不建类的权威概念

| 概念 | 状态 | 为什么不建类 |
|---|---|---|
| `Task` | active | 由 a2a-sdk 管理，runtime 只持 `TaskState`。**再建一个 Task 类就有了两个真值源** |
| `Session` | **proposal** | L0 标为提案。当前实现 = A2A context 的隐含范围，**不落地领域类，也不建 SessionTaskBinding** |

## 三、避免使用的词（用左边，别用右边）

| 用 | 不要用 | 依据 |
|---|---|---|
| `Task` | `Run` | L0「禁止混淆」：服务端执行生命周期状态一律用 Task |
| `AgentHandler` | `AgentRuntimeHandler` | 旧名，已废；守卫见 `test_agent_handler_spi.py::test_old_handler_name_gone` |
| `stream_query` | `execute` | 旧执行入口名，已废；守卫见 `::test_no_execute_method` |
| `RuntimeRedisClient` | 任何以 `Cache*` / `KV*` 开头的别名 | 端口权威名，对齐 java |

<!-- BANNED-TERMS
AgentRuntimeHandler
-->

> 上方 `BANNED-TERMS` 注释块里的词由 `test_vocabulary_discipline.py` 机械核对，
> **加词只需改这里**，测试自动跟上——手写两份清单必然漂移。

## 四、一处未收口的名（写代码前先看这条）

| 位置 | 叫法 |
|---|---|
| 设计 `Feat-Func-002ba` | `ReActRuntimeHandler` |
| 代码 `adapters/outbound/agentcore/handler.py` 的 `stream_query` | `ReActAgentHandler` |

**代码为准**（`AgentHandler` 是权威后缀，`RuntimeHandler` 属已废的旧构词）。
设计侧待改，已登记在实现符合性清单。**新写的 handler 一律用 `<框架名>AgentHandler`。**

## 五、Python 版本地词（上游没有，本仓自定）

| 词 | 含义 |
|---|---|
| **洋葱分层** | `domain` → `application` → `ports` → `adapters` 的同心圆，依赖只能从外向内 |
| **端口消费方 / 端口实现方** | 分工线是端口的两侧，不是「application 对 adapter」。序列化与 TTL 策略在消费方，Redis I/O 与 key 物理格式在实现方 |
| **接缝（seam）** | 测试观察行为的公共边界。清单与纪律见 `METHOD-测试接缝纪律.md` |
| **垂直切片（tracer bullet）** | 任务拆解单位：穿透所有层的窄而完整的一条路。规则见 `METHOD-任务拆解规则.md` |
| **已具名 / 待建** | 验收条目的两种取值。已具名 = 点名到可直接跑的用例；待建 = 通过条件已可判定、验证物未落 |

## 六、写作纪律

- **概念级改动要全文传播**：改一个名或一条语义，全仓搜一遍改干净，不留半旧半新。
- **引述必须带出处锚**，形如 `文件:行`；**禁裸行号**（`` `:616` `` 这种），机器与人都无从判断它属于哪个文件。
- **交付文档不写变更过程**：只写现在该怎么做，不写它是怎么演变成现在这样的。读者没有作者的推导轨迹，写了也用不上。
