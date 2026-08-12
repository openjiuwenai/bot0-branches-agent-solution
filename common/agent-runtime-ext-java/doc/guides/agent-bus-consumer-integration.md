# 接入 Agent Bus Consumer

本指南说明如何让一个已有的 Agent Runtime 订阅 Agent Bus 请求事件。业务开发者仍然只需提供
`AgentHandler`；Agent Bus SDK 和 `agent-service-bus-consumer` 负责 Broker 连接、事件订阅、A2A
请求桥接和响应发布。

## 1. 前置条件

- 宿主应用能够启动基础 Runtime，并已提供标准 A2A `RequestHandler` 和 `TaskStore`。
- 宿主已经注册一个可处理业务请求的 `AgentHandler`。
- Agent Bus Relay 和 Broker 可访问，所需 Topic 已按 Agent Bus 的部署要求准备。
- Runtime 的逻辑 `service-id` 和租户范围已经确定。

## 2. 添加依赖

在 Runtime 宿主中引入：

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-service-bus-consumer</artifactId>
  <version>0.1.0</version>
</dependency>
```

该模块依赖 Agent Bus SDK。宿主不需要实现 Broker consumer、producer 或 Topic 订阅接口。

### 2.1 从源码构建

`agent-service-bus-consumer` 在 `agent-runtime-ext-java` 聚合 POM 中属于可选 profile。默认构建
不会包含该模块。从聚合目录构建时必须激活同名 profile：

```bash
cd common/agent-runtime-ext-java
mvn -Pagent-service-bus-consumer clean install
```

只构建 Consumer 模块时可以直接进入其目录，不需要激活聚合 POM 的 profile：

```bash
cd common/agent-runtime-ext-java/agent-service-bus-consumer
mvn clean install
```

单模块构建要求其父 POM、Agent Bus SPI 和 Agent Bus SDK 已能从当前 Maven reactor、本地仓库或远程
仓库解析。业务应用直接依赖已经发布的 `agent-service-bus-consumer` JAR 时也不需要 `-P`；
`-Pagent-service-bus-consumer` 只控制聚合工程是否参与构建，不是 Runtime 的功能开关。

## 3. 配置

最小配置如下：

```yaml
openjiuwen:
  service:
    service-id: ${RUNTIME_SERVICE_ID}
    bus:
      consumer:
        enabled: ${AGENT_BUS_ENABLED:false}

agent-bus:
  role:
    runtime:
      enabled: ${openjiuwen.service.bus.consumer.enabled}
    caller:
      enabled: ${openjiuwen.service.bus.consumer.enabled}
  nameserver: ${AGENT_BUS_NAMESERVER:127.0.0.1:9876}
  tenant: ${AGENT_BUS_TENANT}
  event-bus-service-id: ${AGENT_BUS_EVENT_BUS_SERVICE_ID}
```

配置含义：

| 配置项 | 作用 |
|---|---|
| `openjiuwen.service.service-id` | Runtime 的稳定逻辑服务 ID，用来匹配目标为本 Runtime 的请求事件 |
| `openjiuwen.service.bus.consumer.enabled` | Runtime 对外唯一的 Agent Bus 开关；同时启用入站消费和 Runtime 间 Bus Caller |
| `agent-bus.role.runtime.enabled` | SDK 内部映射项，值引用公共开关，装配请求 consumer 和响应 producer |
| `agent-bus.role.caller.enabled` | SDK 内部映射项，值引用公共开关，装配请求 submitter 和响应 consumer |
| `agent-bus.nameserver` | Broker 名称服务地址 |
| `agent-bus.tenant` | 本 Runtime 订阅和发布事件时使用的租户范围 |
| `agent-bus.event-bus-service-id` | Agent Bus Relay 的逻辑服务 ID |

普通部署不需要调整 consumer 的内部并发、轮询、重试和修复参数。确有容量调优需要时，可在
`openjiuwen.service.bus.consumer.tuning` 下设置：

```yaml
openjiuwen:
  service:
    bus:
      consumer:
        tuning:
          poll-interval: 1s
          payload-max-in-flight: 16
          bridge-max-in-flight: 16
          projection-max-in-flight: 16
          response-relay-max-attempts: 5
          response-relay-backoff: 100ms
          repair-interval: 5s
```

## 4. 业务 Handler

业务 Handler 与普通 A2A Runtime 相同，不需要增加 Agent Bus 专用方法。例如：

```java
@Bean
AgentHandler agentHandler() {
    return new MyAgentHandler();
}
```

Agent Bus 创建、流式创建和查询请求最终复用基础 Runtime 的 A2A `RequestHandler`，并由其调用该
`AgentHandler`。业务 Handler 不应直接消费 Topic，也不应发布 Task 投影事件。

## 5. 启动与检查

推荐按以下顺序启动：

1. 启动 Broker。
2. 启动 Agent Bus Relay。
3. 启动 Runtime 宿主。
4. 启动 Gateway 或调用端。

Runtime 启动时会检查必要装配。如果缺少 `service-id`、租户、A2A `TaskStore`、
`runtimeRequestConsumer` 或 `runtimeResponseProducer`，应用会直接启动失败，而不是在收到首条
消息后才报错。

成功启动后应能观察到：

- Agent Bus SDK 已创建名为 `runtimeRequestConsumer` 和 `runtimeResponseProducer` 的 Bean。
- consumer 按租户和 `service-id` 订阅发给本 Runtime 的请求事件。
- 收到创建请求后，Runtime 执行业务 Handler，并发布 `*_ACCEPTED` 以及后续响应或状态事件。
- 收到查询请求后，Runtime 按 payload 中的 `taskId` 查询自身 `TaskStore`。

## 6. 流式调用

Agent Bus 只传输流式控制面事件，不传输 token chunk 或 SSE frame：

1. 流式创建请求通过 Agent Bus 到达 Runtime。
2. Runtime 调用 A2A `SendStreamingMessage` 语义，并等待第一个可观察流事件。
3. Runtime 发布 `*_ACCEPTED` 和 `*_STREAM_READY`，其中包含 `taskId` 和 `streamRef`。
4. Gateway 使用这些信息访问 Runtime 的标准 A2A `SubscribeToTask` HTTP/SSE 入口。
5. 后续流数据经点对点 SSE 返回，不再经过 Agent Bus。

`streamRef` 是当前进程生成的、与租户和 Task 绑定的短期接续凭据。它不是 SSE 数据本身。

## 7. 排障

| 现象 | 检查项 |
|---|---|
| 启动提示缺少 delivery adapter | 确认两个 SDK role 属性均引用公共开关，并检查 Agent Bus SDK 是否成功装配 |
| 启动提示缺少 response publisher | 检查公共开关、Broker 地址和 Agent Bus SDK 装配结果 |
| Runtime 收不到事件 | 检查 tenant、目标 `service-id`、Relay 和 Topic 是否一致 |
| 请求被判定为 Task 不存在 | 查询/订阅 payload 必须携带真实 `taskId`，不能使用 client invocation ID 代替 |
| 流式请求只有 `STREAM_READY` | 这是控制面预期行为；后续数据应从 A2A `SubscribeToTask` SSE 获取 |
| `payloadRef` 请求返回 payload 为空 | 当前自动装配只读取内联 payload，尚不解析仅含 `payloadRef` 的正文 |
| 重启后重复处理历史请求 | 当前受理记录和响应投影记录保存在内存中，不跨进程重启恢复 |

## 8. 示例与相关文档

- [agent-bus-consumer-demo](../../../example/agent-bus-consumer-demo)
- [Agent Bus Consumer 特性](../features/agent-bus-consumer.md)
- [扩展模块 README](../../README.md)
