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

## 剩余切片

| 切片 | 用例数（spec） | 状态 |
|---|---|---|
| B2 control 出站 | 12 | ⏳ 下一片 |
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
