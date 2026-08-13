# Agent Bus Runtime demos

该目录包含两个独立的 Spring Boot Runtime，不再通过 profile 在同一份 Handler 中切换角色。

| 模块 | 默认端口 | 默认 serviceId | 用途 |
| --- | --- | --- | --- |
| `agent-bus-consumer-caller-demo` | 18080 | `agent-bus-consumer-caller-demo` | 源 Runtime；收到请求后通过 `a2a_delegate` 调用 `target-agent` |
| `agent-bus-consumer-callee-demo` | 18081 | `agent-bus-consumer-callee-demo` | 目标 Runtime；提供普通、流式、INPUT_REQUIRED 和 client-tool 续接场景 |

构建：

```bash
mvn clean package
```

分别启动：

```bash
java -jar agent-bus-consumer-callee-demo/target/agent-bus-consumer-callee-demo-0.1.0.jar
java -jar agent-bus-consumer-caller-demo/target/agent-bus-consumer-caller-demo-0.1.0.jar
```

用于 Runtime A → Runtime B 验证时，RDC 至少需要以下映射：

```text
caller-agent -> agent-bus-consumer-caller-demo -> http://127.0.0.1:18080
target-agent -> agent-bus-consumer-callee-demo -> http://127.0.0.1:18081
```

运行 agent-client S1–S13 时，将 `demo-a2a-agent-a` 和默认 Agent 映射到 callee 即可；caller 进程
用于独立验证 Runtime 间调用，不参与 client → Gateway → Runtime 的目标侧业务模拟。

S1–S13 推荐映射：

```text
demo-a2a-agent-a -> agent-bus-consumer-callee-demo -> http://127.0.0.1:18081
travel-hotel     -> agent-bus-consumer-callee-demo -> http://127.0.0.1:18081
```

`CalleeAgentHandler` 中只保留目标端业务模拟：普通/流式返回、client tool 多轮、
`user_input` 续传以及 trace/agentId 透传验证。`CallerAgentHandler` 只负责发起
`a2a_delegate` 并在 `runtime.remoteToolResults` 到达后收口，两者不再共用业务 Handler。
