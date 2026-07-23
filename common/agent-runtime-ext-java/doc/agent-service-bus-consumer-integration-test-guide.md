# agent-service-bus-consumer（FEAT-017）联调测试指南

本文说明如何在 Linux/macOS 上验证 `agent-service-bus-consumer` 与 agent-bus 的联调，包括不依赖外部服务的内存联调，以及使用本机 RocketMQ 进程的 broker 往返测试。本文不依赖 Docker。

除非特别说明，所有命令均在 `agent-solution` 仓库根目录执行。

## 1. 测试边界

FEAT-017 负责以下中间链路：

```text
agent-bus *_deliver
        |
        v
AgentBusBrokerDeliveryPort
        |
        v
RuntimeBusEventConsumer -> A2A bridge -> Task/响应投影
        |
        v
AgentBusOutboxResponsePublisher
        |
        v
agent-bus *_resp_in
```

FEAT-013/014 负责外围两跳：`*_req -> *_deliver` 和 `*_resp_in -> *_resp_out`，以及 Postgres outbox/inbox、relay 重试和 DLQ。FEAT-017 的测试不复制这些实现，而是使用 agent-bus 提供的 SPI、testkit 和 RocketMQ adapter 验证双方边界契约。

对应测试文件：

- `AgentBusInMemoryIntegrationTest`：使用 `agent-bus-testkit` 的 `InMemoryBroker` 模拟 gateway、bus 和 runtime。
- `RealRocketMqFeat017IntegrationTest`：使用真实 `RocketMqBrokerForwardingRelay` 和 `RocketMqBrokerForwardingConsumer` 验证 RocketMQ 往返。
- `Feat017BusIntegrationSupport`：构造标准 agent-bus envelope、运行时 A2A bridge fixture 和响应 outbox fixture。

## 2. 内存联调

### 2.1 覆盖场景

内存联调不需要 RocketMQ，覆盖：

1. gateway/bus 将 `CLIENT_INVOCATION_REQUESTED` 投递到 `*_deliver`。
2. FEAT-017 按 tenant 和 target service 订阅并消费。
3. 请求进入与标准 A2A 入口等价的 bridge fixture。
4. runtime 输出 `INVOCATION_ACCEPTED` 和 `INVOCATION_RESPONSE`。
5. 响应通过 agent-bus outbox envelope 回到 `*_resp_in`。
6. 同一 `tenantId + idempotencyKey` 重投时，A2A bridge 只执行一次。
7. `CLIENT_INVOCATION_QUERY_REQUESTED` 通过标准 `GetTask` 语义查询 runtime 自有 TaskStore。
8. 查询以 payload 中的服务端 `taskId` 为准，不能用 gateway 的 `clientInvocationId` 替代。

### 2.2 执行命令

先安装 agent-bus 模块，使 runtime 扩展工程能解析 SPI、SDK 和 testkit：

```bash
mvn -f common/agent-bus/pom.xml \
  -pl agent-bus-spi,agent-bus-testkit,agent-bus-sdk -am \
  -DskipTests install
```

运行内存联调：

```bash
mvn -f common/agent-runtime-ext-java/pom.xml \
  -pl agent-service-bus-consumer -am test \
  -Dtest=AgentBusInMemoryIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期结果：3 个测试通过，外部 RocketMQ 未启动也不影响执行。

## 3. 真实 RocketMQ 联调

### 3.1 测试拓扑

```text
bus/gateway fixture
  -> ascend_bus_{invocation|a2a}_deliver
  -> RocketMQ
  -> FEAT-017 runtime consumer
  -> A2A bridge fixture
  -> ACCEPTED + RESPONSE outbox envelope
  -> RocketMQ
  -> ascend_bus_{invocation|a2a}_resp_in
  -> bus response verifier
```

该测试覆盖 invocation 和 A2A 两个事件族。consumer group 每次运行带随机后缀，避免共享 broker 上旧消费位点干扰。

Task 查询场景会在 runtime TaskStore 中同时保存目标 Task 和一个以 `clientInvocationId` 命名的诱饵 Task。Gateway 查询 payload 使用：

```json
{
  "jsonrpc": "2.0",
  "method": "GetTask",
  "params": {
    "id": "runtime-task-id",
    "historyLength": 10,
    "tenant": "tenant-a"
  },
  "clientInvocationId": "client-invocation-decoy"
}
```

runtime 必须只以 `params.id` 查询 TaskStore，并发布携带 `runtime-task-id` 的 `INVOCATION_RESPONSE`。如果实现错误地使用 `clientInvocationId`，测试会查到诱饵 Task 并因 taskId 不一致而失败。

### 3.2 准备本机环境

要求：

- 64 位 Linux 或 macOS。
- 已安装 JDK，并且 `java -version` 可以正常执行。建议与本工程统一使用 JDK 17。
- 已安装 `curl` 和 `unzip`。

macOS 通过 Homebrew 安装 JDK 17 后，如果 `/usr/bin/java` 仍然找不到 Java，需要在当前终端显式设置：

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export PATH="${JAVA_HOME}/bin:${PATH}"
java -version
mvn -version
```

Linux 应按发行版的 JDK 安装路径设置 `JAVA_HOME`，并同样确认 `java -version` 和 `mvn -version` 都使用 JDK 17。

本测试使用 RocketMQ Broker 5.3.1；agent-bus 当前使用 RocketMQ Client 5.1.4。该组合已通过本文真实 Broker 联调验证。下载并解压 Broker 官方二进制包：

```bash
export ROCKETMQ_VERSION="5.3.1"
export ROCKETMQ_RUNTIME_DIR="/tmp/feat017-rocketmq"
export ROCKETMQ_ARCHIVE="${ROCKETMQ_RUNTIME_DIR}/rocketmq-${ROCKETMQ_VERSION}.zip"
export ROCKETMQ_INSTALL_DIR="${ROCKETMQ_RUNTIME_DIR}/rocketmq-all-${ROCKETMQ_VERSION}-bin-release"

mkdir -p "${ROCKETMQ_RUNTIME_DIR}"
curl -fL \
  "https://archive.apache.org/dist/rocketmq/${ROCKETMQ_VERSION}/rocketmq-all-${ROCKETMQ_VERSION}-bin-release.zip" \
  -o "${ROCKETMQ_ARCHIVE}"
unzip -q "${ROCKETMQ_ARCHIVE}" -d "${ROCKETMQ_RUNTIME_DIR}"
```

如果已经安装了相同版本，只需把 `ROCKETMQ_INSTALL_DIR` 指向其解压目录。可执行以下命令确认目录正确：

```bash
test -x "${ROCKETMQ_INSTALL_DIR}/bin/mqnamesrv"
test -x "${ROCKETMQ_INSTALL_DIR}/bin/mqbroker"
test -x "${ROCKETMQ_INSTALL_DIR}/bin/mqadmin"
java -version
```

### 3.3 启动 NameServer 和 Broker

先启动 NameServer：

```bash
mkdir -p "${ROCKETMQ_RUNTIME_DIR}/logs" "${ROCKETMQ_RUNTIME_DIR}/store"
export JAVA_OPT_EXT="-server -Xms512m -Xmx512m -Xmn128m"

nohup sh "${ROCKETMQ_INSTALL_DIR}/bin/mqnamesrv" \
  > "${ROCKETMQ_RUNTIME_DIR}/logs/nameserver.log" 2>&1 &
echo $! > "${ROCKETMQ_RUNTIME_DIR}/nameserver.pid"
```

等待 NameServer 就绪，并确认日志出现 `The Name Server boot success`：

```bash
for attempt in {1..30}; do
  grep -q "The Name Server boot success" \
    "${ROCKETMQ_RUNTIME_DIR}/logs/nameserver.log" && break
  sleep 1
done

grep "The Name Server boot success" \
  "${ROCKETMQ_RUNTIME_DIR}/logs/nameserver.log" || exit 1
```

再启动 Broker。仓库内的 `broker.conf` 使用 `127.0.0.1:9876`，适用于测试进程和 RocketMQ 都在同一台机器的场景：

```bash
export FEAT017_BROKER_CONFIG="${PWD}/common/agent-runtime-ext-java/agent-service-bus-consumer/src/test/resources/e2e/rocketmq/broker.conf"

nohup sh "${ROCKETMQ_INSTALL_DIR}/bin/mqbroker" \
  -n 127.0.0.1:9876 \
  -c "${FEAT017_BROKER_CONFIG}" \
  > "${ROCKETMQ_RUNTIME_DIR}/logs/broker.log" 2>&1 &
echo $! > "${ROCKETMQ_RUNTIME_DIR}/broker.pid"
```

等待 Broker 启动并注册到 NameServer：

```bash
for attempt in {1..60}; do
  grep -q "The broker\[broker-a.*boot success" \
    "${ROCKETMQ_RUNTIME_DIR}/logs/broker.log" && break
  sleep 1
done

grep "The broker\[broker-a.*boot success" \
  "${ROCKETMQ_RUNTIME_DIR}/logs/broker.log" || exit 1
sh "${ROCKETMQ_INSTALL_DIR}/bin/mqadmin" clusterList -n 127.0.0.1:9876
```

`clusterList` 必须包含 `DefaultCluster`、`broker-a` 和 `127.0.0.1:10911`，再继续创建 Topic。

`broker.conf` 已启用 `enablePropertyFilter=true`。这是 tenantId 和 targetServiceId SQL92 broker-side filter 生效的必要条件。

### 3.4 创建测试 Topic

```bash
for topic in \
  ascend_bus_invocation_req ascend_bus_invocation_deliver \
  ascend_bus_invocation_resp_in ascend_bus_invocation_resp_out \
  ascend_bus_a2a_req ascend_bus_a2a_deliver \
  ascend_bus_a2a_resp_in ascend_bus_a2a_resp_out; do
  sh "${ROCKETMQ_INSTALL_DIR}/bin/mqadmin" updatetopic \
    -n 127.0.0.1:9876 \
    -c DefaultCluster \
    -t "${topic}" || exit 1
done

sh "${ROCKETMQ_INSTALL_DIR}/bin/mqadmin" topicList \
  -n 127.0.0.1:9876 | grep '^ascend_bus_'
```

预期能看到 8 个 `ascend_bus_*` topic。

### 3.5 执行真实 broker 测试

Linux/macOS：

```bash
export ROCKETMQ_NAMESERVER=127.0.0.1:9876

mvn -f common/agent-runtime-ext-java/pom.xml \
  -pl agent-service-bus-consumer -am test \
  -Dtest=RealRocketMqFeat017IntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

预期结果：

- `invocationRequestTraversesDeliverRuntimeAndResponseIngress` 通过。
- `a2aRequestTraversesDeliverRuntimeAndResponseIngress` 通过。
- `clientInvocationQueryUsesRuntimeTaskIdInsteadOfClientInvocationId` 通过。
- 请求分别经过 `ascend_bus_invocation_deliver` 和 `ascend_bus_a2a_deliver`。
- 响应分别进入 `ascend_bus_invocation_resp_in` 和 `ascend_bus_a2a_resp_in`。
- 每个请求都产生 ACCEPTED 和 RESPONSE，且 tenant、target service、correlationId 和 taskId 保持一致。
- Task 查询只产生 `INVOCATION_RESPONSE`；TaskStore 的查询参数和响应 taskId 都必须等于 payload 中的服务端 taskId，且响应不得把 `clientInvocationId` 当成 taskId。

如果未设置 `ROCKETMQ_NAMESERVER`，该测试会自动跳过，不影响普通单元测试。

## 4. 与 FEAT-013/014 完整 relay 联调

真实 RocketMQ 测试直接从 `*_deliver` 开始，并在 `*_resp_in` 结束，准确覆盖 FEAT-017 的职责边界。需要验证完整链路时，组合执行：

1. FEAT-013/014 的 `RealBrokerTwoHopRelayIntegrationTest`，确认 gateway、Postgres outbox/inbox 和 event-bus relay 的两跳治理。
2. 本文的 `RealRocketMqFeat017IntegrationTest`，确认真实 runtime 消费与响应回写。
3. 使用同一组 8 个 RocketMQ topic、tenant 和 serviceId 做多进程验收。

完整责任链如下：

```text
gateway -> *_req -> event-bus relay -> *_deliver
       -> FEAT-017 runtime -> *_resp_in
       -> event-bus response relay -> *_resp_out -> gateway
```

Postgres、Flyway、relay fat-jar 和数据库检查命令沿用 `feat-013-014-test-guide.md`。本文启动的本机 RocketMQ 只服务于 FEAT-017 broker 边界测试，不启动 Postgres 和 relay 进程。

## 5. 常见问题

### 5.1 测试一直收不到消息

确认：

- `ROCKETMQ_NAMESERVER` 指向宿主机可访问的 NameServer。
- `nameserver.log` 和 `broker.log` 均出现 `boot success`，且没有端口占用或 JVM 内存错误。
- Broker 对测试进程发布的地址可达；本地配置使用 `brokerIP1=127.0.0.1`。
- `mqadmin topicList` 能列出 8 个 `ascend_bus_*` topic。
- Broker 配置包含 `enablePropertyFilter=true`。

检查本机进程和端口：

```bash
ps -p "$(cat /tmp/feat017-rocketmq/nameserver.pid)"
ps -p "$(cat /tmp/feat017-rocketmq/broker.pid)"
sh "${ROCKETMQ_INSTALL_DIR}/bin/mqadmin" clusterList -n 127.0.0.1:9876
```

### 5.2 报 SQL92 filter 不支持

说明 Broker 没有加载本目录的 `broker.conf`，或者启动的是另一套 Broker。先停止本机 Broker，再按 3.3 节指定 `-c "${FEAT017_BROKER_CONFIG}"` 重新启动：

```bash
sh "${ROCKETMQ_INSTALL_DIR}/bin/mqshutdown" broker
```

### 5.3 Broker 启动时提示内存不足

确认启动前设置了较小的测试堆：

```bash
export JAVA_OPT_EXT="-server -Xms512m -Xmx512m -Xmn128m"
```

如果机器资源仍不足，可以进一步降低 `-Xms` 和 `-Xmx`，但二者应保持一致。

### 5.4 Maven 找不到 agent-bus-testkit

先执行：

```bash
mvn -f common/agent-bus/pom.xml \
  -pl agent-bus-spi,agent-bus-testkit,agent-bus-sdk -am \
  -DskipTests install
```

再执行 runtime 扩展工程的测试。

## 6. 清理环境

```bash
sh "${ROCKETMQ_INSTALL_DIR}/bin/mqshutdown" broker
sh "${ROCKETMQ_INSTALL_DIR}/bin/mqshutdown" namesrv
```

确认不再需要日志、消息数据和下载包后，可删除本次测试专用目录：

```bash
rm -rf /tmp/feat017-rocketmq
```

如果 RocketMQ 运行在另一台机器上，需要把 `broker.conf` 的 `brokerIP1` 改成测试机可访问的地址，同时把 `ROCKETMQ_NAMESERVER` 改成远端 NameServer 地址，并确保网络允许访问 9876、10909 和 10911 端口。

## 7. 实际验证记录

2026-07-23 已在以下 macOS 环境完整执行第 2、3、6 节：

| 项目 | 实际值/结果 |
| --- | --- |
| 操作系统 | macOS 26.5.2，Apple Silicon arm64 |
| JDK | Homebrew OpenJDK 17.0.19 |
| RocketMQ Broker | 5.3.1 |
| agent-bus RocketMQ Client | 5.1.4 |
| Topic | 8 个 `ascend_bus_*` Topic 全部创建成功 |
| `AgentBusInMemoryIntegrationTest` | 3 个通过，0 失败，0 错误，0 跳过 |
| `RealRocketMqFeat017IntegrationTest` | 3 个通过，0 失败，0 错误，0 跳过 |
| `agent-service-bus-consumer` 全量测试 | 39 个通过，0 失败，0 错误，0 跳过 |

本记录只证明上述 macOS 环境已验证；Linux 命令保持 POSIX shell 兼容，但仍应在目标 Linux 发行版上复验。
