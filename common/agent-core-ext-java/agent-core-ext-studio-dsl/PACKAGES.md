# agent-core-ext-studio-dsl Package 说明

本文说明 `agent-core-ext-studio-dsl` 模块 `src/main/java` 下各 Java package 的职责边界。  
根 package：`com.openjiuwen.studio.dsl`（共 16 个）。

## 完整 package 清单（16）

1. `com.openjiuwen.studio.dsl`
2. `com.openjiuwen.studio.dsl.autoconfigure`
3. `com.openjiuwen.studio.dsl.config`
4. `com.openjiuwen.studio.dsl.adapter`
5. `com.openjiuwen.studio.dsl.adapter.control`
6. `com.openjiuwen.studio.dsl.adapter.model`
7. `com.openjiuwen.studio.dsl.adapter.interact`
8. `com.openjiuwen.studio.dsl.adapter.external`
9. `com.openjiuwen.studio.dsl.contract`
10. `com.openjiuwen.studio.dsl.registry`
11. `com.openjiuwen.studio.dsl.exec`
12. `com.openjiuwen.studio.dsl.model`
13. `com.openjiuwen.studio.dsl.python`
14. `com.openjiuwen.studio.dsl.bridge`
15. `com.openjiuwen.studio.dsl.schema`
16. `com.openjiuwen.studio.dsl.util`

## 总览

| 层次 | Package | 一句话 |
| --- | --- | --- |
| 入口 | `com.openjiuwen.studio.dsl` | 编程式模块入口 |
| 接线 | `com.openjiuwen.studio.dsl.autoconfigure` | 可选 Spring Boot 自动配置 |
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
| 默认桥接 | `com.openjiuwen.studio.dsl.bridge` | core / MCP / Agent 默认实现 |
| 校验 | `com.openjiuwen.studio.dsl.schema` | DSL/IR 壳校验 |
| 工具 | `com.openjiuwen.studio.dsl.util` | 深拷贝、条件、路径、模板、媒体等 |

一句话对照：

- `com.openjiuwen.studio.dsl.contract`：宿主能换什么  
- `com.openjiuwen.studio.dsl.adapter.*`：内置节点怎么跑  
- `com.openjiuwen.studio.dsl.exec` + `com.openjiuwen.studio.dsl.model`：怎么组图和带数据跑  
- `com.openjiuwen.studio.dsl.bridge` / `com.openjiuwen.studio.dsl.python`：对接 AgentCore 与 Python 的默认实现  

---

## 入口与接线

### `com.openjiuwen.studio.dsl`

编程式入口。

- 典型类型：`StudioDslModule`
- 作用：一站式创建节点注册表、默认执行器、装配桥等；无 Spring 时的主接入点

### `com.openjiuwen.studio.dsl.autoconfigure`

可选 Spring Boot 自动配置。

- 作用：classpath 存在 Spring Boot 时把模块接到 Bean；纯 Java 宿主可不依赖本包用法

### `com.openjiuwen.studio.dsl.config`

模块与节点配置属性。

- 典型类型：`StudioDslProperties`、`StudioDslNodeProperties`
- 作用：嵌套深度、节点相关开关等，供 `StudioDslModule` / 自动配置读取

---

## 节点实现（adapter）

### `com.openjiuwen.studio.dsl.adapter`

节点公共壳。

- 典型类型：`AbstractStudioNode`、`DelegatingStudioNode`、`PassthroughStudioNode`、`SimpleNodeFactory`
- 作用：统一 invoke → userFields 包装、委托 core 可执行体、默认透传（含媒体）

### `com.openjiuwen.studio.dsl.adapter.control`

控制流节点。

| 节点类型 | Handler（示意） |
| --- | --- |
| `jiuwen.start` | `StartNodeHandler` |
| `jiuwen.end` | `EndNodeHandler` |
| `jiuwen.branch` | `BranchNodeHandler` |
| `jiuwen.loop` | `LoopNodeHandler` |
| `jiuwen.aggregate` | `AggregateNodeHandler` |
| `jiuwen.nestedWorkflow` | `NestedWorkflowNodeHandler` |
| `jiuwen.setVariable` | `SetVariableNodeHandler` |
| `jiuwen.exception` | `ExceptionNodeHandler` |

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
| `jiuwen.input` | `InputNodeHandler` |
| `jiuwen.message` | `MessageNodeHandler` |
| `jiuwen.card` | `CardNodeHandler` |
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
| `NodeHandlerFactory` | 节点类型工厂（内置 + 显式 `register`） |
| `CodeLogic` / `CodeLogicContext` | Java 代码节点逻辑 |
| `PythonCodeExecutor` | Python 执行器 |
| `CoreExecutableFactory` | 桥接 AgentCore LLM / Questioner / Knowledge 等 |
| `McpToolInvoker` | MCP 工具调用 |
| `ToolRegistry` | Plugin / Tool 查找 |
| `AgentInvoker` / `AgentRegistry` | Agent 节点调用与注册 |
| `SubWorkflowResolver` | 嵌套子工作流解析 |

#### 要不要「实现」？

| 东西 | 谁实现 |
| --- | --- |
| `NodeHandlerFactory` | **内置已实现**（`BuiltinNodeBootstrap`）；宿主可 `register` **新类型**，**不能**覆盖已有 canonical |
| `CodeLogic` | 走 Java 代码节点时由部署方显式注册 |
| `PythonCodeExecutor` | **模块已有默认** `SubprocessPythonCodeExecutor`；宿主可在构造时注入替换 |
| `CoreExecutableFactory` / MCP / Agent / `SubWorkflowResolver` | **模块有默认 / 占位**；生产再注入 |

**已移除**：ServiceLoader、`replace` / `registerOrReplace`、用 META-INF 覆盖内置 `jiuwen.*`。

### `com.openjiuwen.studio.dsl.registry`

类型注册与内置装载。

- 典型类型：`NodeTypeRegistry`、`CodeLogicRegistry`、`BuiltinNodeBootstrap`
- 作用：按 canonical type / alias 创建可执行体；启动时注册内置节点（无 ServiceLoader）

---

## 执行与数据模型

### `com.openjiuwen.studio.dsl.exec`

运行时骨架。

- 典型类型：`NodeBuildContext`、`WorkflowAssemblyBridge`、`WorkflowVariableScope`、`NodeExecutionException`；以及旧名兼容 `WorkflowAssemblerBridge`
- 作用：构建上下文、AssembledWorkflow → 可执行体映射、线性执行并关闭变量作用域、可区分失败异常

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

### `com.openjiuwen.studio.dsl.bridge`

默认桥接实现（可被宿主构造期注入替换）。

- 典型类型：`ConfigDrivenCoreExecutableFactory`、`DefaultMcpToolInvoker`、`DefaultAgentInvoker`、`InMemoryToolRegistry`、`InMemoryAgentRegistry`
- 作用：配置驱动对接 AgentCore 可执行体；内存注册表与默认 HTTP/MCP/Agent 调用占位
- **与 FEAT-031 的关系**：FEAT **没有**写「必须提供 bridge / 默认占位实现」。FEAT 只要求有插件 / mcp / agent **节点类型**（§2 / §5.1），并把 agent 远程 A2A 语义 OUT 给 FEAT-004。本包是 **L2 落地**（`bridge/`：宿主侧默认桥、可构造期注入），方便模块开箱可测；不是 FEAT §3 / §5.6 的原文条款。

### `com.openjiuwen.studio.dsl.schema`

DSL / IR 壳校验。

- 典型类型：`DslNodeShellValidator`
- 作用：组装前检查 id / type / configs 等基本字段，避免无效节点进入执行

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
├── bridge/
├── config/
├── autoconfigure/
├── schema/
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
| `com.openjiuwen.studio.dsl.contract` | §2「节点工厂契约」「自定义节点」；§3 节点工厂 / CodeLogic；§5.3 | 统一扩展契约（`NodeHandlerFactory`、`CodeLogic`、`PythonCodeExecutor` 等） |
| `com.openjiuwen.studio.dsl.registry` | §2「自定义节点类型扩展」；§5.1 / §5.3；§3 自定义节点注册 | 内置 21 种注册、别名、ServiceLoader 发现、按 IR 创建 |
| `com.openjiuwen.studio.dsl.adapter` | §5.3（内置经 NodeHandlerFactory 接入）；公共壳 | Handler 基类 / 委托 / 透传 |
| `com.openjiuwen.studio.dsl.adapter.control` | §2 编排控制 8 行；§5.1 编排控制类 | start / end / branch / loop / aggregate / nested / setVariable / exception |
| `com.openjiuwen.studio.dsl.adapter.model` | §2 模型推理 4 行；§5.1 模型推理类 | LLM / intent / extractor / knowledge |
| `com.openjiuwen.studio.dsl.adapter.interact` | §2 交互 4 行；§5.1 交互类 | input / message / card / questioner |
| `com.openjiuwen.studio.dsl.adapter.external` | §2 外部调用 5 行 + 代码 Java/Python；§5.1 外部调用类；§5.2 | code / plugin / mcp / agent / streamTransform |
| `com.openjiuwen.studio.dsl.exec` | §2「工作流变量作用域」「嵌套深度」「失败表面」；§5.5；§3 失败表面 | `WorkflowVariableScope`、装配桥、`NodeBuildContext`、失败异常、线性烟雾执行 |
| `com.openjiuwen.studio.dsl.model` | §2「节点间数据传递」「多模态」；§3 数据模型 / 多模态；§5.4；§1「只消费装配产物」 | `Assembled*` / `NodePayload` / `MediaPart` / `NodeCauseCode` |
| `com.openjiuwen.studio.dsl.python` | §2「代码节点支持 Python」；§5.2；§5.6「机制由 L2」 | 子进程执行、隔离目录、超时处理（机制选型，非沙箱承诺） |
| `com.openjiuwen.studio.dsl.bridge` | **FEAT 未点名本包**；间接相关：§2 插件 / mcp / agent 节点 MUST、agent 远程语义 OUT 给 FEAT-004（§5.1 / §7）；**包本身来自 L2**（默认桥、可构造期注入） | Core / MCP / Agent 等**默认 / 占位**实现，方便模块可跑、可测；生产真实调用由宿主注入 contract，不是 FEAT 黑盒条文 |
| `com.openjiuwen.studio.dsl.config` | §2「嵌套深度由配置声明」；§3「节点配置」 | 模块 / 节点属性（深度、python 等） |
| `com.openjiuwen.studio.dsl.autoconfigure` | §3 部署注册面的 Spring 接线（FEAT 未点名包，属落地） | 可选 Spring Boot 自动配置 |
| `com.openjiuwen.studio.dsl.schema` | §3「节点配置」最小外壳；§5.6 DSL 全量校验 OUT 给装载方 | 仅最小壳校验，不是全量 DSL 装载 |
| `com.openjiuwen.studio.dsl.util` | 支撑 §5.4 / §5.5 / setVariable 等（FEAT 未单列包） | 深拷贝、条件、路径、模板、媒体、会话隔离等工具 |

### FEAT 章节 → Package（反查）

| FEAT 章节 | 主要落在哪些包 |
| --- | --- |
| §2 / §5.1 四大类 21 节点 | `com.openjiuwen.studio.dsl.adapter.control` / `com.openjiuwen.studio.dsl.adapter.model` / `com.openjiuwen.studio.dsl.adapter.interact` / `com.openjiuwen.studio.dsl.adapter.external` + `com.openjiuwen.studio.dsl.registry` |
| §2 / §5.3 节点工厂 + 自定义 | `com.openjiuwen.studio.dsl.contract` + `com.openjiuwen.studio.dsl.registry` |
| §2 代码 Java CodeLogic | `com.openjiuwen.studio.dsl.contract`（`CodeLogic`）+ `com.openjiuwen.studio.dsl.adapter.external`（code）+ `com.openjiuwen.studio.dsl.registry` |
| §2 / §5.2 Python | `com.openjiuwen.studio.dsl.python` + `PythonCodeExecutor` + `com.openjiuwen.studio.dsl.adapter.external` |
| §2 / §5.4 数据与多模态 | `com.openjiuwen.studio.dsl.model` + `com.openjiuwen.studio.dsl.util`（如 `MediaSupport`） |
| §2 / §5.5 嵌套与深度 | `com.openjiuwen.studio.dsl.adapter.control`（nested）+ `com.openjiuwen.studio.dsl.exec` + `SubWorkflowResolver` + `com.openjiuwen.studio.dsl.config` |
| §2 变量作用域 | `com.openjiuwen.studio.dsl.exec`（`WorkflowVariableScope`）+ `com.openjiuwen.studio.dsl.adapter.control`（setVariable） |
| §2 / §3 失败表面 | `com.openjiuwen.studio.dsl.model`（`NodeCauseCode`）+ `com.openjiuwen.studio.dsl.exec`（`NodeExecutionException`） |
| §1 / §5.6「只消费装配产物」 | `com.openjiuwen.studio.dsl.model`（`Assembled*`）；不做全量 DSL 装载 |
| §3「不新增 HTTP」 | 全模块无 serve；入口是 `com.openjiuwen.studio.dsl` + 可选 `com.openjiuwen.studio.dsl.autoconfigure` |

阅读提示：

- 需求正文点名的能力 → 主要是 `com.openjiuwen.studio.dsl.contract` / `com.openjiuwen.studio.dsl.registry` / `com.openjiuwen.studio.dsl.adapter.*` / `com.openjiuwen.studio.dsl.exec` / `com.openjiuwen.studio.dsl.model` / `com.openjiuwen.studio.dsl.python`
- `com.openjiuwen.studio.dsl.config` / `com.openjiuwen.studio.dsl.autoconfigure` / `com.openjiuwen.studio.dsl.schema` / `com.openjiuwen.studio.dsl.util` / 根包 → 落地与接线，对应 §3 配置 / 注册入口或 L2 细化，不是另开一套 FEAT 外特性

---

## 相关文档

- 模块总览与接入：[`README.md`](./README.md)
- 假宿主示例：[`../../example/agentcore-ext-studio-dsl-host-demo`](../../example/agentcore-ext-studio-dsl-host-demo)
- 特性需求：仓库内 `低码转高码需求/0819doc/docs/develop/02-features/FEAT-031-studio-dsl-node-type-extension.md`
- Python 节点覆盖对照：[`PYTHON-NODE-COVERAGE.md`](./PYTHON-NODE-COVERAGE.md)
