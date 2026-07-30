# Gateway 对接测试手册

> 给 gateway 负责同事：在你 Windows 本地用我的 verification-app 调试页面对接你自己实现的 gateway。
> 全程只需做三件事：① 填 gateway 地址 ② 备好环境并启动 verification-app ③ 用页面调试 / 看日志。

---

## ① 填 gateway 地址

verification-app 通过环境变量 `AGENT_GATEWAY_URL` 找你的 gateway。**先启动你自己的 gateway**，确认它在本机监听的地址和端口（例如 `http://127.0.0.1:8080`），然后在**启动 verification-app 的同一个终端**里执行：

```powershell
set AGENT_GATEWAY_URL=http://127.0.0.1:8080
```

把 `8080` 换成你 gateway 实际监听的端口。

**说明**：
- 这个变量是 verification-app 连 gateway 的**唯一配置点**，没有配置文件。
- 给的是 baseUrl（不含 `/a2a`）。SDK 会自动在末尾追加 `/a2a`，所以你的 gateway 必须在 `POST /a2a` 路径上接收请求。如果你的 gateway 入口路径不是 `/a2a`，要么在 gateway 侧把入口挂到 `/a2a`，要么在变量里直接写完整地址（如 `http://127.0.0.1:8080/a2a`，SDK 检测到已含 `/a2a` 就不再追加）。
- 每个请求会带 `Authorization: Bearer mock-token`。你的 gateway 至少要校验"存在且非空"，缺失/空一律 401。

---

## ② 备好环境并启动 verification-app

### 一次性准备：检查 Java 和 Maven

```powershell
java -version
mvn -version
```

两条都要求能打印出版本号。需要 **JDK 17+** 和 **Maven 3.9+**。若报"不是内部命令"，先安装对应软件并配好 PATH。

### 一次性准备：构建本工程

```powershell
cd "d:\java版EDPA重构\正秋的agent-solution仓\agent-solution_dxn\common\example\agent-client-demo"
mvn -q -o clean package
```

构建成功后在 `verification-app\target\` 下生成 `verification-app.jar`。

### 每次启动 verification-app（三行，顺序执行）

```powershell
cd "d:\java版EDPA重构\正秋的agent-solution仓\agent-solution_dxn\common\example\agent-client-demo"
set AGENT_GATEWAY_URL=http://127.0.0.1:8080
java -cp "verification-app\target\verification-app.jar;..\..\agent-client\agent-client-sdk-for-jvm\target\agent-client-sdk-for-jvm.jar;mock-gateway\target\mock-gateway.jar;%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-databind\2.17.3\jackson-databind-2.17.3.jar;%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-core\2.17.3\jackson-core-2.17.3.jar;%USERPROFILE%\.m2\repository\com\fasterxml\jackson\core\jackson-annotations\2.17.3\jackson-annotations-2.17.3.jar" com.openjiuwen.client.verify.CloudClientVerification --ui
```

看到下面的输出即启动成功，保持这个终端不要关：

```
======================================================
  agent-client 验证控制台已启动
  请在浏览器打开: http://127.0.0.1:9090/
  按 Ctrl+C 结束
======================================================
```

**说明**：
- `AGENT_GATEWAY_URL` 必须在启动 verification-app **之前**设好；如果忘了设或设空，verification-app 会直接报错退出，不会启动。
- classpath 里的三个 jar（`verification-app` / `agent-client-sdk-for-jvm` / `mock-gateway`）都要带上。`mock-gateway.jar` 在页面调试模式下不会真正运行，但代码加载时需要它在 classpath 上。`agent-client-sdk-for-jvm.jar` 在上级 `..\..\agent-client\agent-client-sdk-for-jvm\target\` 下（SDK 本体归 agent-client 模块）。
- 三个 Jackson jar 是 SDK 的运行时依赖，首次构建后会在 `%USERPROFILE%\.m2\repository\` 下。如果版本号不同（构建时 Maven 会提示实际版本），按你本地的实际版本号替换。
- 自己的端口可用 `set UI_PORT=9090` 改（默认 9090）。
- 启动后**保持终端开着**，所有调试日志都会打印在这里（见 ③）。

---

## ③ 用页面调试 / 看日志

### 页面操作

浏览器打开 `http://127.0.0.1:9090/`。这是一个验证看板，跑的就是 CI 那份用例，页面分两栏：

- **左栏**：场景列表（12 个），每个场景显示分类、一句话考察点，以及本次的通过/失败计数。
  - 点右上角 **运行全部**，或勾选若干场景后点 **运行选中**，只跑你关心的那几个。
  - 点场景卡片可把右栏过滤成只看这个场景；再点一次取消过滤。
- **右栏**：逐条断言，✓/✗ 加原始断言文案（与 CLI 输出一致，便于直接贴给对方定位）。
- 顶栏显示当前连接的 gateway 地址（外接时应显示 `http://127.0.0.1:8080 (external)`），以及总体状态徽标。

### 看日志

verification-app 启动的那个终端会实时打印调用过程，包括：
- 每次调用的 taskId、toolCallId
- 工具执行结果
- 断言通过/失败
- 异常栈

如果页面卡住或断言失败，**先看这个终端的日志**，通常能直接定位是 gateway 返回了什么导致的问题。

### 你的 gateway 需要支持的协议（出现问题时对照）

| 项 | 要求 |
|----|------|
| 入口 | `POST /a2a`，JSON-RPC 2.0 |
| 鉴权 | 每个请求必须有 `Authorization: Bearer <非空>`，否则 401 `{"code":"AUTH_MISSING"}` |
| 方法 | `SendStreamingMessage`（SSE 流式响应，用于创建 STREAMING 调用）、`SendMessage`（单条 JSON，用于创建 BLOCKING/ASYNC 调用 + 一切续传）、`GetTask`（单条 JSON，状态查询） |
| 方法白名单 | 建议只放行上面三个；其余（如 `CancelTask` / `SubscribeToTask`）返回 `400 {"code":"VALIDATION_METHOD"}`。**客户端 v0730 不会调用这两个方法** |
| 状态查询 | `GetTask`，参数是 **`params.taskId`**（注意：不是 A2A 别名 `params.id`）。返回该 Task 当前权威快照。客户端在 ASYNC 观察、BLOCKING 推进、以及**流中断后确认真实进展**时都会调它 |
| 创建调用 | `params.message.taskId` 为空；按 `message.messageId` 幂等去重；读 `params.metadata.clientTools` 获取客户端工具清单 |
| 请求工具 | `INPUT_REQUIRED` 状态，工具调用意图放在 `result.status.message.metadata._interrupt`（`_interrupt_kind=client_tool`，含 `toolCallId`/`toolName`/`arguments`） |
| 续传 | 客户端对既有 `taskId` 再发**同步** `SendMessage`，正文为一个 text part（工具结果或用户输入文本），并稳定携带 `contextId`。**v0730 不在 wire 上回传 `toolCallId`**：同一时刻只有单个 pending，由 runtime 按该 pending 自动关联即可。`toolCallId` 只在客户端本地用于去重 |
| 中断即关流 | 投递 `INPUT_REQUIRED` 后关闭当前 SSE 流，等客户端续传后再开下一段。这是**约定行为**，客户端不会把它当作异常中断 |
| 完成 | `TASK_STATE_COMPLETED`，输出文本放在 artifact 或 `status.message.parts` |

当前的 12 个场景分别考察：

| 场景 | 考察点 | 对你 gateway 的要求 |
|------|--------|------------------|
| S1 | 流式 + 工具多轮：逐个 `_interrupt` 请求工具，收续传推进，全部完成转 COMPLETED | `SendStreamingMessage` + `_interrupt` + 续传推进 |
| S2 | BLOCKING 走同步接口拿最终结果；再用 STREAMING 跑一次单工具 | `SendMessage` 创建须返回该 Task 的**当前完整状态**（含输出文本），不要只回一个 working 占位 |
| S3 | 用户输入续传：`_interrupt_kind=user_input`，收用户输入后完成 | `_interrupt_kind=user_input` + 同步续传 |
| S4 / S5 | 不带工具的普通多轮，直接 COMPLETED | 同 conversationId 多次创建，各自独立 Task |
| S6 | 故意不带鉴权 token，期望 gateway 返回 401 | 401 + `{"code":"AUTH_MISSING"}` |
| S7 | ASYNC 受理后用 `GetTask` 观察进展 | **`GetTask` + `params.taskId`** |
| S8 | 流在非终态下中断，但服务端其实已完成 → 客户端靠 `GetTask` 恢复为终态，业务不感知中断 | 中断后 `GetTask` 仍能查到该 Task 的真实状态 |
| S9 | 流在非终态下中断且服务端仍在跑 → 客户端投影「进展不确定」并给出恢复线索，**不判失败、不悬挂** | 同上；此场景验证客户端行为，gateway 只需如实返回 WORKING |
| S10 | 续接一个已终态的调用，应拿到稳定错误码而非裸异常 | 纯客户端行为，不发请求到 gateway |
| S11 | 业务附加属性（trace / correlation）随请求上 wire | 读 `params.metadata.attributes`（字符串键值对，**为空时整段省略**）。参考实现会把 `attributes.traceId` 回显进输出文本以便断言；你的 gateway **不必**复刻回显，但需保证透传不报错 |
| S12 | 暴露窗口已关闭时不宣告任何端侧工具 | 与 S5 同构：`metadata.clientTools` 缺省，gateway 不应请求工具 |

> **S1** 里客户端会验证"每个工具恰好执行一次"（参考实现会故意重复投递一次 `INPUT_REQUIRED` 来测客户端去重，你的 gateway **不必**复刻，正常按序投递即可）。
>
> **S8 / S9** 是本次新增的断连场景。参考实现用输入文本前缀 `DROP_THEN_COMPLETE` / `DROP_STAYS_WORKING` 触发"发出首帧后直接关流"，你的 gateway **不必**复刻这个触发方式——这两个场景只在跑内置 mock gateway 时生效；对接你的 gateway 时，若不实现该触发，这两个场景会因收不到预期中断而失败，可暂时忽略，或按上表要求确认 `GetTask` 在中断后仍可用。
