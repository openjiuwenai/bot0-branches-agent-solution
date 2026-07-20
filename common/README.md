# common 目录说明

`common` 存放相互独立的扩展工程、Agent 自进化模块和配套示例。各工程分别构建；Java 工程之间不存在统一的 Maven reactor 聚合关系。

## 目录结构

```text
common
|-- agent-core-ext-java
|   `-- react-rails
|-- agent-runtime-ext-java
|   `-- agent-service-adapters
|       |-- agent-service-adapters-agentcore-ext
|       `-- agent-service-adapters-versatile
|-- agents
|   `-- pev
|-- agent-evolve
|   |-- evoagent
|   `-- evoagent-adapter
`-- example
    |-- agentcore-ext-deepagent-remote-a2a-demo
    |-- agentcore-ext-remote-a2a-tool-demo
    `-- versatile-a2a-adapter-demo
```

## 扩展工程

- `agent-runtime-ext-java`：运行时扩展模块的 Maven 父工程。
- `agent-core-ext-java`：`agent-core-java` 的纯 SDK 扩展工程，当前包含 `react-rails`。
- `agents`：具体 Agent 实现工程，当前包含 PEV Agent。
- `agent-evolve/evoagent`：Skill/managed-doc 自进化服务，Python 3.12 + uv 独立工程。
- `agent-evolve/evoagent-adapter`：日志、Skill 与 managed-doc sidecar，Python 3.12 + uv 独立工程。
- `agent-service-adapters-agentcore-ext`：AgentCore adapter 的增强模块，复用 runtime 的远端 A2A card 注册发现结果，在 AgentCore handler 执行链路前补充远端工具注入。
- `agent-service-adapters-versatile`：Versatile adapter，把查询请求适配到远端 HTTP/SSE 工作流服务。

## 配套示例

- `example/agentcore-ext-remote-a2a-tool-demo`：AgentCore ext 远端 A2A 工具注入示例。
  - `agent-a-deepagent-runtime`：Agent A，使用 `agent-service-adapters-agentcore-ext` 的 DeepAgent runtime。
  - `agent-b-versatile-runtime`：Agent B，通过 A2A 暴露的 Versatile runtime。
- `example/agentcore-ext-deepagent-remote-a2a-demo`：DeepAgent 到 DeepAgent 的远端 A2A 委托示例。
  - `agent-a-deepagent-runtime`：Agent A，通过注入的远端 A2A 工具委托 Agent B。
  - `agent-b-deepagent-runtime`：Agent B，通过 A2A 暴露 DeepAgent runtime。
- `example/versatile-a2a-adapter-demo`：Versatile adapter 的独立查询和 A2A 请求示例。

## 编译打包流程

### 正式Release版本

如果依赖的 openJiuwen runtime 和 openJiuwen core 已经正式发布，直接在本仓构建 `common` 扩展和示例：

```powershell
mvn -f common\agent-core-ext-java\pom.xml `
  clean install

mvn -f common\agents\pom.xml `
  clean install

mvn -f common\agent-runtime-ext-java\pom.xml `
  clean install

mvn -f common\example\versatile-a2a-adapter-demo\pom.xml `
  clean install

mvn -f common\example\agentcore-ext-remote-a2a-tool-demo\pom.xml `
  clean install

mvn -f common\example\agentcore-ext-deepagent-remote-a2a-demo\pom.xml `
  clean install
```

`common` 和 example 都不跳过测试。

Agent 自进化模块分别验证：

```bash
cd common/agent-evolve/evoagent && uv sync --all-extras && uv run pytest tests/
cd common/agent-evolve/evoagent-adapter && uv sync && uv run pytest tests/
```

### 非正式Release版本

非正式Release版本可能依赖的是 openJiuwen core 或 openJiuwen runtime 的非正式版本、开发分支，先安装依赖仓库，再构建本仓。

先在 openJiuwen core 仓库执行(目前依赖feature/630分支)：

```powershell
mvn clean install
```

再安装 openJiuwen runtime(目前依赖develop分支)：

```powershell
mvn -f vendor\agent-runtime-java\pom.xml `
  clean install `
  "-DskipTests"
```

最后构建本仓 `common` 扩展和示例：

```powershell
mvn -f common\agent-core-ext-java\pom.xml `
  clean install

mvn -f common\agents\pom.xml `
  clean install

mvn -f common\agent-runtime-ext-java\pom.xml `
  clean install

mvn -f common\example\versatile-a2a-adapter-demo\pom.xml `
  clean install

mvn -f common\example\agentcore-ext-remote-a2a-tool-demo\pom.xml `
  clean install

mvn -f common\example\agentcore-ext-deepagent-remote-a2a-demo\pom.xml `
  clean install
```
