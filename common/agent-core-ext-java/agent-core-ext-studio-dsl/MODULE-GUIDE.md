# agent-core-ext-studio-dsl 模块结构说明

本文描述 `agent-core-ext-studio-dsl` 的 **Maven 产物**、**Java package 布局**、**各源文件职责** 与 **测试目录**。  
根 package：`com.openjiuwen.studio.dsl`。

> 对齐 FEAT-031（2026-08-26）：21 种 `jiuwen.*` + 3 种 `EI.*` 内置节点；Python 代码节点；**不含**自定义节点 SPI 扩展。

---

## 1. Maven 模块与产物

| 项 | 说明 |
| --- | --- |
| 父模块 | `agent-core-ext-java`（`packaging=pom`，聚合 3 个子模块） |
| 本模块 artifactId | `agent-core-ext-studio-dsl` |
| 版本 | `0.1.0` |
| 打包 | **单个 JAR**（`packaging=jar`，默认） |
| 运行时依赖 | `agent-core-java`、`jedis`（会话变量 Redis） |
| 源码规模（main） | **177** 个 `.java`，**38** 个 leaf package |
| 测试规模 | **57** 个 `.java`（`src/test`） |

**构建：**

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -pl :agent-core-ext-studio-dsl -am clean test
```

**宿主依赖坐标：**

```xml
<dependency>
  <groupId>com.openjiuwen</groupId>
  <artifactId>agent-core-ext-studio-dsl</artifactId>
  <version>0.1.0</version>
</dependency>
```

---

## 2. 整体架构

```
宿主 AssembledNode / AssembledWorkflow
        │
        ▼
StudioDslModule ──► NodeTypeRegistry（24 内置 Handler）
        │                    │
        │                    ▼
        │           adapter/*NodeHandler（薄壳）
        │                    │
        │                    ▼
        │           flow* / kb / questioner …（Engine，对齐 Python）
        │                    │
        ▼                    ▼
NodeBuildContext      ComponentExecutable.invoke
（contract 注入）            │
        │                    ▼
        └──────────► NodePayload → { userFields: … }
```

**固定模式：** Handler 实现 `NodeHandlerFactory`（内部契约）→ `AbstractStudioNode` 统一包装 `userFields` → 委托 `*Engine` 执行业务。

**查找节点实现：** `BuiltinNodeBootstrap` 找 Handler → Handler 的 import 指向 `*Engine`。

---

## 3. 模块根文档（非代码）

| 文件 | 作用 |
| --- | --- |
| `README.md` | 接入说明、24 内置节点表、宿主契约、边界 |
| `MODULE-GUIDE.md` | 本文：模块 / package / 文件全景 |
| `PACKAGES.md` | 包职责与 FEAT 章节对照 |
| `PYTHON-1TO1-PORT.md` | Java ↔ Python 文件映射 |
| `PYTHON-NODE-COVERAGE.md` | Python 节点覆盖矩阵 |
| `WORKFLOW-NODE-CASES.md` | 工作流节点用例索引 |
| `WORKFLOW-COMPLETION-MATRIX.md` | 完成度矩阵 |
| `WORKFLOW-STATUS.md` | 实现状态 |

---

## 4. Java Package 与文件

### 4.1 根包 `com.openjiuwen.studio.dsl`

| 文件 | 作用 |
| --- | --- |
| `StudioDslModule.java` | **编程式入口**：创建内置 `NodeTypeRegistry`、默认 Python 执行器、装配桥；`newRootContext()`、`mapExecutables()` |

---

### 4.2 `config` — 模块配置

| 文件 | 作用 |
| --- | --- |
| `StudioDslNodeProperties.java` | 运行时配置：最大嵌套深度、Python 解释器/超时/工作目录、代码节点本地执行模式等 |

---

### 4.3 `contract` — 宿主可注入契约

| 文件 | 作用 |
| --- | --- |
| `PythonCodeExecutor.java` | Python 代码节点执行接口 |
| `ToolRegistry.java` | Plugin/API 工具查找 |
| `EmptyToolRegistry.java` | 默认空 ToolRegistry |
| `SubWorkflowResolver.java` | 嵌套子工作流 IR → `AssembledWorkflow` |
| `KnowledgeBaseConfigProvider.java` | 从 OBS/环境变量等拉取 KB 配置 |
| `KnowledgeStorageProvider.java` | 读取 KB 配置文件内容 |
| `SecretDecryptor.java` | KB 密钥参数解密 |
| `NodeHandlerFactory.java` | **内部**内置 Handler 工厂契约（非宿主扩展 SPI） |

---

### 4.4 `registry` — 类型注册

| 文件 | 作用 |
| --- | --- |
| `NodeTypeRegistry.java` | canonical + alias → 创建 `ComponentExecutable`；`register()` 仅包内可见，供 bootstrap 使用 |
| `BuiltinNodeBootstrap.java` | 启动时注册 24 个内置 Handler |

---

### 4.5 `model` — 领域数据（无执行逻辑）

| 文件 | 作用 |
| --- | --- |
| `AssembledNode.java` | 宿主传入的已装配节点（id、irType、configs） |
| `AssembledWorkflow.java` | 已装配工作流（节点列表 + 元数据） |
| `NodePayload.java` | 节点输出载荷；`toInvokeMap()` → `{userFields:…}` |
| `NodeCauseCode.java` | 可区分失败码枚举 |
| `MediaPart.java` | 多模态部件（图/音频等） |

---

### 4.6 `exec` — 执行骨架

| 文件 | 作用 |
| --- | --- |
| `NodeBuildContext.java` | 构建上下文：workflowId、嵌套深度、registry、python/tool/subWorkflow 注入、变量作用域 |
| `WorkflowVariableScope.java` | 工作流变量作用域 |
| `WorkflowAssemblyBridge.java` | `AssembledWorkflow` → `Map<id, ComponentExecutable>`；`mergeLinearStep()` 合并子步骤输出 |
| `NodeExecutionException.java` | 带 `NodeCauseCode` 的运行时异常 |

---

### 4.7 `python` — Python 代码执行

| 文件 | 作用 |
| --- | --- |
| `PythonExecRequest.java` | 执行请求（脚本、args、超时） |
| `PythonExecResult.java` | stdout/stderr/exitCode/解析结果 |
| `SubprocessPythonCodeExecutor.java` | **默认**子进程 + 隔离工作目录 |
| `InprocessPythonCodeExecutor.java` | 进程内执行（测试/特殊场景） |
| `SandboxPythonCodeExecutor.java` | 沙箱执行器插槽（测试用） |
| `PythonCodeRunners.java` | subprocess/inprocess/sandbox 策略选择 |

---

### 4.8 `store` — 会话变量存储

| 文件 | 作用 |
| --- | --- |
| `ConversationValsStore.java` | start/setVariable 用的 KV 接口 |
| `ConversationValsStores.java` | 全局 holder；默认内存，可切 Jedis |
| `InMemoryConversationValsStore.java` | 内存实现 |
| `JedisConversationValsStore.java` | Redis 实现（对齐 Python `get_redis_client`） |

---

### 4.9 `util` — 无状态工具

| 文件 | 作用 |
| --- | --- |
| `DeepCopies.java` | Map/List 深拷贝 |
| `ConditionEvaluator.java` | branch/loop 条件表达式求值 |
| `PathResolver.java` | `${a.b.c}` 路径取值 |
| `TemplateRenderer.java` | `{{var}}` 模板渲染 |
| `TypeCoercer.java` | 类型强制转换 |
| `MediaSupport.java` | 多模态注入 LLM/Plugin；`mediaOf()` 解析 `__media__` |
| `SanitizeMessage.java` | 消息内容清洗 |
| `SessionStateIsolator.java` | 嵌套子工作流 session 状态隔离 |
| `FlowCodeSchemaSupport.java` | 代码节点 schema 校验/默认值 |
| `DictStreamTransformConfig.java` | 流式 dict 变换配置 |
| `DictStreamPath.java` | 流式路径解析 |
| `DictStreamTransformer.java` | streamTransform 核心变换逻辑 |

---

### 4.10 `adapter` — 节点 Handler 壳（24 个）

#### 公共

| 文件 | 作用 |
| --- | --- |
| `AbstractStudioNode.java` | 统一 `invoke` → `doInvoke` → `NodePayload`；中断/abort 处理 |
| `StudioStreamFrames.java` | 流式帧常量；`partial_content` / `message_end` / `workflow_end` 写入 |

#### `adapter.control` — 控制流 8 节点

| 文件 | IR 类型 | 委托 Engine / 说明 |
| --- | --- | --- |
| `StartNodeHandler.java` | `jiuwen.start` | `flowstart.FlowStartEngine` |
| `EndNodeHandler.java` | `jiuwen.end` | `flowend.*`（Engine + Mix + Generator） |
| `BranchNodeHandler.java` | `jiuwen.branch` | 组装 AgentCore `BranchComponent` |
| `LoopNodeHandler.java` | `jiuwen.loop` | `StudioLoopGroupAssembler` + 循环体 |
| `AggregateNodeHandler.java` | `jiuwen.aggregate` | `flowaggregate.FlowAggregateEngine` |
| `NestedWorkflowNodeHandler.java` | `jiuwen.subWorkflow` | `flowsubworkflow.FlowSubWorkflowEngine` |
| `SetVariableNodeHandler.java` | `jiuwen.setVariable` | `flowsetvariable.FlowSetVariableEngine` |
| `ExceptionNodeHandler.java` | `jiuwen.exception` | `flowexception.FlowExceptionEngine` |
| `StudioLoopGroupAssembler.java` | — | loop 子图 → AgentCore loop 结构 |
| `StudioSubWorkflowAssembler.java` | — | 子工作流 executable 映射 |
| `SubInteractiveSupport.java` | — | 子工作流内交互节点辅助 |
| `SubRequestScope.java` | — | 子请求作用域隔离 |

#### `adapter.model` — 模型 4 + EI 1

| 文件 | IR 类型 | 委托 Engine |
| --- | --- | --- |
| `LlmNodeHandler.java` | `jiuwen.LLMComponent` | `llmchain.LlmChainEngine` |
| `IntentDetectionNodeHandler.java` | `jiuwen.intentDetection` | `intentdetection.IntentDetectionEngine` |
| `ExtractorNodeHandler.java` | `jiuwen.extractor` | `extractor.ExtractorEngine` |
| `KnowledgeRetrievalNodeHandler.java` | `jiuwen.knowledgeRetrieval` | `kb.KnowledgeRetrievalEngine` |
| `ComplexIntentDetectionNodeHandler.java` | `EI.ComplexIntentDetection` | `complexintent.ComplexIntentDetectionEngine` |

#### `adapter.interact` — 交互 4 + EI 1

| 文件 | IR 类型 | 委托 Engine |
| --- | --- | --- |
| `InputNodeHandler.java` | `jiuwen.input` | `flowinput.FlowInputEngine` |
| `MessageNodeHandler.java` | `jiuwen.message` | `flowmessage.FlowMessageEngine` |
| `CardNodeHandler.java` | `jiuwen.card` | `flowcard.FlowCardEngine` |
| `QuestionerNodeHandler.java` | `jiuwen.questioner` | `questioner.QuestionerEngine` |
| `QaNodeHandler.java` | `EI.qa` | `flowqa.FlowQaEngine` |
| `FlowInputUtils.java` | — | 转发至 `flowinput.FlowInputUtils`（兼容层，main 未引用） |

#### `adapter.external` — 外部 5 + EI 1

| 文件 | IR 类型 | 委托 Engine |
| --- | --- | --- |
| `CodeNodeHandler.java` | `jiuwen.code` | `flowcode.FlowCodeEngine` + Python |
| `PluginNodeHandler.java` | `jiuwen.plugin` | `flowapi.FlowApiEngine` |
| `McpNodeHandler.java` | `jiuwen.mcp` | `flowmcp.FlowMcpEngine` |
| `AgentNodeHandler.java` | `jiuwen.agent` | `flowagent.FlowAgentEngine` |
| `StreamTransformNodeHandler.java` | `jiuwen.streamTransform` | `flowstreamtransform.FlowStreamTransformEngine` |
| `ParamOutputNodeHandler.java` | `EI.ParamOutput` | userFields/systemFields 透传 |

---

### 4.11 节点 Engine 包（对齐 Python `workflow_node/*.py`）

每个 `flow*` 包通常含 `*Config` + `*Engine`；Handler 只负责注册与委托。

| Package | 文件 | 作用 |
| --- | --- | --- |
| **flowstart** | `FlowStartConfig` | start IR 配置解析 |
| | `FlowStartEngine` | 初始化全局变量、conversation vals、query |
| | `FlowStartAssignmentSupport` | 变量赋值 helper |
| **flowend** | `FlowEndEngine` | 类型转换、`#end_` 前缀、userFields、模板 split |
| | `FlowEndGeneratorSupport` | 流式生成器消费与回放 |
| | `FlowEndMixCoordinator` | batch/stream mix 协调 |
| | `FlowEndConfig` | IR 配置解析（**已写好，EndNodeHandler 尚未接线**） |
| **flowaggregate** | `FlowAggregateConfig` / `FlowAggregateEngine` | 多路分支结果聚合 |
| **flowsubworkflow** | `FlowSubWorkflowConfig` / `FlowSubWorkflowEngine` | 子流解析、child context、执行合并 |
| | `SubWorkflowState` / `SubWorkflowExecutionStatus` / `SubWorkflowException` | 子流状态与异常 |
| **flowsetvariable** | `FlowSetVariableConfig` / `FlowSetVariableEngine` | 写 session / conversation vals |
| **flowexception** | `FlowExceptionConfig` / `FlowExceptionEngine` | 工作流 abort |
| | `WorkflowAbortException` | Python `ExceptionInfo` 对齐信号 |
| **flowinput** | `FlowInputEngine` / `FlowInputState` / `FlowInputUtils` | 用户输入、中断恢复 |
| **flowmessage** | `FlowMessageConfig` / `FlowMessageEngine` | 消息节点渲染输出 |
| **flowcard** | `FlowCardConfig` / `FlowCardEngine` | 卡片 UI 数据输出 |
| **flowcode** | `FlowCodeEngine` | 组装 Python 请求、解析 stdout JSON |
| **flowapi** | `FlowApiEngine` | REST/SSE 插件调用主逻辑 |
| | `FlowApiParam` / `FlowApiTypeTransform` | 参数与类型转换 |
| | `FlowApiErrors` / `FlowApiStatusCode` | 错误码 |
| | `RestfulApiSseClient` | SSE HTTP 客户端 |
| **flowmcp** | `FlowMcpEngine` | MCP 工具调用编排 |
| | `IrToMcpServerConfig` / `RuntimeAwareMcpClient` | IR → MCP 配置与客户端 |
| | `McpToolParam` / `TypeTransform` | 参数与类型转换 |
| | `FlowMcpErrors` / `FlowMcpStatusCode` | 错误码 |
| **flowagent** | `FlowAgentConfig` / `FlowAgentEngine` / `FlowAgentToolLoader` | 子 Agent 调用 |
| **flowstreamtransform** | `FlowStreamTransformEngine` | 流式 dict 路径变换 |
| **flowqa** | `FlowQaConfig` / `FlowQaEngine` / `StructInputSchemas` | EI.qa 结构化 QA |
| **llmchain** | `LlmChainConfig` / `LlmChainPrompt` / `LlmChainEngine` | LLM 链式调用 |
| **llm** | `MessageHistorySupport` | 会话历史读写 |
| | `ConversationUserMessage` / `ConversationAssistantMessage` | 历史消息模型 |
| **intentdetection** | `IntentDetectionConfig` / `IntentDetectionConfigFormatter` | 意图配置 |
| | `IntentDetectionEngine` / `IntentDetectionLlmDetector` | 意图识别主流程 |
| | `IntentFaqMatcher` / `IntentDetectionState` | FAQ 规则与状态 |
| **extractor** | `ExtractorConfig` / `ExtractorConfigFormatter` / `ExtractorConfigValidator` | 抽取 schema |
| | `ExtractorEngine` / `ExtractorLlmExtractor` | 信息抽取主流程 |
| **complexintent** | `ComplexIntentDetectionConfig` / `ComplexIntentDetectionEngine` / `ComplexIntentState` | EI 复合意图 |
| **questioner** | `QuestionerConfig` / `QuestionerEngine` / `QuestionerState` | 追问主逻辑 |
| | `QuestionerField` / `QuestionerLlmExtractor` | 字段定义与 LLM 抽取 |
| | `QuestionerKeywords` / `QuestionerRailsHints` / `QuestionerTraceStore` | 关键词、Rails 提示、trace |

---

### 4.12 `kb` — 知识检索（16 文件）

| 文件 | 作用 |
| --- | --- |
| `KnowledgeRetrievalEngine.java` | 检索编排入口 |
| `KnowledgeRequestContext.java` | 单次请求上下文 |
| `KnowledgeRetrievalCacheStore.java` | Redis 结果/图片缓存 |
| `KnowledgeBaseConfigProviders.java` | KB 配置 provider 全局 holder |
| `ObsKnowledgeBaseConfigProvider.java` | 从 OBS 拉 KB 配置（默认） |
| `EnvVarKnowledgeBaseConfigProvider.java` | 环境变量 KB 配置 |
| `InMemoryKnowledgeStorageProvider.java` | 内存 storage（测试 stub） |
| `KBAdapterFactory.java` | `connectorType` → 适配器注册表 |
| `KBServiceAdapter.java` | 适配器接口 |
| `LakeSearchAdapter.java` | LakeSearch HTTP + Kerberos |
| `KooSearchAdapter.java` | KooSearch 适配 |
| `RagFlowAdapter.java` | Ragflow 适配 |
| `GeneralKBAdapter.java` | 通用 KB 适配 |
| `KbHttp.java` | KB HTTP 工具 |
| `KBSearchResult.java` | 检索结果模型 |
| `KerberosAuth.java` | Kerberos 认证头构建 |

---

### 4.13 `rails` — questioner 字段校验（12 文件）

| 文件 | 作用 |
| --- | --- |
| `RailsRegistry.java` | 注册全部 validator/formatter |
| `RailsAction.java` / `ValidateAction.java` / `FormatAction.java` | Rails 动作继承链 |
| `ActionConfig.java` | 动作配置 |
| **validators/** | |
| `LengthLimitValidateAction.java` | 长度限制 |
| `NumberRangeValidateAction.java` | 数值范围 |
| `EnumLegalityValidateAction.java` | 枚举合法 |
| `CommonDataFormatCheckAction.java` | 通用格式 |
| `TimeParseAction.java` | 时间解析 |
| **formatters/** | |
| `DateTimeFormatValidateAction.java` | 日期时间格式校验 |
| `DateUtilCompatibleParser.java` | 日期解析（兼容 Python dateutil） |

---

## 5. 内置节点一览（24）

| 组 | 数量 | canonical 类型（节选） |
| --- | --- | --- |
| 控制 | 8 | `jiuwen.start` … `jiuwen.exception` |
| 模型 | 4 | `jiuwen.LLMComponent`、`intentDetection`、`extractor`、`knowledgeRetrieval` |
| 交互 | 4 + 1 EI | `input`、`message`、`card`、`questioner`；`EI.qa` |
| 外部 | 5 + 2 EI | `code`、`plugin`、`mcp`、`agent`、`streamTransform`；`EI.ParamOutput`、`EI.ComplexIntentDetection` |

FEAT-031 承诺 21 个 `jiuwen.*`；另 3 个 `EI.*` 为 P6 扩展，与 FEAT 正文分开统计。

---

## 6. 测试目录 `src/test/java`

| 类别 | 代表文件 | 测什么 |
| --- | --- | --- |
| 注册/合规 | `NodeTypeRegistryTest`、`NodeGuardrailsTest` | 24 类型、别名、FEAT 约束 |
| 节点 parity | `WorkflowNode*CasesTest`、`*ParityTest` | 对齐 Python workflow_node |
| Engine 单测 | `flowend/FlowEndEngineTest`、`flowstart/FlowStartEngineTest` 等 | 单 Engine 行为 |
| 集成 | `StudioDslModuleTest` | `StudioDslModule` 宿主入口烟雾 |
| 测试支撑 | `testsupport/LinearWorkflowTestSupport` | 测试侧线性链式执行（**非生产 API**） |
| | `testsupport/StubModelContext`、`support/InMemoryToolRegistry` | Mock / stub |
| | `flowmcp/RecordingMcpClient` | MCP 测试录制客户端 |

---

## 7. 快速定位

| 你想找… | 去看… |
| --- | --- |
| 怎么接入 | `StudioDslModule` |
| 某 IR 类型谁处理 | `BuiltinNodeBootstrap` → 对应 `*NodeHandler` |
| 节点真实逻辑 | Handler import 的 `*Engine` |
| 宿主该注入什么 | `contract/*` + `NodeBuildContext` |
| 失败码 | `NodeCauseCode` + `NodeExecutionException` |
| Python 代码节点 | `python/*` + `FlowCodeEngine` |
| KB 检索 | `kb/KnowledgeRetrievalEngine` + adapters |

---

## 8. 已知遗留（可选清理）

| 项 | 说明 |
| --- | --- |
| `flowend/FlowEndConfig` | 已写好，`EndNodeHandler` 仍直接读 `node.configs()` |
| `adapter/interact/FlowInputUtils` | 兼容转发层，main 未引用 |

---

*生成基准：main 177 文件 / test 57 文件；与仓库当前 `feat/031-studio-dsl-python-1to1` 分支一致。*
