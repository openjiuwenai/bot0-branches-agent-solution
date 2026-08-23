# agent-core-ext-intent-suite

`agent-core-ext-intent-suite` 是面向 AgentCore DeepAgent 的意图匹配与结果路由 Java SDK。它将用户语义匹配到 Agent Card skill 或用户自定义意图，并执行绑定的结果函数。

本模块是纯 Java 类库，不包含 Spring Boot 自动配置、HTTP 服务或远端 Agent Card 发现能力。当前只支持 DeepAgent 场景；runtime 侧的 Agent Card 注册、远端调用和配置绑定由 `agent-runtime-ext-java` 提供。

## 依赖

业务工程依赖具体制品，不依赖 `agent-core-ext-java` 聚合 POM：

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-ext-intent-suite</artifactId>
    <version>0.1.0</version>
</dependency>
```

当前模块按父 POM 使用 `agent-core-java` `0.1.14.post1`。依赖版本应与实际发布制品和 runtime 版本保持配套。

本地构建和测试：

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -pl :agent-core-ext-intent-suite -am clean test
```

## 30 秒上手

下面的示例使用默认初始化器和一个自定义匹配器。生产环境可以将 `IntentMatcher` 替换为
`RerankerIntentMatcher`，并传入 AgentCore 的 `Reranker` 实例。

```java
IntentSuiteConfig config = IntentSuiteConfig.builder()
        .matchThreshold(0.65D)
        .build();

IntentSuite suite = IntentSuite.builder(config)
        .initializer(new DefaultIntentInitializer())
        .matcher(context -> {
            // 根据 context.routingSemantic() 从 context.catalogSnapshot() 中选择一个意图。
            return Optional.empty();
        })
        .build();

CustomIntentRegistration calculator = new CustomIntentRegistration(
        "calculator",
        "对数字执行加减乘除等算术计算",
        context -> new InvokeToolAction("calculator",
                Map.of("query", context.routingSemantic())));

CustomIntentRegistration fallback = new CustomIntentRegistration(
        "fallback",
        "",
        context -> new FinishAction(
                Map.of("status", "UNMATCHED"),
                "未匹配到可执行的能力，请补充说明。"));

suite.replaceCatalog(new IntentCatalogInput(
        List.of(),
        List.of(calculator),
        fallback));

IntentDeepAgentBinder binder = new IntentDeepAgentBinder();
binder.bind(deepAgent, suite);
```

`bind` 会向 DeepAgent 注入名为 `intent_match` 的虚拟工具和意图路由提示词。重复绑定同一个
Suite 是幂等的；一个 DeepAgent 不能绑定两个不同的 Suite。

## 初始化目录

### Agent Card

`AgentCardInput` 由协议标准的 A2A `AgentCard` 和 runtime 中稳定的远端 Agent 名称组成。默认
初始化器会为 Agent Card 中每个支持文本输入的 skill 创建一个可匹配意图，多个 skills 会生成多个
意图项：

```java
AgentCard card = loadAgentCard();
AgentCardInput remote = new AgentCardInput(card, "balance-agent");

suite.replaceCatalog(new IntentCatalogInput(
        List.of(remote),
        List.of(calculator),
        fallback));
```

Agent Card 意图使用内置的 `A2ADelegateIntentResultFunction`，结果为：

```text
InvokeToolAction(
    toolName = "a2a_delegate",
    arguments = {"agentName": "balance-agent", "remoteInput": <routing semantic>}
)
```

`a2a_delegate` 是 runtime 侧约定的目标工具。仅使用 core JAR 时，Suite 可以生成该动作，但不能
执行远端请求；需要 runtime-ext 安装对应的 A2A delegate 适配和远端 Agent 注册。

### Custom intent 和 fallback

custom intent 必须提供唯一 ID、用于匹配的 description 和 `IntentResultFunction`。结果函数是同步
回调，接收完整的 `IntentExecutionContext`，并返回一个 `IntentAction`：

| 动作 | 行为 |
| --- | --- |
| `InvokeToolAction` | 将意图调用改写为 DeepAgent 中已注册的本地 Tool；Tool 的完整参数由结果函数生成 |
| `ReturnAction` | 将结果作为意图工具结果返回给模型 |
| `FinishAction` | 将结果写入工具消息，并直接结束当前 Agent 回合，适合 fallback 或固定答复 |

fallback 不参与匹配。只有 matcher 没有返回匹配项时才执行 fallback；没有 fallback 时，Suite 返回
`UNMATCHED`。

## 动态更新目录

Suite 创建时会先使用空目录完成初始化。Agent Card 或 custom intent 准备好后，调用
`replaceCatalog` 提交完整目录：

```java
suite.replaceCatalog(new IntentCatalogInput(
        latestAgentCards,
        latestCustomIntents,
        latestFallback));
```

每次替换都是全量替换并生成新的目录版本，正在执行的请求继续使用其开始时的目录快照。调用方负责
维护完整的 Agent Card、custom intent 和 fallback 集合；runtime 的注册中心事件不属于本模块 API。

## 自定义 SPI

- `IntentInitializer`：将 `IntentCatalogInput` 转换为不可变的 `InitializedIntents`。默认实现是
  `DefaultIntentInitializer`。
- `IntentMatcher`：使用 `IntentExecutionContext` 从当前目录选择一个 matchable intent，返回
  `Optional.empty()` 表示未匹配。默认实现是 `RerankerIntentMatcher`。
- `IntentResultFunction`：使用已选中的 intent 和完整上下文生成 `IntentAction`。它必须是同步回调；
  需要用户中断和恢复的业务应注册为 DeepAgent Tool，并使用 AgentCore 现有 Rail 中断机制处理。

三个 SPI 在 Suite 中按“初始化目录 -> 匹配 -> 执行选中意图结果函数”的顺序协同工作。开发者通常只
需要构造一个 Suite，再将它绑定到 DeepAgent。

## DeepAgent 和 runtime 边界

Core 模块负责：

- 意图目录模型、初始化器、matcher 和结果函数 SPI；
- `intent_match` 工具、路由 Rail 和提示词 Rail；
- Agent Card skill 到 `a2a_delegate` 动作的转换；
- 本地已注册 Tool 的动作改写。

Runtime-ext 负责：

- 从配置或远端 Agent Card 注册表获取 Agent Card；
- 将 Agent Card 快照转换为 `IntentCatalogInput` 并调用 `replaceCatalog`；
- 提供 `a2a_delegate` 的实际远端调用和中断续接；
- 将 Spring 配置和 Bean 绑定到 core 的 SPI。

core 不保证自动注册本地 Tool，也不直接执行 `a2a_delegate`。使用 `InvokeToolAction` 时，目标本地
Tool 必须已经注册到 DeepAgent；使用远端 Agent 时必须配套 runtime-ext 的 delegate 适配。

## 参考示例

完整的多 Agent 银行路由示例位于
[`common/example/bank-intent-routing-a2a-demo`](../../example/bank-intent-routing-a2a-demo)。该示例展示
Agent Card 路由、本地工具、fallback、同 Task 中断续接、意图跳变、语义指代和多目标计划。
