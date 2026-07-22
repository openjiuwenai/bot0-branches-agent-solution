# FEAT-011 切片与测试矩阵（SLICE-PLAN）

> 阶段：Phase A 产出物。**未进入 Phase B（未写业务代码）**。
> 真源：`/Users/kevin/Work/temp/spring-ai-ascend-claude-011/architecture/L2-Low-Level-Design/agent-gateway/Feat-Func-011-client-invocation-route-forwarding.md`（已合入 #440）。
> 实现仓：`/Users/kevin/Work/agent-solution`，分支 `feat/feat-011-direct-route`（base = `origin/common` `8770d87`）。
> 落点：`common/agent-gateway`（**当前不存在，S0 切片创建**）。

---

## 0. 已锁决策（编码基线，不再议）

| # | 决策 | 落地口径 |
|---|------|----------|
| D1 | 真源 | 以已合入 FEAT-011 L2 为准。**S6～S9（GetTask/Cancel/Subscribe/UNKNOWN）730 不交付**；入口若可达→禁止假成功。version-scope 与 L2 冲突处按 L2，记为已知漂移。 |
| D2 | RDC 集成 | Gateway 内定义 **RDC Client 端口（接口）**；730 默认实现走 RDC **HTTP API**（`GET /api/registry/instances/{t}/{a}` + `POST /api/registry/route-handle/resolve`）。测试对端口打桩。**禁止**设计成"必须与 RDC 同进程才能运行"。同进程部署非 730 硬约束。 |
| D3 | DRAINING | 以 RDC 实际返回为准；Gateway 取**排序后首条**、不二次过滤（对齐 L2 §4.4 P1）。 |
| D4 | 粘滞索引 | 730 **单机内存**；代码 + README 标明多实例限制（非共享存储）。 |
| D5 | 包结构 | 按 L2 §1.3 + 参照 `registry-discovery-center` 惯例（`module-metadata.yaml`、独立 Maven 模块、无 reactor 聚合）。不再开 `/arch-driven`。 |
| D6 | 对端 | RDC 与 runtime 下游**均**走"端口 + 桩 + HTTP 实现"模式（D2 的推广），模块集成测一律打桩。 |

## 1. 730 交付边界（摘自 L2 §0.2）

**交付（IN-1～IN-8）**：入口治理 G1～G5、按逻辑目标选路（含默认 Agent）、同步直连、流式 SSE 桥接、选路失败明确失败、端侧工具续跑透传、continueInput 关联转发、统一 A2A 入口。

**不交付（IN-9/IN-10 + S6～S9）**：GetTask / CancelTask / SubscribeToTask 转发、UNKNOWN 同键恢复。入口若可达→返回"不支持/未开放"明确错误或直接不路由，**禁止静默当 SendMessage 成功**。

## 2. 测试三层（本模块内）

- **单元测（unit）**：治理规则（G1～G5）、选路决策（默认 Agent / 取首条）、粘滞索引、错误折叠、拓扑清洗——纯逻辑，不碰 HTTP。
- **模块集成测（module-it）**：`@SpringBootTest` / `MockMvc` / `WebMvcTest` 跑 `POST /a2a` 全链：Facade → 治理 → RDC(桩) → Direct/SSE(桩 runtime) → 响应/SSE。验证"调用链 + 副作用（下游是否被调用）"。
- **场景充分**：L2 验收表逐行映射；失败/边界 ≥ 成功路径。

**端口与桩**（D2/D6）：
- `RdcRouteClient`（端口）：`searchInstancesByAgentId(tenantId, agentId) → List<AgentCardDto>`、`resolveRouteHandle(handle, tenantId) → RouteResolution`。默认实现 `HttpRdcRouteClient`（调 RDC HTTP）。测试用 fake/stub。
- `AgentRuntimeClient`（端口）：同步 `invokeSync(...)` + 流式 `openStream(...)`。默认实现 `HttpAgentRuntimeClient`（HTTP/SSE 到 runtime `/a2a`）。测试用 fake/stub。
- 治理组件为内部类，直接单测；不暴露端口。

## 3. 目标包结构（S0 落地，对齐 L2 §1.3）

```
common/agent-gateway/
├── pom.xml
├── module-metadata.yaml              # 参照 registry-discovery-center
└── src/main/java/com/openjiuwen/gateway/
    ├── GatewayApplication.java
    ├── facade/      A2aFacade：解析 JSON-RPC、方法分发、响应/SSE 写出（POST /a2a）
    ├── governance/
    │   ├── auth/    G1 鉴权（Bearer，principalId，401/403）
    │   ├── tenant/  G2 权威租户（凭据绑定，清洗自报，403）
    │   ├── validate/ G3 参数校验（method 白名单，agentId 可缺省/空串非法，续跑带 taskId，400）
    │   ├── idempotency/ G4 创建幂等（tenantId+messageId，短路/409）
    │   └── audit/   G5 审计留痕（PASSED/REJECTED，traceId）
    ├── routing/     默认 Agent 解析、RdcRouteClient 端口+Http 实现、取首条、粘滞索引（单机内存）
    ├── direct/      AgentRuntimeClient 端口 + HTTP 同步转发
    ├── sse/         SSE 桥接 + client 断开 release（不生成/不缓存 token）
    ├── path/        DIRECT vs BUS（730 固定 DIRECT）
    └── obs/         审计落点、route trace、traceId 关联
```
> 包名 `com.openjiuwen.gateway` 为暂定（参照 `com.openjiuwen.rdc` 短名惯例），S0 确认。

## 4. 切片矩阵

顺序（对齐 L2 §2.3 + dev-loop）：`S0 → S1-G1 → S1-G2 → S1-G3 → S1-G4 → S1-G5 → RDC-PORT → S2-sync → S2-SSE → S5 → S3 → S4(可选)`

| 切片 ID | 行为（一句话） | L2 验收 ID | 单测 | 模块集成测 | 依赖桩 | Done 标准 |
|---|---|---|---|---|---|---|
| **S0-骨架** | 模块/包可编译；端口接口就位 | — | — | smoke：context 加载、`POST /a2a` 可达（404/501 占位不报错即可） | — | `mvn -f common/agent-gateway/pom.xml compile test` 通过；包结构 §3 就位；`RdcRouteClient`/`AgentRuntimeClient` 端口存在 |
| **S1-G1** | 每个 HTTP 带 Bearer；缺失/非法→401 `AUTH_MISSING`/`AUTH_INVALID`；拒绝不选路 | T-G1-1..T-G1-5 | `AuthRuleTest`：5 条判断顺序 | `A2aFacadeWebMvcTest`：无 Bearer→401、且 RDC/runtime 桩 0 调用 | auth 测试凭据配置 | G1 全验收行有测试；断言"未调下游" |
| **S1-G2** | 凭据绑定权威租户；清洗自报；解析失败→403 `TENANT_UNRESOLVED` | T-G2-1..T-G2-4 | `TenantResolverTest`：绑定/无绑定/自报覆盖 | facade：带冲突 `X-Tenant-Id` 仍走权威；无绑定→403 | 凭据→租户绑定表（测试） | G2 全验收行；权威值进可信上下文 |
| **S1-G3** | JSON-RPC 合法 + method 白名单；创建 `agentId` 可缺省/空串非法；续跑必带 `taskId` | T-G3-1..T-G3-6 | `ParamValidatorTest`：创建/续跑/未交付方法各分支 | facade：空串 agentId→400 `VALIDATION_AGENT_ID`；续跑无 taskId 走创建类 | — | G3 全验收行；method/agentId/taskId 读路径正确 |
| **S1-G4** | 创建 `tenantId+messageId` 去重；同键同文短路；同键异文 409；续跑/无键跳过 | T-G4-1..T-G4-5 | `IdempotencyStoreTest`：首次/短路/冲突/无键/续跑 | facade：重复创建短路同 taskId、不二次转发；异文→409 | 幂等存储（单机内存） | G4 全验收行；无双重成功副作用 |
| **S1-G5** | 通过/拒绝都留痕；无完整 token/正文；缺 traceparent 自生成 traceId | T-G5-1..T-G5-4 | `AuditSinkTest`：字段集/无密文/自生成 traceId | facade：拒绝留痕 rejectStage+code；通过留痕 PASSED | 审计 sink（内存捕获） | G5 全验收行；审计不阻断主路径 |
| **RDC-PORT** | `RdcRouteClient` 端口 + HTTP 默认实现 + 测试桩 | 支撑 T-S2-1/4/5、T-S5-1/2 | `HttpRdcRouteClientTest`（用 fake HTTP server 解码请求/响应） | — | RDC fake（返回候选/空/resolve 失败） | 端口可被桩替换；HTTP 实现打通 RDC DTO；**不强依赖同进程** |
| **S2-sync** | 治理通过→effectiveAgentId（显式/默认）→RDC 取首条→resolve→HTTP 同步直连；首 taskId 写粘滞；拓扑清洗 | T-S2-1,T-S2-2,T-S2-5,T-S2-6,T-S2-7 | `RouterTest`：默认 Agent / 取首条 / 再入显式 / 粘滞写入 | facade：同步创建到达指定 runtime、返回可消费面、响应无 routeHandle；默认配置缺失→配置错误失败（§4.6） | RDC 桩 + runtime 桩（同步） | S2 同步全验收行；响应注入 `params.metadata.tenantId`（AC-RT-1/GW-RT-10） |
| **S2-SSE** | `SendStreamingMessage`→开下游流→逐帧桥接；不生成/不缓存 token；断开 release；不自动 Cancel | T-S2-3、SC-2 | `SseBridgeTest`：开始桥接/正常结束/断开 release | facade：流式创建 SSE 桥接；断开后下游流被取消；Gateway 未生成 token | runtime 桩（SSE Flux） | SSE 三类断言齐全；断开→release |
| **S5** | 空候选/resolve 失败→明确选路失败，不调 runtime、不伪造 Task、不泄漏拓扑；区分 S1 拒绝与粘滞未命中 | T-S5-1..T-S5-5 | `RouteFailureTest`：空/resolve 失败/配置错误分类 | facade：空列表→失败且 runtime 桩 0 调用；响应无拓扑 | RDC 桩（空/resolve 失败） | S5 全验收行；错误分层（ROUTE_* ≠ AUTH_*/VALIDATION_*） |
| **S3** | 带 `taskId`+新 `messageId`+TextPart→只读粘滞→透传原实例；粘滞未命中→明确失败；关联失败（-32001/-32004）→失败非新建 | T-S3-1..T-S3-4 | `StickyIndexTest`：命中/未命中/同 taskId 两次；`_interrupt` 透传 | facade：续跑到原实例、parts 不改、响应无 routeHandle；粘滞未命中→失败；不调 search | 粘滞存储（单机内存）+ runtime 桩 | S3 全验收行；续跑成功路径不走 search |
| **S4(可选)** | continueInput 与 S3 同 wire（`SendMessage`+原 taskId+新 messageId+TextPart）；复用 S3 粘滞；关联失败不投影为新建成功 | T-S4-1..T-S4-4 | （复用 S3，差量在错误映射） | facade：continueInput 到原 owner；关联不可续接→明确失败 | 同 S3 | S4 全验收行；差量代码集中在错误映射/观测 |

> **最低场景集合覆盖**（dev-loop §最低场景集合，跨切片断言）：
> - 治理拒绝不转发：G1～G5 任一拒绝 → RDC/runtime/SSE 桩 **0 调用**（每个 G 切片的 module-it 断言 + S5 的 T-S5-3）。
> - 默认 Agent：S2-sync（T-S2-2）。
> - 选路失败不调 runtime：S5（T-S5-1/2）。
> - SSE release / 不缓存 token：S2-SSE（T-S2-3、SC-2）。
> - 粘滞续跑 + 响应无 routeHandle：S2-sync 写入（T-S2-7）+ S3 读取（T-S3-1/4）。
> - 无拓扑泄漏：S2/S3/S5 响应与错误体均断言无 endpoint/routeHandle/实例地址明文。
> - 创建幂等：S1-G4（T-G4-1..3）。

## 5. 验收→测试追溯表（Phase B 逐切片填充；状态列初始 `待写`）

| 验收 ID | 切片 | 测试类#方法（暂定） | 层级 | 状态 |
|---|---|---|---|---|
| T-G1-1 | S1-G1 | AuthRuleTest#missingHeaderReturnsAuthMissing / #blankHeaderReturnsAuthMissing + A2aControllerWebMvcTest#noAuthorizationReturns401AuthMissing | unit+it | ✅ |
| T-G1-2 | S1-G1 | AuthRuleTest#nonBearerSchemeReturnsAuthInvalid / #emptyBearerTokenReturnsAuthInvalid + A2aControllerWebMvcTest#nonBearerSchemeReturns401AuthInvalid | unit+it | ✅ |
| T-G1-3 | S1-G1 | AuthRuleTest#unknownTokenReturnsAuthInvalid + A2aControllerWebMvcTest#unknownTokenReturns401AuthInvalid | unit+it | ✅ |
| T-G1-4 | S1-G1 | AuthRuleTest#validTokenReturnsPrincipal + A2aControllerWebMvcTest#validTokenPassesG1 | unit+it | ✅ |
| T-G1-5 | S1-G1 | A2aControllerWebMvcTest#noAuthorizationReturns401AuthMissing（失败在 HTTP 治理层，非已接受 Task） | it | ✅ |
| T-G2-1 | S1-G2 | TenantResolverTest#boundCredentialResolvesAuthoritativeTenant + A2aControllerWebMvcTest#boundCredentialPassesGovernance | unit+it | ✅ |
| T-G2-2 | S1-G2 | TenantResolverTest#unboundCredentialReturns403TenantUnresolved + A2aControllerWebMvcTest#unboundCredentialReturns403TenantUnresolved | unit+it | ✅ |
| T-G2-3 | S1-G2 | TenantResolverTest#conflictingSelfReportIsIgnored + A2aControllerWebMvcTest#conflictingSelfReportStillPasses | unit+it | ✅ |
| T-G2-4 | S1-G2 | TenantResolverTest#matchingSelfReportStillYieldsAuthoritative | unit | ✅ |
| T-G3-1 | S1-G3 | ParamValidatorTest#createWithNonEmptyAgentIdPopulatesContext | unit | ✅ |
| T-G3-2 | S1-G3 | ParamValidatorTest#createWithoutAgentIdIsAccepted | unit | ✅ |
| T-G3-3 | S1-G3 | ParamValidatorTest#createWithEmptyAgentIdReturns400ValidationAgentId + A2aControllerWebMvcTest#emptyAgentIdReturns400ValidationAgentId | unit+it | ✅ |
| T-G3-4 | S1-G3 | ParamValidatorTest#resumeWithTaskIdPopulatesTaskId | unit | ✅ |
| T-G3-5 | S1-G3 | ParamValidatorTest#resumeMissingTaskIdIsTreatedAsCreate | unit | ✅ |
| T-G3-6 | S1-G3 | ParamValidatorTest#malformedBodyReturns400ValidationJsonrpc / #methodNotInWhitelistReturns400ValidationMethod + A2aControllerWebMvcTest#badMethodReturns400ValidationMethod / #malformedBodyReturns400ValidationJsonrpc | unit+it | ✅ |
| T-G4-1 | S1-G4 | IdempotencyRuleTest#firstCreateRegistersInFlightAndProceeds | unit | ✅ |
| T-G4-2 | S1-G4 | IdempotencyRuleTest#sameKeySameBodyCompletedReplaysPriorResult（seeded completed；REPLAY 的 e2e 待 S2 产生结果） | unit | ✅ |
| T-G4-3 | S1-G4 | IdempotencyRuleTest#sameKeyDifferentBodyReturnsConflict + A2aControllerWebMvcTest#sameMessageIdDifferentBodyReturns409Conflict | unit+it | ✅ |
| T-G4-4 | S1-G4 | IdempotencyRuleTest#noMessageIdSkipsDedup + A2aControllerWebMvcTest#createWithoutMessageIdProceedsTwice | unit+it | ✅ |
| T-G4-5 | S1-G4 | A2aControllerWebMvcTest#resumeWithTaskIdSkipsIdempotency | it | ✅ |
| T-G5-1 | S1-G5 | GovernanceAuditorTest#passedRecordCarriesTenantMethodTrace + A2aControllerWebMvcTest#passedRequestIsAuditedPassedWithSelfGeneratedTraceId | unit+it | ✅ |
| T-G5-2 | S1-G5 | GovernanceAuditorTest#rejectedRecordCarriesStageAndCode / #rejectedStageInferredForAllGovernanceCodes + A2aControllerWebMvcTest#g1FailureAuditedAsRejectedWithStage | unit+it | ✅ |
| T-G5-3 | S1-G5 | GovernanceAuditorTest#recordsNeverCarryCredentialOrBody（AuditEvent 结构上无 token/body 字段） | unit | ✅ |
| T-G5-4 | S1-G5 | A2aControllerWebMvcTest#passedRequestIsAuditedPassedWithSelfGeneratedTraceId（无 traceparent → 自生成 traceId，主路径不失败） | it | ✅ |
| T-S2-1 | S2-sync | RouterTest#explicitAgentRoutesToFirstCandidateAndWritesSticky + A2aControllerWebMvcTest#createWithAgentForwardsAndReturnsTaskBody | unit+it | ✅ |
| T-S2-2 | S2-sync | RouterTest#noAgentIdFallsBackToDefaultAgent + A2aControllerWebMvcTest#createWithoutAgentUsesDefaultAgent | unit+it | ✅ |
| T-S2-3 | S2-SSE | SseBridgeTest + RouterTest#routeStreamBridgesFramesAndWritesStickyOnFirstTaskId + A2aControllerWebMvcTest#streamingCreateBridgesRuntimeFramesAsSse | unit+it | ✅ |
| T-S2-4 | S5 | RouterTest#emptyCandidatesReturnsRouteNoCandidates + A2aControllerWebMvcTest#emptyCandidatesReturnsRouteNoCandidates（S5 切片补 T-S5-* 专测） | unit+it | ✅ |
| T-S2-5 | S2-sync | RouterTest#multiInstancePicksFirstCandidate | unit | ✅ |
| T-S2-6 | S2-sync | RouterTest#reentryWithExplicitAgentDoesNotUseDefault | unit | ✅ |
| T-S2-7 | S2-sync | RouterTest#explicitAgentRoutesToFirstCandidateAndWritesSticky（sticky 写入）+ A2aControllerWebMvcTest#createWithAgentForwardsAndReturnsTaskBody（响应无 routeHandle） | unit+it | ✅ |
| T-S3-1 | S3 | StickyIndexTest.resumeReachesOriginal / A2aFacadeResumeTest | unit+it | 待写 |
| T-S3-2 | S3 | StickyIndexTest.missReturnsFailNoNewTask | unit+it | 待写 |
| T-S3-3 | S3 | StickyIndexTest.resumeMissingTaskIdNotSticky | unit | 待写 |
| T-S3-4 | S3 | StickyIndexTest.sameTaskIdTwoResumesSameOwner | unit | 待写 |
| T-S4-1 | S4 | A2aFacadeContinueInputTest.reachesOriginalOwner | it | 待写 |
| T-S4-2 | S4 | A2aFacadeContinueInputTest.associationFailExplicit | it | 待写 |
| T-S4-3 | S4 | A2aFacadeContinueInputTest.noTaskIdNotS4 | it | 待写 |
| T-S4-4 | S4 | A2aFacadeContinueInputTest.expiredTerminalExplicitFail | it | 待写 |
| T-S5-1 | S5 | RouteFailureTest.emptyListNoRuntime / A2aFacadeRouteFailTest | unit+it | 待写 |
| T-S5-2 | S5 | RouteFailureTest.resolveFailNoRuntime | unit+it | 待写 |
| T-S5-3 | S5 | A2aFacadeRouteFailTest.governanceRejectNotS5 | it | 待写 |
| T-S5-4 | S5 | StickyIndexTest.stickyMissIsS3NotS5 | unit | 待写 |
| T-S5-5 | S2-sync | RouterTest.defaultConfigMissingConfigError | unit+it | 待写 |
| SC-1 | S2-sync/S5 | （聚合）合法创建到达 runtime / 失败明确 | — | 待写 |
| SC-2 | S2-SSE | SseBridgeTest（逐帧桥接/释放流/空流；Gateway 不生成 token——结构透传） | — | ✅ |
| SC-3 | S2-sync | RouterTest（默认/指定 Agent） | — | 待写 |
| SC-4 | S1/S5 | （聚合）拒绝/选路失败明确、无拓扑泄漏 | — | 待写 |
| SC-5 | S3 | StickyIndexTest + 关联失败 | — | 待写 |
| SC-6 | 全 | （断言）不写 Task 库/不执行 Agent/不上架——以"无下游业务副作用 + 无 TaskStore 依赖"体现 | — | 待写 |
| SC-7 | 全 | S6～S9 入口若可达不假成功（占位错误/不路由） | — | 待写 |

## 6. 实现期需顺手确认（非阻塞，遇到再定）

- **包名** `com.openjiuwen.gateway` 是否与既有约定冲突（S0 确认）。
- **RDC HTTP 基址/鉴权**：Gateway 调 RDC 的 base-url、是否带 `X-Caller-Ref`/`traceparent`（FEAT-016 §6.1）。730 用配置项 + 联调值，不硬编码。
- **runtime 联调地址/凭据形态**：runtime `/a2a` 的 base-url 与 Bearer（Gateway→runtime 是否复用 client token 还是平台凭据）——L2 §4.10 不强制，联调定；730 测试全用桩。
- **多实例 Gateway 粘滞共享**：D4 已定 730 单机内存 + 标注限制；不实现共享存储。
- **构建环境**：`common` 各模块独立、依赖已 install 的 openJiuwen core(feature/630)+runtime(develop) 制品。S0 若编译缺依赖→停下报告（不擅自改 vendored 依赖）。

## 7. Phase B 循环口径（摘自 dev-loop，备忘）

每切片：取下一未完成 → 写 1 个失败测试（优先 module-it/行为单测）→ 最小实现转绿 → 补同切片剩余验收行（逐条 RED→GREEN）→ 仅 GREEN 时重构 → `git commit feat(gateway): FEAT-011 <切片ID> — <行为>` → 更新本表 Done + 追溯表状态 → 简报（映射哪条验收）→ 停等"继续"或按自动续跑协议推进。

充分性自检（每场景切片结束）：验收行 100% 有测试 ID；失败/边界 ≥ 成功；单测覆盖规则、集成测覆盖整链；无拓扑泄漏断言；730 OUT 项不假成功。

## 8. Phase B 进度

构建环境：JDK 21 (Temurin 21.0.11, `JAVA_HOME=/Users/kevin/jdks/jdk-21.0.11+10/Contents/Home`) + Maven 3.9.15 (`~/.m2/wrapper`)；非交互 shell 需显式 `export JAVA_HOME`。本会话 shell 无系统 JDK/mvn，构建在上述 JAVA_HOME 下跑通。

| 切片 | 状态 | 验收覆盖 | commit |
|---|---|---|---|
| S0 骨架 | ✅ GREEN（11 测全过） | — | feat(gateway): FEAT-011 S0 |
| S1-G1 鉴权 | ✅ GREEN | T-G1-1..5（见 §5） | feat(gateway): FEAT-011 S1-G1 |
| S1-G2 租户 | ✅ GREEN（17 测全过） | T-G2-1..4（见 §5） | feat(gateway): FEAT-011 S1-G2 |
| S1-G3 校验 | ✅ GREEN（28 测全过） | T-G3-1..6（见 §5） | feat(gateway): FEAT-011 S1-G3 |
| S1-G4 幂等 | ✅ GREEN（37 测全过） | T-G4-1..5（见 §5；T-G4-2 REPLAY e2e 待 S2） | feat(gateway): FEAT-011 S1-G4 |
| S1-G5 审计 | ✅ GREEN（43 测全过） | T-G5-1..4（见 §5；顺带解决 TD-2 traceId 入口贯穿） | feat(gateway): FEAT-011 S1-G5 |
| RDC-PORT | ✅ GREEN（48 测全过） | 支撑 T-S2-*/T-S5-*（HttpRdcRouteClient + MockWebServer + FakeRdcRouteClient） | feat(gateway): FEAT-011 RDC-PORT |
| S2-sync | ✅ GREEN（60 测全过） | T-S2-1/2/4/5/6/7（见 §5；T-S2-4 S5 专测后续补） | feat(gateway): FEAT-011 S2-sync |
| S2-SSE | ✅ GREEN（65 测全过） | T-S2-3、SC-2（见 §5） | feat(gateway): FEAT-011 S2-SSE |
| S5 | ⏳ 下一片 | T-S5-1..5 | — |
| S3 / S4 | 待办 | 见 §5 | — |

已知技术债（非阻塞）：Mockito self-attaching 警告（JDK 未来版本需把 mockito agent 加到 surefire argLine）。（TD-2 traceId 入口贯穿已在 S1-G5 解决。）
