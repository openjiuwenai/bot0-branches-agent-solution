# Python workflow_node → Java 1:1 复刻清单

目标：在 `agent-core-ext-studio-dsl` 现有 Handler 上补齐  
`jiuwen/extension/workflow_node` + `agent_runtime/extension/workflow_node` 的节点行为；能力写进节点实现本身（无 ServiceLoader 覆盖）。

**规模**：两目录合计约 **1.9 万行** Python。目标是行为 1:1 + `workflow_node` 用例全覆盖。

**当前完成度（2026-08-26）**：

- **用例**：55 个 Python 文件均在 `WORKFLOW-NODE-CASES.md` 建档；**55 done**（4 个原真网源仅 mock，真网联调 OUT）。
- **实现**：内置 24 节点已注册；StreamTransform/Loop/Condition/Message/Aggregate/End（含 mix + Iterator 生成器）/Exception/Nested/… 已按 Python 主行为加深，但真沙箱、OBS/Kerberos、真 LLM/SSE、完整图 `WorkflowWrapper.astream` 等仍有缺口——**不是逐行无差异的 1:1 完工**。
- **测试**：模块 `mvn test` → **287 通过 / 16 跳过 / 0 失败**。

## 已锁定决策（2026-08-25）

- **Redis**：按 Python workflow（`get_redis_client` + 键 `global.vals.{wf}.{conv}` + TTL）对齐；Java 侧用 **Jedis 5.1.5**（`ConversationValsStore` / `JedisConversationValsStore`，无配置时 in-memory）。
- **EI.***：要注册（后续 P6）。
- **冲刺顺序**：P1 Start → **P2 Code** → P3…

| 期 | 内容 | 预估量级 |
| --- | --- | --- |
| P0 | 盘点 + 契约（本文件） | — |
| P1 | `jiuwen.start` 全量（校验/默认值/memory/会话变量/Redis/systemFields） | **严格 1:1**（`flowstart.FlowStartEngine` + `store/*`） |
| P2 | `jiuwen.code` + code_runner（local / inprocess / sandbox 插槽） | **已落地主路径**（`PythonCodeRunners` / `InprocessPythonCodeExecutor` / sandbox 插槽+fallback / schema coerce / `CODE_BLACK_LIST`） |
| P3 | `jiuwen.questioner`（**agent_runtime `questioner.py` DirectReply 全量**）+ rails | **完成**（确认流；rails 约束追问+prompt；ModelContext 读写；关键词/退出确认文案；`llm_based` 追问；`llm_info` trace；literal_eval 兜底；时间/中文相对日） |
| P4 | `jiuwen.knowledgeRetrieval` + kb_adapter | **完成 1:1**（`KnowledgeRetrievalEngine`；OBS；Redis；Kerberos；Handler 不经 core bridge） |
| P5 | 其余已有 Handler 行为对齐（message/card/end/input/setVariable/…） | **加深 + 用例迁测**（见 `WORKFLOW-NODE-CASES.md`；`WorkflowNodeCasesTest`） |
| P6 | **目录内但非 FEAT-031 的 EI.***：`EI.qa` / `EI.ParamOutput` / `EI.ComplexIntentDetection` | **三者均完成 1:1** |

## 节点对照（是否已有 Java 类型 ≠ 行为是否 1:1）

| Python 来源 | IR | Java 类型 | 行为 1:1 |
| --- | --- | --- | --- |
| jiuwen start | `jiuwen.start` | 有 | **严格 1:1**（`flowstart.FlowStartEngine` ← `start.py`；Redis 冷启动/`_request`/io_state；host：`userFields` unwrap、`buildWorkflowId`、`ConversationValsStore`） |
| jiuwen end | `jiuwen.end` | 有 | **严格 1:1**（`flowend.FlowEndEngine` ← `end.py`；Iterator≈AsyncGenerator；`__terminal__`/session 幂等为 Studio host） |
| jiuwen message/card/input/setVariable/aggregate/exception/subWorkflow | 对应 jiuwen.* | 有 | Message/Card/Input/Exception/SetVariable/Aggregate/SubWorkflow：**严格 1:1**（Engine 包） |
| agent_runtime questioner | `jiuwen.questioner` | 有 | **1:1 源**（LLM/reflection/Redis Trace/时间兜底/ModelContext；无模型时 mock/JSON/启发式） |
| jiuwen questioner | 同上 | 有 | 以 agent_runtime 为准（jiuwen 精简版不单独对齐） |
| agent_runtime flow_code + runners | `jiuwen.code` | 有 | **主路径已对齐**（exec_env / localExecMode / sandbox 插槽+fallback / schema / blacklist；JVM 无 CPython inprocess，用轻量子进程） |
| agent_runtime knowledge + kb | `jiuwen.knowledgeRetrieval` | 有 | **完成 1:1**（`KnowledgeRetrievalEngine` = FlowKnowledgeRetrieval） |
| jiuwen flow_qa | `EI.qa` | **有** | **完成 1:1**（`FlowQaEngine`：策略/占位符/needReply/struct schema） |
| ParamOutput | `EI.ParamOutput` | **有** | **完成 1:1**（缺 userFields→{}；非 Map 原值） |
| complex_intent | `EI.ComplexIntentDetection` | **有** | **完成 1:1**（IntentDetection + SubWorkflow + groups；`installTestBridge`） |
| branch/loop | core | 有 | **loop 已接 core `LoopGroup`+`LoopComponent`（同 Python test_loop_component）** |

## 硬依赖（1:1 必须引入或模拟）

- **Redis**（Start 会话变量）：当前 studio-dsl POM **无** Redis 客户端  
- **KB 后端**（lakesearch / ragflow / koosearch…）  
- **Sandbox 执行环境**（FEAT 原 OUT，1:1 需可替换执行器或真实沙箱）  
- **中断 / INPUT_REQUIRED**（Questioner，常与 FEAT-008 交错）

## 约定（本次需求）

- 不做「覆盖换 Handler」；缺口直接改 `adapter.*` / 新增节点实现。**已移除 ServiceLoader / replace。**
- 新增节点类型时同步改 `BuiltinNodeBootstrap` 与测试。  
- 每完成一个节点：对照 Python 单测/行为补 Java 单测。
