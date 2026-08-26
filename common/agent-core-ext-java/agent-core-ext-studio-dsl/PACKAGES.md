# agent-core-ext-studio-dsl Package 说明

本文说明 `agent-core-ext-studio-dsl` 模块 `src/main/java` 下各 Java package 的职责边界。  
根 package：`com.openjiuwen.studio.dsl`（主源码不含 `bridge` / `schema`；二者已移除）。

## 核心 package 清单（节选）

1. `com.openjiuwen.studio.dsl`
2. `com.openjiuwen.studio.dsl.config`
3. `com.openjiuwen.studio.dsl.adapter`（及 control/model/interact/external）
4. `com.openjiuwen.studio.dsl.contract`（含 `EmptyToolRegistry`）
5. `com.openjiuwen.studio.dsl.registry`
6. `com.openjiuwen.studio.dsl.exec`
7. `com.openjiuwen.studio.dsl.model`
8. `com.openjiuwen.studio.dsl.python`
9. `com.openjiuwen.studio.dsl.util`
10. 各节点 Engine 包（`flow*` / `kb` / `rails` / …）

> **已移除**：`com.openjiuwen.studio.dsl.bridge`、`com.openjiuwen.studio.dsl.schema`；以及 FEAT-031 OUT 的 `CodeLogic` / `CodeLogicContext` / `CoreExecutableFactory` / `CodeLogicRegistry`（代码节点仅 Python）。测试侧可在 `src/test/.../support` 保留 `InMemoryToolRegistry`。

## 总览

| 层次 | Package | 一句话 |
| --- | --- | --- |
| 入口 | `com.openjiuwen.studio.dsl` | 编程式模块入口 |
| 配置 | `com.openjiuwen.studio.dsl.config` | 模块 / 节点配置属性 |
| 节点公共 | `com.openjiuwen.studio.dsl.adapter` | 节点基类、委托壳、透传 |
| 控制流节点 | `com.openjiuwen.studio.dsl.adapter.control` | start / end / branch / loop 等 |
| 模型节点 | `com.openjiuwen.studio.dsl.adapter.model` | LLM / 意图 / 抽取 / 知识检索 |
| 交互节点 | `com.openjiuwen.studio.dsl.adapter.interact` | input / message / card / questioner |
| 外部节点 | `com.openjiuwen.studio.dsl.adapter.external` | code / plugin / mcp / agent / stream |
| 宿主契约 | `com.openjiuwen.studio.dsl.contract` | 构造期注入接口（非 ServiceLoader） |
| 注册 | `com.openjiuwen.studio.dsl.registry` | 类型注册表与内置 bootstrap |
| 执行骨架 | `com.openjiuwen.studio.dsl.exec` | 组装、线性执行、作用域、异常 |
| 领域模型 | `com.openjiuwen.studio.dsl.model` | Assembled* / Payload / 失败码 / 媒体 |
| Python | `com.openjiuwen.studio.dsl.python` | 子进程 Python 执行 |
| 工具 | `com.openjiuwen.studio.dsl.util` | 深拷贝、条件、路径、模板、媒体等 |

一句话对照：

- `com.openjiuwen.studio.dsl.contract`：宿主能换什么  
- `com.openjiuwen.studio.dsl.adapter.*`：内置节点怎么跑  
- `com.openjiuwen.studio.dsl.exec` + `com.openjiuwen.studio.dsl.model`：怎么组图和带数据跑  
- `com.openjiuwen.studio.dsl.python`：Python 代码执行 

---

## 入口与配置

### `com.openjiuwen.studio.dsl`

编程式入口。

- 典型类型：`StudioDslModule`
- 作用：一站式创建节点注册表、默认执行器、装配桥等；宿主主接入点（无 Spring 自动装配）

### `com.openjiuwen.studio.dsl.config`

模块与节点配置属性。

- 典型类型：`StudioDslNodeProperties`
- 作用：嵌套深度、节点相关开关等，供 `StudioDslModule` 读取

---

## 节点实现（adapter）

### `com.openjiuwen.studio.dsl.adapter`

节点公共壳。

- 典型类型：`AbstractStudioNode`、`StudioStreamFrames`
- 作用：统一 invoke → userFields 包装；各 `*NodeHandler` 委托对应 `*Engine`

### `com.openjiuwen.studio.dsl.adapter.control`

控制流节点。

| 节点类型 | Handler（示意） |
| --- | --- |
| `jiuwen.start` | `StartNodeHandler` → `flowstart.FlowStartEngine` |
| `jiuwen.end` | `EndNodeHandler` → `flowend.FlowEndEngine` |
| `jiuwen.branch` | `BranchNodeHandler` |
| `jiuwen.loop` | `LoopNodeHandler` |
| `jiuwen.aggregate` | `AggregateNodeHandler` → `flowaggregate.FlowAggregateEngine` |
| `jiuwen.nestedWorkflow` / `jiuwen.subWorkflow` | `NestedWorkflowNodeHandler` → `flowsubworkflow.FlowSubWorkflowEngine` |
| `jiuwen.setVariable` | `SetVariableNodeHandler` → `flowsetvariable.FlowSetVariableEngine` |
| `jiuwen.exception` | `ExceptionNodeHandler` → `flowexception.FlowExceptionEngine` |

### `com.openjiuwen.studio.dsl.adapter.model`

模型类节点。

| 节点类型 | Handler（示意） |
| --- | --- |
| `jiuwen.LLMComponent`（及 llm 别名） | `LlmNodeHandler` |
| `jiuwen.intentDetection` | `IntentDetectionNodeHandler` |
| `jiuwen.extractor` | `ExtractorNodeHandler` |
| `jiuwen.knowledgeRetrieval` | `KnowledgeRetrievalNodeHandler` |

### `com.openjiuwen.studio.dsl.adapter.interact`

交互类节点。

| 节点类型 | Handler（示意） |
| --- | --- |
| `jiuwen.input` | `InputNodeHandler` → `flowinput.FlowInputEngine` |
| `jiuwen.message` | `MessageNodeHandler` → `flowmessage.FlowMessageEngine` |
| `jiuwen.card` | `CardNodeHandler` → `flowcard.FlowCardEngine` |
| `jiuwen.questioner` | `QuestionerNodeHandler` |

### `com.openjiuwen.studio.dsl.adapter.external`

外部能力节点。

| 节点类型 | Handler（示意） |
| --- | --- |
| `jiuwen.code` | `CodeNodeHandler` |
| `jiuwen.plugin` | `PluginNodeHandler` |
| `jiuwen.mcp` | `McpNodeHandler` |
| `jiuwen.agent` | `AgentNodeHandler` |
| `jiuwen.streamTransform` | `StreamTransformNodeHandler` |

---

## 契约与注册

### `com.openjiuwen.studio.dsl.contract`

宿主构造期注入契约（接口，不含 ServiceLoader 发现；不含覆盖内置 Handler）。

| 契约 | 作用 |
| --- | --- |
| `NodeHandlerFactory` | 内置 Handler 内部工厂契约（非宿主扩展 SPI） |
| `PythonCodeExecutor` | Python 执行器 |
| `ToolRegistry` / `EmptyToolRegistry` | Plugin / Tool 查找 |
| `SubWorkflowResolver` | 嵌套子工作流解析 |
| `KnowledgeBaseConfigProvider` / `KnowledgeStorageProvider` / `SecretDecryptor` | KB |

#### 要不要「实现」？

| 东西 | 谁实现 |
| --- | --- |
| `NodeHandlerFactory` | **内置已实现**（`BuiltinNodeBootstrap`）；仅模块内注册，**不能**覆盖已有 canonical |
| `PythonCodeExecutor` | **模块已有默认** `SubprocessPythonCodeExecutor`；宿主可在构造时注入替换 |
| `ToolRegistry` / `SubWorkflowResolver` / KB providers | **模块有默认 / 占位**；生产再注入 |

**已移除**：ServiceLoader、`replace` / `registerOrReplace`、用 META-INF 覆盖内置 `jiuwen.*`。

### `com.openjiuwen.studio.dsl.registry`

类型注册与内置装载。

- 典型类型：`NodeTypeRegistry`、`BuiltinNodeBootstrap`
- 作用：按 canonical type / alias 创建可执行体；启动时注册内置节点（无 ServiceLoader）

---

## 执行与数据模型

### `com.openjiuwen.studio.dsl.exec`

运行时骨架。

- 典型类型：`NodeBuildContext`、`WorkflowAssemblyBridge`、`WorkflowVariableScope`、`NodeExecutionException`
- 作用：构建上下文、`AssembledWorkflow` → 可执行体映射（`mapExecutables`）、子工作流步骤合并（`mergeLinearStep`）、可区分失败异常

### `com.openjiuwen.studio.dsl.model`

领域模型（无执行逻辑）。

- 典型类型：`AssembledNode`、`AssembledWorkflow`、`NodePayload`、`MediaPart`、`NodeCauseCode`
- 作用：宿主传入的已组装图结构、节点输出载荷、多模态部件、失败码枚举

---

## 专项能力

### `com.openjiuwen.studio.dsl.python`

Python 代码节点执行。

- 典型类型：`PythonExecRequest`、`PythonExecResult`、`SubprocessPythonCodeExecutor`
- 作用：隔离工作目录、子进程执行、stdout JSON 解析；用户脚本需 `main(args: dict) -> dict`



### `com.openjiuwen.studio.dsl.util`

无业务状态的工具集。

| 类型（示意） | 作用 |
| --- | --- |
| `DeepCopies` | Map/List 深拷贝 |
| `ConditionEvaluator` | 分支 / 循环条件求值 |
| `PathResolver` | 字段路径取值 |
| `TemplateRenderer` | 消息等模板渲染 |
| `MediaSupport` | 多模态部件注入 / Prompt 展平 |
| `SessionStateIsolator` | 嵌套会话状态隔离 |
| `TypeCoercer` | 类型强制转换辅助 |

---

## 目录树（与 package 对应）

```
src/main/java/com/openjiuwen/studio/dsl/
├── StudioDslModule.java          # 根 package
├── adapter/                      # adapter + control/model/interact/external
├── contract/
├── registry/
├── exec/
├── model/
├── python/
├── config/
└── util/
```

---

## 与 FEAT-031 需求对照

需求文档：`低码转高码需求/0819doc/docs/develop/02-features/FEAT-031-studio-dsl-node-type-extension.md`  
（Studio DSL 节点类型扩展承载；章节号以下表为准。）

FEAT 定黑盒能力；包名由 L2 / 本模块落地拆分。下表说明每个 package 主要落在需求的哪一段。

### Package → FEAT 章节

| Package | 主要对应需求位置 | 对应什么 |
| --- | --- | --- |
| `com.openjiuwen.studio.dsl`（根，`StudioDslModule`） | §3「入口」整体；§1 代码落地 | 编程式模块入口，把节点 / contract / 执行拼起来；非 HTTP |
| `com.openjiuwen.studio.dsl.contract` | §2「节点工厂契约」；§3 节点工厂；§5.3 | 统一扩展契约（`NodeHandlerFactory`、`PythonCodeExecutor`、`ToolRegistry` 等） |
| `com.openjiuwen.studio.dsl.registry` | §5.1 内置 21 节点；§2 节点执行 | 内置节点注册、别名、按 IR 创建 |
| `com.openjiuwen.studio.dsl.adapter` | §5.3（内置经 NodeHandlerFactory 接入）；公共壳 | Handler 基类 / 委托 / 透传 |
| `com.openjiuwen.studio.dsl.adapter.control` | §2 编排控制 8 行；§5.1 编排控制类 | start / end / branch / loop / aggregate / nested / setVariable / exception |
| `com.openjiuwen.studio.dsl.adapter.model` | §2 模型推理 4 行；§5.1 模型推理类 | LLM / intent / extractor / knowledge |
| `com.openjiuwen.studio.dsl.adapter.interact` | §2 交互 4 行；§5.1 交互类 | input / message / card / questioner |
| `com.openjiuwen.studio.dsl.adapter.external` | §2 外部调用 5 行 + 代码 Java/Python；§5.1 外部调用类；§5.2 | code / plugin / mcp / agent / streamTransform |
| `com.openjiuwen.studio.dsl.exec` | §2「工作流变量作用域」「嵌套深度」「失败表面」；§5.5；§3 失败表面 | `WorkflowVariableScope`、装配映射、`NodeBuildContext`、失败异常 |
| `com.openjiuwen.studio.dsl.model` | §2「节点间数据传递」「多模态」；§3 数据模型 / 多模态；§5.4；§1「只消费装配产物」 | `Assembled*` / `NodePayload` / `MediaPart` / `NodeCauseCode` |
| `com.openjiuwen.studio.dsl.python` | §2「代码节点支持 Python」；§5.2；§5.6「机制由 L2」 | 子进程执行、隔离目录、超时处理（机制选型，非沙箱承诺） |
| `com.openjiuwen.studio.dsl.config` | §2「嵌套深度由配置声明」；§3「节点配置」 | 模块 / 节点属性（深度、python 等） |
| `com.openjiuwen.studio.dsl.util` | 支撑 §5.4 / §5.5 / setVariable 等（FEAT 未单列包） | 深拷贝、条件、路径、模板、媒体、会话隔离等工具 |

### FEAT 章节 → Package（反查）

| FEAT 章节 | 主要落在哪些包 |
| --- | --- |
| §2 / §5.1 四大类 21 节点 | `com.openjiuwen.studio.dsl.adapter.control` / `com.openjiuwen.studio.dsl.adapter.model` / `com.openjiuwen.studio.dsl.adapter.interact` / `com.openjiuwen.studio.dsl.adapter.external` + `com.openjiuwen.studio.dsl.registry` |
| §5.1 内置节点工厂 | `com.openjiuwen.studio.dsl.registry` + `com.openjiuwen.studio.dsl.adapter.*` |
| §2 代码节点（仅 Python） | `com.openjiuwen.studio.dsl.python` + `PythonCodeExecutor` + `adapter.external`（code） |
| §2 / §5.2 Python | `com.openjiuwen.studio.dsl.python` + `PythonCodeExecutor` + `com.openjiuwen.studio.dsl.adapter.external` |
| §2 / §5.4 数据与多模态 | `com.openjiuwen.studio.dsl.model` + `com.openjiuwen.studio.dsl.util`（如 `MediaSupport`） |
| §2 / §5.5 嵌套与深度 | `com.openjiuwen.studio.dsl.adapter.control`（nested）+ `com.openjiuwen.studio.dsl.exec` + `SubWorkflowResolver` + `com.openjiuwen.studio.dsl.config` |
| §2 变量作用域 | `com.openjiuwen.studio.dsl.exec`（`WorkflowVariableScope`）+ `com.openjiuwen.studio.dsl.adapter.control`（setVariable） |
| §2 / §3 失败表面 | `com.openjiuwen.studio.dsl.model`（`NodeCauseCode`）+ `com.openjiuwen.studio.dsl.exec`（`NodeExecutionException`） |
| §1 / §5.6「只消费装配产物」 | `com.openjiuwen.studio.dsl.model`（`Assembled*`）；不做全量 DSL 装载 |
| §3「不新增 HTTP」 | 全模块无 serve；入口是 `com.openjiuwen.studio.dsl`（`StudioDslModule`） |

阅读提示：

- 需求正文点名的能力 → 主要是 `com.openjiuwen.studio.dsl.contract` / `com.openjiuwen.studio.dsl.registry` / `com.openjiuwen.studio.dsl.adapter.*` / `com.openjiuwen.studio.dsl.exec` / `com.openjiuwen.studio.dsl.model` / `com.openjiuwen.studio.dsl.python`
- `com.openjiuwen.studio.dsl.config` / `com.openjiuwen.studio.dsl.util` / 根包 → 落地与配置，对应 §3 或 L2 细化，不是另开一套 FEAT 外特性

---

## 相关文档

- 模块总览与接入：[`README.md`](./README.md)
- 特性需求：仓库内 `低码转高码需求/0819doc/docs/develop/02-features/FEAT-031-studio-dsl-node-type-extension.md`
- Python 节点覆盖对照：[`PYTHON-NODE-COVERAGE.md`](./PYTHON-NODE-COVERAGE.md)
