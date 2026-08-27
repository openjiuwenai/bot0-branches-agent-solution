# v0.1.0 Release Note

Release Date: July 30, 2026

---

Welcome to OpenJiuwen Agent Solution v0.1.0! Centered on the integration, execution, and autonomous evolution of agent services, this release delivers an end-to-end capability foundation across four parts: the Agent Runtime, Core Framework Capability Extensions, Agent Self-Evolution Engine, and General-Purpose Agent. The runtime supports Versatile intent-based workflow routing, custom REST service entry points, compatible onboarding of agents built on heterogeneous frameworks such as AgentScope, and SkillHub subscription. The core framework completes ReActAgent's cognitive guardrails with answer evaluation and verification, replanning control, and failure degradation. The self-evolution engine forms a closed loop of "Data Backflow → Trajectory Evaluation → Optimization Engine" to continuously improve Prompts and Skills based on agents' real execution trajectories. For Java technology stacks in vertical industries such as finance, this release also delivers EDPAgent, an enterprise-grade general-purpose agent covering the complete capabilities of reasoning, control, collaboration, planning, and governance — making agent service integration more flexible, execution more reliable, evolution more autonomous, and rollout more reassuring.

---

## 🚀 New Features

### ⚙️ Agent Runtime

This release brings four new capabilities to the runtime — Versatile intent-based workflow routing, custom REST API service entry points, compatible onboarding of agents built on heterogeneous frameworks such as AgentScope, and SkillHub subscription — covering common scenarios of agent service integration and execution:

- **Versatile Intent-Based Workflow Routing**: Automatically selects the workflow service address by intent for routing and dispatch; the final result in streaming responses is automatically extracted from the result node, with no need to worry about node details.
- **Custom REST API Service Entry**: Existing REST APIs can be mapped to standard agent service calls without any modification; both synchronous JSON and SSE streaming responses are supported. In the current version, a single runtime instance hosts one agent and supports one path matching rule.
- **Compatible Onboarding of Heterogeneous Agent Frameworks**: Agents built on heterogeneous frameworks such as AgentScope can be wrapped and onboarded into the runtime directly, uniformly providing standard semantics for query, streaming, failure, and suspension; all three suspension scenarios — message interruption, human confirmation, and waiting for external tools — support resumption.
- **SkillHub Subscription**: Skill packages declared by an agent are automatically downloaded at startup, with integrity verification ensuring package trustworthiness; readiness is blocked when critical Skills are missing to prevent launching in an unhealthy state, startup can degrade gracefully when optional Skills fail, and credential information is masked and never leaked.

### 🧩 Core Framework Capability Extensions

This release completes the cognitive guardrail capabilities of the execution kernel, equipping ReActAgent's reasoning and execution with quality validation and risk protection:

- **ReActAgent Cognitive Capability Completion**: Adds three cognitive guardrails to ReActAgent — validating the final answer against success criteria, limiting replanning attempts to prevent divergence, and degrading to termination upon device failure; delivered as a pure Java SDK with no dependency on Spring or runtime extensions.

### 🧬 Agent Self-Evolution Engine

This release delivers the self-evolution engine's complete closed loop of "Data Backflow → Trajectory Evaluation → Optimization Engine" along with a self-evolving Agent, which performs quality assessment based on agents' real execution trajectories, continuously improves Prompts and Skills, and enables agents to evolve autonomously:

- **Data Backflow**: Backflows structured trajectories from runtime logs or OpenTelemetry trace data, supporting both log-based and standard-trace-based modes, and automatically cleans and normalizes them into a standard conversation format for evaluation.
- **Trajectory Evaluation**: Dual-channel evaluation combining metric-based and LLM-based approaches, covering precision, keyword matching, semantic similarity, task completion, trajectory quality, and safety across multiple dimensions; automatically locates optimization points in Skills and Prompts and produces actionable recommendations.
- **Optimization Engine**: Performs Skill optimization and Prompt optimization based on evaluation results; optimized results are verified through hot updates on a business agent before being written back to the target agent to take effect.
- **Self-Evolving Agent**: Chains together the entire workflow of dataset import, trajectory evaluation, strategy optimization, and sandbox verification using native agent capabilities, making the self-evolution closed loop for business agents available out of the box.

### 🤖 General-Purpose Agent

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

## 📚 Related Documentation

- `common/README.md`: Directory overview and the build and packaging workflows for official and unofficial releases.

### ⚙️ Agent Runtime & 🧩 Core Framework Capability Extensions

- `common/example/versatile-a2a-adapter-demo/README.md`: Packaging, startup, and request scripts for the Versatile intent-based workflow routing example.
- `common/example/agentcore-ext-remote-a2a-tool-demo/README.md`: Packaging, startup, and request scripts for the DeepAgent remote A2A tool injection example.
- `common/example/agentcore-ext-deepagent-remote-a2a-demo/README.md`: Packaging, startup, and request scripts for the DeepAgent remote A2A delegation and interruption recovery example.
- `common/example/multi-deep-research-demo/README.md`: Packaging, startup, and request scripts for the multi-agent deep research example.

### 🧬 Agent Self-Evolution Engine

- `common/agent-evolve/evoagent/docs/README.md`: Project overview and documentation navigation for the self-evolution engine.
- `common/agent-evolve/evoagent/docs/02-部署指南/evoagent部署指南.md`: Environment installation and dual-container deployment.
- `common/agent-evolve/evoagent/docs/03-API文档/api-evoagent.md`: API reference.
- `common/agent-evolve/evoagent/docs/04-特性使用指南/`: User guides for data backflow, trajectory evaluation, optimization engine, and the self-evolving Agent.

### 🤖 General-Purpose Agent

- `common/agents/edp-agent-java/docs/快速入门/`: Core features, product introduction, and quick starts for development and operations.
- `common/agents/edp-agent-java/docs/开发指南/`: Redis integration, built-in tools, external integrations, development approaches, development environment setup, skill development, and configuration guides.
- `common/agents/edp-agent-java/docs/运维指南/`: Docker deployment, health checks and logging, daily operations, and environment configuration guides.
- `common/agents/edp-agent-java/docs/参考指南/`: Tool API and environment variable references.
- `common/agents/edp-agent-java/docs/支持与排错/`: Troubleshooting, FAQ, technical support, and version changes.

---

## 🧪 Build and Verification

The extensions depend on `agent-runtime-java` 0.1.1.post1 and `agent-core-java` 0.1.14.post1.

```bash
# Runtime extensions and core framework extensions
mvn -f common/agent-runtime-ext-java/pom.xml clean install
mvn -f common/agent-core-ext-java/pom.xml -pl :agent-core-ext-react-rails -am clean install

# Representative example verification
mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-remote-a2a-tool-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-deepagent-remote-a2a-demo/pom.xml clean install
mvn -f common/example/multi-deep-research-demo/pom.xml clean install
```

### Maven Coordinates

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
```

Dependency requirements: `com.openjiuwen:agent-runtime-java:0.1.1.post1`, `com.openjiuwen:agent-core-java:0.1.14.post1`.

---

## 🙏 Acknowledgments

Thank you to all contributors who submitted requirements, issues, pull requests, design reviews, code development, and test verification for OpenJiuwen Agent Solution v0.1.0!
