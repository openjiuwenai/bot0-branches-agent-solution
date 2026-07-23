# FEAT-012 开发进展报告（最终）

> 分支：`feat/feat-012-bus-forwarding`（7 commit ahead of 011 tip `a535dc1`）。
> **162/162 GREEN**（011 的 79 + B0-B9 的 83 新测）。

## 切片完成清单

| 切片 | commit | 测试数 | 内容 |
|---|---|---|---|
| B0 依赖+骨架 | `7425dcf` | +1 | install agent-bus-spi；pom 加依赖；module-metadata 解禁；BusSpiClasspathTest |
| B1 path/ 选择器 | `e2c7a9f` | +18 | PathMode(DIRECT\|BUS) + PathSelector(@Component)；TDD RED→GREEN；参数化 |
| B2 control 出站 | `6facd6b` | +12 | EnvelopeBuilder + PayloadStore + BusControlForwarder( enqueue)；AgentCardRoute +targetServiceId |
| B3 wait+projection | `aad13b2` | +25 | FiveStateFolder + ProjectionTracker(dedup/terminal) + WaitWindow(dual-window) + G4BusWiring + SyncDisconnectHandler |
| B4 facade BUS 同步 | `bcb4ad3` | +10 | BusForwarder(search→enqueue→poll→fold→respond→G4)；ProjectionFeed 端口 + FakeProjectionFeed |
| B5-B9 合并 | `a27c223` | +16 | StreamRefResolver + StreamReadyGateTest(3) + BusStreamingAndResumeTest(13: B5 流式/B6 S5/B7 续跑/B8 continueInput/B9 config) |

## 测试统计

- **162 总测试**（011 的 79 复用 + B0-B9 的 83 新增）。
- 测试类：011 的 11 + 012 新增 10（BusSpiClasspathTest, PathSelectorTest, PathSelectorWiringTest, EnvelopeBuilderTest, PayloadStoreTest, BusControlForwarderTest, FiveStateFolderTest, ProjectionDedupTest, WaitWindowTest, G4BusWiringTest, SyncDisconnectTest, StreamReadyGateTest, BusForwarderTest, BusStreamingAndResumeTest）。

## 012 新增代码

- `path/`：PathMode, PathSelector
- `bus/control/`：PayloadStore, InMemoryPayloadStore, EnvelopeBuilder, BusControlForwarder, ProjectionFeed, StreamRefResolver
- `bus/wait/`：FiveStateFolder, WaitWindow, G4BusWiring, SyncDisconnectHandler
- `bus/projection/`：ProjectionTracker
- `bus/`：BusForwarder (orchestrator)
- `routing/`：AgentCardRoute 扩展(+targetServiceId)
- 测试桩：FakeForwardingOutboxPort, FakeProjectionFeed, FakeStreamRefResolver

## 验收覆盖

- 012 L2 IN-1~IN-9（Gateway 侧 BUS）：✅ 全覆盖
- T-S1-B*（治理拒绝零 I-04）：结构保证（BusForwarder 在治理后运行）
- T-S2-B1~B10（创建五态/STREAM_READY/超时/produce 失败）：BusForwarderTest + BusStreamingAndResumeTest
- T-S3-B*（续跑 continuation）：sticky 验证 + payloadRef 携带 taskId
- T-S4-B*（continueInput wire==S3）：验证
- T-S5-B*（选路失败零 I-04）：BusForwarderTest emptyCandidates + produceFail
- AC-CFG-1~10：path-mode 不可见(B1) + SSE release(011) + 文档勾选(7/8)
- SC-1~12：聚合覆盖

## 已知限制（非阻塞）

- FEAT-017 无生产代码（SC-8 软阻塞）：e2e 联调另算；Gateway 用桩先验。
- INPUT_REQUIRED 枚举缺失（SDK）：桩分支预留；e2e 等 agent-bus 补枚举。
- correlationId 策略 B（Gateway 自生成）：✅ 已实现。
- streamRef 标准 SSE 分支（AC-017-3）：StreamRefResolver 端口 + 桩。
