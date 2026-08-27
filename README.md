# openJiuwen agent-solution

[Chinese Version](README.zh.md) | [English Version](README.md)

## Introduction

**openJiuwen agent-solution** is an openJiuwen extension solution repository for Agent application integration and general industry scenarios.

The current version contains three independent extension projects: runtime adapters, pure AgentCore SDK extensions, and concrete Agent implementations. This repository does not reimplement runtime capabilities such as HTTP ingress, A2A protocol support, remote card discovery and communication, or session orchestration. Those capabilities are provided by `agent-runtime-java`; the Agent execution core is provided by `agent-core-java`.

## Quick Start

### Requirements

- **Java**: JDK 17+
- **Build tool**: Maven 3.9+
- **Runtime dependency**: `com.openjiuwen:agent-runtime-java:0.1.1.post1`
- **Execution core dependency**: `com.openjiuwen:agent-core-java:0.1.14.post1`

### Quick Build (Aggregator Pom)

A root aggregator pom (`pom.xml`) builds the managed extension and agent jars in one command. It is a pure build wrapper — it does **not** change any module's parent or dependencies, and the modules remain independent peers.

```bash
mvn install                      # build all 8 managed jars (default)
mvn install -Pmechanism          # build only the 6 mechanism jars
mvn install -Pbusiness           # build only the 2 business jars
mvn install -DskipTests          # build all, skipping tests
```

Managed jars: `agent-service-adapters-agentcore-ext`, `agent-service-adapters-agentscope`, `agent-service-adapters-versatile`, `agent-service-app-custom-rest`, `agent-service-spec-ext`, `agent-core-ext-react-rails` (mechanism); `edp-agent-engine`, `adapter-versatile-agent-java` (business). The aggregator pom itself is never published (`maven.deploy.skip=true`).

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

---

Welcome to OpenJiuwen Agent Solution v0.1.0! Centered on the integration, execution, and autonomous evolution of agent services, this release delivers an end-to-end capability foundation across four parts: the Agent Runtime, Core Framework Capability Extensions, Agent Self-Evolution Engine, and General-Purpose Agent. The runtime supports Versatile intent-based workflow routing, custom REST service entry points, compatible onboarding of agents built on heterogeneous frameworks such as AgentScope, and SkillHub subscription. The core framework completes ReActAgent's cognitive guardrails with answer evaluation and verification, replanning control, and failure degradation. The self-evolution engine forms a closed loop of "Data Backflow → Trajectory Evaluation → Optimization Engine" to continuously improve Prompts and Skills based on agents' real execution trajectories. For Java technology stacks in vertical industries such as finance, this release also delivers EDPAgent, an enterprise-grade general-purpose agent covering the complete capabilities of reasoning, control, collaboration, planning, and governance — making agent service integration more flexible, execution more reliable, evolution more autonomous, and rollout more reassuring.

---

## New Features

### Agent Runtime

This release brings four new capabilities to the runtime — Versatile intent-based workflow routing, custom REST API service entry points, compatible onboarding of agents built on heterogeneous frameworks such as AgentScope, and SkillHub subscription — covering common scenarios of agent service integration and execution:

- **Versatile Intent-Based Workflow Routing**: Automatically selects the workflow service address by intent for routing and dispatch; the final result in streaming responses is automatically extracted from the result node, with no need to worry about node details.
- **Custom REST API Service Entry**: Existing REST APIs can be mapped to standard agent service calls without any modification; both synchronous JSON and SSE streaming responses are supported. In the current version, a single runtime instance hosts one agent and supports one path matching rule.
- **Compatible Onboarding of Heterogeneous Agent Frameworks**: Agents built on heterogeneous frameworks such as AgentScope can be wrapped and onboarded into the runtime directly, uniformly providing standard semantics for query, streaming, failure, and suspension; all three suspension scenarios — message interruption, human confirmation, and waiting for external tools — support resumption.
- **SkillHub Subscription**: Skill packages declared by an agent are automatically downloaded at startup, with integrity verification ensuring package trustworthiness; readiness is blocked when critical Skills are missing to prevent launching in an unhealthy state, startup can degrade gracefully when optional Skills fail, and credential information is masked and never leaked.

### Core Framework Capability Extensions

This release completes the cognitive guardrail capabilities of the execution kernel, equipping ReActAgent's reasoning and execution with quality validation and risk protection:

- **ReActAgent Cognitive Capability Completion**: Adds three cognitive guardrails to ReActAgent — validating the final answer against success criteria, limiting replanning attempts to prevent divergence, and degrading to termination upon device failure; delivered as a pure Java SDK with no dependency on Spring or runtime extensions.

### Agent Self-Evolution Engine

This release delivers the self-evolution engine's complete closed loop of "Data Backflow → Trajectory Evaluation → Optimization Engine" along with a self-evolving Agent, which performs quality assessment based on agents' real execution trajectories, continuously improves Prompts and Skills, and enables agents to evolve autonomously:

- **Data Backflow**: Backflows structured trajectories from runtime logs or OpenTelemetry trace data, supporting both log-based and standard-trace-based modes, and automatically cleans and normalizes them into a standard conversation format for evaluation.
- **Trajectory Evaluation**: Dual-channel evaluation combining metric-based and LLM-based approaches, covering precision, keyword matching, semantic similarity, task completion, trajectory quality, and safety across multiple dimensions; automatically locates optimization points in Skills and Prompts and produces actionable recommendations.
- **Optimization Engine**: Performs Skill optimization and Prompt optimization based on evaluation results; optimized results are verified through hot updates on a business agent before being written back to the target agent to take effect.
- **Self-Evolving Agent**: Chains together the entire workflow of dataset import, trajectory evaluation, strategy optimization, and sandbox verification using native agent capabilities, making the self-evolution closed loop for business agents available out of the box.

### General-Purpose Agent

The first official release of EDPAgent Java, an enterprise-grade general-purpose dynamic planning agent for Java technology stacks in vertical industries such as finance, delivering core capabilities including reasoning, control, human-agent collaboration, workflow invocation, data pass-through, task planning, and utterance governance:

- **DeepAgent Reasoning Mechanism**: Replaces the traditional single-turn ReAct with a closed-loop architecture of "Plan — Execute — Observe — Reflect", supporting task state management, dynamic path adjustment, automatic dependency resolution, and hard interception prior to planning.
- **Interception and Control**: Multiple interceptors form a processing chain by priority, covering the entire workflow of task cancellation, state maintenance, execution limits, tool invocation, interruption handling, logging, event push, and utterance rendering, forming a comprehensive governance and security control system.
- **ask_user Follow-Up Tool**: Involves users in confirmation at key decision points to mitigate business risks, supporting interruption persistence, rich parameter configuration, mandatory scenario constraints, and automatic execution resumption after interruption.
- **call_mcp Script Invocation Tool**: Invokes scripts via MCP SSE services, providing a securely isolated Python script execution sandbox with support for automatic primary/standby switchover, token authentication, data pass-through writes, and invocation count limits.
- **Versatile Workflow Invocation**: Delegates complex business processes to external workflow services, separating the responsibilities of agents and business systems; supports REST/A2A dual modes, interruption and resumption, result normalization, and data pass-through reads.
- **cancel_task Task Termination Tool**: Allows users to terminate the currently executing business process at any time.
- **Inter-Tool Data Pass-Through**: Enables direct structured data transfer between tools through session-level key-value storage, without LLM paraphrasing, avoiding data loss and hallucination injection; supports multi-level scope isolation and concurrency safety.
- **Task Planning**: Provides task planning and lifecycle management based on a Todo state machine, supporting task templates, state updates, dynamic path rules, and cross-turn persistence to support multi-step execution of complex business processes.
- **Rule-Based Business Control**: Through two modes — framework default configuration and scenario configuration — hierarchically controls business scope, tool whitelists, invocation counts, subtask quantities, execution steps, and compliance, ensuring agents execute safely within authorized boundaries.
- **Chain-of-Thought Visualization**: Visualizes the thinking process through frame control and stage utterance configuration, supporting both real streaming and fixed-utterance modes to enhance the interaction experience.
- **Utterance Management**: Ensures consistent, controllable, and compliant agent output through three-level configuration of general, scenario, and Skill utterances, along with variable substitution and scenario-level overrides.
- **Isolated Execution Environment**: Safeguards the security and stability of the agent execution environment through multi-layer isolation mechanisms, meeting enterprise-grade deployment requirements.

---

# v0.1.1 Release Note

Release Date: August 30, 2026

---

Welcome to OpenJiuwen Agent Solution v0.1.1! Centered on the agent service invocation chain, this release delivers end-to-end standardized capabilities across five parts: the Agent Client, Agent Bus, Agent Runtime, Core Framework Capability Extensions, and Agent Self-Evolution Engine. It also completes two engineering optimizations — artifact size and dependency versions — and enhances the parallel execution orchestration of the general-purpose agent EDPAgent, making agent service integration simpler, collaboration smoother, evolution more autonomous, and delivery more frictionless.

---

## New Features

### Agent Client

The invocation entry point between applications and agent services, uniformly encapsulating service invocation, local tool collaboration, and streaming display, while shielding underlying details such as protocol adaptation and reconnection:

- **Standardized Service Invocation**: A unified API handles the creation, querying, and cancellation of agent invocations; supports breakpoint reconnection after link interruption and automatic circuit-breaker protection against infrastructure failures, so long-running task results are never lost.
- **Local Tool Collaboration**: Registered local tools can be driven and invoked by remote agents without being exposed to the server by default; observation-type operations execute automatically, while action-type operations execute only after authorization — keeping data secure and controllable.
- **Multi-Stream Demultiplexing**: When multiple agents collaborate, interleaved streaming outputs are automatically demultiplexed and rendered by source, with each stream attributable to a specific agent; the display resumes from where it left off after reconnection.

### Agent Bus

Carries invocation and event flows between clients and agent services; the three component types — gateway, event bus, and registry/discovery center — can each be deployed independently and replaced as needed:

- **Invocation Routing and Forwarding**: Routes client invocations to target runtimes by agent ID, uniformly handling authentication and tenant identification; all invocation types — blocking, streaming, query, and cancellation — can be forwarded, and tasks can be resumed across instances after disconnection.
- **Bus Event Flow**: Invocations and responses can flow asynchronously through the event bus, decoupling clients from agent services; collaboration invocation events between agents also support bus forwarding.
- **Instance Route Query**: Queries available runtime instances by agent, supporting multiple instance candidates and version matching; when the registry is temporarily unavailable, invocations degrade automatically without interrupting business.

### Agent Runtime

This release brings six new capabilities to the runtime — intent-based call transfer via the Versatile controller, user interaction interruption and recovery, client-side tool invocation response, bus event subscription, invocation chain tracing, and task concurrency limiting — covering the entire chain of agent services from integration and interaction to production operation:

- **Intent-Based Call Transfer**: Connects to the Versatile controller to automatically recognize intent messages and invoke target agents; controller exceptions and rollback signals are automatically distinguished and handled, with session continuity maintained throughout.
- **Interaction Interruption and Recovery**: Tasks suspend while an agent awaits additional information from the user and resume from the breakpoint once the client submits input; the experience is consistent for local and remote agents.
- **Client-Side Tool Response**: When an agent needs to use a client's local tool, it pauses and sends a request; execution resumes automatically after the client submits the result.
- **Bus Event Subscription**: The runtime can subscribe to and consume bus events once embedded, with no additional sidecar components required.
- **Invocation Chain Tracing**: Cross-platform invocations automatically carry a unified trace ID, making invocation chains traceable end to end; trajectory data supports OpenTelemetry standard reporting — ready upon configuration and disabled by default.
- **Concurrency and Rate Limiting**: Supports configuring the maximum number of concurrent tasks; new tasks are automatically rejected under overload to protect the service quality of running tasks.

### Core Framework Capability Extensions

This release adds two collaboration capabilities to the execution kernel: agent perception and task matching enable agents to discover one another and collaborate through task delegation, while dynamic client-side tool assembly enables agents to call client local tools on demand:

- **Agent Perception and Task Matching**: Agents automatically perceive other agents on the platform, precisely match by task semantics, and initiate delegated invocations; complex requests are decomposed first and then executed task by task.
- **Dynamic Client-Side Tool Assembly**: The tool visibility surface is assembled dynamically per task; once the agent selects a tool, execution is handed over to the runtime; tools are not cached and are not shared across tasks.

### Agent Self-Evolution Engine

This release brings four new capabilities to the self-evolution engine — trajectory enrichment, the Agent evaluator, GEPA optimization algorithm adaptation, and northbound SkillHub integration — building on the "Data Backflow → Trajectory Evaluation → Optimization Engine" closed loop to further cover trajectory attribution in dynamic planning scenarios and offline iteration of Skill versions:

- **Trajectory Enrichment**: In dynamic planning scenarios, supports inline mapping between trajectory span nodes and skill / agent.md, achieving fine-grained correspondence between trajectories and Skills / AgentRules, and providing more precise data for attribution and optimization.
- **Agent Evaluator**: Supports the Agent-as-a-Judge evaluator with path recognition and chain attribution capabilities, enabling determination and attribution analysis of trajectory execution paths.
- **Prompt Optimizer**: Supports automatic iterative prompt optimization based on evaluation feedback, with the SkillOpt algorithm; this release adds support for the GEPA optimization algorithm.
- **Northbound SkillHub Integration**: Supports offline update and iteration of Skill versions through SkillHub integration, facilitating Skill version management and iteration.

### Engineering and Compatibility Optimizations

Two engineering optimizations for enterprise delivery environments, with artifact size and dependency versions fully adapted to enterprise pipelines:

- **Artifact Size Optimization**: The EDPAgent deliverable JAR size has been optimized to within the 200MB limit of enterprise pipelines, so deployment is no longer blocked.
- **Unified Open-Source Dependency Versions**: The open-source dependency versions of agent-core and agent-runtime are aligned, eliminating dependency inconsistencies between modules.

### General-Purpose Agent

For enterprise scenarios, this release of EDPAgent Java, the general-purpose dynamic planning agent, focuses on execution orchestration, with complex tasks completed in parallel by multiple sub-agents:

- **Planning Workflow and Parallel Sub-Agent Execution**: The main agent plans once and launches multiple sub-agents in parallel within the same turn; once all results have returned, it performs aggregation reasoning in a single pass — eliminating serial waiting in multi-subtask scenarios.