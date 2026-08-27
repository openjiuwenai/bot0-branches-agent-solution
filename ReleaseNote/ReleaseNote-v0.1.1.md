# v0.1.1 Release Note

Release Date: August 30, 2026

---

Welcome to OpenJiuwen Agent Solution v0.1.1! Centered on the agent service invocation chain, this release delivers end-to-end standardized capabilities across five parts: the Agent Client, Agent Bus, Agent Runtime, Core Framework Capability Extensions, and Agent Self-Evolution Engine. It also completes two engineering optimizations — artifact size and dependency versions — and enhances the parallel execution orchestration of the general-purpose agent EDPAgent, making agent service integration simpler, collaboration smoother, evolution more autonomous, and delivery more frictionless.

---

## 🚀 New Features

### 🔌 Agent Client

The invocation entry point between applications and agent services, uniformly encapsulating service invocation, local tool collaboration, and streaming display, while shielding underlying details such as protocol adaptation and reconnection:

- **Standardized Service Invocation**: A unified API handles the creation, querying, and cancellation of agent invocations; supports breakpoint reconnection after link interruption and automatic circuit-breaker protection against infrastructure failures, so long-running task results are never lost.
- **Local Tool Collaboration**: Registered local tools can be driven and invoked by remote agents without being exposed to the server by default; observation-type operations execute automatically, while action-type operations execute only after authorization — keeping data secure and controllable.
- **Multi-Stream Demultiplexing**: When multiple agents collaborate, interleaved streaming outputs are automatically demultiplexed and rendered by source, with each stream attributable to a specific agent; the display resumes from where it left off after reconnection.

### 🚌 Agent Bus

Carries invocation and event flows between clients and agent services; the three component types — gateway, event bus, and registry/discovery center — can each be deployed independently and replaced as needed:

- **Invocation Routing and Forwarding**: Routes client invocations to target runtimes by agent ID, uniformly handling authentication and tenant identification; all invocation types — blocking, streaming, query, and cancellation — can be forwarded, and tasks can be resumed across instances after disconnection.
- **Bus Event Flow**: Invocations and responses can flow asynchronously through the event bus, decoupling clients from agent services; collaboration invocation events between agents also support bus forwarding.
- **Instance Route Query**: Queries available runtime instances by agent, supporting multiple instance candidates and version matching; when the registry is temporarily unavailable, invocations degrade automatically without interrupting business.

### ⚙️ Agent Runtime

This release brings six new capabilities to the runtime — intent-based call transfer via the Versatile controller, user interaction interruption and recovery, client-side tool invocation response, bus event subscription, invocation chain tracing, and task concurrency limiting — covering the entire chain of agent services from integration and interaction to production operation:

- **Intent-Based Call Transfer**: Connects to the Versatile controller to automatically recognize intent messages and invoke target agents; controller exceptions and rollback signals are automatically distinguished and handled, with session continuity maintained throughout.
- **Interaction Interruption and Recovery**: Tasks suspend while an agent awaits additional information from the user and resume from the breakpoint once the client submits input; the experience is consistent for local and remote agents.
- **Client-Side Tool Response**: When an agent needs to use a client's local tool, it pauses and sends a request; execution resumes automatically after the client submits the result.
- **Bus Event Subscription**: The runtime can subscribe to and consume bus events once embedded, with no additional sidecar components required.
- **Invocation Chain Tracing**: Cross-platform invocations automatically carry a unified trace ID, making invocation chains traceable end to end; trajectory data supports OpenTelemetry standard reporting — ready upon configuration and disabled by default.
- **Concurrency and Rate Limiting**: Supports configuring the maximum number of concurrent tasks; new tasks are automatically rejected under overload to protect the service quality of running tasks.

### 🧩 Core Framework Capability Extensions

This release adds two collaboration capabilities to the execution kernel: agent perception and task matching enable agents to discover one another and collaborate through task delegation, while dynamic client-side tool assembly enables agents to call client local tools on demand:

- **Agent Perception and Task Matching**: Agents automatically perceive other agents on the platform, precisely match by task semantics, and initiate delegated invocations; complex requests are decomposed first and then executed task by task.
- **Dynamic Client-Side Tool Assembly**: The tool visibility surface is assembled dynamically per task; once the agent selects a tool, execution is handed over to the runtime; tools are not cached and are not shared across tasks.

### 🧬 Agent Self-Evolution Engine

This release brings four new capabilities to the self-evolution engine — trajectory enrichment, the Agent evaluator, GEPA optimization algorithm adaptation, and northbound SkillHub integration — building on the "Data Backflow → Trajectory Evaluation → Optimization Engine" closed loop to further cover trajectory attribution in dynamic planning scenarios and offline iteration of Skill versions:

- **Trajectory Enrichment**: In dynamic planning scenarios, supports inline mapping between trajectory span nodes and skill / agent.md, achieving fine-grained correspondence between trajectories and Skills / AgentRules, and providing more precise data for attribution and optimization.
- **Agent Evaluator**: Supports the Agent-as-a-Judge evaluator with path recognition and chain attribution capabilities, enabling determination and attribution analysis of trajectory execution paths.
- **Prompt Optimizer**: Supports automatic iterative prompt optimization based on evaluation feedback, with the SkillOpt algorithm; this release adds support for the GEPA optimization algorithm.
- **Northbound SkillHub Integration**: Supports offline update and iteration of Skill versions through SkillHub integration, facilitating Skill version management and iteration.

### 🛠️ Engineering and Compatibility Optimizations

Two engineering optimizations for enterprise delivery environments, with artifact size and dependency versions fully adapted to enterprise pipelines:

- **Artifact Size Optimization**: The EDPAgent deliverable JAR size has been optimized to within the 200MB limit of enterprise pipelines, so deployment is no longer blocked.
- **Unified Open-Source Dependency Versions**: The open-source dependency versions of agent-core and agent-runtime are aligned, eliminating dependency inconsistencies between modules.

### 🤖 General-Purpose Agent

For enterprise scenarios, this release of EDPAgent Java, the general-purpose dynamic planning agent, focuses on execution orchestration, with complex tasks completed in parallel by multiple sub-agents:

- **Planning Workflow and Parallel Sub-Agent Execution**: The main agent plans once and launches multiple sub-agents in parallel within the same turn; once all results have returned, it performs aggregation reasoning in a single pass — eliminating serial waiting in multi-subtask scenarios.

---

## 📚 Related Documentation

- `common/README.md`: Directory overview and the build and packaging workflows for official and unofficial releases.

### 🔌 Agent Client

- `common/agent-client/README.md`: agent-client SDK module description and delivery boundary.
- `common/agent-client/docs/getting-started.md`: Quick start for the client SDK.
- `common/agent-client/docs/proposals/agent-client-v1-design.md`: Client SDK V1 design proposal (including the standard streaming response data protocol and multi-hop stream parsing).
- `common/example/agent-client-demo/README.md`: Packaging, startup, and request scripts for the client SDK verification project.

### 🚌 Agent Bus

- `common/agent-bus/README.md`: Responsibilities of the three agent bus components and the DIRECT / BUS forwarding relationships.
- `common/agent-bus/agent-gateway/README.md`: Gateway client invocation routing and forwarding, plus bus forwarding description.
- `common/agent-bus/event-bus/README.md`: Event bus invocation event forwarding description.
- `common/agent-bus/registry-discovery-center/README.md`: Registry/discovery center and runtime instance route query description.
- `common/example/agent-gateway-demo/README.md`: Gateway direct-connection and bus forwarding smoke test example.

### ⚙️ Agent Runtime

- `common/agent-runtime-ext-java/doc/features/`: Runtime extension feature descriptions (bus event subscription and consumption, client-side tool invocation response, Versatile intent-based call transfer, AgentScope interruption recovery, etc.).
- `common/agent-runtime-ext-java/doc/guides/`: Runtime extension integration guides (bus consumption integration, AgentCore extension tools, external runtime integration, etc.).
- `common/agent-runtime-ext-java/doc/configuration.md`: Runtime extension configuration reference (including concurrency limiting and OTel trajectory reporting configuration).
- `common/example/agent-bus-consumer-demo/README.md`: Bus event subscription and consumption caller / callee examples.
- `common/example/agentscope-a2a-interrupt-demo/README.md`: User interaction interruption and resumption example.

### 🧩 Core Framework Capability Extensions

- `common/agent-core-ext-java/README.md`: Core framework extension overview.
- `common/agent-core-ext-java/agent-core-ext-intent-suite/README.md`: Intent suite (agent perception and downstream task matching invocation) description.
- `common/example/bank-intent-routing-a2a-demo/README.md`: Multi-agent intent matching routing and A2A delegation example.

### 🧬 Agent Self-Evolution Engine

- `common/agent-evolve/evoagent/docs/README.md`: Project overview and documentation navigation.
- `common/agent-evolve/evoagent/docs/02-部署指南/evoagent部署指南.md`: Environment installation and dual-container deployment.
- `common/agent-evolve/evoagent/docs/03-API文档/api-evoagent.md`: API reference.
- `common/agent-evolve/evoagent/docs/04-特性使用指南/`: User guides for data backflow, trajectory evaluation, optimization engine, the self-evolving Agent, and other features.

### 🤖 General-Purpose Agent

- `common/agents/edp-agent-java/docs/快速入门/`: Product introduction and quick starts for development and operations.
- `common/agents/edp-agent-java/docs/开发指南/`: Built-in tools, external integrations, development approaches, skill development, and configuration guides.
- `common/agents/edp-agent-java/docs/运维指南/`: Docker deployment, health checks and logging, daily operations, and environment configuration guides.
- `common/agents/edp-agent-java/docs/参考指南/`: Tool API and environment variable references.
- `common/agents/edp-agent-java/docs/支持与排错/`: Troubleshooting, FAQ, technical support, and version changes.
- `common/agents/edp-agent-java/deploy/README.md`: Deployment scripts and configuration description.

---

## 🧪 Build and Verification

The extensions depend on `agent-runtime-java` 0.1.2 and `agent-core-java` 0.1.15.

```bash
# Client SDK and verification project (including the agent-client-sdk-for-jvm artifact)
mvn -f common/example/agent-client-demo/pom.xml clean install

# Agent bus (in dependency order: registry/discovery center → event bus → gateway)
mvn -f common/agent-bus/registry-discovery-center/pom.xml clean install
mvn -f common/agent-bus/event-bus/pom.xml clean install
mvn -f common/agent-bus/agent-gateway/pom.xml clean install

# Runtime extensions and core framework extensions
mvn -f common/agent-runtime-ext-java/pom.xml clean install
mvn -f common/agent-core-ext-java/pom.xml clean install

# Representative example verification
mvn -f common/example/agent-bus-consumer-demo/pom.xml clean install
mvn -f common/example/bank-intent-routing-a2a-demo/pom.xml clean install

# General-purpose agent EDPAgent Java
mvn -f common/agents/edp-agent-java/pom.xml clean install
```

The Agent Self-Evolution Engine is a Python deliverable deployed via Docker dual containers and does not rely on Maven builds; see `common/agent-evolve/evoagent/docs/02-部署指南/evoagent部署指南.md` for build and startup instructions.

### Maven Coordinates

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-client-sdk-for-jvm</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>event-bus-sdk</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-service-bus-consumer</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-ext-intent-suite</artifactId>
    <version>0.1.1</version>
</dependency>
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>edp-agent-engine</artifactId>
    <version>0.1.1</version>
</dependency>
```

Dependency requirements: `com.openjiuwen:agent-runtime-java:0.1.2`, `com.openjiuwen:agent-core-java:0.1.15`.

---

## 🙏 Acknowledgments

Thank you to all contributors who submitted requirements, issues, pull requests, design reviews, code development, and test verification for OpenJiuwen Agent Solution v0.1.1!
