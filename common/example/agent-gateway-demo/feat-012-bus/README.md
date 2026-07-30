# FEAT-012 BUS — Gateway example

对照联调套件 **R / B0–B3**。本目录证明 Gateway 侧可交付部分；**不宣称**真 BUS 两跳 E2E（B3）。

## 重要边界

| 事实 | 影响 |
|------|------|
| `A2aController` 按 `PathSelector` 分发 | `path-mode=bus` → `GatewayBusConfiguration` 装配 `BusForwarder`/`BrokerProjectionFeed`/`GatewayOutboxDispatcher` |
| Caller 装配 | **编译**只依赖 `agent-bus-spi`；**运行** classpath 需 `agent-bus-sdk`（本 demo 模块把 SDK 上 classpath → caller-role + reliability autoconfig：`requestProducer`/`responseConsumer`/`JdbcForwardingOutbox`）；caller outbox 泵 `GatewayOutboxDispatcher` |
| Fake 单测仍覆盖编排 | B1 用 `validate.sh --bus-unit`（不启 MQ） |
| 真 BUS 两跳 / 向刚 FEAT-017 | 联调用晓娜 integrate 分支；不等 !92 合入 |

## 怎么跑

```bash
# Suite R + B2（需 Gateway :8080；R 需 RDC+Runtime 或仅 --governance-only）
GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh
GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh --governance-only   # R 仅治理 + B2
GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh --r-only
GATEWAY_URL=http://127.0.0.1:8080 ./smoke.sh --b2-only

# Suite B1 — Fake BUS 单测（不启进程）
cd .. && ./validate.sh --bus-unit

# 一键：结构 + B1 + 联机 R/B2
cd .. && ./validate.sh --bus-unit --online
```

## 覆盖矩阵

### Suite R — DIRECT 回归（012 未破坏 011）

| 内容 | 入口 |
|------|------|
| G1～G5 / S2 / S5 / 幂等 / sticky / 流式 / 拓扑 | **复用** [`../feat-011-direct/smoke.sh`](../feat-011-direct/smoke.sh) |

`./smoke.sh` 默认先跑 Suite R。

### Suite B1 — Gateway 带桩（Fake outbox / projection / wait）

| L2 / 命题 | 单测类 |
|-----------|--------|
| path-mode 解析 / Spring 接线 | `PathSelectorTest`, `PathSelectorWiringTest` |
| SPI 在 classpath | `BusSpiClasspathTest` |
| 信封组装 / correlationId / tenant | `EnvelopeBuilderTest` |
| PayloadStore | `PayloadStoreTest` |
| enqueue / produce 失败 | `BusControlForwarderTest` |
| caller outbox 泵 | `GatewayOutboxDispatcherTest` |
| 同步编排 search→wait→fold | `BusForwarderTest` |
| 流式 / STREAM_READY / resume | `BusStreamingAndResumeTest` |
| 五态折叠 | `FiveStateFolderTest` |
| 双窗口超时 | `WaitWindowTest` |
| STREAM_READY 门闩 | `StreamReadyGateTest` |
| 同步断开释放 | `SyncDisconnectTest` |
| G4 complete/abort on BUS | `G4BusWiringTest` |
| 投影幂等 / 乱序 | `ProjectionDedupTest` |

### Suite B2 — 共享入口治理（path 切 bus 后仍须先过门）

| TC 面 | `./smoke.sh`（B2 段） | 注意 |
|-------|----------------------|------|
| G1-01～03 | ✅ | 无票 / 非 Bearer / 坏 Bearer |
| G3-02 / 03 / 05 | ✅ | 坏 JSON / 坏 method / 空 agentId |
| S5-01 未知 agent | ✅ | `ROUTE_NO_CANDIDATES` + 无拓扑 |
| MQ / 入队证据 | ❌ | **禁止**写成 BUS 通过 |

### Suite B3 — 真两跳 E2E

| 状态 | 说明 |
|------|------|
| **本 example 不跑** | facade 已按 `path-mode` 分流；仍需丁勇/向刚真栈 + Runtime consumer（联调 integrate 分支） |
| 场景基线 | 联调 `FEAT-012-联调/TESTCASES.md` 套件 B3 |

## 打桩对照

| 角色 | Suite R/B2 | Suite B1 | Suite B3 |
|------|------------|----------|----------|
| 晓娜 / 翼维 Client | curl | — | 验证台（后置） |
| 国庆 RDC / Runtime | 真栈或 011 stub | Fake 端口在单测内 | 真栈 |
| 丁勇/向刚 BUS | — | Fake Outbox/Feed | 真 MQ |

## 配置

见 [`../application-example.yml`](../application-example.yml)。联调 BUS 时设 `gateway.path-mode=bus`（或 `--spring.profiles.active=bus`）并重启 Gateway；本 example 的 R/B1/B2 不依赖真 MQ。
