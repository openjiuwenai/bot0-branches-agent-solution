# event-bus — 转发总线（两跳治理 relay + 生产/消费 SDK）

event-bus 是 Agent 解决方案的转发总线子平面：在 gateway / caller 与 runtime 之间提供**两跳 broker 转发**（req → deliver → resp），内置治理（去重 / 租户校验 / correlation 匹配 / poison 守卫）与可靠投递（JDBC outbox/inbox + Flyway）。生产进程为 `event-bus-relay`（独立 Spring Boot fat-jar，`eventbus` profile）；caller / runtime 通过 `event-bus-sdk` 以 Spring 自动装配接入，编程面向 SPI 端口。

## 项目概述

| 维度 | 说明 |
|---|---|
| 职责 | 两跳事件转发 + 治理（去重 / 租户 / correlation / poison）+ 可靠投递（outbox/inbox） |
| 主入口 | `com.openjiuwen.bus.EventBusRelayApplication`（`eventbus` profile，relay 唯一生产进程） |
| 持久化 | PostgreSQL（outbox/inbox 表，Flyway V1/V3/V4，由 `event-bus-sdk` 提供 migration） |
| Broker | RocketMQ（nameserver + broker + 8 topic） |
| 契约/模型 | `com.openjiuwen.bus.forwarding.spi`（纯 Java 端口 + 值类型 + 状态机 + 端点解析器） |
| 配置命名空间 | `agent-bus.*`（`AgentBusBrokerProperties`，flat 字段，无嵌套 `topics`） |
| Java 版本 | 17（reactor 属性 `event-bus.java.release`） |
| Spring Boot | 4.0.5 |

## 模块结构

| 模块 | artifactId | 说明 |
|---|---|---|
| 契约 | `event-bus-spi` | 纯 Java 契约：转发端口（`ForwardingOutboxPort` / `BrokerForwardingProducerPort` / `BrokerForwardingConsumerPort` 等）+ 值类型 + 状态机 + 端点解析器。零生产依赖 |
| 测试替身 | `event-bus-testkit` | InMemory broker / outbox / inbox / dispatcher / delivery 替身 + `TestAgentRuntime`。仅 test 作用域消费 |
| SDK | `event-bus-sdk` | 生产/消费 SDK：JDBC outbox/inbox + RocketMQ adapter + A2A 投递 + Flyway migration + Spring 自动装配（caller / runtime / relay 角色 + reliability） |
| Relay | `event-bus-relay` | 独立两跳治理 relay（Spring Boot fat-jar，`eventbus` profile）。唯一生产进程；不含 gateway / registry 组件 |

reactor pom：`common/agent-bus/event-bus/pom.xml`（artifactId `event-bus`，packaging `pom`，无 root pom）。reactor 构建顺序：spi → testkit → sdk → relay（testkit 看不到 sdk 类型）。

## 快速启动

### 前置：RocketMQ + Postgres

relay 启动需 Postgres（outbox/inbox + Flyway）+ RocketMQ nameserver/broker + 8 topic。编排文件随 `event-bus-relay` 测试资源提供：

```bash
cd common/agent-bus/event-bus/event-bus-relay/src/test/java/com/openjiuwen/bus/conf
docker compose up -d          # postgres(agentbus:5432) + rocketmq-nameserver(9876) + broker(10909/10911) + rocketmq-init
docker compose ps -a          # postgres/nameserver/broker=healthy；rocketmq-init=exited(0)（一次性，需 -a 可见）
# 就绪判据：nameserver/broker health=healthy 且 init 已 Exited(0) 再启动 relay（勿以"进程已启动"替代协议可用性）
# 重置（清积压 + 损坏的 consumer-group 订阅元数据）：docker compose down -v
```

broker.conf 需 `brokerIP1=127.0.0.1`、`enablePropertyFilter=true`（host 可达性 + 属性过滤）。

### 构建

```bash
cd common/agent-bus/event-bus
mvn install -DskipTests        # 落地 spi/sdk/testkit/relay 到 ~/.m2
```

> caller / runtime 消费方只需 `event-bus-spi`（编译）+ `event-bus-sdk`（运行 classpath）；relay 自身需 install 后 `spring-boot:run`。

### 运行 relay

```bash
cd common/agent-bus/event-bus
mvn -pl event-bus-relay spring-boot:run -Dspring-boot.run.profiles=eventbus
```

成功标志：`Started EventBusRelayApplication` + Flyway 建表 + `SUBSCRIBE forward relay` + `SUBSCRIBE response relay`。

> **冷启动**：relay 与消费方的 `DefaultLitePullConsumer` 首次 poll 前 ~30s rebalance 预热，首请求前等 30s，别误判卡死。
> relay 无 HTTP 端口，仅 broker client + JDBC。

### 测试

```bash
cd common/agent-bus/event-bus
mvn clean test                 # ECJ 会掩盖跨模块 stale-class，务必带 clean
```

broker 集成测试（`RealBroker*IT`）以 `@EnabledIfEnvironmentVariable(ROCKETMQ_NAMESERVER)` 守卫：**开发模式**下未设置环境变量时跳过、suite 保持 green（默认 green **不等于**真实 Broker 部署验收通过——被跳过的测试未触达 NameServer/Broker/Topic/SQL92 属性过滤）。对真实 broker 跑单个切片：

```bash
ROCKETMQ_NAMESERVER=host:9876 mvn -pl event-bus-relay test -Dtest=RealBrokerTwoHopRelayIntegrationTest
```

**验收模式（fail-closed）**：缺少 nameserver 立即非零退出，且要求三个 `RealBroker*IT` 执行数 > 0、`Skipped=0`：

```bash
# 位置参数（Windows/PowerShell 最简单，无需 env 透传）
bash common/agent-bus/event-bus/broker-acceptance.sh 127.0.0.1:9876
# 或环境变量
ROCKETMQ_NAMESERVER=127.0.0.1:9876 bash common/agent-bus/event-bus/broker-acceptance.sh
```

Windows/PowerShell（`bash` 不便时可用 `broker-acceptance.ps1`：交互模式默认末尾暂停避免窗口一闪而过；ASCII-only 以规避 Windows PowerShell 5.1 把无 BOM UTF-8 当 ANSI 误解析）：

```powershell
powershell -File common\agent-bus\event-bus\broker-acceptance.ps1 127.0.0.1:9876
# CI/无交互：加 -CI，不暂停、以退出码表达结果（失败=1）
```

> `RealBroker*IT` 属组件级真实 Broker 集成切片，不等同于已部署应用间的产品端到端验收。

## 配置

配置命名空间 `agent-bus.*`（`@ConfigurationProperties(prefix="agent-bus")` → `AgentBusBrokerProperties`）。所有值支持 `${VAR:default}` 环境变量覆盖。

```yaml
agent-bus:
  nameserver: ${AGENT_BUS_NAMESERVER:localhost:9876}
  namespace: ${AGENT_BUS_NAMESPACE:ascend-prod}
  producer-group: ${AGENT_BUS_PRODUCER_GROUP:eventbus-producer}
  tenant: ${AGENT_BUS_TENANT:tenant-a}
  gateway-service-id: ${AGENT_BUS_GATEWAY_SERVICE_ID:gateway-01}
  event-bus-service-id: ${AGENT_BUS_EVENT_BUS_SERVICE_ID:eventbus-01}
  poll-wait-millis: ${AGENT_BUS_POLL_WAIT_MILLIS:3000}
  role:
    caller:
      enabled: false          # caller（gateway / 任一生产方）：激活 requestProducer + responseConsumer
    runtime:
      enabled: false          # runtime（消费方）：激活 runtimeRequestConsumer
    relay:
      enabled: false          # relay 两跳角色（event-bus-relay 的 eventbus profile 内开启）
  reliability:
    enabled: true             # 激活 JdbcForwardingOutbox（需 DataSource）+ claim 端口
```

- `role.caller / runtime / relay.enabled` 三角色互斥开关；relay 进程开 `relay`，caller / runtime 各自进程开自己的角色
- `reliability.enabled=true` 时需配 `spring.datasource`（Postgres）；outbox/inbox 表由 `event-bus-sdk` 的 Flyway（V1/V3/V4）建

### Topic

topic 由 `DefaultBrokerTopicResolver` 按 `AgentBusEventType` 派生（`ascend_bus_<family>_<suffix>`），**不经配置项声明**。8 个 topic：`ascend_bus_invocation_{req,deliver,resp_in,resp_out}` + `ascend_bus_a2a_{req,deliver,resp_in,resp_out}`。

## 使用 SDK（caller / runtime 接入）

caller / runtime 把 `event-bus-sdk` 放到运行时 classpath（编译只依赖 `event-bus-spi`），按角色开关自动装配：

| 角色 | 进程 | 开关 | 自动装配 bean |
|---|---|---|---|
| caller | gateway / 任一生产方 | `agent-bus.role.caller.enabled=true` | `requestProducer`（产 req）+ `responseConsumer`（消费 resp_out） |
| runtime | 消费方 | `agent-bus.role.runtime.enabled=true` | `runtimeRequestConsumer`（消费 deliver）+ 产 resp_in |
| relay | `event-bus-relay` | `agent-bus.role.relay.enabled=true`（`eventbus` profile） | forward relay + response relay 两跳 worker |

caller 编程面向 SPI 端口（`ForwardingOutboxPort` / `BrokerForwardingProducerPort`），SDK 提供 JDBC / RocketMQ 实现并按类型注入 bean，无需自声明 broker client。
