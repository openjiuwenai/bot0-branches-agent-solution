# Python workflow_node → Java 1:1 复刻清单

目标：在 `agent-core-ext-studio-dsl` 现有 Handler 上补齐  
`jiuwen/extension/workflow_node` + `agent_runtime/extension/workflow_node` 的节点行为；能力写进节点实现本身（无 ServiceLoader 覆盖）。

**规模**：两目录合计约 **1.9 万行** Python（含 questioner ~2.2k、sub_workflow ~1.5k、end ~1.5k 等）。无法单次会话全部无缺口交付，按下列分期推进。

## 已锁定决策（2026-08-25）

- **Redis**：按 Python workflow（`get_redis_client` + 键 `global.vals.{wf}.{conv}` + TTL）对齐；Java 侧用 **Jedis 5.1.5**（`ConversationValsStore` / `JedisConversationValsStore`，无配置时 in-memory）。
- **EI.***：要注册（后续 P6）。
- **冲刺顺序**：P1 Start → **P2 Code** → P3…

| 期 | 内容 | 预估量级 |
| --- | --- | --- |
| P0 | 盘点 + 契约（本文件） | — |
| P1 | `jiuwen.start` 全量（校验/默认值/memory/会话变量/Redis/systemFields） | **已落地**（`store/*` + `StartNodeHandler`） |
| P2 | `jiuwen.code` + code_runner（local / inprocess / sandbox 插槽） | **已落地主路径**（`PythonCodeRunners` / `InprocessPythonCodeExecutor` / sandbox 插槽+fallback / schema coerce / `CODE_BLACK_LIST`） |
| P3 | `jiuwen.questioner`（两份取并集）+ rails | **已落地主路径**（`rails/*` 六类 action；`QuestionerEngine` 状态机 / questionContent / 字段提取+rails / INPUT_REQUIRED；延后 Redis Trace / reflection / 全量中文时间） |
| P4 | `jiuwen.knowledgeRetrieval` + kb_adapter | **已落地主路径**（`kb/*`：General/LakeSearch/KooSearch/Ragflow + factory；`KnowledgeRetrievalEngine` FAQ/CLOSE/CUSTOM/normalize；inline `kbConfig` 替代 OBS；延后 Kerberos / Redis 图片缓存） |
| P5 | 其余已有 Handler 行为对齐（message/card/end/input/setVariable/…） | **加深 + 用例迁测**（见 `WORKFLOW-NODE-CASES.md`；`WorkflowNodeCasesTest`） |
| P6 | **目录内但非 FEAT-031 的 EI.***：`EI.qa` / `EI.ParamOutput` / `EI.ComplexIntentDetection` | **已注册主路径**（QA 策略+needReply；ParamOutput 透传；ComplexIntent 目录匹配；延后真 LLM/子工作流） |

## 节点对照（是否已有 Java 类型 ≠ 行为是否 1:1）

| Python 来源 | IR | Java 类型 | 行为 1:1 |
| --- | --- | --- | --- |
| jiuwen start | `jiuwen.start` | 有 | **主路径已对齐**（memory/Redis/校验/默认值/systemFields） |
| jiuwen end | `jiuwen.end` | 有 | **主路径加深**（`#end_` / 类型转换 / struct / 幂等 invoke / message_end+workflow_end / 基础 stream；全量 mix/异步生成器延后） |
| jiuwen message/card/input/setVariable/aggregate/exception | 对应 jiuwen.* | 有 | **主路径加深**（Message：template 必填、enable_history、struct 结束帧、message_outputs；Aggregate：mode/groups list/类型校验；Exception：默认 abort+workflow_exception；Input：`FlowInputUtils`） |
| jiuwen questioner | `jiuwen.questioner` | 有 | **主路径已对齐**（见 P3；无 Redis Trace / 真 LLM 抽字段时用 mockExtractedFields / JSON / 单字段启发式） |
| agent_runtime questioner | 同上 | 有 | **同上**（以 agent_runtime 为准） |
| agent_runtime flow_code + runners | `jiuwen.code` | 有 | **主路径已对齐**（exec_env / localExecMode / sandbox 插槽+fallback / schema / blacklist；JVM 无 CPython inprocess，用轻量子进程） |
| agent_runtime knowledge + kb | `jiuwen.knowledgeRetrieval` | 有 | **主路径已对齐**（inline `kbConfig`；OBS Provider / Kerberos / Redis 图缓存延后） |
| jiuwen flow_qa | `EI.qa` | **有** | **主路径已对齐**（options/random|index / needReply；struct schema 全量延后） |
| ParamOutput | `EI.ParamOutput` | **有** | **已对齐**（userFields+systemFields 透传） |
| complex_intent | `EI.ComplexIntentDetection` | **有** | **分支路由主路径**（catalog 匹配；真 LLM IntentDetection + 子工作流延后） |
| branch/loop | core | 有（适配 core） | 以 core+Handler 为准 |

## 硬依赖（1:1 必须引入或模拟）

- **Redis**（Start 会话变量）：当前 studio-dsl POM **无** Redis 客户端  
- **KB 后端**（lakesearch / ragflow / koosearch…）  
- **Sandbox 执行环境**（FEAT 原 OUT，1:1 需可替换执行器或真实沙箱）  
- **中断 / INPUT_REQUIRED**（Questioner，常与 FEAT-008 交错）

## 约定（本次需求）

- 不做「覆盖换 Handler」；缺口直接改 `adapter.*` / 新增节点实现。**已移除 ServiceLoader / replace。**
- 新增节点类型时同步改 `BuiltinNodeBootstrap` 与测试。  
- 每完成一个节点：对照 Python 单测/行为补 Java 单测。
