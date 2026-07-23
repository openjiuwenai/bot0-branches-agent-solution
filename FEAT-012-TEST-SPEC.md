# FEAT-012 测试规格（TEST-SPEC）

> 零代码阶段产出。本文件定义 B1~B9 每个切片的**全部测试用例**（类名、方法名、Given/When/Then、覆盖率目标、桩），作为自动化开发的规格真源。
> 开发时照本文件逐切片实现，测试全绿即切片完成。
> 每切片完成后输出报告（落盘）。
> 分支：`feat/feat-012-bus-forwarding`（只在此 commit）。

---

## 测试分层

| 层级 | 目的 | 形态 |
|---|---|---|
| **unit** | 纯逻辑：折叠规则、窗口超时、投影幂等/乱序、信封组装、correlationId 生成、PayloadStore、错误映射 | 普通 JUnit5 + AssertJ，无 Spring |
| **module-it** | facade→治理→path→bus(桩)→projection(桩)→wait→回传 整链 | `@WebMvcTest` / `@SpringBootTest` + 桩 bean |
| **wiring** | Spring 配置装配验证（path-mode config、bean 注入） | `@SpringBootTest(properties=…)` |

## 桩清单（Gateway 自建 + 复用 011）

| 桩 | 替代 | 能力 |
|---|---|---|
| `FakeForwardingOutboxPort` | `ForwardingOutboxPort` | 记录 enqueue 调用（envelope/sourceServiceId/targetServiceId）；可注入 produce 成功 / `UNAVAILABLE` / `ROUTE_NOT_FOUND`；返回 `ForwardingReceipt` |
| `FakeProjectionFeed` | `BrokerForwardingConsumerPort` | 测试按 `correlationId` 注入投影（ACCEPTED/RESPONSE/REJECTED/FAILED/STREAM_READY/TERMINAL）；`poll()` 返回队列；`commit()`/`reject()` 记账 |
| `FakePayloadStore` | `PayloadStore`（Gateway 自建端口） | `stash(body)→ref`；`fetch(ref)→body`；内存 Map |
| `FakeStreamRefResolver` | `StreamRefResolver`（Gateway 自建端口） | `resolve(streamRef)→endpointUrl`（canned）；返回 null = 解析失败 |
| `FakeRdcRouteClient` | 复用 011 | 候选列表 / resolve（可注入空 / 失败） |
| `FakeAgentRuntimeClient` | 复用 011 | 同步响应 / 流式帧（BUS 流式续跑 I-06 用） |

---

## B1 — path/ 选择器

### 单元测试 `PathSelectorTest`

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 1 | `fromConfigAcceptsValidModes` (参数化) | `"direct"/"DIRECT"/" direct "/"Direct"` | `PathMode.fromConfig(x)` | `DIRECT` |
| 2 | 同上 | `"bus"/"BUS"/" Bus "` | 同上 | `BUS` |
| 3 | `fromConfigBlankOrNullDefaultsDirect` | `null`/`""`/`"   "` | `fromConfig` | `DIRECT`（lenient） |
| 4 | `fromConfigInvalidFailsFast` (参数化) | `"tube"/"highway"/"DIRECT_PATH"/"bus!"/"123"` | `fromConfig` | `IllegalArgumentException`（含 "gateway.path-mode"） |
| 5 | `selectorBusMode` | `new PathSelector("bus")` | `.mode()/.isBus()/.isDirect()` | `BUS / true / false` |
| 6 | `selectorDirectModeExplicit` | `new PathSelector("direct")` | 同上 | `DIRECT / false / true` |
| 7 | `selectorDirectModeDefault` | `new PathSelector("")` | `.isDirect()` | `true` |

### 装配测试 `PathSelectorWiringTest`

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 8 | `busModeWiredFromSpringConfig` | `@SpringBootTest(properties="gateway.path-mode=bus")` | `@Autowired PathSelector` | `isBus()==true`；`mode()==BUS` |

> 默认 direct 装配由 `A2aRouteSmokeTest`（全量上下文）覆盖（PathSelector bean 存在、默认 direct）。

### 覆盖率目标
- 功能：fromConfig 全分支（direct/bus/blank/null/invalid×N）+ selector 两态 × 3 方法 = 100%。
- 代码：PathMode.fromConfig + PathSelector 构造 + 全部 getter。

---

## B2 — bus/control 出站（I-04 enqueue）

### 单元测试 `EnvelopeBuilderTest`

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 1 | `buildsCreateEnvelopeWithAllRequiredFields` | ctx(tenantId=T1, agentId=A1)；RDC 返回 handle H1(tenantScope=T1)；payloadRef=REF | `buildEnvelope(ctx, routeHandle, correlationId, payloadRef)` | envelope: eventType=`CLIENT_INVOCATION_REQUESTED`；tenantId=T1；routeHandle=H1；correlationId 非空；idempotencyKey 非空；sourceServiceId/targetServiceId 非空；payloadPolicy=DATA_BEARING；payloadRef=REF |
| 2 | `tenantMismatchThrows` | ctx tenantId=T1；routeHandle tenantScope=T2 | `buildEnvelope` | `IllegalArgumentException`（tenant_mismatch） |
| 3 | `correlationIdSelfGenerated` | 两次 buildEnvelope（不同 ctx） | `.correlationId()` | 两次值不同（Gateway 自生成，策略 B）；不含 clientInvocationId |
| 4 | `resumeEnvelopeCarriesTaskIdInPayload` | ctx(taskId=task-7)；payloadRef | `buildEnvelope(ctx, …)` | envelope eventType 仍 `CLIENT_INVOCATION_REQUESTED`；payloadRef 携带的 A2A body 含 taskId |
| 5 | `payloadPolicyControlOnlyWhenNoBody` | 空 body | `buildEnvelope(…, null)` | payloadPolicy=CONTROL_ONLY；payloadRef=null |

### 单元测试 `PayloadStoreTest`

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 6 | `stashAndFetchRoundtrip` | body=`{"jsonrpc":…}` | `stash(body)→ref`；`fetch(ref)` | ref 非空；`fetch(ref)==body` |
| 7 | `fetchMissingReturnsEmpty` | 未 stash 的 ref | `fetch("ghost")` | `Optional.empty()` |
| 8 | `stashDifferentBodiesGetDifferentRefs` | bodyA, bodyB | `stash(A)→refA`；`stash(B)→refB` | `refA != refB` |

### 单元测试 `BusControlForwarderTest`

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 9 | `forwardEnqueuesEnvelope` | ctx(创建类)；RDC 候选 [H1]；resolve OK；FakeOutbox enqueue→成功 | `forward(ctx)` | FakeOutbox 收到 1 次 enqueue（envelope.eventType=CLIENT_INVOCATION_REQUESTED）；未调 runtime（I-03） |
| 10 | `forwardProduceUnavailableReturnsEnqueueFailed` | FakeOutbox enqueue→UNAVAILABLE | `forward(ctx)` | `GovernanceException(503, ENQUEUE_FAILED)` |
| 11 | `forwardRouteNotFoundReturnsEnqueueFailed` | FakeOutbox enqueue→ROUTE_NOT_FOUND | 同上 | `GovernanceException(503, ENQUEUE_FAILED)` |
| 12 | `forwardDoesNotCallRuntimeDirect` | 任何创建 | `forward(ctx)` | FakeAgentRuntimeClient.lastEndpoint==null（I-03 不走） |

### 覆盖率目标
- 功能：信封组装（创建/续跑/租户一致性）+ correlationId 自生成唯一性 + PayloadStore 存取 + enqueue 成功/UNAVAILABLE/ROUTE_NOT_FOUND + 不调 runtime。
- 代码：EnvelopeBuilder 全分支 + PayloadStore + BusControlForwarder 全出口。

---

## B3 — bus/wait + projection 五态

### 单元测试 `FiveStateFolderTest`

| # | 方法 | Given 投影 | When 折叠 | Then 结果面 |
|---|---|---|---|---|
| 1 | `response→CompletedResponse` | INVOCATION_RESPONSE | fold | `COMPLETED_RESPONSE` |
| 2 | `accepted→AcceptedWithTask` | INVOCATION_ACCEPTED(taskId=t1) | fold | `ACCEPTED_WITH_TASK`；taskId=t1 |
| 3 | `rejected→Rejected` | INVOCATION_REJECTED | fold | `REJECTED` |
| 4 | `failed→Failed` | INVOCATION_FAILED | fold | `FAILED` |
| 5 | `streamReady→StreamReady` | INVOCATION_STREAM_READY(streamRef=sr1) | fold | `STREAM_READY`；streamRef=sr1 |
| 6 | `terminal→CompletedResponse` | INVOCATION_TERMINAL(含结果) | fold | `COMPLETED_RESPONSE`（终态含结果） |
| 7 | `emptyProjection→Unknown` | （无投影） | fold | `UNKNOWN` |

### 单元测试 `ProjectionDedupTest`（§4.6.1）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 8 | `duplicateProjectionIgnored` | 同 (correlationId, eventType, 摘要) 投影到 2 次 | 第二次到达 | **不**二次折叠/回传/complete |
| 9 | `lateProjectionAfterTerminalIgnored` | 先 TERMINAL→折叠；后迟到 ACCEPTED | ACCEPTED 到达 | **忽略**（不降级终态） |
| 10 | `responseBeforeAcceptedFolds` | 先 RESPONSE（未见 ACCEPTED） | fold RESPONSE | `COMPLETED_RESPONSE`（不要求先见 ACCEPTED） |
| 11 | `streamReadyBeforeAcceptedAllowed` | 先 STREAM_READY（未见 ACCEPTED） | 到达 | **允许**（可分离；仍须 client 连接才建 I-06） |

### 单元测试 `WaitWindowTest`（双窗口 + 超时）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 12 | `acceptWindowTimeout→Unknown` | accept-wait 30s；无 ACCEPTED/REJECTED/FAILED/RESPONSE | 超时 | `UNKNOWN`；G4 abort |
| 13 | `responseWindowTimeout→Accepted` | 已 ACCEPTED(taskId)；response-wait 60s；无最终响应 | 超时 | `ACCEPTED_WITH_TASK`；**禁止**改报 UNKNOWN |
| 14 | `acceptedThenResponseWithinWindow→Completed` | ACCEPTED → RESPONSE 在窗口内 | fold | `COMPLETED_RESPONSE` |

### 单元测试 `G4BusWiringTest`（§4.6.3 G4 complete/abort 接投影终态）

| # | 方法 | Given 投影 | When | Then G4 动作 |
|---|---|---|---|---|
| 15 | `response→complete` | RESPONSE 折叠完成 | 回传后 | G4 `complete(结果体)` |
| 16 | `rejected→complete` | REJECTED | 同上 | G4 `complete(拒绝面)` |
| 17 | `failed→complete` | FAILED | 同上 | G4 `complete(失败面)` |
| 18 | `acceptedTimeout→complete` | ACCEPTED + response 窗口超时 | 同上 | G4 `complete(已接受面)` |
| 19 | `produceFail→abort` | enqueue 失败 | 失败时 | G4 `abort`（释放 IN_FLIGHT） |
| 20 | `acceptTimeout→abort` | accept 窗口超时→UNKNOWN | 同上 | G4 `abort` |
| 21 | `syncDisconnect→abort` | 同步等待中 client 断开 | 断开时 | G4 `abort`；**不**自动 Cancel Task |
| 22 | `streamingNormalEnd→complete` | 流式正常消费完 | 结束后 | G4 `complete(首帧/摘要)` |
| 23 | `streamingFail→abort` | 流式失败 | 失败时 | G4 `abort` |
| 24 | `replayAfterCompleteNoRePublish` | G4 已 complete；同键重试 | 重试 | **REPLAY**；不二次 enqueue（FakeOutbox 第 2 次 enqueue==0） |
| 25 | `retryAfterAbortReRegisters` | G4 已 abort；同键重试 | 重试 | **NEW**→可再 enqueue |

### 单元测试 `SyncDisconnectTest`（§4.6.2）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 26 | `syncDisconnectReleasesWindow` | 同步等待中 | client HTTP 断开 | 释放等待窗口；不 publish CANCEL；Task 不 Cancel；迟到投影可丢弃 |
| 27 | `syncDisconnectAbortsG4` | 同上 | 同上 | G4 `abort` |

### 覆盖率目标
- 功能：五态全 + 幂等/乱序/终态闭合 + 双窗口超时（accept/response）+ G4 全接线（complete×5/abort×4/replay/retry）+ 同步断开。
- 代码：FiveStateFolder + ProjectionDedup + WaitWindow + G4BusWiring + SyncDisconnectHandler 全分支。

---

## B4 — facade BUS 同步接线

### 集成测试 `A2aBusFacadeTest`（path=bus，@WebMvcTest + 桩）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 1 | `syncCreateReturnsFiveState` | 治理通过；RDC 候选；FakeOutbox OK；FakeProjectionFeed 注入 RESPONSE | POST /a2a SendMessage(path=bus) | 200 + 结果面（COMPLETED_RESPONSE）；FakeOutbox enqueue==1 |
| 2 | `syncCreateAccepted` | FakeProjectionFeed 注入 ACCEPTED(taskId) | 同上 | 200 + ACCEPTED_WITH_TASK + taskId |
| 3 | `syncCreateRejected` | FakeProjectionFeed 注入 REJECTED | 同上 | 200 + REJECTED |
| 4 | `syncCreateFailed` | FakeProjectionFeed 注入 FAILED | 同上 | 200 + FAILED |
| 5 | `syncCreateUnknown` | accept 窗口超时（短配）；无投影 | 同上 | 200 + UNKNOWN |
| 6 | `governanceRejectZeroEnqueue` | 缺 Bearer | POST /a2a | 401 AUTH_MISSING；FakeOutbox enqueue==0 |
| 7 | `emptyCandidatesZeroEnqueue` | RDC 空列表 | 创建 | 503 ROUTE_NO_CANDIDATES；FakeOutbox enqueue==0 |
| 8 | `defaultAgentBusCreate` | 无 agentId；默认配置 | 创建 | FakeOutbox envelope targetServiceId==默认 Agent |
| 9 | `correlationIdSelfGenerated` | 任意创建 | POST /a2a | FakeOutbox envelope.correlationId 非空且不含 clientInvocationId（策略 B） |
| 10 | `produceFailReturnsEnqueueFailed` | FakeOutbox→UNAVAILABLE | 创建 | 503 ENQUEUE_FAILED |
| 11 | `noTopologyLeak` | 成功创建 | 响应 body | 不含 routeHandle/topic/worker/endpoint 明文 |
| 12 | `syncDisconnectReleases` | 等待投影时 client 断开 | 断开 | 释放窗口；不 Cancel Task |

### 覆盖率目标
- 功能：同步 BUS 创建五态 + 治理/选路失败零 enqueue + 默认 Agent + correlationId B + 拓扑无泄漏 + 同步断开。
- 代码：facade path=bus 分支 + BusControlForwarder + WaitWindow + Folder 端到端。

---

## B5 — 流式 STREAM_READY → I-06

### 单元测试 `StreamReadyGateTest`

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 1 | `streamReadyAfterAccepted` | ACCEPTED → STREAM_READY | gate check | 允许建 I-06 |
| 2 | `streamReadyBeforeAccepted` | STREAM_READY（未见 ACCEPTED） | gate check | **允许**（可分离）；仍须 streamRef |
| 3 | `acceptedWithoutStreamReady` | 仅 ACCEPTED | gate check | **不**建 I-06（等待 STREAM_READY） |
| 4 | `streamReadyWithStreamRef` | STREAM_READY(streamRef=sr1) | resolve | FakeStreamRefResolver→endpoint；建 I-06 |
| 5 | `noStreamReadyNoBridge` | 任何非 STREAM_READY | gate check | 不开 I-06 |

### 集成测试 `A2aBusFacadeTest`（流式追加）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 6 | `streamingCreateBridgesAfterStreamReady` | FakeProjectionFeed 注入 ACCEPTED+STREAM_READY | POST SendStreamingMessage(path=bus) | 200 text/event-stream；先 STREAM_READY 再 I-06；逐帧桥接 |
| 7 | `tokenNotInBusPayload` | 流式创建 | 检查 FakeOutbox envelope | payloadRef/body 不含 token / SSE 帧 |
| 8 | `streamingDisconnectReleases` | 流式桥接中 client 断开 | 断开 | I-06 release；不 Cancel Task |
| 9 | `streamingNormalEndCompletes` | 流正常消费完 | 结束 | G4 complete(首帧/摘要) |
| 10 | `streamingNoStreamReadyFails` | 仅 ACCEPTED（无 STREAM_READY）→窗口超时 | 超时 | 明确失败（不伪造流成功） |

### 覆盖率目标
- 功能：STREAM_READY 门控（与 ACCEPTED 分离）+ streamRef 解析 + I-06 桥接 + token 不进 bus + release + 正常结束 complete + 无 STREAM_READY 失败。
- 代码：StreamReadyGate + StreamRefResolver + facade 流式分支 + SseBridge 复用。

---

## B6 — S5 选路失败（零 I-04）

### 集成测试 `A2aBusFacadeTest`（S5 追加）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 1 | `emptyCandidatesZeroI04` | RDC 空 | 创建(path=bus) | 503 ROUTE_NO_CANDIDATES；FakeOutbox enqueue==0；无投影等待 |
| 2 | `noEnvelopeTargetZeroI04` | RDC 有行但缺 routeHandle/targetServiceId | 同上 | 503 选路失败；FakeOutbox enqueue==0 |
| 3 | `governanceRejectNotS5` | 缺 Bearer | 创建 | 401 AUTH_MISSING（§3，非 S5）；FakeOutbox==0 |
| 4 | `stickyMissNotS5` | 带 taskId 续跑；sticky 未命中 | 续跑 | RESUME_OWNER_UNKNOWN（§5，非 S5） |
| 5 | `defaultMissingConfigError` | 无 agentId + 默认配置缺失 | 创建 | 500 DEFAULT_AGENT_UNCONFIGURED（配置错误，非空路由 S5） |
| 6 | `physicalEndpointMissingNotS5` | 信封目标齐（handle+serviceId）；仅缺物理 endpoint | 同步创建 | **允许** I-04 enqueue（不按 S5 挡） |
| 7 | `rdcUnavailableZeroI04` | RDC 超时/不可用 | 创建 | 选路失败；FakeOutbox==0（可与空列表分码） |

### 覆盖率目标
- 功能：S5 全触发（空/缺目标/RDC不可用）+ 分层（≠治理/粘滞/配置/仅缺endpoint）+ 零 I-04。
- 代码：facade S5 收口 + bus/control 不被调用断言。

---

## B7 — S3 续跑（continuation）

### 单元测试 `ContinuationEnvelopeTest`

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 1 | `resumeEnvelopeCarriesTaskId` | ctx(taskId=task-7) | buildEnvelope | payloadRef 携带的 A2A body 含 taskId=task-7；eventType 仍 CLIENT_INVOCATION_REQUESTED |
| 2 | `resumeUsesStickyRoute` | sticky(task-7→H1) | buildEnvelope | envelope.routeHandle==H1（沿用创建路由；不重新 search） |
| 3 | `resumeNoSearchCalled` | sticky(task-7→H1) | forward(ctx) | FakeRdcRouteClient.searchInstancesByAgentId **未调用** |

### 集成测试 `A2aBusFacadeTest`（S3 追加）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 4 | `resumeReachesOriginalOwner` | sticky(task-7→H1)；FakeProjectionFeed 注入 RESPONSE | POST SendMessage(taskId=task-7)(path=bus) | 200 COMPLETED_RESPONSE；FakeOutbox envelope payloadRef 含 taskId=task-7 |
| 5 | `resumeNoRouteRefExplicitFail` | sticky 未命中(taskId=ghost) | 续跑 | 404 RESUME_OWNER_UNKNOWN；不 enqueue；不新建 Task |
| 6 | `resumeTaskNotFoundFail` | FakeProjectionFeed 注入 FAILED(TASK_NOT_FOUND) | 续跑 | 明确失败（不新建 Task） |
| 7 | `resumeInputRequiredStubBranch` | FakeProjectionFeed 注入 INPUT_REQUIRED(taskId=t1)（桩） | 续跑 | 等待输入面（桩分支，R2/D3） |

### 覆盖率目标
- 功能：continuation（taskId 不新建）+ sticky 路由复用 + 不 search + owner 不可定位失败 + TASK_NOT_FOUND 失败 + INPUT_REQUIRED 桩。
- 代码：ContinuationEnvelopeBuilder + facade 续跑分支。

---

## B8 — S4 continueInput

### 集成测试 `A2aBusFacadeTest`（S4 追加）

| # | 方法 | Given | When | Then |
|---|---|---|---|---|
| 1 | `continueInputReachesOwner` | sticky(task-ci→H1)；FakeProjectionFeed RESPONSE | POST SendMessage(taskId=task-ci)(path=bus) | 同 T-S3（到原 owner 同 Task）；不区分业务来源 |
| 2 | `continueInputAssocFail` | FakeProjectionFeed FAILED(TASK_NOT_FOUND) | 同上 | 明确失败（不新建） |
| 3 | `continueInputNoTaskIdNotS4` | 无 taskId | 创建类 | 走 S2（非 S4 续跑） |

> 无新代码（wire==S3）；仅验证复用。

### 覆盖率目标
- 功能：wire==S3 验证 + 关联失败 + 无 taskId 不走 S4。
- 代码：facade 续跑分支（复用 B7，无新增）。

---

## B9 — AC-CFG 横切

### 集成测试 `ConfigDeployTest`

| # | 方法 | Given | When | Then | 对应 |
|---|---|---|---|---|---|
| 1 | `pathModeClientInvisible` | path=bus；client 不带 path 字段 | 创建 | 走 BUS；响应无 path 明文 | AC-CFG-1 |
| 2 | `produceFailEnqueueFailed` | FakeOutbox→UNAVAILABLE | 创建 | ENQUEUE_FAILED；无假 taskId | AC-CFG-2 |
| 3 | `acceptTimeoutUnknown` | accept 窗口短配；无投影 | 创建 | UNKNOWN | AC-CFG-3 |
| 4 | `responseTimeoutAccepted` | 已 ACCEPTED；response 窗口超时 | 创建 | ACCEPTED_WITH_TASK（非 UNKNOWN） | AC-CFG-4 |
| 5 | `consumerNotReadyFails` | FakeProjectionFeed 未就绪（不返回投影） | 创建 | 不假成功（UNKNOWN/失败） | AC-CFG-5/9 |
| 6 | `sseReleaseNotDisableable` | 流式已建 I-06；client 断开 | 断开 | release；配置无法关闭 | AC-CFG-6 |
| 7 | `doc: artifactNotBoundToFixture` | （文档勾选） | — | 正式制品不绑 gateway profile | AC-CFG-7 |
| 8 | `doc: networkReachable` | （联调环境） | — | Gateway 可达 RDC/Broker/runtime SSE | AC-CFG-8 |

### 覆盖率目标
- 功能：AC-CFG-1~6/9 自动化；AC-CFG-7/8 文档/部署勾选。
- 代码：配置约束 + 窗口超时 + 消费者就绪门控 + SSE release。

---

## 汇总

| 切片 | 单元测试 | 集成/装配测试 | 总用例 | 验收覆盖 |
|---|---|---|---|---|
| B1 path/ | 7 | 1 | 8 | AC-CFG-1 |
| B2 control 出站 | 12 | — | 12 | T-S2-B9, AC-CFG-2 |
| B3 wait+projection | 27 | — | 27 | §4.6.1/2/3, T-S2-B6/B7/B8/B10, SC-11/12 |
| B4 facade BUS 同步 | — | 12 | 12 | T-S1-B*, T-S2-B1/B2/B4/B9, SC-1/4 |
| B5 流式 STREAM_READY | 5 | 5 | 10 | T-S2-B3, SC-2, AC-CFG-6 |
| B6 S5 选路失败 | — | 7 | 7 | T-S5-B1~B7, SC-4 |
| B7 S3 续跑 | 3 | 4 | 7 | T-S3-B1~B5, SC-5 |
| B8 S4 continueInput | — | 3 | 3 | T-S4-B1~B4 |
| B9 AC-CFG 横切 | — | 8(6自动+2文档) | 8 | AC-CFG-1~10, SC-10 |
| **合计** | **54** | **40** | **94** | 全 012 L2 验收 ID |

> 94 个新测试用例（不含 011 的 80 个）；覆盖率目标：每个切片功能覆盖 100%（验收行全映射）、代码覆盖 ≥ 90%（核心折叠/窗口/信封逻辑全分支）。
