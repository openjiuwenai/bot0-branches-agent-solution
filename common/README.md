# common 目录说明

`common` 存放本仓的通用扩展模块和配套示例，主要分为运行时扩展工程和 example 两部分。

## 目录结构

```text
common
|-- agent-runtime-ext-java
|   `-- agent-service-adapters
|       |-- agent-service-adapters-agentcore-ext
|       `-- agent-service-adapters-versatile
`-- example
    |-- agentcore-ext-remote-a2a-tool-demo
    `-- versatile-a2a-adapter-demo
```

## 扩展工程

- `agent-runtime-ext-java`：运行时扩展模块的 Maven 父工程。
- `agent-service-adapters-agentcore-ext`：AgentCore adapter 的增强模块，在 AgentCore handler 执行链路前补充远端 A2A card 发现和远端工具注入。
- `agent-service-adapters-versatile`：Versatile adapter，把查询请求适配到远端 HTTP/SSE 工作流服务。

## 配套示例

- `example/agentcore-ext-remote-a2a-tool-demo`：AgentCore ext 远端 A2A 工具注入示例。
  - `agent-a-deepagent-runtime`：Agent A，使用 `agent-service-adapters-agentcore-ext` 的 DeepAgent runtime。
  - `agent-b-versatile-runtime`：Agent B，通过 A2A 暴露的 Versatile runtime。
- `example/versatile-a2a-adapter-demo`：Versatile adapter 的独立查询和 A2A 请求示例。

## 构建

在仓库根目录执行：

```powershell
mvn "-Dmaven.repo.local=.m2\repository" -f common\agent-runtime-ext-java\pom.xml clean install "-DskipTests"
mvn "-Dmaven.repo.local=.m2\repository" -f common\example\agentcore-ext-remote-a2a-tool-demo\pom.xml clean package "-DskipTests"
```
