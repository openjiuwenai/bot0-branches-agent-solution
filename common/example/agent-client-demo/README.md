# agent-client-demo（JVM 端云客户端示例）

> **状态：Prototype / 建立在拟议 API 之上。** 依据 version-scope `FEAT-006`（标准化智能体调用 +
> 状态管理）与 `FEAT-007`（本地工具 SPI + 远端多轮驱动）重构，wire 对齐 L2
> `Feat-Func-006 / Feat-Func-007` 与 runtime `Feat-Func-009`（A2A JSON-RPC 2.0 over HTTP + SSE）。
> 符号名待协议定稿后机械对齐；架构边界（分层、依赖红线、SPI、治理骨架、
> “执行成功 ≠ 已被服务端接收”等不变量）保持稳定。

## 工程定位

本 demo 位于 `common/example/agent-client-demo/`，是一个可独立构建的多模块工程，由三部分组成：

| 模块 | 物理位置 | 角色 | 是否 SDK 交付 | 说明 |
|------|----------|------|:---:|------|
| `agent-client-sdk-for-jvm` | `common/agent-client/agent-client-sdk-for-jvm/` | ★ SDK 本体（JVM 版） | **是** | `api` / `*.spi` / `internal` / `transport`。公共 API 不泄漏第三方类型；Jackson 仅在 `transport.a2a` 内部用于编解码 A2A 报文。 |
| `mock-gateway` | `common/example/agent-client-demo/mock-gateway/` | ◇ 模拟网关+runtime | 否 | 纯 JDK `com.sun.net.httpserver` 实现 A2A 入口，可独立运行的小微服务。**SDK 对它零依赖。** |
| `verification-app` | `common/example/agent-client-demo/verification-app/` | ◇ 业务样例 + 可执行验收 | 否 | 注册本地工具、发起真实 HTTP 调用打到 `mock-gateway`，用退出码表达成败。 |

> SDK 命名说明：`agent-client-sdk-for-jvm` 明确这是面向 JVM 环境的 SDK 实现；
> 未来 `for-android` / `for-ios` / `for-harmony` 等多端 SDK 尚在建设中。
> 跨端能力由线协议中立性保证，不是靠现在写多份 SDK（详见 `agent-client/docs/device-portability-and-v1-delivery.md`）。

> `agent-client-sdk-for-jvm` 内另含 `transport.fake.InProcessFakeGateway`：**测试工具（非交付）**，
> 供无网络环境做纯逻辑单测；真实交付路径是 `transport.a2a.A2aHttpTransportProvider`。

## 这个 demo 验证了什么（全部经真实 HTTP 断言）

`verification-app` 内嵌启动 `mock-gateway`，再由 SDK 经真实 HTTP + SSE 发起调用，覆盖：

1. **STREAMING + client 工具多轮**：远端经 `_interrupt` 逐个请求工具 → SDK 自动就地执行并续传 → 完成。
2. **BLOCKING + client 工具**：非流式（`message/send`）路径同样能驱动多轮。
3. **用户输入续传**（`continueInput`）。
4. **取消**（`cancel`，本版本非 MUST，但 wire 已打通）。

关键不变量由断言守护：

- 每个工具 **恰好执行一次**（流式路径故意重复投递一次 `INPUT_REQUIRED`，验证去重）。
- **ACTION** 工具触发且仅触发一次审批；**OBSERVATION** 直接放行。
- 调用到达 `COMPLETED` / `CANCELED` 终态，快照查询与事件流一致。

## 构建

前置：JDK 17+、Maven 3.9+。本机 JDK 更高版本也行——父 `pom.xml` 用 `<release>17</release>`
把 API 与字节码锁定在 JDK 17 基线。

```bash
cd common/example/agent-client-demo
mvn -q -o clean package
```

产物（瘦 jar）：
- `../../agent-client/agent-client-sdk-for-jvm/target/agent-client-sdk-for-jvm.jar`
- `mock-gateway/target/mock-gateway.jar`
- `verification-app/target/verification-app.jar`

> 本工程刻意零 Web 框架、SDK 侧仅一个 Jackson 运行时依赖，保证离线 / 鲲鹏 aarch64 可构建。
> 因此不打 fat-jar（避免联网拉取 assembly/shade 插件依赖），改用 classpath 运行。

## 运行端到端自校验

先拼好 classpath（PowerShell，在 `common/example/agent-client-demo/` 下执行）：

```powershell
$m="$env:USERPROFILE\.m2\repository\com\fasterxml\jackson"
$cp=@(
  "..\..\agent-client\agent-client-sdk-for-jvm\target\agent-client-sdk-for-jvm.jar",
  "mock-gateway\target\mock-gateway.jar",
  "verification-app\target\verification-app.jar",
  "$m\core\jackson-databind\2.17.3\jackson-databind-2.17.3.jar",
  "$m\core\jackson-core\2.17.3\jackson-core-2.17.3.jar",
  "$m\core\jackson-annotations\2.17.3\jackson-annotations-2.17.3.jar"
) -join ';'
```

### 方式 A：命令行自检（跑完即退出）

```powershell
java -cp $cp com.huawei.ascend.client.verify.CloudClientVerification
Write-Host "exit=$LASTEXITCODE"   # 0 = 全部断言通过
```

### 方式 B：薄可视化前端（推荐不熟悉 Java 时使用）

**不需要 Node。** Java 进程内嵌一个小 HTTP 服务，浏览器打开即可：

```powershell
java -cp $cp com.huawei.ascend.client.verify.CloudClientVerification --ui
```

终端会打印类似：

```
  请在浏览器打开: http://127.0.0.1:9090/
```

在页面点「开始验证」，会实时看到 4 个场景的进度与绿/红断言。端口可用环境变量 `UI_PORT` 改。

> 前端是 `verification-app` 里的静态 HTML + 原生 JS（`src/main/resources/web/`），
> 经 JDK `HttpServer` 提供；**不要**为这个验证控制台再单独起 Node 工程。

Linux / macOS（classpath 分隔符为 `:`）：

```bash
M="$HOME/.m2/repository/com/fasterxml/jackson"
CP="../../agent-client/agent-client-sdk-for-jvm/target/agent-client-sdk-for-jvm.jar:mock-gateway/target/mock-gateway.jar:verification-app/target/verification-app.jar:\
$M/core/jackson-databind/2.17.3/jackson-databind-2.17.3.jar:\
$M/core/jackson-core/2.17.3/jackson-core-2.17.3.jar:\
$M/core/jackson-annotations/2.17.3/jackson-annotations-2.17.3.jar"
java -cp "$CP" com.huawei.ascend.client.verify.CloudClientVerification
echo "exit=$?"
```

## 把 mock-gateway 当独立微服务运行

也可以先独立起网关，再让 `verification-app` 通过环境变量指向它（发起真正跨进程 HTTP）：

```bash
# 终端 1：启动网关（默认 8080，或传端口/设 PORT）
java -cp "mock-gateway/target/mock-gateway.jar:$M/core/jackson-databind/2.17.3/jackson-databind-2.17.3.jar:$M/core/jackson-core/2.17.3/jackson-core-2.17.3.jar:$M/core/jackson-annotations/2.17.3/jackson-annotations-2.17.3.jar" \
  com.huawei.ascend.mockgateway.MockGatewayServer 8080

# 终端 2：让自校验连到外部网关
AGENT_GATEWAY_URL=http://127.0.0.1:8080 java -cp "$CP" com.huawei.ascend.client.verify.CloudClientVerification
```

## 预期输出

```
[verify] started embedded mock-gateway at http://127.0.0.1:xxxxx

== Scenario 1: STREAMING + client tools (real HTTP + SSE) ==
  [ok]   streaming invocation completed, state=COMPLETED
  [ok]   readPage executed exactly once despite duplicate INPUT_REQUIRED, actual=1
  [ok]   submitOrder executed exactly once, actual=1
  [ok]   approval requested exactly once for the ACTION tool, actual=1
... (Scenario 2/3/4) ...
ALL CHECKS PASSED
```

## Docker 一键验收

构建上下文为 `common/` 目录（SDK 与 demo 分属两个子目录，需要同时拷入容器）：

```bash
cd common
docker build -t ascend/agent-client-demo:0.2.0 -f example/agent-client-demo/Dockerfile .
docker run --rm ascend/agent-client-demo:0.2.0   # 退出码 0=全部断言通过，非 0=失败
```

## SDK 包结构（对齐设计四层）

```
agent-client-sdk-for-jvm : com.huawei.ascend.client
├── api/            公共 API：AgentClient / AgentClients / InvocationRequest / ContinueInputRequest
│                   / InvocationCall / InvocationEvent / TaskState / InvocationSnapshot / InvocationMode
├── tool.spi/       本地工具 SPI：LocalTool / LocalToolDescriptor / ToolExposurePolicy / ToolView
│                   / ToolInvocation / ToolExecutionRecord / ToolExecutionContext / LocalToolRegistry
├── spi/            治理扩展点：Governance(PolicyGuard / ApprovalProvider)
├── state.spi/      客户端状态存储 SPI：ClientStateStore（幂等去重 / 提交去重）
├── transport.spi/  传输抽象：TransportProvider / ToolWireSpec
├── transport.a2a/  ★ 默认传输：A2aHttpTransportProvider（真实 A2A JSON-RPC over HTTP+SSE）+ A2aJsonCodec
├── transport.fake/ 测试工具（非交付）：InProcessFakeGateway（进程内、无网络）
└── internal/       内核编排：DefaultAgentClient(invocationRef↔taskRef 映射) / ToolDispatcher
                    / DefaultToolRegistry / InMemoryStateStore / ObservationTextRenderer
```

## 标识与 wire 映射（对齐 Feat-Func-006 / 009）

| 业务概念 | 所有者 | A2A wire 字段 |
|----------|--------|---------------|
| `conversationId` | 业务应用 | `message.contextId` |
| `invocationId` / `invocationRef` | 客户端 | `message.messageId`（后续操作句柄） |
| `taskId` / `taskRef` | runtime | Task/事件的 `id` / `taskId`（SDK 内部映射，不外泄） |
| ToolView | 客户端 | `params.metadata.clientTools[{name,description,inputSchema}]`（`name = toolId`） |
| 工具调用意图 | runtime | `metadata._interrupt`（`kind=client_tool`/`user_input`） |
| 工具结果续传 | 客户端 | 对既有 `taskId` 的 `message/send|stream`，结果渲染为 `TextPart` |

## 已知边界

- 内存态状态存储，不承诺跨进程重启恢复；需要时换 `ClientStateStore` 的持久化实现。
- 不做工具沙箱：进程内护栏（有界执行器 + deadline + 异常边界），强隔离属宿主部署能力。
- 不依赖虚拟线程（JDK 17 基线）；升级 21+ 时执行器可平滑替换，公共 API 不变。
- 取消 / 重订阅本版本非 MUST：`cancel` 已打通 wire；`mock-gateway` 支持 `tasks/cancel`。
