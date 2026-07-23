# FEAT-012 开发进展报告

> 分支：`feat/feat-012-bus-forwarding`。只在此分支 commit。
> 测试规格：`FEAT-012-TEST-SPEC.md`（94 用例，B1–B9）。
> 真源：012 L2（已上库基线）+ agent-bus SDK（as-built）。

## 已完成切片

### B0 — 依赖 + 骨架 ✅（commit `7425dcf`）

- `mvn install` agent-bus-spi 0.1.0（FEAT-013 转发 SPI：ForwardingEnvelope / AgentBusEventType / InvocationResponseStatus / ForwardingOutboxPort / BrokerForwardingConsumerPort）。
- gateway pom 加 `<dependency>com.openjiuwen:agent-bus-spi</dependency>`；module-metadata 解禁 SPI、禁 sdk/relay。
- `BusSpiClasspathTest`（1 测）：证明 SPI 在 classpath。
- **80/80 GREEN**（011 的 79 + smoke 1），011 零回归。

### B1 — path/ 选择器 ✅

- **TDD 纪律**：先写测试（`PathSelectorTest` 7 用例参数化展开 17 + `PathSelectorWiringTest` 1 装配）→ **RED**（stub 编译失败"找不到符号"）→ 实现 `PathMode`(enum DIRECT|BUS, fromConfig) + `PathSelector`(@Component, @Value) → **GREEN**。
- 覆盖：fromConfig 全分支（direct/bus/blank/null/case-insensitive/invalid×6→fail-fast）+ selector 两态 × 3 方法 + Spring 装配（path-mode=bus→isBus）。
- 验收映射：AC-CFG-1（path-mode 对 client 不可见）。
- **98/98 GREEN**（+18 新测）。

### B2 — bus/control 出站 ✅

- **TDD 流程**：按 TEST-SPEC §B2 写 12 单元测 → RED（编译失败类不存在）→ 实现 → GREEN。
- 实现：`PayloadStore`(端口) + `InMemoryPayloadStore`(@Component) + `EnvelopeBuilder`(@Component，构造 ForwardingEnvelope + 自生成 correlationId/messageId) + `BusControlForwarder`(orchestrator: stash body→payloadRef→build envelope→enqueue；失败→ENQUEUE_FAILED)。`AgentCardRoute` 扩展加 `targetServiceId`（向后兼容）。`FakeForwardingOutboxPort`(测试桩)。
- 验收映射：T-S2-B9（produce 失败→ENQUEUE_FAILED）、AC-CFG-2。
- `BusControlForwarder` 暂不 `@Component`（B4 facade 接线时再加，避免全量上下文测缺 ForwardingOutboxPort bean）。
- **111/111 GREEN**（+12 新测 + RouterTest +1 因 AgentCardRoute 扩展）。

### B3 — bus/wait + projection 五态 ✅

- 实现：`FiveStateFolder`(static: AgentBusEventType→InvocationResponseStatus + isTerminal) + `ProjectionTracker`(per-correlationId dedup/terminal-closure/out-of-order) + `WaitWindow`(dual-window accept/response timeout) + `G4BusWiring`(maps fold→IdempotencyRule complete/abort) + `SyncDisconnectHandler`(release window + abort G4)。
- 验收映射：§4.6.1 投影幂等/乱序/终态闭合、§4.6.2 同步断开、§4.6.3 G4 complete/abort 接投影、T-S2-B6/B7/B8/B10、SC-11/12。
- **136/136 GREEN**（+25 新测）。

## 剩余切片

| 切片 | 用例数（spec） | 状态 |
|---|---|---|
| ~~B2 control 出站~~ | ~~12~~ | ✅ |
| ~~B3 wait+projection~~ | ~~27~~ | ✅ |
| B3 wait+projection 五态 | 27 | 待办 |
| B4 facade BUS 同步 | 12 | 待办 |
| B5 流式 STREAM_READY | 10 | 待办 |
| B6 S5 选路失败 | 7 | 待办 |
| B7 S3 续跑 | 7 | 待办 |
| B8 S4 continueInput | 3 | 待办 |
| B9 AC-CFG 横切 | 8 | 待办 |
| **合计** | **86** | |

## 测试总数

- 当前：**98**（011 的 80 + B0 smoke 1 + B1 的 17+1）。
- 目标：011 的 80 + B0 1 + B1–B9 spec 94 = **175**（预估）。
