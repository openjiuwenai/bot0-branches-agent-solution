# Workflow 完成度矩阵（Java studio-dsl ↔ Python workflow_node）

**基准日期**：2026-08-26  
**对照源**：

| 侧 | 路径 |
| --- | --- |
| Java builtins | `BuiltinNodeBootstrap` + `adapter/{control,model,interact,external}/*Handler` |
| Python jiuwen | `agent-studio/.../jiuwen/extension/workflow_node/` |
| Python agent_runtime | `agent-studio/.../agent_runtime/extension/workflow_node/` |
| FEAT-031 21 节点 | `低码转高码需求/0817doc/.../FEAT-031-studio-dsl-node-type-extension.md` §5.1 |
| 用例驱动面 | `jiuwen/test/cases/workflow_node/`（55 文件，见 `WORKFLOW-NODE-CASES.md`） |

**状态约定（A 表）**：

| 状态 | 含义 |
| --- | --- |
| **present** | 已注册且可 invoke；主路径可用，但相对 Python 仍缺大块语义 |
| **thin** | 契约委派 / 透传 / catalog 启发式；几乎无独立业务深度 |
| **deepened** | 有专用 Engine/工具/适配器，主路径行为已按 Python 加深，并有对应 Cases |
| **missing** | 无 Handler / 未注册 |

**注册规模**：`BuiltinNodeBootstrap` = FEAT-031 **21** + P6 **3× EI.*** = **24** 类型。无 **missing** 节点类型。

---

## A) Workflow 节点 → Java handler 状态

| # | IR（canonical + 别名） | Python 来源 | Java Handler | FEAT-031 | 状态 | 依据（简） |
| ---: | --- | --- | --- | :---: | --- | --- |
| 1 | `jiuwen.start` | `jiuwen/.../start.py` | `StartNodeHandler` + `flowstart.FlowStartEngine` + `FlowStartAssignmentSupport` + `store/*` | ✓ | **完成（严格 1:1）** | 校验/默认/memory；Redis 冷启动不写默认；`_request`/`io_state`；bool=`bool()`；id 解析对齐 Python；`userFields` unwrap/`buildWorkflowId`/ValsStore=Studio host |
| 2 | `jiuwen.end` | `jiuwen/.../end.py` | `EndNodeHandler` + `flowend.FlowEndEngine` + `FlowEndMixCoordinator` + `FlowEndGeneratorSupport` | ✓ | **完成（严格 1:1）** | invoke 扁平 metadata；`#end_`/类型转换（跳过 Iterator、bool=`bool()`）；query 注入；struct；幂等；stream/collect/transform；mix；template split 空 marker；Iterator≈AsyncGenerator；`__terminal__`/`end_invoke_executed`=Studio host |
| 3 | `jiuwen.branch` | core（非 workflow_node 目录） | `BranchNodeHandler` | ✓ | **deepened** | 多条件 AND/OR + `ConditionEvaluator`；有 Branch Cases |
| 4 | `jiuwen.loop` | core `LoopComponent`+`LoopGroup` | `LoopNodeHandler` + `StudioLoopGroupAssembler` | ✓ | **deepened** | 与 Python 同路径：组装 LoopGroup（start/end/connections 或线性自动串）→ `LoopComponent`；breakCondition→`LoopBreak`；`_request.` 写 global；index/item 经 inputs_schema |
| 5 | `jiuwen.aggregate` / `aggregation` / `flowAggregate` | `flow_aggregate.py` | `AggregateNodeHandler` + `FlowAggregateEngine` | ✓ | **完成（严格 1:1）** | first-non-null / groups map\|list / 类型校验 / collect 拼流；空 groups→空 userFields |
| 6 | `jiuwen.subWorkflow` / `workflowComposite` | `sub_workflow.py` | `NestedWorkflowNodeHandler` + `FlowSubWorkflowEngine` + `StudioSubWorkflowAssembler` + `SubRequestScope` | ✓ | **完成（严格 1:1）** | Engine：`prepareChildInputs`/`REQUEST`/`parse`/`interrupt`树扫/`packageSuccess`；Handler 装配+Pregel/线性；软 hang 为 host 适配（Python `session.interact`） |
| 7 | `jiuwen.setVariable` | `loop_set_variable.py` | `SetVariableNodeHandler` + `FlowSetVariableEngine` | ✓ | **完成（严格 1:1）** | invoke 返回空（Python `None`）；副作用写 scope/global/Redis；线性链/Loop 从 scope 合并 |
| 8 | `jiuwen.exception` | `flow_exception.py`（`exception_handler.py` 为横切工具，非节点） | `ExceptionNodeHandler` + `FlowExceptionEngine` | ✓ | **完成（严格 1:1）** | `__abort__` 互斥 + `workflow_exception` + `WorkflowAbortException`；无 soft/defaultOutputs |
| 9 | `jiuwen.LLMComponent` / `llm` / `llm_chain` / `llmChain` | `llm_chain.py` | `LlmNodeHandler` + `LlmChainEngine` | ✓ | **完成** | 1:1 thinking/JSON/流式/memory/vision |
| 10 | `jiuwen.intentDetection` | `intent_detection.py` | `IntentDetectionNodeHandler` + `IntentDetectionEngine` + `IntentFaqMatcher` | ✓ | **完成** | 1:1 FAQ/LLM/memory/doc_line/状态/BranchRouter |
| 11 | `jiuwen.extractor` / `infoExtraction` | `flow_extractor.py` | `ExtractorNodeHandler` + `ExtractorEngine` | ✓ | **完成** | 严格 1:1 extension extractor（LLM-only + check_config + as_dict） |
| 12 | `jiuwen.knowledgeRetrieval` | `agent_runtime/.../flow_knowledge_retrieval.py` + `kb_adapter/*` | `KnowledgeRetrievalNodeHandler` + `KnowledgeRetrievalEngine` + `kb/*` | ✓ | **完成** | 严格 1:1：Handler 只走 Engine（非 core bridge）；OBS/FAQ/CLOSE/CUSTOM/多KB/Redis；真网集成测延后 |
| 13 | `jiuwen.input` / `flowInput` | `flow_input.py` | `InputNodeHandler` + `flowinput.FlowInputEngine` | ✓ | **完成（严格 1:1）** | START：`interact` 后 `return {}`（中断由 interact/session 状态驱动） |
| 14 | `jiuwen.message` | `flow_message.py` | `MessageNodeHandler` + `FlowMessageEngine` | ✓ | **完成（严格 1:1）** | invoke 仅 `{result}`；stream `MESSAGE_NODE_STREAM`+`message_end`；collect/transform；BJ `time_stamp`；stream 不写 `message_outputs` |
| 15 | `jiuwen.card` / `flowCard` | `flow_card.py` | `CardNodeHandler` + `FlowCardEngine` | ✓ | **完成（严格 1:1）** | `FlowCardEngine`：template/struct/collect/transform/stream 分块 + BJ `time_stamp` |
| 16 | `jiuwen.questioner` | **`agent_runtime`** `questioner.py`（1:1 源） | `QuestionerNodeHandler` → `questioner/*` + `rails/*` | ✓ | **完成** | DirectReply 全量：确认流/rails 追问+prompt/ModelContext 读写/关键词/时间兜底/llm_info trace |
| 17 | `jiuwen.code` | `agent_runtime/flow_code.py` + `code_runner/*` | `flowcode.FlowCodeEngine` + thin Handler | ✓ | **完成** | schema/blacklist/runners/sandbox fallback/stream/trace（仅 Python） |
| 18 | `jiuwen.plugin` / `api` / `flowApi` | `flow_api.py` | `flowapi.FlowApiEngine` + thin Handler | ✓ | **完成** | invoke/stream/format/interact/auth；mock+ToolRegistry+IR HTTP SSE（`RestfulApiSseClient`） |
| 19 | `jiuwen.mcp` / `flowMcp` | `flow_mcp.py` | `McpNodeHandler` + `FlowMcpEngine` | ✓ | **完成** | 严格 1:1：core SSE/StreamableHttp + runtime_auth + 新旧 IR；stub via `installTestClient` |
| 20 | `jiuwen.agent` / `flowAgent` | `flow_agent.py` | `AgentNodeHandler` + `FlowAgentEngine` | ✓ | **完成** | 严格 1:1：core `ReActAgent` + plugins 工具装载；stub via `installTestBridge` |
| 21 | `jiuwen.streamTransform` | `flow_stream_transform.py` | `flowstreamtransform.FlowStreamTransformEngine` + thin Handler | ✓ | **完成** | invoke/collect/transform + resolve_stream_inputs；async→Iterable；错误码 101170/171/172 |
| 22 | `EI.qa`（别名 `ei.qa` / `jiuwen.flowQa`） | `flow_qa.py` | `QaNodeHandler` + `FlowQaEngine` | ✓ | **完成** | 严格 1:1：策略/占位符/needReply/struct schema 归一化/stream；`FlowQaParityTest` |
| 23 | `EI.ParamOutput` | `agent_runtime/ParamOutput.py` | `ParamOutputNodeHandler` | ✓ | **完成** | 严格 1:1：缺 userFields→{}；非 Map→原值；+systemFields |
| 24 | `EI.ComplexIntentDetection` | `complex_intent_detection.py` | `ComplexIntentDetectionNodeHandler` + `ComplexIntentDetectionEngine` | ✓ | **完成** | 严格 1:1：IntentDetection 组合 + 分支 SubWorkflow + groups 聚合；`ComplexIntentDetectionParityTest` |

**非节点文件（目录内、不进 A 表状态）**：`utils.py`、`exception_handler.py`、`rails/*`、`code_runner/*`、`kb_adapter/*` → 见 B/C。

---

## B) Java studio-dsl 相对「用例驱动面 / FEAT-031 21」的 EXTRA

「extra」= 模块内代码/能力 **并非** 仅由 `jiuwen/test/cases/workflow_node` 55 用例驱动，或 **不在** FEAT-031 21 节点清单内。

| 项 | 位置 | 为何算 EXTRA | 备注 |
| --- | --- | --- | --- |
| `EI.qa` | `QaNodeHandler` + `FlowQaEngine` | 不在 FEAT-031 21；`FlowQaParityTest` + `WorkflowNodeInteractControlEiParityTest` | **完成** |
| `EI.ParamOutput` | `ParamOutputNodeHandler` | 同上；无对应 workflow_node 用例文件 | P6 透传 |
| `EI.ComplexIntentDetection` | `ComplexIntentDetectionNodeHandler` + Engine | `ComplexIntentDetectionParityTest` | **完成** |
| `rails/*` | `com.openjiuwen.studio.dsl.rails` | FEAT-031 未单列；来自 `agent_runtime/.../rails` | 服务 questioner；六类 action 已落地 |
| `store/*`（含 Jedis） | `ConversationValsStore` / `JedisConversationValsStore` | FEAT 未要求 Redis 客户端；1:1 Start 对齐引入 | 无 Redis 时 in-memory |
| `contract/*` | `ToolRegistry` / `SubWorkflowResolver` / `PythonCodeExecutor` / KB providers / … | 宿主注入契约（Agent 走 `FlowAgentEngine`） |
| `python/*` 沙箱插槽 | `SandboxPythonCodeExecutor` 等 | FEAT-031 OUT 沙箱；用例多用 mock/subprocess | 真实沙箱依赖外部 |
| `config` | `StudioDslNodeProperties` 等 | 编程式配置，非 workflow_node 测试面 | — |
| `schema` / `bridge` / `exec` 装配桥 | DSL 壳与线性执行 | 承接装配，超出单测用例目录本身 | 线性链支撑 Cases |
| 文档副产物 | `PYTHON-1TO1-PORT.md` / `WORKFLOW-NODE-CASES.md` / 本文件 | 工程资产 | — |

**不算 EXTRA（虽包多，但由 FEAT-031 / 用例直接驱动）**：`kb/*`（knowledgeRetrieval）、`questioner/*`（questioner）、多数 `adapter/*` Handler、`util/ConditionEvaluator` / `DictStreamTransformer` 等。

---

## C) Python 能力在 Java 仍明显 missing / thin 的诚实缺口

| 缺口 | Python 锚点 | Java 现状 | 严重度 |
| --- | --- | --- | --- |
| LLM 链全量（thinking / JSON·markdown / 流式帧 / 图文） | `llm_chain.py` ~1.2k LOC | `LlmNodeHandler` thin + `@Disabled` RealLlm | **高** |
| MCP 真传输（SSE / Streamable HTTP / 参数位变换） | `flow_mcp.py` | `FlowMcpEngine` + core `SseClient`/`StreamableHttpClient`；测试 stub 客户端 | **中**（真网集成测仍 deferred） |
| Agent ReAct + 工具装载 / 多工具图 | `flow_agent.py` | `FlowAgentEngine` + core `ReActAgent`；测试 stub | **中**（真 LLM/多工具图仍 deferred） |
| Extractor LLM 主路径 | `flow_extractor.py` | `ExtractorEngine` 严格 1:1 | **完成** |
| IntentDetection FAQ + LLM | `intent_detection.py` | FAQ+LLM 主路径已落地 | **低**（缺 memory/profile、图 BranchRouter） |
| ComplexIntent：LLM 分类 + 嵌套 SubWorkflow 执行 | `complex_intent_detection.py` | Engine 已组合 IntentDetection + SubWorkflowResolver；真 IR 存储加载仍 deferred | **低** |
| SubWorkflow pydantic 配置严校验 | `SubWorkflowConfig` | 装配/解析已齐；pydantic 级 schema 校验仍可加 | **低** |
| Code：真 CPython inprocess + 真沙箱隔离 | `code_runner/*` | JVM 子进程近似；sandbox 插槽 | **中** |
| KB：OBS Provider / Kerberos / Redis 图片缓存 | `kb_adapter/*` + `kb_config_providers.py` | `ObsKnowledgeBaseConfigProvider` + `KnowledgeRetrievalCacheStore` + `KerberosAuth` | **已齐** |
| QA struct schema 全量归一化 | `flow_qa.py` | `StructInputSchemas` 已对齐 | **低**（边缘 DSL 形态） |
| 用例层：多实例 Questioner、真 ReAct 图 | `WORKFLOW-NODE-CASES.md` deferred | 骨架 / 降维线性链 | **中**（编排面） |
| `exception_handler` 横切错误分支挂载 | `exception_handler.py` | 无对等横切包装器 | **低–中** |

---

## WorkflowNode*CasesTest 一览（约方法数）

计数 = `@Test` / `@ParameterizedTest` 方法（含 `@Disabled`）。`ControllerSuite` javadoc 中的 `{@code @Test}` 不计。

| 类 | ≈ 方法数 | 备注 |
| --- | ---: | --- |
| `WorkflowNodeCasesTest` | 25 | Aggregate / Message / InputUtils 等 Nested |
| `WorkflowNodeStreamTransformCasesTest` | 29 | 最厚单节点面 |
| `WorkflowNodeLlmMockCasesTest` | 5 | LLM / intent / card / plugin 多参 mock（原真网 Python 源 #33/#41/#50/#51） |
| `WorkflowNodeControllerSuiteCasesTest` | 17 | D-tier 降维线性链（#2–8, #11–20） |
| `WorkflowNodeQuestionerInterruptCasesTest` | 13 | |
| `WorkflowNodeBranchMultiConditionCasesTest` | 10 | |
| `WorkflowNodeSubWorkflowCasesTest` | 14 | stream + REQUEST sync |
| `WorkflowNodeMcpCasesTest` | 8 | |
| `WorkflowNodeAgentCasesTest` | 7 | |
| `WorkflowNodeExtractorCasesTest` | 7 | |
| `WorkflowNodeInputWorkflowCasesTest` | 7 | |
| `WorkflowNodeExceptionCasesTest` | 6 | |
| `WorkflowNodePluginApiCasesTest` | 6 | |
| `WorkflowNodePlanExecuteCasesTest` | 6 | |
| `WorkflowNodeLoopCasesTest` | 7 | LoopGroup + `_request` + connections |
| `WorkflowNodeStartEndCasesTest` | 5 | |
| `WorkflowNodeEndMixGeneratorCasesTest` | 7 | mix / generator / collect / transform |
| `WorkflowNodeCardCasesTest` | 4 | |
| `WorkflowNodeIntentDetectionCasesTest` | 4 | |
| `WorkflowNodeAggregationCommonCasesTest` | 3 | |
| **合计（19 类）** | **≈ 193** | 启用约 **177**（减 RealLlm Disabled） |

相关非 `*CasesTest` 但覆盖节点的：`WorkflowNodeInteractControlEiParityTest`（含 EI.*）、`CodeNodeParityTest`、`KnowledgeRetrievalParityTest`、`StartNodeParityTest`、`NodeGuardrailsTest` 等——不计入上表。

---

## 一句话结论

**类型面齐（24/24 注册，FEAT-031 无 missing）；深度面不齐——LLM / MCP / Agent / 真模型 Extractor·Intent 仍 thin，编排交互主路径多数 deepened；模块还携带 EI.*、rails、Jedis store、contract SPI 等相对 55 用例 / 21 节点清单的 EXTRA。**

交叉引用：`PYTHON-1TO1-PORT.md`、`PYTHON-NODE-COVERAGE.md`、`WORKFLOW-NODE-CASES.md`。
