# Workflow 完成度一览（给验收用）

**更新**：2026-08-26  
**模块测试**：`mvn test`（真 LLM / 真网联调非 FEAT-031 阻塞项）。  
**用例面**：55 个 Python `workflow_node` 文件 → **55 done**（其中 4 个真网源仅 mock 替身，见 `WORKFLOW-NODE-CASES.md`）。

---

## 表 1 — Workflow 节点：完成 / 未完成

「完成」= Java 有 Handler 且主路径已加深（deepened）或行为等价透传。  
「未完成」= thin/present，或缺真 LLM/SSE/图调度等关键能力。

| IR | 完成度 | 说明 |
| --- | --- | --- |
| `jiuwen.start` | **完成（严格 1:1）** | `FlowStartEngine` + `start.py`；Redis 冷启动不污染默认；`_request`/io_state；Studio `userFields` unwrap / `buildWorkflowId` / `ConversationValsStore` = host 适配 |
| `jiuwen.end` | **完成（严格 1:1）** | `FlowEndEngine` + `end.py`；Iterator≈AsyncGenerator；Studio `__terminal__` / session 幂等为 host 适配 |
| `jiuwen.branch` | **完成** | ConditionEvaluator + Cases |
| `jiuwen.loop` | **完成** | IR → core `LoopGroup`+`LoopComponent`（同 Python）；`_request.` / connections / break |
| `jiuwen.aggregate` | **完成（严格 1:1）** | `FlowAggregateEngine` + `flow_aggregate.py` |
| `jiuwen.subWorkflow` | **完成（严格 1:1）** | `FlowSubWorkflowEngine` + `sub_workflow.py`；Handler 装配 Pregel/线性；软 hang = host 适配 |
| `jiuwen.setVariable` | **完成（严格 1:1）** | `FlowSetVariableEngine` + `loop_set_variable.py` |
| `jiuwen.exception` | **完成（严格 1:1）** | `FlowExceptionEngine` + `flow_exception.py` |
| `jiuwen.message` | **完成（严格 1:1）** | `FlowMessageEngine` + `flow_message.py` |
| `jiuwen.input` | **完成（严格 1:1）** | `FlowInputEngine` + `flow_input.py` |
| `jiuwen.card` | **完成（严格 1:1）** | `FlowCardEngine` + `flow_card.py` |
| `jiuwen.questioner` | **完成** | agent_runtime DirectReply 全量 1:1（确认流/rails 约束追问+LLM prompt/ModelContext/关键词/时间/llm_info trace）；`response_type=reply` 不在 agent_runtime 范围 |
| `jiuwen.code` | **完成** | Engine 严格 1:1（agent_runtime flow_code） |
| `jiuwen.plugin` / api | **完成** | Engine 严格 1:1（含 IR HTTP SSE） |
| `jiuwen.streamTransform` | **完成** | Engine 严格 1:1（invoke/collect/transform） |
| `jiuwen.knowledgeRetrieval` | **完成** | 严格 1:1 `flow_knowledge_retrieval.py`：`KnowledgeRetrievalEngine`（OBS/inline、FAQ/CLOSE/CUSTOM、多 KB、归一化缓存）；Handler 只走 Engine |
| `jiuwen.extractor` | **完成** | 严格 1:1 `flow_extractor.py`：lazy init、context 历史、LLM 抽取、`check_config`、`as_dict` 输出 |
| `jiuwen.intentDetection` | **完成** | 严格 1:1 `intent_detection.py`：`IntentDetectionEngine`（FAQ→LLM、global intent、memory/userProfile、`doc_line` KG、`reset`/`load_state`/`get_state`）+ 图内 `BranchRouter`；测试 stub LLM |
| `jiuwen.LLMComponent` | **完成** | 严格 1:1 `llm_chain.py`：`LlmChainEngine`（template/history/format/memory/vision、thinking 三态流式、JSON/text、usage flatten）；去 mockOutput 捷径 |
| `jiuwen.mcp` | **完成** | 严格 1:1 `flow_mcp.py`：`FlowMcpEngine`（IR→`McpServerConfig`、sse/streamable_http 客户端、新旧 arguments、runtime_auth、输出格式化）；测试 stub `installTestClient` |
| `jiuwen.agent` | **完成** | 严格 1:1 `flow_agent.py`：`FlowAgentEngine`（ReActAgent 组合、plugins 工具装载、query 映射、invoke/stream/collect）；测试 stub `installTestBridge` |
| `EI.qa` | **完成** | 严格 1:1 `flow_qa.py`：`FlowQaEngine`（random/index、{{placeholder}}、needReply 中断、struct schema 归一化、stream frames） |
| `EI.ParamOutput` | **完成** | 严格 1:1 `ParamOutput.py`：userFields 透传；缺 key→空 map；非 Map→原值包进 userFields |
| `EI.ComplexIntentDetection` | **完成** | 严格 1:1 `complex_intent_detection.py`：`ComplexIntentDetectionEngine`（IntentDetection 组合、分支 SubWorkflow、groups 聚合）；stub `installTestBridge` |

### 用例维度（55 文件）

| 状态 | 数 | 代表 |
| --- | ---: | --- |
| **done** | 55 | 可启用 JUnit（mock/线性链）；含 4 个原真网 Python 源的 mock 替身 |
| **out** | — | 真 LLM/真 HTTP 联调（`test_llm_chain` 等 4 文件）非 FEAT-031 验收 |

---

## 表 2 — 现有 Java 相对「Workflow 用例面」多余 / 超范围

「多余」= **不是** `jiuwen/test/cases/workflow_node` 55 用例直接驱动，或 **超出** FEAT-031 的 21 种清单（但仍可能来自 Python 扩展目录）。

| 类别 | 内容 | 判定 |
| --- | --- | --- |
| **超 FEAT-031 21** | `EI.qa` / `EI.ParamOutput` / `EI.ComplexIntentDetection` | Python 目录有源；**55 用例几乎无独立测文件**；属 P6 加量 |
| **支撑基建（非节点）** | `rails/*` | 服务 questioner，非独立 Workflow 节点 |
| | `store/*`（Jedis/内存） | Start 会话变量；用例目录不直接测 Redis |
| | `contract/*`（Invoker/Registry/`EmptyToolRegistry`/…） | 宿主注入契约；默认空 ToolRegistry |
| | `config` | 模块/节点配置属性（编程式读取） |
| | `exec` | 装配桥 / 线性执行 / 变量作用域（无 bridge、无 schema 包） |
| **插槽未兑现** | `SandboxPythonCodeExecutor` | FEAT 沙箱 OUT；真实环境未接 |
| **文档** | `PYTHON-*` / `WORKFLOW-*` | 工程资产，非运行时 |

**不算多余（应对 Workflow）**：`adapter/*` 各 Handler、`kb/*`、`questioner/*`、`python/*`（subprocess）、`util/ConditionEvaluator`、`DictStreamTransformer` 等。

---

## 表 3 — 一句话总览

| 维度 | 结论 |
| --- | --- |
| 节点类型覆盖 | 24/24 已注册，**无缺失类型** |
| 行为深度 | 编排/交互多数完成；LLM/MCP/Agent/EI.* 主路径已 1:1（真网/多工具图等仍有延后项） |
| 用例迁移 | **55/55 done**（mock）；真网联调 OUT |
| Java 多余 | 主要是 **EI.*（相对 FEAT 21）**、**rails/store/contract/装配层**、沙箱插槽 |

详细矩阵见同目录 `WORKFLOW-COMPLETION-MATRIX.md`。
