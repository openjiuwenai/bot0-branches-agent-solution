# Python workflow_node ↔ Java studio-dsl 覆盖对照

本文对照 Studio runtime 中两处工作流节点扩展目录，与当前 Java 模块 `agent-core-ext-studio-dsl`（FEAT-031）的实现覆盖情况。

**对照基准日期**：按仓库当前代码整理。  
**Java 侧**：`com.openjiuwen.studio.dsl`（`BuiltinNodeBootstrap` 注册的 **24** 种内置节点 = FEAT-031 的 21 种 + P6 三个 `EI.*`）。  
**Python 侧**：

- `agent-studio/0812/agent-studio/agent-runtime/jiuwen/extension/workflow_node`
- `agent-studio/0812/agent-studio/agent-runtime/agent_runtime/extension/workflow_node`

派发参考：`jiuwen/serve/controllers/execution/ir_converter.py` 的 `_create_component`。

---

## 结论摘要

| 范围 | 结论 |
| --- | --- |
| FEAT-031 的 21 种 `jiuwen.*` | Java **均有对应 Handler / 类型注册** |
| 目录文件 = 行为 1:1 对齐 | **否**（Start / Questioner / Code / KB 等深度不等） |
| `EI.qa` / `EI.ParamOutput` / `EI.ComplexIntentDetection` | Java **有**（P6 已注册主路径） |
| `rails`、多实现 `code_runner`、各 KB adapter | **非 FEAT-031 节点清单项**；Java 未 1:1 搬迁 |

一句话：**`jiuwen.*` 工作流节点类型在 Java 里基本齐；两目录中的 EI 节点与大量运行时配套能力没有都进当前 Java 代码。**

---

## 1. `jiuwen/extension/workflow_node` → Java

| Python 文件 | IR / 角色 | Java（studio-dsl） | 备注 |
| --- | --- | --- | --- |
| `start.py` | `jiuwen.start` | 有 `StartNodeHandler` + `flowstart.FlowStartEngine` | **严格 1:1** |
| `end.py` | `jiuwen.end` | 有 `EndNodeHandler` + `flowend.FlowEndEngine` | **严格 1:1** |
| `flow_message.py` | `jiuwen.message` | `MessageNodeHandler` + `FlowMessageEngine` | **严格 1:1 完成** |
| `flow_aggregate.py` | `jiuwen.aggregate` | `AggregateNodeHandler` + `FlowAggregateEngine` | **严格 1:1 完成** |
| `flow_exception.py` | `jiuwen.exception` | `ExceptionNodeHandler` + `FlowExceptionEngine` | **严格 1:1 完成** |
| `llm_chain.py` | `jiuwen.LLMComponent` 及 llm 别名 | 有 `LlmNodeHandler` | |
| `questioner.py` | `jiuwen.questioner` | 有 `QuestionerNodeHandler` | **1:1（agent_runtime）**：rails / 中断 / LLM+reflection / Redis Trace / 时间兜底 / ModelContext |
| `flow_code.py` | `jiuwen.code` | 有 `CodeNodeHandler` | |
| `intent_detection.py` | `jiuwen.intentDetection` | 有 `IntentDetectionNodeHandler` | |
| `flow_input.py` | `jiuwen.input` / `flowInput` | `InputNodeHandler` + `FlowInputEngine` | **严格 1:1 完成** |
| `flow_card.py` | `jiuwen.card` / `flowCard` | `CardNodeHandler` + `FlowCardEngine` | **严格 1:1 完成** |
| `flow_extractor.py` | `jiuwen.extractor` / `infoExtraction` | 有 `ExtractorNodeHandler` | |
| `flow_api.py` | `jiuwen.plugin` / `api` / `flowApi` | 有 `PluginNodeHandler` | |
| `flow_mcp.py` | `jiuwen.mcp` / `flowMcp` | 有 `McpNodeHandler` | |
| `flow_agent.py` | `jiuwen.agent` / `flowAgent` | 有 `AgentNodeHandler` | 远程 A2A 语义属 FEAT-004 |
| `sub_workflow.py` | `jiuwen.subWorkflow` / `workflowComposite` | `NestedWorkflowNodeHandler` + `FlowSubWorkflowEngine` | **严格 1:1 完成** |
| `loop_set_variable.py` | `jiuwen.setVariable` | `SetVariableNodeHandler` + `FlowSetVariableEngine` | **严格 1:1 完成** |
| `flow_stream_transform.py` | `jiuwen.streamTransform` | 有 `StreamTransformNodeHandler` | |
| `flow_qa.py` | **`EI.qa`** | 有 `QaNodeHandler` → `FlowQaEngine` | **完成 1:1** |
| `ParamOutput.py` | **`EI.ParamOutput`** | 有 `ParamOutputNodeHandler` | **完成 1:1** |
| `complex_intent_detection.py` | **`EI.ComplexIntentDetection`** | 有 `ComplexIntentDetectionNodeHandler` → Engine | **完成 1:1** |
| `utils.py` | 工具 | 非节点 | — |

### 不在本目录、但属于 FEAT 21 种的类型

| IR | Python 侧常见来源 | Java |
| --- | --- | --- |
| `jiuwen.branch` | `openjiuwen` core（如 `BranchComponent`） | 有 `BranchNodeHandler` |
| `jiuwen.loop` | core（如 `LoopGroup`） | 有 `LoopNodeHandler` |
| `jiuwen.knowledgeRetrieval` | 见下节 `agent_runtime` | 有 `KnowledgeRetrievalNodeHandler` |

---

## 2. `agent_runtime/extension/workflow_node` → Java

| Python | 角色 | Java | 备注 |
| --- | --- | --- | --- |
| `flow_code.py` + `code_runner/*` | 代码执行（inprocess / local / sandbox 等） | 有 `jiuwen.code` | 默认 **subprocess**（`SubprocessPythonCodeExecutor`）；无完整三套 runner |
| `flow_knowledge_retrieval.py` + `kb_adapter/*` | `jiuwen.knowledgeRetrieval` | 有类型 → Engine | **完成 1:1** |
| `questioner.py` | `jiuwen.questioner` | 有类型 | **1:1（agent_runtime）**：rails / 中断 / LLM+reflection / Redis Trace / 时间兜底 / ModelContext |
| `complex_intent_detection.py` | **`EI.ComplexIntentDetection`** | 有 `ComplexIntentDetectionNodeHandler` → Engine | **完成 1:1** |
| `ParamOutput.py` | **`EI.ParamOutput`** | 有 `ParamOutputNodeHandler` | **完成 1:1** |
| `rails/*` | 校验 / 格式化（供 questioner 等） | 有 `rails/*` | P3 已落地 |

---

## 3. FEAT-031 二十一节点 ↔ Java 注册一览

| 组 | IR（canonical） | Java Handler |
| --- | --- | --- |
| 控制 | `jiuwen.start` | `StartNodeHandler` |
| 控制 | `jiuwen.end` | `EndNodeHandler` |
| 控制 | `jiuwen.branch` | `BranchNodeHandler` |
| 控制 | `jiuwen.loop` | `LoopNodeHandler` |
| 控制 | `jiuwen.aggregate` | `AggregateNodeHandler` → `flowaggregate.FlowAggregateEngine` |
| 控制 | `jiuwen.subWorkflow`（别名含 `workflowComposite`） | `NestedWorkflowNodeHandler` → `flowsubworkflow.FlowSubWorkflowEngine` |
| 控制 | `jiuwen.setVariable` | `SetVariableNodeHandler` + `flowsetvariable.FlowSetVariableEngine` |
| 控制 | `jiuwen.exception` | `ExceptionNodeHandler` + `flowexception.FlowExceptionEngine` |
| 模型 | `jiuwen.LLMComponent` | `LlmNodeHandler` |
| 模型 | `jiuwen.intentDetection` | `IntentDetectionNodeHandler` |
| 模型 | `jiuwen.extractor` | `ExtractorNodeHandler` |
| 模型 | `jiuwen.knowledgeRetrieval` | `KnowledgeRetrievalNodeHandler` |
| 交互 | `jiuwen.input` | `InputNodeHandler` + `flowinput.FlowInputEngine` |
| 交互 | `jiuwen.message` | `MessageNodeHandler` + `flowmessage.FlowMessageEngine` |
| 交互 | `jiuwen.card` | `CardNodeHandler` + `flowcard.FlowCardEngine` |
| 交互 | `jiuwen.questioner` | `QuestionerNodeHandler` |
| 外部 | `jiuwen.code` | `CodeNodeHandler` |
| 外部 | `jiuwen.plugin` | `PluginNodeHandler` |
| 外部 | `jiuwen.mcp` | `McpNodeHandler` |
| 外部 | `jiuwen.agent` | `AgentNodeHandler` |
| 外部 | `jiuwen.streamTransform` | `StreamTransformNodeHandler` |

注册入口：`com.openjiuwen.studio.dsl.registry.BuiltinNodeBootstrap`。

---

## 4. Python 深度能力仍有延后项（类型已覆盖）

| IR / 能力 | 所在 Python 侧 | 说明 |
| --- | --- | --- |
| `EI.qa` | `jiuwen/.../flow_qa.py` | **完成 1:1**（`flowqa.FlowQaEngine`） |
| `EI.ParamOutput` | `agent_runtime/.../ParamOutput.py` | **完成 1:1** |
| `EI.ComplexIntentDetection` | `agent_runtime/.../complex_intent_detection.py` | **完成 1:1**（`complexintent.ComplexIntentDetectionEngine`） |
| sandbox code runner | `agent_runtime/.../code_runner/` | 插槽+fallback；真实沙箱环境依赖外部 |
| KB OBS / Kerberos | `agent_runtime/.../kb_adapter/` | inline `kbConfig` 主路径已落地 |
| questioner Redis Trace + LLM 抽字段 + 时间兜底 | `agent_runtime/.../questioner.py` + rails | rails 六类 + TraceStore + LlmExtractor + ModelContext history 已落地 |

---

## 5. 「有类型」≠「行为对齐」

下列类型 Java **已注册**，但相对 Python 扩展实现常见差距（非完整清单）：

| 节点 | 典型差距 |
| --- | --- |
| `jiuwen.start` | Python：会话变量、Redis、`MEMORY_VARIABLE`、对话历史等；Java：core Start + Studio `userFields`/`systemFields` 形态 |
| `jiuwen.questioner` | Python：rails、中断 / `INPUT_REQUIRED`、LLM+reflection、Redis Trace；Java：`questioner/*` 对齐 |
| `jiuwen.code` | Python：多 runner（inprocess/subprocess）；无 Java CodeLogic 路径 |
| `jiuwen.knowledgeRetrieval` | Python：`flow_knowledge_retrieval`；Java：`KnowledgeRetrievalEngine` 1:1 |

---

## 6. 相关文档

- 模块总览：[`README.md`](./README.md)
- Package 说明与 FEAT 对照：[`PACKAGES.md`](./PACKAGES.md)
- 特性需求：仓库内 `0819doc/docs/develop/02-features/FEAT-031-studio-dsl-node-type-extension.md`
- L2：`0819doc/docs/develop/03-architecture/L2-Low-Level-Design/agent-core-ext/FEAT-Func-031-studio-dsl-node-type-extension.md`
