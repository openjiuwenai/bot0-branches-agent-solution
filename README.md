# openJiuwen agent-solution

[Chinese Version](README.zh.md) | [English Version](README.md)

## Introduction

**openJiuwen agent-solution** is an openJiuwen extension solution repository for Agent application integration and general industry scenarios.

The current version contains three independent extension projects: runtime adapters, pure AgentCore SDK extensions, and concrete Agent implementations. This repository does not reimplement runtime capabilities such as HTTP ingress, A2A protocol support, remote card discovery and communication, or session orchestration. Those capabilities are provided by `agent-runtime-java`; the Agent execution core is provided by `agent-core-java`.

## Quick Start

### Requirements

- **Java**: JDK 17+
- **Build tool**: Maven 3.9+
- **Runtime dependency**: `com.openjiuwen:agent-runtime-java:0.1.0`
- **Execution core dependency**: `com.openjiuwen:agent-core-java:0.1.13`

### Build Extension Modules

```powershell
mvn -f common\agent-core-ext-java\pom.xml clean install
mvn -f common\agents\pom.xml clean install
mvn -f common\agent-runtime-ext-java\pom.xml clean install
```

### Build Example Projects

```powershell
mvn -f common\example\versatile-a2a-adapter-demo\pom.xml clean install
mvn -f common\example\agentcore-ext-remote-a2a-tool-demo\pom.xml clean install
mvn -f common\example\agentcore-ext-deepagent-remote-a2a-demo\pom.xml clean install
mvn -f common\example\multi-deep-research-demo\pom.xml clean install
```

## Architecture

The projects under `common` are peers and are built separately. They have no Maven parent or reactor aggregation relationship with one another.

| Module | Description |
|--------|-------------|
| `common/agent-runtime-ext-java` | Maven parent project for runtime extensions. It currently contains the AgentCore extension adapter and the Versatile adapter. |
| `common/agent-core-ext-java` | Pure SDK extensions for `agent-core-java`; currently aggregates the Spring-free `react-rails` feature jar. |
| `common/agents` | Concrete Agent implementations; currently aggregates the self-contained PEV Agent. |
| `agent-service-adapters-agentcore-ext` | Reuses remote A2A card registration results discovered by the runtime, injects remote agents as tools before the AgentCore handler executes, and delegates remote calls through `a2a_delegate` interrupts. |
| `agent-service-adapters-versatile` | Implements the runtime `AgentHandler` SPI and adapts query requests to remote HTTP/SSE workflow services. |
| `common/example` | Example projects for runtime extension adapters, A2A exposure, remote delegation, and runtime wiring. |

Design details:

- [agent-service-adapters-agentcore-ext-design.md](common/agent-runtime-ext-java/doc/agent-service-adapters-agentcore-ext-design.md)
- [agent-service-adapters-versatile-design.md](common/agent-runtime-ext-java/doc/agent-service-adapters-versatile-design.md)

## Features

- **AgentCore remote A2A tool injection**: installs remote agents discovered from runtime remote agent cards as AgentCore-visible tools.
- **Interrupt mechanism**: converts remote tool calls into delegate interrupts that can be handled by the runtime, and injects remote results back into AgentCore after resume.
- **Versatile HTTP/SSE adaptation**: converts runtime query requests into remote workflow service calls and consumes SSE or line-stream responses.
- **ReAct cognitive rails**: explicit Java rails for verification, replanning, and failure degradation without automatic framework wiring.
- **PEV Agent**: a self-contained Plan-Execute-Verify-Diagnose-Dispatch implementation built on `agent-core-java`.

## Project Structure

```text
agent-solution
|-- common
|   |-- agent-core-ext-java
|   |   `-- react-rails
|   |-- agent-runtime-ext-java
|   |   `-- agent-service-adapters
|   |       |-- agent-service-adapters-agentcore-ext
|   |       `-- agent-service-adapters-versatile
|   |-- agents
|   |   `-- pev
|   `-- example
|       |-- agentcore-ext-deepagent-remote-a2a-demo
|       |-- agentcore-ext-remote-a2a-tool-demo
|       |-- multi-deep-research-demo
|       `-- versatile-a2a-adapter-demo
|-- LICENSE
|-- README.en.md
`-- README.md
```

## Examples

```text
common/example
|-- agentcore-ext-deepagent-remote-a2a-demo
|-- agentcore-ext-remote-a2a-tool-demo
|-- multi-deep-research-demo
`-- versatile-a2a-adapter-demo
```

## Maven Coordinates

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-agentcore-ext</artifactId>
    <version>0.1.0</version>
</dependency>

<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-adapters-versatile</artifactId>
    <version>0.1.0</version>
</dependency>

<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>react-rails</artifactId>
    <version>0.1.0</version>
</dependency>

<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>pev</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Contributing

We welcome issues, pull requests, design discussions, documentation improvements, code contributions, and usage feedback. See [CONTRIBUTING.md](CONTRIBUTING.md) before contributing.

## License

This project is licensed under the [Apache License 2.0](LICENSE).
