# agent-core-ext-studio-dsl

Studio 低码 DSL 的节点执行 SDK（FEAT-031）：把宿主已组装的 `AssembledNode` / `AssembledWorkflow` 转成 AgentCore `ComponentExecutable`，提供 21 种 `jiuwen.*` 内置节点、Python 代码节点与可区分失败码；另含 L2 扩展 3 种 `EI.*`（合计模块内 24 种）。

类库，非独立应用。不负责 DSL/IR 加载、HTTP 入口、边调度或完整编排（由宿主 runtime / 装配方承接，装配方文档待建）。无 ServiceLoader，内置节点不可被覆盖。


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
- 多节点映射：`module.mapExecutables(workflow, ctx)`；生产建议传入 `tenantId`：`module.newRootContext(workflowExecId, tenantId)`
- 单测链式跑通：`src/test/.../LinearWorkflowTestSupport`（非生产 API）
- 未知节点类型 → `NodeExecutionException` + `NodeCauseCode.UNKNOWN_NODE_TYPE`

生产环境按需替换协作实现（示例）：

```java
StudioDslModule module = StudioDslModule.create(props)
        .withToolRegistry(realTools)
        .withSubWorkflowResolver(configs -> loadChildWorkflow(configs));

// 知识检索（全局 wiring，不经 NodeBuildContext；见下方「生产注意事项」）
KnowledgeBaseConfigProviders.setStorageProvider(obsStorage);
KnowledgeBaseConfigProviders.setSecretDecryptor(crypt);
```

### 生产注意事项

**Python 代码节点 — 勿用 `inprocess`**

默认 `StudioDslNodeProperties.localExecMode` 与 Python `LOCAL_CODE_EXEC_MODE` 对齐为 **`inprocess`**（共享系统临时目录、无租户/工作流隔离目录）。L2 D4 明确：**生产环境必须使用 `subprocess`**（或 `exec_env=sandbox` + 宿主注入 `PythonCodeRunners.setSandboxExecutor`）。

```java
StudioDslNodeProperties props = new StudioDslNodeProperties();
props.setLocalExecMode("subprocess"); // 生产必设
props.setPythonWorkdirRoot("/var/studio/python-workdirs"); // 建议按 tenant/workflow 隔离
StudioDslModule module = StudioDslModule.create(props);
```

**FlowApi / Plugin — `X-Auth-Token`**

当 IR `auth.scope=USER` 且要求 `X-Auth-Token` 时，Java 与 Python 均会回落到占位值 `defaultUser|0`（常量 `FlowApiEngine.PYTHON_PARITY_AUTH_TOKEN_PLACEHOLDER`），**仅供开发/单测**。生产必须在 invoke 前通过 session 工作流参数注入真实令牌：

```java
// session 侧设置 runtime_auth_headers → apiId 或 "default" → header map
session.updateState(Map.of(
    "runtime_auth_headers",
    Map.of("default", Map.of("X-Auth-Token", realUserId + "|" + realTenantId))));
```

已注入的 `X-Auth-Token` 不会被占位值覆盖。

**知识检索 — 全局 Provider 与多租户**

`KnowledgeBaseConfigProviders` 为 **JVM 级静态** wiring（与 Python `set_kb_provider` 一致），**不能**按 `StudioDslModule` 实例隔离。多租户宿主应：(1) 在 `KnowledgeBaseConfigProvider` 内按请求上下文路由；(2) 或节点 inline `kbConfig`；(3) 或进程/ClassLoader 隔离。

- `setProvider(null)` → 重置为 `ObsKnowledgeBaseConfigProvider`
- `setStorageProvider(null)` → 清除 storage（未配置时 `storage()` 抛错）
- 单测重置：`KnowledgeBaseConfigProviders.resetToDefaults()`

## 内置节点（24）

| 组 | IR 类型（节选） |
| --- | --- |
| 控制 | `start` / `end` / `branch` / `loop` / `aggregate` / `subWorkflow` / `setVariable` / `exception` |
| 模型 | `LLMComponent` / `intentDetection` / `extractor` / `knowledgeRetrieval` |
| 交互 | `input` / `message` / `card` / `questioner`；`EI.qa`（L2） |
| 外部 | `code`（仅 Python，`main(args)->dict`）/ `plugin` / `mcp` / `agent` / `streamTransform`；`EI.ParamOutput` / `EI.ComplexIntentDetection`（L2） |

FEAT-031 正式 MUST 为 21 种 `jiuwen.*`；3 种 `EI.*` 为 L2 扩展验收范围。内置节点在模块内固定注册，不可覆盖；自定义节点 / Java SPI 不在本特性范围。

## 宿主契约（`contract`）

| 接口 | 用途 | 注入方式 | 默认（`create()`） |
| --- | --- | --- | --- |
| `PythonCodeExecutor` | 代码节点 Python 执行 | `StudioDslModule` / `NodeBuildContext` | `SubprocessPythonCodeExecutor` |
| `ToolRegistry` | Plugin `apiId` 查 Tool | `withToolRegistry` → `NodeBuildContext` | `EmptyToolRegistry`（查不到） |
| `SubWorkflowResolver` | 嵌套子流 IR → `AssembledWorkflow` | `withSubWorkflowResolver` → `NodeBuildContext` | 未配置则 `SUBWORKFLOW_REF_INVALID` |
| `KnowledgeBaseConfigProvider` | KB 连接/库配置 | `KnowledgeBaseConfigProviders.setProvider`（JVM 全局） | `ObsKnowledgeBaseConfigProvider` |
| `KnowledgeStorageProvider` | OBS/存储读 KB JSON | `KnowledgeBaseConfigProviders.setStorageProvider`（JVM 全局） | 未配置则失败（除非节点 inline `kbConfig`） |
| `SecretDecryptor` | 解密 OBS 中 SECRET 参数 | `KnowledgeBaseConfigProviders.setSecretDecryptor` | 不解密，原样使用 |

`NodeHandlerFactory` 为模块内部契约（24 内置 Handler），**不是**宿主扩展 SPI。

## 边界

**做**：节点注册与 invoke、变量作用域、嵌套深度、多模态透传、`NodeCauseCode` 失败表面。

**不做**：图调度、IR 引用解析、协议入口、远端 MCP/Agent/KB 真实现（由宿主注入 `contract` 或环境配置）。

### 出站 URL 信任边界（SSRF）

`FlowApi` / `MCP` / `SSE` 等节点会对 IR 配置中的 URL 做出站校验（默认拒绝私网/环回；可通过 `studio.dsl.outbound.allowPrivate=true` 放宽，**仅限开发/单测**）。

- **信任假设**：URL 来自宿主已校验的工作流 IR，本模块不再做 DSL 级引用解析或二次鉴权。
- **宿主职责**：生产环境应在网络层隔离出站（防火墙 / egress proxy / 专用 VPC），并限制谁可发布含外部 URL 的工作流。
- **单测**：Surefire 默认设置 `studio.dsl.outbound.allowPrivate=true`，以便 mock `localhost` 端点。

## 测试

模块烟雾：`StudioDslModuleTest`（含在默认 `mvn test` 中）。
