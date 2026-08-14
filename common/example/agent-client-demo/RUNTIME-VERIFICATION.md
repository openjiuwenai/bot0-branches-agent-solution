# Agent Client -> Mock Runtime 本地验证

本目录新增两个验证模块：

- `mock-runtime`：脚本化 A2A Runtime，默认监听 `http://127.0.0.1:19090/a2a`。
- `verification-app-to-runtime`：集成 `agent-client-sdk-for-jvm` 的 Web UI，默认监听 `http://127.0.0.1:18080/`。

## Windows 启动

在 PowerShell 中进入本目录后执行：

```powershell
.\start-runtime-verification.ps1
```

脚本会离线构建两个模块、后台启动服务并打印 UI 地址。自定义端口：

```powershell
.\start-runtime-verification.ps1 -MockRuntimePort 19091 -VerificationAppPort 18081
```

停止服务：

```powershell
.\stop-runtime-verification.ps1
```

日志位于 `target/runtime-verification-logs/`。

若默认端口已被其他本地服务占用，请使用自定义端口启动。脚本只记录并停止自己本次
启动的监听进程，不会扫描或终止其他 Java 服务。

## UI 观测面

- Call Tree：STREAMING 模式下 SDK `InvocationCall.callTree()` 的实时快照。
- SDK Events：标准 `InvocationEvent` 时间线。
- Final Snapshot：`InvocationSnapshot`，包括恢复线索和树完整度。
- Runtime Wire：Mock Runtime 实际收到的 A2A JSON-RPC 请求，并可核对 Runtime 恢复请求未携带 `Last-Event-ID`。
- Diagnostics：本地工具执行、GetTask 查询、Publisher 完成和异常信息。

验证 Runtime 隔离时，应确认 Runtime Wire 中没有 `Authorization` 和
`params.metadata.agentId`。`credentialToken`、保留租户属性和 `agentId` 会由 UI
故意传给 SDK，以验证 RuntimeEndpointPolicy 确实进行了隔离。

## 场景

场景覆盖普通流、五层树、B1/B2 交错、乱序 output、INPUT_REQUIRED、端侧工具、
断线重放、重复重放、cursor 过期、恢复熔断、创建结果未知和畸形拓扑。

`BLOCKING/ASYNC` 可与任意普通场景组合，但不会构造调用树。BLOCKING 在非终态响应后
由 SDK 有界查询 `GetTask`；ASYNC accepted 后不启动后台轮询，由验证应用显式调用
`getInvocation` 查询当前状态。

Mock Runtime 是确定性本地验证工具，不能替代与真实 Runtime 的契约测试和联调。
