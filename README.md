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
| `common/agent-core-ext-java` | Pure SDK extensions for `agent-core-java`; currently aggregates the Spring-free `agent-core-ext-react-rails` feature jar. |
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
|   |   `-- agent-core-ext-react-rails
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
|-- agent-gateway-demo
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
    <artifactId>agent-core-ext-react-rails</artifactId>
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

---

# v0.1.0 Release Note

Release Date: July 30, 2026

- **Capability Extensions** (openJiuwen agent-solution) covers three parts: runtime extensions, core framework extensions, and self-evolution engine. Runtime extensions support Versatile intent workflow routing, custom RESTful API service entry points, heterogeneous framework agent compatibility such as AgentScope, and SkillHub subscription; core framework extensions supplement ReActAgent with cognitive rails for evaluation and verification, replan control, and failure degradation; self-evolution engine provides a closed-loop of "Data Replay → Trajectory Evaluation → Optimization Engine", performing quality assessment based on Agent real-world execution trajectories and continuously improving Prompts and Skills to enable Agent self-driven evolution;
- **General Agent** (EDPAgent Java) addresses the Java technology stack requirements of vertical industries such as finance, delivering an enterprise-grade general agent with comprehensive governance capabilities built on top of OpenJiuwen DeepAgent, covering DeepAgent reasoning mechanism, interception and control, human-machine collaboration tools, workflow invocation, data pass-through, task planning, rule governance, chain of thought visualization, utterance management, and isolated execution environment, meeting stringent requirements for security, controllability, and observability;

## New Features

### I. Capability Extensions

This component covers three parts: runtime extensions, core framework extensions, and self-evolution engine. Runtime extensions use the runtime's `AgentHandler` SPI, `A2ARemoteAgentCardRegistry`, and Spring Boot auto-configuration as integration entry points, with runtime capabilities provided by `agent-runtime-java` and execution core provided by `agent-core-java`; core framework extensions supplement ReActAgent with cognitive rails for evaluation and verification, replan control, and failure degradation; self-evolution engine performs quality assessment and continuous optimization based on Agent real-world execution trajectories.

**1. Runtime Extension-Versatile Intent Workflow Adaptation:** Supports intent-based routing distribution by selecting endpoints URL templates based on intent, and supports SSE response `result-node-name` minimal result node extraction, extracting the final result when `node_name` matches and `node_type` is `"End"`.

**2. Runtime Extension-Custom REST API Service Entry Point:** Provides a custom REST edge adapter on top of standard Agent service semantics, mapping existing REST API patterns to standard Agent service calls through `RestRequestMapper` / `RestResponseMapper` SPI, supporting synchronous JSON and SSE streaming responses. In the current version, a single runtime instance hosts only one Agent and allows only one REST path pattern.

**3. Runtime Extension-Heterogeneous Agent Framework Compatibility:** Extends compatibility with the AgentScope framework on top of `agentcore-ext` and `Versatile` adapter, supporting wrapping local `ReActAgent` and `HarnessAgent`, mapping to runtime query, stream, failure, and pause semantics, and verifying three types of pause/resume: message stop, manual confirmation, and single external pending tool.

**4. Runtime Extension-Skill Hub Subscription:** Downloads skill packages declared by the Agent at startup through a replaceable Skill Hub SPI, using required / optional semantics, with SHA-256 or standard integrity verification; required skill failures block ready, optional skill failures allow degraded startup, and credentials are desensitized and not exposed.

**5. Core Framework Extension-ReActAgent Cognitive Capability Enhancement (agent-core-ext-react-rails):** Adds the `agent-core-ext-react-rails` module, supplementing ReActAgent with three cognitive rails: `CriteriaVerificationRail` (verifies final answer against success criteria), `ReplanRail` (limits replan count to prevent divergence), `RootCauseRail` (device failure degradation termination), all short-circuiting the loop via `forceFinish` gate in `afterModelCall`. Pure Java SDK, no dependency on Spring or runtime-ext.

**6. Self-Evolution Engine-Data Replay:** Replays structured trajectories from Agent execution logs or OpenTelemetry data, supporting both log and standard (OTel) modes, normalized into standard conversation format through cleaning for evaluation use.

**7. Self-Evolution Engine-Trajectory Evaluation:** Provides metric evaluators (F1, precision, keyword matching, semantic similarity) and LLM evaluators (multi-dimensional scoring of task completion, trajectory quality, and security), identifying Skill / Prompt optimization points and outputting actionable recommendations.

**8. Self-Evolution Engine-Optimization Engine:** Executes Skill optimization (reflection → aggregation → selection → application, supporting SkillOpt/TF-GRPO) and Prompt optimization (automatic iteration with verification through business Agent hot-update) based on evaluation results, writing results back to the target Agent.

**9. Self-Evolution Engine-Self-Evolution Agent:** Chains the full process of dataset import → trajectory evaluation → strategy optimization → sandbox Rollout verification through native agent capabilities, implementing the business Agent self-evolution closed loop.

### II. General Agent

EDPAgent Java v0.1.0 is the first official Java release of EDPAgent (Enterprise-grade Dynamic Planning Agent). The following are the core capabilities released in this version.

**1. ReAct Mechanism Upgraded to DeepAgent Mechanism:** Implements a closed-loop architecture of "plan — execute — observe — reflect" based on the DeepAgent reasoning loop paradigm, replacing the traditional single-turn ReAct, supporting task state management, dynamic path adjustment, automatic dependency resolution, and planning pre-check hard interception.

**2. Interception and Control Mechanism:** Forms a processing chain through multiple interceptors in priority order, covering the entire process including task cancellation, state maintenance, execution limits, tool invocation, interrupt handling, logging, event push, and utterance rendering, forming a comprehensive governance and security control system.

**3. ask_user (User Information Follow-up) Tool:** Involves users in confirmation at critical decision points to mitigate business risks, supporting interrupt persistence, rich parameter configuration, mandatory scenario constraints, and automatic execution recovery after interruption.

**4. call_mcp (Generic Script Invocation) Tool:** Invokes scripts via MCP SSE service, providing a security-isolated Python script execution sandbox, supporting active-standby auto-switch, Token authentication, data pass-through writing, and invocation count limits.

**5. Versatile Workflow Invocation:** Delegates complex business processes to external workflow services for execution, achieving separation of responsibilities between Agent and business systems, supporting REST/A2A dual modes, interrupt-resume, result normalization, and data pass-through reading.

**6. cancel_task (Cancel Current Task) Tool:** Supports users to terminate the currently executing business process at any time.

**7. Inter-Tool Data Channel Pass-Through:** Implements direct structured data passing between tools through session-level key-value storage, without LLM retransmission, avoiding data loss and hallucination injection, supporting multi-level scope isolation and concurrency safety.

**8. Task Planning:** Provides task planning and lifecycle management based on the Todo state machine, supporting task templates, state updates, dynamic path rules, and cross-turn persistence, enabling complex business multi-step execution.

**9. Rule-Based Business Governance:** Provides hierarchical governance of business scope, tool whitelists, invocation counts, subtask quantities, execution steps, and compliance through both framework default configuration and scenario configuration modes, ensuring Agents execute safely within authorized scope.

**10. Chain of Thought:** Implements Agent thinking process visualization through frame control and stage utterance configuration, supporting both real streaming and fixed utterance dual modes, enhancing interaction experience.

**11. Utterance Management:** Ensures Agent output is consistent, controllable, and compliant through three-level configuration of general utterances, scenario utterances, and Skill utterances, with variable substitution and scenario-level override mechanisms.

**12. Isolated Execution Environment:** Ensures the security and stability of the Agent execution environment through multi-layer isolation mechanisms, meeting enterprise-grade deployment requirements.

---