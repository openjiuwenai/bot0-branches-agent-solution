# Agent Core Ext Java

`agent-core-ext-java` 是 [openJiuwen agent-core-java](https://gitcode.com/openJiuwen/agent-core-java)
的独立扩展工程。它用于承载基于 AgentCore 公共扩展点实现的 Java SDK，在不修改
`agent-core-java` 源码的前提下补充可复用能力。

本工程位于 `agent-solution/common`，与同目录下的其他工程分别构建，不属于统一的 Maven
reactor。它不是可独立启动的应用，也不负责 Agent Runtime 的协议入口、服务启动或远端
Agent 适配；运行时相关扩展见 [`../agent-runtime-ext-java`](../agent-runtime-ext-java)。

## 工程组织

根目录的 `pom.xml` 是独立的 Maven 聚合 POM，负责声明子模块、公共依赖和构建配置。
实际可被业务工程使用的代码位于各子模块中。

以下信息以对应文件为准，避免在本文档中重复维护：

- 精确的子模块清单、Java 要求和依赖版本：根目录及子模块的 `pom.xml`。
- 模块能力、接入方式和行为边界：各子模块的 `README.md`。
- 配置项和测试前置条件：模块源码、测试说明和示例工程。

## 当前模块与功能

| 模块 | 类型 | 作用 |
| --- | --- | --- |
| `agent-core-ext-java` | Maven 聚合父工程 | 统一组织 AgentCore 扩展子模块及其公共构建配置 |
| [`react-rails`](react-rails/README.md) | Java SDK | 为 AgentCore 的 ReAct Agent 补充认知控制、收敛和可观测能力 |

`react-rails` 当前覆盖以下功能方向：

- **结果验证与收敛**：根据成功条件检查 Agent 输出，在未满足条件时触发纠正、重规划或降级。
- **重规划与上下文治理**：识别重规划意图、限制重复尝试，并压缩失败过程产生的上下文。
- **停滞检测与完成检查**：发现无进展的循环，在结束前检查关键条件是否已经满足。
- **异常降级与自恢复**：在工具或执行阶段发生异常时保留根因信息，并以可识别状态结束流程。
- **模型行为约束**：通过模型包装和提示词策略约束阶段行为及工具调用。
- **可观测性**：对验证、重规划、上下文压缩、异常和终止等关键状态变化提供结构化事件。

这些扩展按需组合，不要求业务应用一次性启用全部功能。具体接入顺序、输入输出和限制以
[`react-rails` 模块文档](react-rails/README.md)为准。

## 前置条件

- 安装满足父 POM 要求的 JDK 和 Maven。
- Maven 能够解析父 POM 声明的 `agent-core-java` 依赖。

如果所需的 AgentCore 制品尚未发布到当前环境可访问的 Maven 仓库，先在匹配版本的
`agent-core-java` 源码仓中执行：

```bash
mvn clean install
```

依赖版本应始终以本工程 POM 为准。

## 构建与安装

在 `agent-solution` 仓库根目录执行完整构建：

```bash
mvn -f common/agent-core-ext-java/pom.xml clean test
```

生成各子模块制品：

```bash
mvn -f common/agent-core-ext-java/pom.xml clean package
```

安装到本地 Maven 仓库，供本机其他工程引用：

```bash
mvn -f common/agent-core-ext-java/pom.xml clean install
```

构建单个子模块时，使用 Maven reactor 选择器，并通过 `-am` 同时构建它依赖的工程：

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -pl :<artifactId> -am clean install
```

具体 `artifactId` 从父 POM 的 `<modules>` 和对应子模块 POM 中获取。

## 在业务工程中使用

业务工程应依赖所需的具体子模块，不要把聚合父 POM 当作运行时类库。依赖坐标和接入代码
以目标子模块的 POM 与 README 为准，依赖版本应与实际构建或发布的制品保持一致。

本工程中的模块默认作为类库使用。业务应用负责创建和配置 AgentCore 对象，并按模块文档
显式接入所需扩展。不要假定扩展模块会自动创建 Agent、启动服务或提供统一的 Spring Boot
自动配置。

## 测试与质量检查

默认测试由 Maven 生命周期执行。部分子模块可能包含需要外部服务、模型或凭据的集成测试，
其启用条件以对应测试说明为准。未满足前置条件而跳过的测试，不应表述为已经执行通过。

格式化、静态检查和其他质量插件是否绑定到默认生命周期，以各模块 POM 为准。提交修改前
应执行受影响模块要求的检查，并确认没有因全目录格式化引入无关变更。

## 打包与发布

`package` 生成的制品位于各子模块的 `target` 目录；`install` 会进一步将制品安装到本地
Maven 仓库。聚合 POM 本身不提供可执行程序，子模块产物是否可执行以模块 POM 和文档为准。

远端发布使用 `deploy`，目标仓库、认证和发布策略由实际发布环境提供。发布前应确认：

- 依赖版本能够从目标环境解析。
- 模块测试和质量检查满足仓库要求。
- 发布制品与 POM 中声明的坐标一致。
- 依赖方使用的版本与已发布制品一致。

## 兼容性

扩展模块直接依赖 AgentCore 的公共扩展接口。升级 AgentCore 依赖时，应重点验证回调顺序、
状态传递、终止语义、模型包装和工具注册等集成边界，并运行受影响模块的完整测试。

版本号和依赖矩阵不在本文档中固化，统一以 Maven POM 为单一事实来源。本文档中的模块与
功能概览用于说明当前工程边界；新增、移除模块或改变能力边界时应同步更新。
