# agent-core-ext-studio-dsl

`agent-core-ext-studio-dsl` 是面向 Studio 低码 DSL 的节点类型扩展与节点执行 Java SDK（FEAT-031）。它把已组装的 `AssembledNode` / `AssembledWorkflow` 映射为 AgentCore 可执行的 `ComponentExecutable`，并提供内置 `jiuwen.*` / `EI.*` 节点、Java/Python 代码节点与可区分失败码。**无 ServiceLoader/无覆盖内置节点。**

本模块是类库，不是可独立启动的应用。它不负责 DSL 加载、HTTP 入口、边调度或完整工作流编排；这些由宿主（Studio 运行时 / 业务工程）提供。可选的 Spring Boot 自动配置在 classpath 存在时可用，没有 Spring 时用 `StudioDslModule` 编程式接入即可。

## 依赖

业务工程依赖具体制品，不依赖 `agent-core-ext-java` 聚合 POM：

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-ext-studio-dsl</artifactId>
    <version>0.1.0</version>
</dependency>
```

当前模块按父 POM 使用 `agent-core-java` `0.1.14.post1`，Java 17。依赖版本应与实际发布制品保持配套。

本地构建和测试：

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -pl :agent-core-ext-studio-dsl -am clean test
```

## 30 秒上手

宿主手组 `AssembledWorkflow`，用 `StudioDslModule` + `WorkflowAssemblyBridge.executeLinear` 跑线性路径（会在结束时关闭变量作用域）：

```java
StudioDslModule module = StudioDslModule.create();
AssembledWorkflow wf = new AssembledWorkflow(
        "demo",
        List.of(
                AssembledNode.of("s", "jiuwen.start", Map.of()),
                AssembledNode.of(
                        "v",
                        "jiuwen.setVariable",
                        Map.of("variableMapping", Map.of("greeting", "hello"))),
                AssembledNode.of("m", "jiuwen.message", Map.of("message", "say ${greeting}")),
                AssembledNode.of("e", "jiuwen.end", Map.of())));

NodeBuildContext ctx = module.newRootContext("demo", "tenant-demo");
Map<String, Object> out =
        module.assemblyBridge().executeLinear(wf, ctx, Map.of("seed", 1), null, null);
```

未知 `jiuwen.*` 类型会以 `NodeExecutionException` + `NodeCauseCode.UNKNOWN_NODE_TYPE` 失败，便于宿主区分配置错误与执行错误。

## 内置节点

`BuiltinNodeBootstrap` 注册 21 种 FEAT-031 节点 + 3 种 `EI.*`（共 24，按组）：

| 组 | 类型 |
| --- | --- |
| 控制 | `jiuwen.start` / `end` / `branch` / `loop` / `aggregate` / `nestedWorkflow` / `setVariable` / `exception` |
| 模型 | `jiuwen.LLMComponent`（别名含 `jiuwen.llm` 等）/ `intentDetection` / `extractor` / `knowledgeRetrieval` |
| 交互 | `jiuwen.input` / `message` / `card` / `questioner`；`EI.qa` |
| 外部 | `jiuwen.code` / `plugin` / `mcp` / `agent` / `streamTransform`；`EI.ParamOutput` / `EI.ComplexIntentDetection` |

宿主可通过 `NodeTypeRegistry.register(NodeHandlerFactory)` **显式**注册自定义类型（不能覆盖已有 canonical）。内置类型仅由 `BuiltinNodeBootstrap` 注册；**已移除** ServiceLoader、`replace` / `registerOrReplace`。

## 代码节点

`jiuwen.code` 支持两条路径：

- **Java**：宿主向 `CodeLogicRegistry` **显式**注册 `CodeLogic`，节点配置 `codeLogicRef` 指向注册名。
- **Python**：默认 `SubprocessPythonCodeExecutor` 在隔离工作目录中子进程执行；用户脚本需定义 `main(args: dict) -> dict`，stdout 仅输出 JSON。

两条路径同时可解析时，模块会以可区分失败码拒绝歧义配置。

## 宿主契约（`contract` 包）

构造期注入点（非插件发现）：

| 契约 | 作用 |
| --- | --- |
| `NodeHandlerFactory` | 节点类型工厂（内置实现 + 显式 register） |
| `CodeLogic` / `CodeLogicRegistry` | Java 代码节点逻辑 |
| `PythonCodeExecutor` | Python 执行器（默认可换） |
| `CoreExecutableFactory` | 桥接 AgentCore LLM / Questioner / Knowledge 等可执行体 |
| `McpToolInvoker` / `ToolRegistry` | MCP / Plugin 工具调用 |
| `AgentInvoker` / `AgentRegistry` | Agent 节点调用 |
| `SubWorkflowResolver` | 嵌套子工作流解析 |

默认实现偏 in-memory / 配置驱动；生产宿主应在构造 `StudioDslModule` / `NodeBuildContext` 时注入真实模型、MCP、Agent 与子工作流解析。

## 模块结构

各 package 的职责说明见 [`PACKAGES.md`](./PACKAGES.md)。

```
src/main/java/com/openjiuwen/studio/dsl/
├── StudioDslModule.java          编程式入口
├── adapter/                      内置节点（control / model / interact / external）
├── contract/                     宿主构造期注入契约（非 ServiceLoader）
├── registry/                     NodeTypeRegistry / CodeLogic / Builtin bootstrap
├── exec/                         构建上下文、装配桥、变量作用域、失败异常
├── model/                        Assembled* / NodePayload / NodeCauseCode / MediaPart
├── python/                       子进程 Python 执行
├── bridge/                       Core / MCP / Agent 默认桥接
├── config/                       节点与模块配置
├── autoconfigure/                可选 Spring Boot 自动配置
├── schema/                       DSL/IR 壳校验
└── util/                         深拷贝、条件、路径、模板、媒体
```

## 边界

本模块负责：

- 节点类型注册、组装节点到 `ComponentExecutable`；
- 线性执行辅助（`executeLinear`）与变量作用域关闭；
- 多模态 payload 透传、嵌套深度守卫、可区分 `NodeCauseCode`。

本模块不负责：

- Studio DSL / IR 全量加载与图边调度；
- HTTP / 协议入口、服务启动；
- 远端 MCP / Agent / 知识库的真实实现（只提供 `contract` 注入点与默认占位）。

## 参考示例

假宿主演示（无 DSL loader / HTTP / 边调度）位于
[`common/example/agentcore-ext-studio-dsl-host-demo`](../../example/agentcore-ext-studio-dsl-host-demo)。
先安装本模块，再在示例目录执行：

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -pl :agent-core-ext-studio-dsl -am install -DskipTests
mvn -f common/example/agentcore-ext-studio-dsl-host-demo/pom.xml package exec:java
```

Package 说明与 FEAT 对照见 [`PACKAGES.md`](./PACKAGES.md)。  
Python `workflow_node` 覆盖对照见 [`PYTHON-NODE-COVERAGE.md`](./PYTHON-NODE-COVERAGE.md)。
