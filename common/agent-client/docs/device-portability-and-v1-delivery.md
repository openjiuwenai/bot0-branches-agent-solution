# Agent Client 设备可移植性与 V1 交付形态

> **状态：Draft / 决策辅助**
>
> 本文回答"这个 SDK 是否要区分设备/操作系统、本地工具执行是否需要沙箱、A2A 与网络请求
> 是否有 OS 差异、第一版应该交付成什么形态、如何测试与验证"。它是决策与评审辅助，配套：
> - 能力拆解与 SDK 设计：`agent-client/docs/proposals/agent-client-v1-design.md`
> - 线协议（对齐 runtime `Feat-Func-009`，拓扑保留 client→gateway）：L2 `architecture/L2-Low-Level-Design/agent-client/Feat-Func-006-*.md` §3.5、`Feat-Func-007-*.md` §3.5，对 gateway 的要求见各 §8
> - 最佳实践与测试方法：`agent-client/docs/getting-started.md`
> - 物理边界：`architecture/L1-High-Level-Design/agent-client/physical.md`
> - 可运行示例：`example/agent-client-demo/`（`agent-client-sdk-for-jvm` 在 `agent-client/` 下，`mock-gateway` / `verification-app` 在 demo 下）

## 1. 核心定位：这是一个 JVM SDK，不是移动原生 SDK

agent-client 交付的是一个 **Java/JVM SDK**，运行环境是 JVM，不是"某个手机操作系统"。

| 平台 | 原生语言 | 运行时 | 能否直接跑本 Java SDK |
|---|---|---|---|
| 服务端 / 桌面 / Linux 容器 | Java / Kotlin | 标准 JVM | 能（主场） |
| Android | Kotlin / Java | ART（非标准 JVM） | 部分能，Android SDK ≠ 完整 JDK，见 §4 |
| iOS | Swift / Objective-C | 无 JVM | 不能 |
| 鸿蒙 HarmonyOS NEXT | ArkTS（TS 系） | 方舟运行时 | 不能 |

结论：**V1 不区分设备/OS，只针对 JVM（服务端 / 桌面 / 容器）。** iOS、鸿蒙 NEXT 没有 JVM，
不可能也不需要直接运行本 SDK；它们将来通过**跨语言协议 SDK**（Swift / ArkTS 各写一个，
彼此不复用代码，只共用同一套 A2A wire 协议）接入平台。跨 OS 可移植性由**线协议中立性**
保证，不是靠现在写多个 SDK。这正是全项目统一 A2A 的深层价值。

## 2. OS 相关性按层拆解

```text
┌─────────────────────────────────────────────┐
│ 工具实现层（读摄像头/删文件/开相册）           │ OS 强相关 → 由【业务开发者】实现，不是 SDK
├─────────────────────────────────────────────┤
│ SDK 公共 API / core                           │ OS 中立 → 只用 JDK 类型，一份代码到处跑
├─────────────────────────────────────────────┤
│ transport 层（发 HTTP / SSE）                 │ 近乎中立 → 仅上 Android 时换实现（§4）
├─────────────────────────────────────────────┤
│ 线协议 A2A JSON（wire）                        │ 彻底 OS/语言中立 → 跨平台的真正保证
└─────────────────────────────────────────────┘
```

绝大部分 OS 相关性与 SDK 无关：
- **工具实现层**是 OS 相关的，但它是业务开发者写的。SDK 只提供 `LocalTool` 注册插槽，
  从不碰摄像头/文件系统，也无需为每个 OS 适配工具。
- **公共 API / core** 坚持只用 JDK 类型（`CompletionStage` / `Flow` / `Instant` / `URI`），
  一份代码在任何 JVM 上一致。

## 3. 本地工具执行：不做沙箱，做进程内护栏

V1 **不做沙箱**，且一个 Java 库本质上做不了可靠的 OS 沙箱：

1. 真正的沙箱是 OS 级机制（独立进程、seccomp、namespace、容器），属宿主/部署能力。
   进程内 `new Thread()` 跑工具与主线程共享同一进程与堆，段错误/OOM 会一起崩，隔离不了。
2. Java 不能安全地强杀线程（`Thread.stop()` 已废弃）。所以超时/取消只能是**协作式**的。

V1 靠三个**进程内护栏**防止工具异常拖垮宿主，而不是沙箱：
- **有界执行器**：工具跑在大小受限的独立线程池，一个慢工具不堵死 SDK。
- **deadline / 超时**：到点放弃等待，结果标 `TIMEOUT`（放弃等待 ≠ 杀死工具）。
- **异常边界**：工具异常被 catch 成结构化 `ToolFailure`，不冒泡到主流程。

需要强隔离（执行不可信代码）时，是把工具跑到独立进程/容器——那是宿主部署方案，
SDK 至多提供"进程外工具执行"的 transport 适配作为后续候选，非 V1 内容
（对齐设计提案 §9.2：不做通用脚本执行器 / 任意代码执行 / 默认 sandbox）。

## 4. A2A 与网络请求的 OS 差异

### 4.1 A2A SDK

- `org.a2aproject.sdk` 是纯 Java 库，无 OS 变体，不存在"iOS 版 / 安卓版 A2A SDK"。
- **A2A 类型绝不能泄漏到公共 API**，只能待在 transport adapter（L3）内。
- client 可能**只需瘦 codec**（HTTP client + JSON）而非完整 A2A server SDK；这对将来上
  Android（依赖体积敏感）有价值，建议列入与 runtime 的评审项。

### 4.2 网络请求

- V1 目标环境（服务端 / 桌面 / 容器 JVM）：JDK 自带 `java.net.http.HttpClient`（JDK 11+）
  即可，纯 Java、无 native、鲲鹏 aarch64 一致。
- **仅当上 Android**：`java.net.http.HttpClient` 到 Android 14（API 34）才有，老版本用
  OkHttp。处理方式不是写两套，而是把 transport 抽象成 `TransportProvider` SPI：core 只依赖
  接口，默认 JDK 实现，上 Android 换 OkHttp 实现，**公共 API 一行不改**。

## 5. 测试与验证：云客户端能证明什么

关键洞察：**验证 SDK 通用性不需要真实摄像头。** 摄像头能否用是"工具实现 + 设备"的事，
不是 SDK 的事。SDK 要证明的是：一个已注册的 `LocalTool` 被远端驱动调用时，SDK 是否正确
完成 schema 校验 → 去重 → 权限/审批钩子 → 执行调度 → deadline/取消 → 结果标准化 → 回传
→ ACK → 多轮串联。这些**全部 OS 中立**，用**假工具**即可完整验证。

> 摄像头工具是"用 SDK 的一个例子"，不是"SDK 的一部分"。V1 验收看 SDK 契约，不看某个设备工具。

因此 Leader 的"Linux 容器云客户端"对 V1 **正确且充分**；真机摄像头属"设备集成示例"，
是后续 wave 的事，不影响 V1 证明通用性。

### 5.1 四层测试途径（均可在容器/CI 运行）

| 层 | 验证什么 | 怎么看结果 |
|---|---|---|
| 单元 | 三套状态机、去重、幂等、超时、注册冲突 | JUnit 断言 + CI 绿 |
| 契约 | 与 bus/runtime 共享 golden fixture 编解码一致、未知字段前向兼容 | 断言 JSON 逐字节一致 |
| 集成（fake gateway / WireMock） | accepted/rejected/UNKNOWN/超时/失败/取消/重连 | 断言 + 结构化日志 |
| 端到端云客户端 | 完整多轮闭环、Action 只执行一次、结果重投不重跑 | **自校验程序**：退出码表达成败 + 链路摘要 |

验证不靠肉眼看日志，而是**让示例自校验并用退出码表达成败**：成功返回 0 并打印链路摘要，
失败非 0 退出。它既是"最佳实践教学程序"，又是"可执行验收用例"，可进 CI。

## 6. 第一版最佳交付形态

| # | 交付物 | 说明 |
|---|---|---|
| 1 | agent-client SDK 本体 | 单 artifact 内分层（api/spi、core、transport、可选 integration）；公共 API 只用 JDK 类型；transport 藏在 `TransportProvider` SPI 后；JDK 17 基线 |
| 2 | 云客户端示例（plain Java + Dockerfile） | 即"最佳实践程序"，见 `example/agent-client-demo/`（SDK 本体 `agent-client-sdk-for-jvm` 在 `agent-client/` 下）；注册 Observation + Action + 占位设备工具，跑通多轮闭环，自校验 |
| 3 | testkit + 四层测试 | 进 CI 作为可执行验收 |
| 4 | 共享 golden fixtures | 与 bus/runtime 对齐 wire（协议提案 §9 清单） |
| 5 | 文档 | `getting-started.md` + 本文（设备/可移植性 FAQ） |

### 6.1 用一个占位设备工具证明"设备无关性"

示例除两个假工具外，再注册一个 `CameraCapturePlaceholderTool`：`descriptor` 声明完整
（side_effect、schema），但 `execute()` 不真的调摄像头，返回一张 stub 图片引用，注释
"真机上把这里换成 CameraX/AVFoundation 即可"。它证明 **SPI 形状是设备无关的**——同一套
`LocalTool` 接口，容器里注册假摄像头、真机上注册真摄像头，**SDK 侧代码零改动**。

这比"搞台真手机验证摄像头"更能证明通用性——通用性的本质恰恰是"不依赖某台特定设备
也能成立"。在一个 Linux 容器里，就同时展示了：SDK 核心能力（假工具跑通闭环，可验收）
+ SDK 对设备工具的开放性（占位摄像头证明 SPI 通用，无需真机）。

## 7. 提案 vs 冻结的关系（重要）

示例先建立在**拟议 API**之上。后续变更分两类，必须区分：

| 变更类型 | 是否 transport 层可闭环 | 对示例代码影响 |
|---|---|---|
| wire 字段（JSON 改名/增减、A2A 报文调整） | 是，完全闭环在 codec adapter | 示例零改动 |
| 公共 API（Java 类型/方法易名） | 否，会波及示例 | 示例需改调用写法，但机械/局部，不触动架构 |

真正稳定的是**架构边界**（四层分层、依赖红线、三套状态机、SPI 边界、治理骨架、
"执行成功≠已接收"不变量），无论 wire 字段还是 Java 符号名如何调整都不动。所以先用示例
把架构跑通，符号名待评审结论出来后机械对齐即可。

## 8. 结论

- V1 只针对 JVM，不区分设备/OS；跨 OS 靠线协议中立性，不靠多份 SDK。
- 本地工具执行用进程内护栏（有界执行器 + deadline + 异常边界），不做沙箱。
- A2A/网络无 OS 变体；仅上 Android 时经 `TransportProvider` 换实现，公共 API 不变。
- Linux 容器云客户端能充分验证 SDK 契约；真机设备工具属后续集成示例。
- 交付形态 = SDK 本体 + 可 `docker run` 的自校验云客户端 + 四层测试 + 共享 fixture + 文档，
  并以占位设备工具证明设备无关性。
