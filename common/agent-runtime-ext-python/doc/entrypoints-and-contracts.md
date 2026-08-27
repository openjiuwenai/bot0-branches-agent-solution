# 入口与数据契约

面向**接入方**：把一个 Agent 装进本 runtime，对外会长出哪些端点、内部要实现哪个契约、数据在这两者之间怎么流。

本文只做归集，每一条的事实源在对应的 L2 详设（`internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/`），与详设冲突时以详设为准。

---

# 第一部分：对外入口

## 1. 入口总览

本 runtime 提供三条互相独立的对外入口，宿主按需装配，装哪条就长哪条的端点。

| 入口 | 协议 | 装配函数 | 对应特性 |
|---|---|---|---|
| **标准协议入口** | A2A JSON-RPC + SSE | `create_a2a_app` | FEAT-001 |
| **自定义 REST 入口** | 宿主自定义 JSON | `create_rest_app` / `build_rest_router` | FEAT-022 |
| **总线消费入口** | 事件订阅，无 HTTP 面 | `build_bus_consumer` | FEAT-017 |

三条入口**共用同一个执行契约**（`AgentHandler`）与同一个编排器。同进程同时起标准协议与自定义 REST 两条时，必须把前者产出的编排器注入后者——见 §4.2。

## 2. 标准协议入口

### 2.1 端点

| 端点 | 方法 | 用途 |
|---|---|---|
| `/a2a` | POST | A2A JSON-RPC 分发面 |
| `/.well-known/agent-card.json` | GET | Agent Card 发现（根路径） |
| `/a2a/.well-known/agent-card.json` | GET | Agent Card 发现（挂载前缀下） |
| `/.well-known/agent.json` | GET | Agent Card 发现（旧路径别名） |
| `/a2a/push-notifications/callback` | POST | 推送通知回调回灌 |

**三条发现路径是三个独立端点**，前缀不同即不同端点，不可只测其一然后声称覆盖。

`mount_path` 参数决定挂载前缀，默认 `/a2a`。

### 2.2 JSON-RPC 方法

`SendMessage`（同步）、`SendStreamingMessage`（SSE 流式）、`GetTask`（查询）、以及推送通知配置相关方法。具体请求响应形态见 `Feat-Func-001b`。

流式响应的每一帧带 `event: jsonrpc` 事件名。

## 3. 自定义 REST 入口

### 3.1 端点

| 端点 | 方法 | 用途 |
|---|---|---|
| `/v1/{项目}/agents/{智能体}/conversations/{会话}` | POST | 消息提交，同步或流式由请求体的 `stream` 决定 |
| `/v1/{通道路径}/cancel` | POST | 取消当前轮次 |

路径模板由宿主通过 `RestChannel` 实现声明。**当前版本只允许配置一个自定义 REST 路径模板**。

### 3.2 两种响应形态

| `stream` | 响应 | 形态 |
|---|---|---|
| `false` | 单个 JSON | 两键体 `{"success": …, "answer": …}`；执行失败时追加 `error` |
| `true` | SSE | 每帧一个 JSON，含 `success`、`agent_id`、`conversation_id`、`output`、`error`、`execution_time`、`custom_rsp_data` |

> **同步路径执行失败时 `success` 为 `false`**，`answer` 保留已产出的正文（没有则为空串），并追加 `error` 给出错误文案（异常原文不上 wire；没有错误块时是固定文案 `Internal error`）。成功与中断仍是两键体，与存量逐字节一致。失败态是对存量的**有意偏离**——存量失败时也报 `success: true`，社区 issue #152 判为缺陷并在本版修正；详设承接见 `Feat-Func-022b` §4.7 与 §8.1。判据由 `deploy-e2e/run-versatile-down.sh` 的第五步与 `agent_runtime/tests/test_mobile_bank_projection.py` 钉住——改动它会转红。

## 4. 总线消费入口

无 HTTP 端点。订阅事件、桥接到 A2A 请求、投影 Task 状态、发布响应事件。装配见 `build_bus_consumer`，配置见《配置参考》的 `openjiuwen.service.bus` 段。

---

# 第二部分：执行契约

## 5. 从入口到处理器

三条入口都走同一条路：

```
入口适配件 → ServeOrchestrator → AgentHandler（宿主实现）
```

入口适配件负责协议解析与响应投影，编排器负责调用契约、逐块中继、取消传导，处理器负责真正的执行。**协议类型不越过编排器**——处理器看不到 A2A 或 HTTP 的任何类型。

## 6. `AgentHandler`：唯一的执行契约

定义在 `agent_runtime/ports/handler.py`，是一个 `Protocol`（结构化子类型）——**不要求继承**，只要方法面对得上即可。落地时显式继承它可获得 mypy 的结构符合性检查。

| 方法 | 签名 | 何时被调 |
|---|---|---|
| `query` | `async (request: ServeRequest) -> QueryResponse` | 同步请求 |
| `stream_query` | `(request: ServeRequest) -> AsyncIterator[QueryChunk]` | 流式请求 |
| `start` | `async () -> None` | 启动期，处理器初始化 |
| `stop` | `async () -> None` | 关停期，资源回收 |
| `clear_session` | `async (conversation_id: str) -> None` | 会话清理 |
| `is_healthy` | `() -> bool` | 健康探测 |

`start` / `stop` / `clear_session` 有默认实现，不需要的处理器可以不写。

## 7. 数据对象

三个数据对象由上位设计定义，落在 `agent_runtime/domain/`：

| 对象 | 方向 | 承载 |
|---|---|---|
| `ServeRequest` | 入 | 框架中立的执行输入：查询正文、会话标识、元数据 |
| `QueryResponse` | 出（同步） | 一次执行的完整结果 |
| `QueryChunk` | 出（流式） | 增量输出、中断等待、错误失败三类块 |

**终态由流控制信号表达，不在数据对象里**：流正常结束即完成，异常传播即失败。`QueryChunk` 只有 chunk／interrupt／error 三类，没有「完成」这一类。

### 7.1 失败必须由异常驱动

处理器**不能以数据方式声明自己失败**——发一个错误块之后还必须让异常传播出去。只发块不传播会让失败静默丢失：入口层看不到异常就不会补失败终态帧，Task 会停在执行中而不是落 FAILED。

这条由 `deploy-e2e/run-versatile-down.sh` 的第四项断言守住。

## 8. 适配件契约

宿主不必自己实现 `AgentHandler`——本 runtime 自带几个适配件，各自把一类既有 Agent 包成该契约：

| 适配件 | 包装什么 | 配置段 |
|---|---|---|
| 本地框架适配 | 进程内的 Agent 框架实例 | `openjiuwen.service.extensions.handler` |
| 远端服务代理 | 独立部署的 Versatile HTTP/SSE 工作流 | `openjiuwen.service.versatile` |
| 宿主事件流处理器 | 只暴露 `initialize` + dict 事件流的宿主 Agent | 无 |

各自的请求构造、响应状态机、结果模式、错误语义见 `Feat-Func-002b` 的三份。

---

## 9. 相关文档

- 《配置参考》`doc/configuration.md`
- 《集成指南》`doc/integration-guide.md`
- 各特性详设 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-*b-*.md`
- 对外兼容面逐项清单 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/COMPAT-SURFACE-INVENTORY.md`
