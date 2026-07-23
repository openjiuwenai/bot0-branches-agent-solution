# FEAT-012 切片与测试矩阵（SLICE-PLAN）

> 阶段：Phase A 产出物。**未进入 Phase B（未写业务代码）**。
> 真源：`/Users/kevin/Work/spring-ai-ascend/architecture/L2-Low-Level-Design/agent-gateway/Feat-Func-012-client-invocation-bus-forwarding.md`（已上库基线）+ `common/agent-bus` SDK（as-built）。
> 实现仓：`/Users/kevin/Work/agent-solution`，分支 `feat/feat-012-bus-forwarding`（base = 011 tip `a535dc1`）。
> 落点：扩展现有 `common/agent-gateway`（加 `bus/{control,projection,wait}` + `path/`）；复用 011 治理/选路/SSE/幂等。
> 摸底：见 `FEAT-012-ORIENTATION.md`。

## 0. 已锁决策（编码基线）

| # | 决策 |
|---|---|
| D1 | 730 做 Gateway 侧 BUS（IN-1~IN-9）；e2e / "BUS 端到端可用" gated on FEAT-017（SC-8），用 013 SDK + 投影桩 先 TDD |
| D2 | **correlationId 策略 B（已冻）**：Gateway 自生成 `correlationId`；client **不上送**；`clientInvocationId` **730 不使用**；**禁止 A，不兼容 A** |
| D3 | INPUT_REQUIRED：730 **建折叠分支 + 桩测**（SDK 枚举暂缺 `INVOCATION_INPUT_REQUIRED`，预留分支，e2e 等 agent-bus 补枚举 + 017） |
| D4 | streamRef：标准 A2A SSE（AC-017-3）；Gateway 加 `StreamRefResolver` 端口 + 桩 |
| D5 | slice 0 `mvn install` agent-bus-spi + gateway pom 加依赖 + 解除 module-metadata `forbidden: agent-bus`（仅 BUS 路径） |
| D6 | 双层错误码（bus 字符串码 vs JSON-RPC）：实现期冻结最小映射表（§4.7.1），不另开契约评审 |
| D7 | 下游（RDC/runtime/013 produce-relay/017）一律**端口 + 桩**直到联调；只在 `feat/feat-012-bus-forwarding` commit |

## 1. 730 交付边界（摘自 012 L2 §0.4）

**交付（IN-1~IN-9）**：入口治理（复用 011，零 I-04）、选路（复用 011，先选路后入队）、同步经 BUS（I-04 出站入队 + 入站投影→五态）、流式（控制面 BUS + `STREAM_READY`→I-06 SSE）、选路失败（零 I-04）、续跑/continueInput（BUS continuation）、统一入口 + path-mode。

**不交付**：IN-10/11（Get/Cancel/Subscribe 经总线、UNKNOWN 同键恢复）；按调用动态 path；端到端宣称 BUS 可用（SC-8）。

## 2. 测试三层（本模块内）

- **单元测（unit）**：信封组装、correlationId 生成、五态折叠、双窗口超时、投影幂等/乱序/终态闭合、错误码映射、streamRef 解析——纯逻辑。
- **模块集成测（module-it）**：`@WebMvcTest` / `@SpringBootTest` 跑 `POST /a2a` 全链（path=bus）：facade → 治理 → path → bus/control(桩 outbox) → bus/projection(桩 consumer) → bus/wait 折叠 → 回传/I-06。
- **桩**（013/017 端口 + Gateway 自建端口）：
  - `FakeForwardingOutboxPort`（记录 enqueue；可注入 produce 成功/失败）
  - `FakeProjectionFeed`（测试注入 `INVOCATION_*` 投影 by correlationId；模拟 BrokerForwardingConsumerPort）
  - `FakePayloadStore`（A2A body → payloadRef 内存存取）
  - `FakeStreamRefResolver`（streamRef → runtime endpoint）
  - RDC/runtime 桩沿用 011（`FakeRdcRouteClient`/`FakeAgentRuntimeClient`）

## 3. 目标包结构（B0 落地，对齐 012 L2 §1.3 + 011 既有）

```
common/agent-gateway/src/main/java/com/openjiuwen/gateway/
├── facade/        # 复用 011；path=bus 时分流到 BUS 栈
├── governance/    # 复用 011（G1~G5、IdempotencyRule complete/abort）
├── routing/       # 复用 011（默认 Agent、RDC、sticky）
├── direct/        # 复用 011（DIRECT 路径 I-03）
├── sse/           # 复用 011 SseBridge；BUS 须先 STREAM_READY
├── obs/           # 复用 011（审计/traceId）
├── path/          # 【新】PathMode(direct|bus) + PathSelector
└── bus/
    ├── control/   # 【新】ForwardingEnvelope 组装 + correlationId(B) + PayloadStore + ForwardingOutboxPort.enqueue（I-04 出站）
    ├── projection/# 【新】消费 INVOCATION_* 投影、按 correlationId 匹配、幂等/乱序/终态闭合
    └── wait/      # 【新】双窗口(accept/response) + 五态折叠(→InvocationResponseStatus) + 同步断开释放 + G4 complete/abort 接投影终态
```

## 4. 切片矩阵

顺序（决策同意）：`B0 → B1 path → B2 control 出站 → B3 wait+projection 五态 → B4 facade BUS 同步 → B5 流式 STREAM_READY → B6 S5 → B7 S3 → B8 S4 → B9 AC-CFG 横切`

| 切片 ID | 行为（一句话） | 012 验收 ID | 单测 | 模块集成测 | 依赖桩 | Done 标准 |
|---|---|---|---|---|---|---|
| **B0 依赖+骨架** | install agent-bus-spi；pom 加依赖；module-metadata 解禁；bus/+path/ 包骨架；smoke | — | — | smoke：path-mode 配置可读、默认 direct 仍走 011、context 加载 | — | agent-bus-spi 在 classpath；`mvn test` 全绿（011 79 测不回归） |
| **B1 path/ 选择** | `PathMode(direct|bus)` 配置 + `PathSelector`；facade 选择点（默认 direct→011；bus 分支待接） | AC-CFG-1 | PathSelectorTest | facade：path=direct 仍 011 行为；path=bus 占位 | — | path-mode 对 client 不可见；default direct 不回归 |
| **B2 control 出站** | `ForwardingEnvelope` 组装（eventType=CLIENT_INVOCATION_REQUESTED；tenantId 权威且==routeHandle.tenantScope；**correlationId Gateway 自生成 B**；idempotencyKey；source/targetServiceId；payloadRef）+ `PayloadStore`(A2A body→ref) + `ForwardingOutboxPort.enqueue` | AC-CFG-2、T-S2-B9 | EnvelopeBuilderTest / PayloadStoreTest | bus/control：创建→enqueue 1 次（正确信封）；produce 失败→`ENQUEUE_FAILED`+G4 abort；不伪造 taskId | FakeOutbox、FakePayloadStore、FakeRdc | 信封字段/租户一致/correlationId 自生成；enqueue 契约 |
| **B3 wait+projection 五态** | `WaitWindow`(accept/response 双窗口) + `ProjectionMatcher`(消费 INVOCATION_*，按 correlationId 匹配) + 折叠→`InvocationResponseStatus`；幂等/乱序/终态闭合(§4.6.1)；同步断开释放(§4.6.2)；G4 complete/abort 接投影终态(§4.6.3) | §4.6.1/2/3、T-S2-B6/B7/B8/B10、SC-11/12 | FolderTest / WaitWindowTest / ProjectionDedupTest | 投影注入：ACCEPTED→已接受、RESPONSE→完成、REJECTED→拒绝、FAILED→失败；accept 超时→未知；response 超时→已接受；重复/迟到投影忽略；同步断开→释放窗口不 Cancel；G4 终态 complete/失败 abort | FakeProjectionFeed | 五态全；幂等/乱序/终态闭合；同步断开；G4 接线 |
| **B4 facade BUS 同步** | path=bus 创建：治理→选路→bus/control enqueue→bus/wait 折叠→五态回传；治理拒绝/选路失败→零 I-04 | T-S1-B1~B5、T-S2-B1/B2/B4/B9、SC-1/4 | — | facade(path=bus)：同步创建五态；治理拒绝→I-04 出站 0；空候选→S5 零 I-04；produce 失败→ENQUEUE_FAILED | FakeOutbox、FakeProjectionFeed、FakeRdc | 同步 BUS 创建端到端（桩）；零 I-04 断言 |
| **B5 流式 STREAM_READY→I-06** | STREAM_READY（与 ACCEPTED 可分离）后才建 I-06；`StreamRefResolver` 端口；token 不进 bus；断开 release；流式正常结束 complete(首帧/摘要，口径 A) | T-S2-B3、SC-2、AC-CFG-6 | StreamReadyGateTest | facade(path=bus 流式)：先 STREAM_READY 再 I-06；Bus 侧无 token；断开 release；无 STREAM_READY 不开 I-06 | FakeProjectionFeed、FakeStreamRefResolver、FakeRuntime | STREAM_READY 门控；token 不进 bus；release |
| **B6 S5 选路失败（零 I-04）** | 选路失败→不 enqueue、不登记投影等待、明确失败、无拓扑 | T-S5-B1~B7、SC-4 | — | facade：空候选→S5 零 I-04；有候选缺信封目标→S5；仅缺物理 endpoint→**不**挡 I-04（T-S5-B7）；治理拒绝≠S5；默认缺失=配置错误 | FakeRdc、FakeOutbox | 零 I-04（出站+入站）；失败分层 |
| **B7 S3 续跑（continuation）** | 续跑(taskId)→`CLIENT_INVOCATION_REQUESTED`+payload.taskId→017 continuation（不新建）；sticky 复用 011；关联失败明确失败；INPUT_REQUIRED 折叠分支（桩，D3） | T-S3-B1~B5、SC-5 | ContinuationEnvelopeTest | facade(path=bus 续跑)：到原 owner 同 Task；无路由引用→明确失败不新建；INPUT_REQUIRED→等待输入（桩投影） | FakeOutbox、FakeProjectionFeed、FakeSticky | continuation 不降级新建；INPUT_REQUIRED 桩分支 |
| **B8 S4 continueInput** | wire==S3，整段复用 BUS 续跑栈；业务差量在 client | T-S4-B1~B4 | — | facade(path=bus)：continueInput wire 到原 owner 同 Task；关联失败明确 | 同 B7 | wire 复用；无新代码（仅测） |
| **B9 AC-CFG 横切** | path-mode 不可见；I-04 入站必启（消费者就绪前不假成功）；SSE release 不可关；窗口单机/重启丢失标注；错误码分层 | AC-CFG-1~10、T-CFG-1~9、SC-10 | — | T-CFG 用例（仅出站→失败；消费者未就绪→失败；release 不可关） | FakeOutbox、FakeProjectionFeed | AC-CFG-1~6/9/10 自动化；**AC-CFG-7/8（制品不绑测具 / 网络可达）按文档或部署勾选，不强求全自动化（L2 SC-10 允许）**；SC-10/11/12 |

> **最低场景集合**（dev-loop，跨切片断言）：治理拒绝→零 I-04；选路失败→零 I-04；同步五态（含未知/已接受超时）；流式 STREAM_READY→I-06 + token 不进 bus + release；续跑 continuation 不新建；INPUT_REQUIRED（桩）；拓扑无泄漏（无 topic/worker/endpoint/routeHandle）；G4 complete/abort 接投影终态；幂等/乱序/终态闭合。

## 5. 验收→测试追溯表（Phase B 逐切片填；初始 `待写`）

| 验收 ID | 切片 | 测试类#方法（暂定） | 层级 | 状态 |
|---|---|---|---|---|
| T-S1-B1~B5 | B4 | A2aBusFacadeTest（治理拒绝→I-04 出站 0） | it | 待写 |
| T-S2-B1 | B4 | 同步创建五态到达 | it | 待写 |
| T-S2-B2 | B4 | 默认 Agent | it | 待写 |
| T-S2-B3 | B5 | 流式 STREAM_READY→I-06 | it | 待写 |
| T-S2-B4 | B6 | 空候选→S5 零 I-04 | it | 待写 |
| T-S2-B6/B7/B8 | B3 | REJECTED/accept 超时→未知/response 超时→已接受 | unit+it | 待写 |
| T-S2-B9 | B2 | produce 失败→ENQUEUE_FAILED+abort | unit+it | 待写 |
| T-S2-B10 | B3 | 首次 ACCEPTED 含 taskId→续跑关联；响应无 routeHandle | unit+it | 待写 |
| §4.6.1 幂等/乱序/终态闭合 | B3 | ProjectionDedupTest | unit | 待写 |
| §4.6.2 同步断开 | B3 | SyncDisconnectTest | unit+it | 待写 |
| §4.6.3 G4 complete/abort | B3 | G4WiringTest | unit | 待写 |
| T-S3-B1~B5 | B7 | 续跑 continuation | it | 待写 |
| T-S4-B1~B4 | B8 | continueInput | it | 待写 |
| T-S5-B1~B7 | B6 | 选路失败零 I-04 | it | 待写 |
| AC-CFG-1~10 / T-CFG-1~9 | B9 | ConfigDeployTest | it | 待写 |
| SC-1~12 | 各 | （聚合） | — | 待写 |

## 6. 实现期需顺手确认（非阻塞，遇切片再定）

- **PayloadStore**：013 SDK 仅 `BusPayloadResolver`（runtime 侧）；sender 侧 Gateway 需自建 `PayloadStore` 端口（A2A body→payloadRef）+ 内存/fake。**确认 013 无 sender payload store 后自建**。
- **produce/relay 归属**：Gateway `enqueue` 后，relay worker（drain outbox+produce）是 013 runtime（in-process bean 还是对端进程）—— B2/B4 切片确认；TDD 用 FakeOutbox 模拟 enqueue+回执。
- **投影去重键**：用 `ForwardingInboxPort`（SDK）还是 Gateway 自建（按 correlationId+eventType+摘要）—— B3 选。
- **streamRef 解析**：`StreamRefResolver` 端口；联调期对接真实解析（标准 SSE 分支）。

## 7. Phase B 循环口径（备忘）

每切片：取下一未完成 → 写 1 个失败测试（优先 module-it/行为单测）→ 最小实现转绿 → 补同切片剩余验收行（逐条 RED→GREEN）→ 仅 GREEN 时重构 → `git commit feat/fix(gateway): FEAT-012 <切片ID> — <行为>`（**只进 `feat/feat-012-bus-forwarding`**）→ 更新本表 Done + 追溯表状态 → 简报 → 下一片（自动续跑，每 2 片报进度，全部完成再叫你）。

充分性自检（每场景切片结束）：012 验收行 100% 有测试 ID；失败/边界 ≥ 成功；单测覆盖规则、集成测覆盖整链；无拓扑泄漏断言；730 OUT 项（IN-10/11）不假成功；BUS 硬约束（零 I-04 / token 不进 bus / STREAM_READY 门控 / G4 complete-abort 接投影）有断言。
