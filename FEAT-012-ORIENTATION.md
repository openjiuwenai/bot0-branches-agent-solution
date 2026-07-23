# FEAT-012 开工前摸底报告（中文阅读笔记）

> 阶段：只读摸底，**未改任何业务代码**。在 `feat/feat-012-bus-forwarding` @ `a535dc1`（011 tip，011 代码在底上）。
> SDK 以 as-built 代码为真源（kickoff「以消费 SDK 为准」）。
> 生成日：2026-07-23。
> 交互方式：本文件即交互介质——你审完后在文末 **§9 决策回填** 里填口径，再让我出 `FEAT-012-SLICE-PLAN.md`。

---

## 1. 仓库 / 分支状态

| 项 | 状态 |
|---|---|
| 代码仓分支 | `feat/feat-012-bus-forwarding`，tip `a535dc1`（= 011 tip），tracking `fork/...`。工作区干净。 |
| 011 底 | `common/agent-gateway` 011 包全在：`facade` / `governance{auth,tenant,validate,idempotency}` / `routing` / `sse` / `direct` / `obs`（79 测 GREEN）。 |
| **新增包**（待加） | `bus/{control,projection,wait}` + `path/`（012 L2 §1.3）；011 治理 / 选路 / SSE / 幂等直接复用。 |
| 对端 SDK | `common/agent-bus`（FEAT-013）在底上、很全；`agent-bus-spi` 纯 Java（无 Spring/JDBC）。 |
| 文档 | 012 L2（已检视 + 复审定稿）、013 L2、017 L2（旁证副本）、013/017 scope 均可读。 |

## 2. 730 做 / 不做（对齐 012 L2 IN-* / SC-8）

**做（IN-1~IN-9，Gateway 侧）**：入口治理复用 011（拒绝 → 零 I-04）；按逻辑目标选路（复用 011，先选路后入队）；同步经 BUS（I-04 出站入队 + 入站投影 → **五态**）；流式（控制面走 BUS + `STREAM_READY` 后点对点 I-06 SSE，token 不进 BUS）；选路失败 → 明确失败零 I-04；续跑 / continueInput（wire 同 011，经 BUS continuation）；统一入口 + path-mode。

**不做**：IN-10/11（GetTask/Cancel/Subscribe 经总线、UNKNOWN 同键恢复）；按调用动态 path；**端到端宣称 BUS 可用**（SC-8 gated on FEAT-017）。

## 3. 相对 011 的复用与增量

- **复用 011**：G1~G5 治理管道、`AuthRule`/`TenantResolver`/`ParamValidator`/`IdempotencyRule`（含 complete/abort）、`Router` 的选路（默认 Agent / 取首条 / resolve）、`SseBridge`（release 纪律）、拓扑清洗、`GovernanceContext`、`A2aController` 入口。
- **增量**：
  - `path/`：`direct|bus` 部署级选择（730 配置固定）。
  - `bus/control/`：组装 `ForwardingEnvelope` + `ForwardingOutboxPort.enqueue`（I-04 出站）。
  - `bus/projection/`：消费 `INVOCATION_*` 投影、按 `correlationId` 匹配、幂等 / 乱序 / 终态闭合（012 L2 §4.6.1）。
  - `bus/wait/`：双窗口（accept / response）+ 五态折叠（→ `InvocationResponseStatus`）+ 超时 → 未知 / 已接受；同步等待 client 断开 → 释放窗口不 Cancel（§4.6.2）；G4 complete/abort 接投影终态（§4.6.3，复用 011 教训）。
  - `sse/`：`STREAM_READY`（与 ACCEPTED 可分离）后才建 I-06；流式正常结束 complete（首帧 / 摘要，同 011 口径 A）。
  - facade：`path=bus` 时走 BUS 栈（`path=direct` 走 011 栈）。

## 4. 013 SDK 可用面（as-built，Gateway 编程对象）

| 类型 | 用途 | 关键签名 / 字段 |
|---|---|---|
| `ForwardingEnvelope` (record) | 出站信封 | messageId / eventType / tenantId / traceId / correlationId / idempotencyKey / routeHandle / capability / sourceServiceId / targetServiceId / deadline / payloadPolicy / payloadRef；强校验 **tenantId == routeHandle.tenantScope()**、DATA_BEARING 必带 payloadRef、**绝不带 payload body/token** |
| `AgentBusEventType` (enum) | 事件判别 | 出站 `CLIENT_INVOCATION_REQUESTED`；入站 `INVOCATION_ACCEPTED/REJECTED/FAILED/RESPONSE/STREAM_READY/TERMINAL`；续跑 / continueInput 出站仍 `CLIENT_INVOCATION_REQUESTED`（无独立 RESUME） |
| `InvocationResponseStatus` (enum) | 五态折叠目标 | COMPLETED_RESPONSE / ACCEPTED_WITH_TASK / STREAM_READY / REJECTED / FAILED / UNKNOWN |
| `ForwardingOutboxPort` | **I-04 出站** | `enqueue(envelope, sourceServiceId, targetServiceId, now) → ForwardingReceipt`；outbox 按 (tenantId,messageId) 去重；produce 由 013 relay worker 异步做（Gateway 不直调 produce） |
| `BrokerForwardingConsumerPort` | **I-04 入站**（传输） | `subscribe(consumerServiceId, eventType, DeliveryFilter)` 一次 + `poll(now) → Optional<BrokerInboundMessage>` + `commit/reject`（at-least-once） |
| `ForwardingInboxPort` | 入站去重（可选） | `receive(envelope, consumerServiceId) → RECEIVED/DUPLICATE_SUPPRESSED`，键 (tenantId,messageId,consumerServiceId) |
| `BrokerInboundMessage` (record) | 入站投影消息 | tenantId / messageId / sourceServiceId / targetServiceId / consumerServiceId / payloadRef / **correlationId** / eventType（Gateway 按 correlationId 匹配、eventType 分类） |
| `ForwardingRouteHandle(value,tenantScope)` / `ForwardingMessageId(value)` / `ForwardingFailureCode` / `DeliveryFilter` | 辅助 | routeHandle 不透明 + tenantScope；FailureCode 分 retryable / nonRetryable |

**可编译性**：`agent-bus-spi` 纯 Java、**未 install 到 m2** → slice 0 先 `mvn install` agent-bus(-spi)，再给 `common/agent-gateway` 加 `<dependency>com.openjiuwen:agent-bus-spi`（并解除 011 module-metadata 里 `forbidden_dependencies: agent-bus` 对 BUS 路径的限制）。

## 5. 017 用桩的边界 + 三个契约缺口

**017 现状**：`status: design_accepted; implementation: pending` —— **无生产代码**（SC-8 软阻塞成立）。Gateway 侧用 **013 SDK + 投影桩** 先做垂直 TDD（同 011 桩 RDC/runtime）。

子代理精读 017 L2 发现三个缺口 / 对齐点：

1. **【缺口·高】`INVOCATION_INPUT_REQUIRED` 在 SDK 枚举里不存在**。017 L2 强制 MUST 产出「等待输入」投影（S3 工具续跑依赖它），但 `AgentBusEventType` 无此值（012 L2 AC-017-4 已点名要 agent-bus 补枚举）。→ 730 Gateway 侧：投影折叠器**预留** INPUT_REQUIRED 分支、用**桩**测；真实 e2e 等 agent-bus 补枚举 + 017 实现。**S3-over-BUS 的可观察性受此阻塞**（需决策：730 是否仍建该分支 + 桩测，见 §7 R2）。
2. **【已定】streamRef = 不透明内部引用，走标准 A2A SSE**（AC-017-3 选定标准分支；017 L2 §3.4 一致）。Gateway 的 I-06 仍用 FEAT-001 标准入口；streamRef 不编码 endpoint，Gateway 需**内部解析 streamRef → runtime SSE 定位**（机制待定，TDD 用桩）。
3. **【缺口·中】双层错误码不对齐**：`INVOCATION_FAILED/REJECTED` 带 **bus 层字符串码**（`TASK_NOT_FOUND` / `PAYLOAD_INVALID` / `TENANT_SCOPE_VIOLATION`…，见 017 §7.2 全表），**非** FEAT-001 JSON-RPC -32001 / -32004；`INVOCATION_RESPONSE` payload 内才可能带 JSON-RPC 码。Gateway 折叠需**双套映射**（012 L2 未给对照表）。

**已确认的好消息**：ACCEPTED 必带真实 taskId（+ idempotencyResult）；STREAM_READY 必带 streamRef + taskId 且与 ACCEPTED 分离；continuation（CLIENT_INVOCATION_REQUESTED + 已有 taskId）→ 017 不分配新 Task，taskId 不存在 / 跨 tenant / 终态 → `INVOCATION_FAILED(TASK_NOT_FOUND, retryable=false)`（**不降级新建**）；`correlationId` 原样回传（Gateway 可靠它匹配窗口）。

## 6. Gateway 侧 I-04 接线模型（摸底结论）

- **出站**：`bus/control` 组装 `ForwardingEnvelope`（eventType = CLIENT_INVOCATION_REQUESTED；tenantId 权威、routeHandle.tenantScope == tenantId；A2A 报文进 payloadRef）→ `ForwardingOutboxPort.enqueue`。produce / relay 是 013 runtime（in-process worker 或对端进程，slice 期确认；TDD 用 fake outbox 模拟 enqueue + 回执）。
- **入站**：`bus/projection` 经 `BrokerForwardingConsumerPort` subscribe `INVOCATION_*` + poll → `BrokerInboundMessage` → 按 `correlationId` 匹配 `bus/wait` 等待窗口 → 折叠为 `InvocationResponseStatus` / 五态 → 回传 / 开 I-06。幂等 / 乱序 / 终态闭合按 012 L2 §4.6.1（可用 `ForwardingInboxPort` 或自建去重）。

## 7. 风险 / 软阻塞 / 实现落点（§9 已签收）

> 多数不是「契约空白」，而是开工落点签收。**R3 摸底误写默认 A —— 已纠正为 L2 策略 B。**

| # | 项 | 状态 | 签收口径（见 §9） |
|---|---|---|---|
| R1 | **FEAT-017 无生产代码** | 软阻塞（SC-8） | **认**：Gateway 用桩先做；e2e 另算 |
| R2 | **INPUT_REQUIRED 枚举缺失** | agent-bus SDK 缺值 | **认**：730 建折叠分支 + 桩测；e2e 等补枚举 + 017 |
| R3 | **correlationId 策略** | **L2 已冻 B**（§4.10 / AC-B-*） | **B**：Gateway 自生成；client 不上送；`clientInvocationId` 730 不用。**禁止 A** |
| R4 | **streamRef 内部解析** | 实现期端口化 | **认**：`StreamRefResolver` 端口 + 桩（标准 SSE） |
| R5 | **agent-bus-spi 依赖** | 未 install | **认**：slice 0 install + pom + 解除 module-metadata 禁止 |
| R6 | **双层错误码映射** | L2 允许实现期冻结 | **认**：最小映射表，不另开契约评审 |

## 8. 下一步（流程）

按 kickoff：**出摸底报告 → 停等切片计划确认 → 再垂直 TDD**。

1. 你审本报告 + 填 §9 决策回填；
2. 我产出 `FEAT-012-SLICE-PLAN.md`（切片 ↔ 012 验收 ID ↔ 测 ↔ 桩 ↔ Done）→ 停等「按此计划开工」；
3. 垂直 TDD（自动续跑、每片落盘、全部完成再叫你；**只在 `feat/feat-012-bus-forwarding` commit**）。

---

## 9. 决策回填（请填写，填完告诉我「按此出切片计划」）

> 直接在下表「你的决策」列填写 / 修正即可。
>
> **2026-07-23 黄晨回填**：契约以已上库 012 L2 为准；下列多数项 L2/kickoff 已定，本表只作开工签收。**R3 纠正**：必须是策略 **B**（非 A）。

| # | 项 | 建议 | 你的决策 |
|---|---|---|---|
| R1 | FEAT-017 用桩先做、e2e 另算 | 认 | **认**（对齐 SC-8 / kickoff） |
| R2 | 730 建 INPUT_REQUIRED 折叠分支 + 桩测（e2e 等 agent-bus 补枚举 + 017 实现） | 认 | **认**（对齐 AC-017-4：730 应消费；枚举缺失用桩 + 预留分支，不挡 Gateway 编码） |
| R3 | correlationId 策略：**A**（client 上送 clientInvocationId）/ **B**（Gateway 自生成）/ 端口兼容两者默认 A | 默认 A | **纠正为 B（已冻）**：client **不上送**；Gateway **自生成** `correlationId`；`clientInvocationId` **730 不使用**。见 012 L2 §4.10 / AC-B-1～3 / AC-013-3。**禁止**默认 A，也**不必**端口兼容 A |
| R4 | Gateway 加 `StreamRefResolver` 端口 + 桩（标准 SSE 分支） | 认 | **认**（对齐 AC-017-3：streamRef 不透明内部引用 → 标准 I-06 SSE） |
| R5 | slice 0 install agent-bus-spi + gateway pom 加依赖 + 解除 module-metadata 禁止 | 认 | **认**（仅 BUS 路径需要；勿顺手改 agent-bus 实现） |
| R6 | 双层错误码映射（实现期冻结） | 认 | **认**（L2 §4.7.1 已允许实现期冻结字面量；先做最小可映射表，不另开契约评审） |
| — | 切片粒度 / 顺序有否额外要求 | 默认：S0(依赖+骨架) → path/ → bus/control 出站 → bus/wait+projection 五态 → 流式 STREAM_READY → S5 → S3/S4 | **同意默认顺序**；每片映射 012 验收 ID；S3/S4 可放后但 730 要交付（桩测） |
| — | 其他禁止 / 边界（除 kickoff 已列） | — | 同 kickoff；另：**R3 不得回退到 A**；不改 L2；不碰 011 分支业务 commit |

**一句话签收：** 按此出切片计划。
