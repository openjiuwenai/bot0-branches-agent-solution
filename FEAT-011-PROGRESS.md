# FEAT-011 开发进展报告（最终，审核用）

> **状态：全部 12 个切片完成，79/79 测试 GREEN（审核必修 #1/#2 + P0-1 + P1 TD-8 流式幂等短路已修），未 push。** 请审核。
> 分支：`feat/feat-011-direct-route`（base `origin/common` `8770d87`）。
> 真源：`/Users/kevin/Work/temp/spring-ai-ascend-claude-011/.../Feat-Func-011-...md`（L2，#440）。
> 实现仓：`/Users/kevin/Work/agent-solution/common/agent-gateway`（新建模块）。
> 日期：2026-07-22。

---

## 0. 一句话结论

按 FEAT-011 L2 的 730 交付边界（IN-1~IN-8）完成 agent-gateway：入口治理 G1~G5 + 同步直连 + SSE 桥接 + 选路失败 + 粘滞续跑 + continueInput，统一 A2A 入口；下游 RDC/runtime 走端口 + HTTP 实现 + 测试桩（不同进程耦合）。**S6~S9（GetTask/Cancel/Subscribe/UNKNOWN）按 L2 不交付**，入口非白名单方法直接拒绝（不假成功）。垂直 TDD，每切片 RED→GREEN→commit，72 测全过。

## 1. 构建工具链

- 远程 cursor-server over SSH，非交互 shell 无系统 JDK/mvn。用 Cursor 装的 Temurin 21.0.11 + `~/.m2/wrapper` 的 Maven 3.9.15：
  `export JAVA_HOME=/Users/kevin/jdks/jdk-21.0.11+10/Contents/Home`；`mvn=~/.m2/wrapper/dists/apache-maven-3.9.15/9925cc1d/bin/mvn`；`mvn -f common/agent-gateway/pom.xml test`。
- 栈：Spring Boot 4.0.5 / Java 21。依赖全命中本地 m2（首构建 ~2s）。

## 2. 已完成切片（12 个，均 GREEN）

| 切片 | commit | 要点 |
|---|---|---|
| S0 骨架 | `1859b3d` | 独立 Maven 模块 + L2 §1.3 包结构 + 双端口（RdcRouteClient/AgentRuntimeClient）+ module-metadata（forbidden_deps 编码 D2 HTTP-only） |
| S1-G1 鉴权 | `e416dcf` | `AuthRule`：Bearer 判断顺序；缺失→AUTH_MISSING、非法→AUTH_INVALID；`CredentialDirectory` 端口 + 配置态实现；`@RestControllerAdvice` 统一错误体 |
| S1-G2 租户 | `f9ffc52` | `TenantResolver`：凭据绑定权威租户，丢弃自报；无绑定→TENANT_UNRESOLVED(403) |
| S1-G3 校验 | `7ed79b7` | `ParamValidator`：JSON-RPC 解析 + method 白名单 + agentId 可缺省/空串非法 + 创建/续跑分类（按 taskId） |
| S1-G4 幂等 | `96b02d5` | `IdempotencyRule`：tenantId+messageId 去重（NEW/REPLAY/CONFLICT/IN_FLIGHT/SKIP）；冲突 409；续跑/无键跳过 |
| S1-G5 审计 | `57f87fa` | `AuditSink` 端口 + `LoggingAuditSink` + `GovernanceAuditor`：通过/拒绝留痕（无 token/正文）；入口解析 traceId 贯穿（解决 TD-2） |
| RDC-PORT | `8857c57` | `HttpRdcRouteClient`（JDK HttpClient，调 RDC HTTP API）+ `RouteResolutionException` + MockWebServer 测试 + `FakeRdcRouteClient` 桩 |
| S2-sync | `9718cea` | `Router.routeCreate`：默认/显式 Agent→取首条→resolve→注入权威 tenantId→同步转发→首 taskId 写粘滞；`StickyIndex`、`DefaultAgentResolver`、`HttpAgentRuntimeClient` |
| S2-SSE | `651cf9e` | `Router.routeStream`（路由后开流，sticky 从首帧写）+ `SseBridge.writeSse`（逐帧 `event: jsonrpc`，try-with-resources 释放）；`openStream` 端口方法 |
| S5 选路失败 | `e2fa78d` | 失败路径（ROUTE_NO_CANDIDATES/ROUTE_RESOLVE_FAILED/DEFAULT_AGENT_UNCONFIGURED）已在 S2 实现；本切片补"未调 runtime + 无拓扑泄漏"断言 + resolve-fail e2e |
| S3 续跑 | `85d40cf` | `Router.routeResume`：只读 sticky 定位 owner（**不调 search**）；未命中→RESUME_OWNER_UNKNOWN(404)；runtime -32001/-32004 关联错误原样透传 |
| S4 continueInput | `332da18` | wire==S3，复用 sticky 路径，**无新生产代码**；补 e2e（到原 owner + 终态 -32004 透传不伪装新建） |

## 3. 测试详情（72 个，全 GREEN）

| 测试类 | 层级 | 数 | 覆盖 |
|---|---|---|---|
| `AuthRuleTest` | unit | 6 | G1 判断顺序 |
| `TenantResolverTest` | unit | 4 | G2 权威租户/清洗/无绑定 |
| `ParamValidatorTest` | unit | 8 | G3 各分支（含流式 method、续跑分类） |
| `IdempotencyRuleTest` | unit | 6 | G4 NEW/REPLAY/CONFLICT/IN_FLIGHT/SKIP/agentId 指纹 |
| `GovernanceAuditorTest` | unit | 4 | G5 PASSED/REJECTED/stage 推断/无密文 |
| `RouterTest` | unit | 12 | 创建直连/默认/取首条/再入/空候选/resolve 失败/默认缺失/无 taskId/stream/sticky/resume/关联错误透传 |
| `HttpRdcRouteClientTest` | unit(MockWebServer) | 5 | RDC HTTP 契约（GET/POST 请求体 + 错误） |
| `HttpAgentRuntimeClientTest` | unit(MockWebServer) | 2 | runtime POST /a2a + 传输失败→FORWARD_FAILED |
| `SseBridgeTest` | unit | 3 | 逐帧写/释放流/空流 |
| `A2aControllerWebMvcTest` | module-it | 21 | facade 全链 G1~G5 + S2 同步/流式 + S5 失败 + S3/S4 续跑（真治理、桩下游） |
| `A2aRouteSmokeTest` | 全量上下文 smoke | 1 | 真 `@SpringBootApplication` 装配 + 真 G1 拒绝 |

每切片验收行 100% 映射到测试 ID（见 SLICE-PLAN §5）；失败/边界用例 ≥ 成功；模块集成测覆盖 facade→治理→RDC(桩)→runtime(桩) 整链；多处断言"未调下游"（FakeXxx.lastEndpoint/lastAgentId after reset）与"无拓扑泄漏"。

## 4. 验收追溯（全部 ✅）

- **S1 治理**：T-G1-1..5、T-G2-1..4、T-G3-1..6、T-G4-1..5、T-G5-1..4 — 全 ✅。
- **S2 创建**：T-S2-1/2/3/4/5/6/7 — 全 ✅（T-S2-3 流式、T-S2-4=S5 失败路径）。
- **S3 续跑**：T-S3-1..4 — 全 ✅。
- **S4 continueInput**：T-S4-1..4 — 全 ✅（wire==S3）。
- **S5 选路失败**：T-S5-1/2/3/5 ✅；T-S5-4（sticky miss 归 S3）✅ via S3。
- **成功标准**：SC-1~SC-7 全部勾选（见 SLICE-PLAN §5）。

## 5. 过程中遇到并已解决的问题

| # | 问题 | 解决 |
|---|---|---|
| 1 | 非交互 shell 无 JDK/mvn；brew 下 openjdk 瓶子卡死（ghcr.io 被墙） | Cursor 用 TUNA 镜像装 Temurin 21；显式 JAVA_HOME + wrapper mvn |
| 2 | AssertJ `extracting(方法引用)` 在 `isInstanceOf` 后类型不匹配 | 改 `catchThrowable` + 显式强转 |
| 3 | Boot 4 `@AutoConfigureMockMvc`/`@WebMvcTest` 包路径变了 | 改用 `org.springframework.boot.webmvc.test.autoconfigure.*` |
| 4 | `.gitignore` 的 `test/`（来自 54eb6fc）误伤所有 Maven `src/test/`，静默丢测试 | 收窄为 `/test/`，amend 进 S1-G1 commit |
| 5 | Boot 4 web starter 不再传递 `jackson-databind` | pom 显式加 jackson-databind |
| 6 | Boot 4 不把 `ObjectMapper` 作为 bean 暴露 | `ParamValidator` 自建私有 mapper |
| 7 | `GovernanceException` 缺带 cause 的构造器 | 补 4 参构造器 |
| 8 | `ResponseEntity<StreamingResponseBody>` 在 `Object` 返回类型下不被流式 handler 识别（走 converter→500） | 流式改用直接写 `HttpServletResponse`（同步、可测、断开即释放） |
| 9 | Fake 下游 bean 在缓存上下文里跨测试方法泄漏状态 | 给 `FakeAgentRuntimeClient`/`FakeRdcRouteClient` 加 `reset()`，`@BeforeEach` 调用 |
| 10 | **G4 `complete()` 未接线**（审核发现）：创建成功后从不 complete，同键重试不走 REPLAY、卡 IN_FLIGHT 409；与"T-G4 全 ✅"矛盾 | controller 同步创建成功路径补 `complete(tenantId, messageId, response)`；补 facade 级 T-G4-2 测试 `sameKeySameBodyReplaysWithoutSecondRuntimeCall`（同键同文重放、不二次调 runtime） |
| 11 | 创建**失败**后 IN_FLIGHT 不释放（同键重试卡 `IDEMPOTENCY_IN_FLIGHT` 409，挡本地重试/联调脚本） | `IdempotencyRule.abort()`；controller 在 create 失败路径（sync `routeCreate` / streaming `routeStream`+`writeSse`）调用；测 `createFailureReleasesInFlightSoRetryCanProceed`（失败后同键重试可达 runtime）+ abort 单测 |

## 6. 技术债（已记录，非阻塞）

| # | 债务 | 状态/建议 |
|---|---|---|
| TD-1 | Mockito self-attaching 警告（未来 JDK 禁动态 agent） | surefire `argLine` 加 mockito agent；升级 JDK 时处理 |
| TD-2 | traceId 曾为 advice 内临时 UUID | ✅ 已在 S1-G5 解决（入口解析 traceparent/自生成，贯穿 context→审计→错误体） |
| TD-3 | `PropertiesCredentialDirectory` 仅单测试凭据（730 简化） | 生产 IdP/claim 映射联调期替换；端口不变 |
| TD-4 | 幂等存储 + 粘滞索引为单机内存（D4） | 多实例 Gateway 需共享存储，明确不在 730；代码/README 已标注 |
| TD-5 | 无 ArchUnit 模块边界/纯度测试 | 收尾期可参考 registry 加 ports 纯度守护 |
| TD-6 | 模块集成测手工 `@Import` 装配治理/routing 组件 | 切片已多，可引入共享 test 切片配置收敛 |
| TD-7 | G4 in-flight 重复命中返回 409 `IDEMPOTENCY_IN_FLIGHT`（L2 允许"实现选定"） | 后续可细化为 200+进行中语义 |
| TD-8 | 流式创建幂等短路 | ✅ **P1 已修（口径 A）**：流式正常消费完（writeSse 正常返回）调 `complete()`，result=**首 SSE 帧**（task 接受/结果面；流空则稳定摘要 `{"jsonrpc":"2.0","result":{"status":"completed"}}`）；同键重试 REPLAY 该 result 为 200 JSON、**不二次开流**（测 `streamingCreateReplaysFirstFrameWithoutSecondStream`）。流进行中同键仍 IN_FLIGHT 409；失败仍走 P0-1 abort（不 complete 失败结果）；同步路径不变 |
| TD-9 | 创建失败未释放 IN_FLIGHT（功能正确性，挡重试） | ✅ **P0-1 已修**：`IdempotencyRule.abort()` + controller 失败路径调用；3 测覆盖（abort 单测 ×2 + facade 重试测） |

## 7. 730 交付边界确认

**已交付（IN-1~IN-8）**：入口治理 G1~G5；按逻辑目标选路（含默认 Agent）；同步直连（含创建幂等 `complete()`→REPLAY 短路）；流式 SSE 桥接；选路失败明确失败；端侧工具续跑透传（粘滞）；continueInput 关联转发；统一 A2A 入口。出站注入权威 `params.metadata.tenantId`。

**拓扑清洗口径（730，option B，已与审核对齐）**：Gateway **不向响应/错误体添加** routeHandle/endpoint；Gateway 自控错误体（`GatewayError` + `FORWARD_FAILED`）已去拓扑（`FORWARD_FAILED` 不再带 endpointUrl，有测）；成功响应透传 runtime body，依赖 runtime（FEAT-001）不回物理拓扑（gateway 不做正文 strip，避免与 runtime 契约重复建）。已加"成功响应不含 routeHandle/endpoint"断言。

**未交付（IN-9/IN-10 + S6~S9，按 L2）**：GetTask / CancelTask / SubscribeToTask 转发、UNKNOWN 同键恢复。method 白名单不含它们 → `VALIDATION_METHOD`(400) 拒绝，**不假成功**（SC-7 已验证）。

**已知简化**：凭据/租户用单测试绑定（TD-3）；幂等+粘滞单机内存（TD-4）；RDC/runtime 联调地址/凭据形态待联调定（测试全用桩）。

## 8. 待联调（不在本仓单测范围）

- RDC 真实 HTTP（base-url、`X-Caller-Ref`/`traceparent`）— `gateway.rdc.base-url` 配置项。
- runtime `/a2a` 真实地址 + Gateway→runtime 凭据形态（L2 §4.10 不强制；联调定）。
- version-scope 与 L2 的已知漂移（GetTask 等列为 MUST 但 L2 730 缓做）— 按 L2，已记。

## 9. 下一步

- 审核已通过（必修项 #1/#2 已落地，复审 73/73 GREEN）。
- push + 开 MR（gitcode）。
- 联调期补真实 RDC/runtime e2e（见 §8，不在本仓单测范围）；TD-8（流式幂等短路）已修（口径 A）。剩余非阻塞：TD-1/3/4/5/6/7 按需处理。
