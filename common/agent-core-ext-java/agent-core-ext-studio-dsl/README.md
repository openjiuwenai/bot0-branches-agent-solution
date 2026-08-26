# agent-core-ext-studio-dsl

Studio 低码 DSL 的节点执行 SDK（FEAT-031）：把宿主已组装的 `AssembledNode` / `AssembledWorkflow` 转成 AgentCore `ComponentExecutable`，提供 21 种 `jiuwen.*` + 3 种 `EI.*` 内置节点、Python 代码节点与可区分失败码。

类库，非独立应用。不负责 DSL/IR 加载、HTTP 入口、边调度或完整编排（宿主 / FEAT-027+ 承接）。无 ServiceLoader，内置节点不可被覆盖。

## 依赖与构建

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-ext-studio-dsl</artifactId>
    <version>0.1.0</version>
</dependency>
```

Java 17，配套 `agent-core-java` `0.1.14.post1`（以实际发布为准）。

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -pl :agent-core-ext-studio-dsl -am clean test
```

## 上手

```java
StudioDslModule module = StudioDslModule.create();
NodeBuildContext ctx = module.newRootContext("demo");
ComponentExecutable exec = module.registry().create(
        AssembledNode.of("v", "jiuwen.setVariable",
                Map.of("variableMapping", Map.of("greeting", "hello"))),
        ctx);
exec.invoke(Map.of("userFields", Map.of()), null, null);
```

- 入口：`StudioDslModule.create()`（编程式装配；本模块不提供 Spring Boot 自动配置）
- 多节点映射：`module.mapExecutables(workflow, ctx)`
- 单测链式跑通：`src/test/.../LinearWorkflowTestSupport`（非生产 API）
- 未知节点类型 → `NodeExecutionException` + `NodeCauseCode.UNKNOWN_NODE_TYPE`

## 内置节点（24）

| 组 | IR 类型（节选） |
| --- | --- |
| 控制 | `start` / `end` / `branch` / `loop` / `aggregate` / `subWorkflow` / `setVariable` / `exception` |
| 模型 | `LLMComponent` / `intentDetection` / `extractor` / `knowledgeRetrieval` |
| 交互 | `input` / `message` / `card` / `questioner`；`EI.qa` |
| 外部 | `code`（仅 Python，`main(args)->dict`）/ `plugin` / `mcp` / `agent` / `streamTransform`；`EI.ParamOutput` / `EI.ComplexIntentDetection` |

内置节点在模块内固定注册，不可覆盖；自定义节点类型扩展不在 FEAT-031 范围。

## 宿主契约（`contract`）

| 接口 | 用途 |
| --- | --- |
| `PythonCodeExecutor` | 代码节点 |
| `ToolRegistry` | Plugin |
| `SubWorkflowResolver` | 嵌套工作流 |
| `KnowledgeBaseConfigProvider` / `KnowledgeStorageProvider` / `SecretDecryptor` | 知识检索 |

生产环境在构造 `StudioDslModule` / `NodeBuildContext` 时注入真实实现；默认多为 in-memory 占位。

## 边界

**做**：节点注册与 invoke、变量作用域、嵌套深度、多模态透传、`NodeCauseCode` 失败表面。

**不做**：图调度、IR 引用解析、协议入口、远端 MCP/Agent/KB 真实现。

## 测试

模块烟雾：`StudioDslModuleTest`（含在默认 `mvn test` 中）。
