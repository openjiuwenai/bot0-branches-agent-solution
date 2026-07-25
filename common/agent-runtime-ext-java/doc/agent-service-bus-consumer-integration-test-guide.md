# FEAT-017 agent-service-bus-consumer 联调测试指南

> 更新日期：2026-07-25
> 适用代码：agent-bus PR 124 角色化装配及 FEAT-017 runtime consumer

## 1. 联调目标

本指南验证以下链路：

```text
gateway/caller
  -> *_req
  -> agent-bus relay
  -> *_deliver
  -> agent-runtime（FEAT-017）
  -> *_resp_in
  -> agent-bus relay
  -> *_resp_out
  -> gateway/caller
```

三个进程职责如下：

| 进程 | agent-bus 角色 | 职责 |
|---|---|---|
| gateway/caller | `caller` | 向 `req` 发布请求，从 `resp_out` 接收响应 |
| agent-bus relay | `relay` | 完成 `req -> deliver` 和 `resp_in -> resp_out` 两跳转发 |
| agent-runtime | `runtime` | 从 `deliver` 接收请求，进入 A2A `RequestHandler`，向 `resp_in` 发布投影 |

FEAT-017 和 Demo 不创建 RocketMQ 客户端、不填写 Topic、不管理 Consumer Group。上述物理细节由
agent-bus SDK 的角色化自动装配完成。

## 2. 当前装配关系

Demo 只提供：

- Spring Boot 应用入口；
- 一个业务 `AgentHandler`；
- 部署配置。

引入 `agent-service-bus-consumer` 和 `agent-bus-sdk` 后，配置：

```yaml
agent-bus:
  role:
    runtime:
      enabled: true
```

agent-bus SDK 自动提供：

| Bean | 类型 | SDK 内部绑定 |
|---|---|---|
| `runtimeRequestConsumer` | `BrokerForwardingConsumerPort` | 消费 `deliver` |
| `runtimeResponseProducer` | `BrokerForwardingProducerPort` | 生产 `resp_in` |

FEAT-017 自动提供：

| 组件 | 作用 |
|---|---|
| `AgentBusBrokerDeliveryPort` | 使用 `runtimeRequestConsumer` 接收并确认消息 |
| `RuntimeBusEventConsumer` | 校验、幂等 admission、调用 A2A bridge |
| `AgentBusResponsePublisher` | 使用 `runtimeResponseProducer` 直接发布响应投影 |
| `BusResponseProjectionStore` / `BusResponseRelay` | 保存未发布投影，并对瞬时发布失败做有界重试 |

当前 runtime 角色采用 direct produce，不要求 Demo 创建 agent-bus outbox、dispatcher 或数据库。
生产级持久 admission/projection/TaskStore 仍属于后续可靠性接入。

## 3. 自动化测试

### 3.1 agent-bus SDK

```bash
cd agent-solution/common/agent-bus
mvn -pl agent-bus-sdk,agent-bus-testkit -am test
```

重点用例：

- `AgentBusRuntimeRoleAutoConfigurationTest`：验证 runtime 角色开关创建
  `runtimeRequestConsumer` 和 `runtimeResponseProducer`；
- `RocketMqBrokerForwardingProducerTest`：验证 direct produce 的 broker 映射；
- `BrokerForwardingPortsContractTest`：验证 producer/consumer SPI。

### 3.2 FEAT-017 内存联调

```bash
cd agent-solution/common/agent-runtime-ext-java
mvn -Pagent-service-bus-consumer \
  -pl agent-service-bus-consumer -am test -DskipITs
```

`agent-service-bus-consumer` 不在 `agent-runtime-ext-java` 的默认 reactor 中，避免
agent-bus 制品尚未发布时影响其他 runtime 扩展模块。只有显式启用同名 Maven profile
时才把 FEAT-017 加入构建。

`AgentBusInMemoryIntegrationTest` 使用 agent-bus testkit，只存在于测试作用域，覆盖：

- 创建请求进入 runtime；
- `ACCEPTED`、`RESPONSE` 投影直接进入 response producer；
- 相同幂等键不重复调用 A2A bridge；
-查询 Task 时使用 `taskId`，不使用 `clientInvocationId` 替代。

### 3.3 真实 RocketMQ adapter 测试

先确认 RocketMQ NameServer 和 Broker 已启动，并设置：

```bash
export ROCKETMQ_NAMESERVER=127.0.0.1:9876
```

然后执行：

```bash
cd agent-solution/common/agent-runtime-ext-java
mvn -Pagent-service-bus-consumer \
  -pl agent-service-bus-consumer -am \
  -DROCKETMQ_NAMESERVER="$ROCKETMQ_NAMESERVER" \
  -Djunit.jupiter.conditions.deactivate= \
  test
```

`RealRocketMqFeat017IntegrationTest` 会直接使用真实 agent-bus RocketMQ producer/consumer adapter，
验证 `deliver -> runtime -> resp_in`。未设置 `ROCKETMQ_NAMESERVER` 时该测试按条件跳过。

## 4. 构建 Demo

先安装当前 agent-bus 和 FEAT-017：

```bash
cd agent-solution/common/agent-bus
mvn -pl agent-bus-sdk -am install -DskipTests

cd ../agent-runtime-ext-java
mvn -Pagent-service-bus-consumer \
  -pl agent-service-bus-consumer -am install -DskipTests
```

再构建 Demo：

```bash
cd ../example/agent-bus-consumer-demo
mvn clean package
```

Demo 源码中不应出现以下内容：

- `org.apache.rocketmq.*`；
- `DefaultBrokerTopicResolver`；
- `deliver`、`resp_in` Topic suffix；
- Producer/Consumer Bean；
- `RuntimeResponseOutboxDispatcher`；
- `agent-bus-testkit` 生产依赖。

## 5. 启动 Runtime Demo

### 5.1 配置

默认配置位于：

```text
common/example/agent-bus-consumer-demo/src/main/resources/application.yml
```

部署时通常只需要覆盖：

```bash
export AGENT_BUS_NAMESERVER=127.0.0.1:9876
export AGENT_BUS_TENANT=tenant-a
export AGENT_BUS_NAMESPACE=ascend-prod
export RUNTIME_SERVICE_ID=agent-bus-consumer-demo
```

其中 `RUNTIME_SERVICE_ID` 同时用于当前临时的 `spring.application.name`。gateway 发布请求时，
`targetServiceId` 必须使用同一个值。FEAT-015/016 registry serviceId 接入后将替换这一临时来源。

### 5.2 启动

```bash
cd agent-solution/common/example/agent-bus-consumer-demo
mvn spring-boot:run
```

启动时应看到：

- agent-bus runtime role 自动配置生效；
- FEAT-017 自动配置生效；
- `runtimeRequestConsumer` 开始订阅；
- 不需要 Demo 自己创建 Topic resolver、consumer 或 producer。

## 6. Topic 准备

RocketMQ 环境需要存在以下 Topic：

```text
ascend_bus_invocation_req
ascend_bus_invocation_deliver
ascend_bus_invocation_resp_in
ascend_bus_invocation_resp_out
ascend_bus_a2a_req
ascend_bus_a2a_deliver
ascend_bus_a2a_resp_in
ascend_bus_a2a_resp_out
```

可由部署平台提前创建，也可在允许自动创建 Topic 的本地 Broker 上由首次发送触发。生产环境建议显式创建，
并按 agent-bus 部署规范设置权限和保留策略。runtime 和 Demo 不负责创建 Topic。

## 7. 三进程完整联调

### 7.1 启动顺序

1. 启动 RocketMQ NameServer 和 Broker。
2. 启动 PostgreSQL，供 agent-bus relay 的 durable inbox/outbox 使用。
3. 启动 agent-bus relay：

   ```bash
   cd agent-solution/common/agent-bus
   mvn -pl agent-bus-relay -am package -DskipTests
   java -jar agent-bus-relay/target/agent-bus-relay-0.1.0.jar \
     --spring.profiles.active=eventbus
   ```

4. 启动 `agent-bus-consumer-demo`。
5. 启动 gateway/caller，启用：

   ```yaml
   agent-bus:
     role:
       caller:
         enabled: true
   ```

agent-bus relay 所需 PostgreSQL 连接通过 `SPRING_DATASOURCE_URL`、
`SPRING_DATASOURCE_USERNAME` 和 `SPRING_DATASOURCE_PASSWORD` 提供。

### 7.2 查询 Task 场景

前置条件：runtime 的 `TaskStore` 中已存在 `taskId=runtime-task-1`。

gateway 发布：

```text
eventType = CLIENT_INVOCATION_QUERY_REQUESTED
targetServiceId = agent-bus-consumer-demo
payload.method = GetTask
payload.params.id = runtime-task-1
clientInvocationId = client-side-id
```

期望：

1. relay 将请求从 `invocation_req` 转发到 `invocation_deliver`；
2. runtime 角色 consumer 收到请求；
3. FEAT-017 调用 A2A `RequestHandler.onGetTask`；
4. `TaskStore.get` 使用 `runtime-task-1`；
5. runtime 角色 producer 向 `invocation_resp_in` 发布 `INVOCATION_RESPONSE`；
6. relay 转发到 `invocation_resp_out`；
7. 响应中的 Task 是 `runtime-task-1`，`clientInvocationId` 不得替代 `taskId`。

## 8. 故障定位

### Runtime 启动提示缺少 `runtimeRequestConsumer`

检查：

```yaml
agent-bus.role.runtime.enabled: true
```

并确认当前使用的是包含 PR 124 的 `agent-bus-sdk`。

### Runtime 启动提示缺少 `runtimeResponseProducer`

原因同上。FEAT-017 当前要求 runtime 角色的请求 consumer 和响应 producer 同时存在，避免启动成
“只能消费、不能响应”的半功能状态。

### 启动时尝试配置 DataSource

Demo 默认 `memory` profile 排除了 DataSource/Flyway 自动配置，并设置：

```yaml
agent-bus.reliability.enabled: false
```

runtime direct role 本身不需要数据库。relay 进程仍需要数据库。

### Runtime 收不到消息

依次确认：

1. gateway 的 `targetServiceId` 与 runtime 的 `spring.application.name` 一致；
2. tenant 一致；
3. relay 已订阅 `req` 并向 `deliver` 转发；
4. Topic 已创建；
5. NameServer 地址和 namespace 一致。

### 响应发布失败

`AgentBusResponsePublisher` 会把 `UNAVAILABLE`/`ROUTE_NOT_FOUND` 转为发布异常；
`BusResponseRelay` 对未发布投影做有界重试。检查 RocketMQ、namespace、Topic 和 relay 的
`resp_in` 订阅。
