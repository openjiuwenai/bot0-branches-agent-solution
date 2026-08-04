# v0.1.0 Release Note

Release Date: July 30, 2026

---

Welcome to OpenJiuwen Agent Solution v0.1.0! This release covers two major components: **Capability Extensions** and **General Agent**:

- **Capability Extensions** (openJiuwen agent-solution) covers three parts: runtime extensions, core framework extensions, and self-evolution engine. Runtime extensions support Versatile intent workflow routing, custom RESTful API service entry points, heterogeneous framework agent compatibility such as AgentScope, and SkillHub subscription; core framework extensions supplement ReActAgent with cognitive rails for evaluation and verification, replan control, and failure degradation; self-evolution engine provides a closed-loop of "Data Replay → Trajectory Evaluation → Optimization Engine", performing quality assessment based on Agent real-world execution trajectories and continuously improving Prompts and Skills to enable Agent self-driven evolution;
- **General Agent** (EDPAgent Java) addresses the Java technology stack requirements of vertical industries such as finance, delivering an enterprise-grade general agent with comprehensive governance capabilities built on top of OpenJiuwen DeepAgent, covering DeepAgent reasoning mechanism, interception and control, human-machine collaboration tools, workflow invocation, data pass-through, task planning, rule governance, chain of thought visualization, utterance management, and isolated execution environment, meeting stringent requirements for security, controllability, and observability;

---

## New Features

### I. Capability Extensions

This component covers three parts: runtime extensions, core framework extensions, and self-evolution engine. Runtime extensions use the runtime's `AgentHandler` SPI, `A2ARemoteAgentCardRegistry`, and Spring Boot auto-configuration as integration entry points, with runtime capabilities provided by `agent-runtime-java` and execution core provided by `agent-core-java`; core framework extensions supplement ReActAgent with cognitive rails for evaluation and verification, replan control, and failure degradation; self-evolution engine performs quality assessment and continuous optimization based on Agent real-world execution trajectories.

**1. Runtime Extension-Versatile Intent Workflow Adaptation:** Supports intent-based routing distribution by selecting endpoints URL templates based on intent, and supports SSE response `result-node-name` minimal result node extraction, extracting the final result when `node_name` matches and `node_type` is `"End"`.

**2. Runtime Extension-Custom REST API Service Entry Point:** Provides a custom REST edge adapter on top of standard Agent service semantics, mapping existing REST API patterns to standard Agent service calls through `RestRequestMapper` / `RestResponseMapper` SPI, supporting synchronous JSON and SSE streaming responses. In the current version, a single runtime instance hosts only one Agent and allows only one REST path pattern.

**3. Runtime Extension-Heterogeneous Agent Framework Compatibility:** Extends compatibility with the AgentScope framework on top of `agentcore-ext` and `Versatile` adapter, supporting wrapping local `ReActAgent` and `HarnessAgent`, mapping to runtime query, stream, failure, and pause semantics, and verifying three types of pause/resume: message stop, manual confirmation, and single external pending tool.

**4. Runtime Extension-Skill Hub Subscription:** Downloads skill packages declared by the Agent at startup through a replaceable Skill Hub SPI, using required / optional semantics, with SHA-256 or standard integrity verification; required skill failures block ready, optional skill failures allow degraded startup, and credentials are desensitized and not exposed.

**5. Core Framework Extension-ReActAgent Cognitive Capability Enhancement (react-rails):** Adds the `react-rails` module, supplementing ReActAgent with three cognitive rails: `CriteriaVerificationRail` (verifies final answer against success criteria), `ReplanRail` (limits replan count to prevent divergence), `RootCauseRail` (device failure degradation termination), all short-circuiting the loop via `forceFinish` gate in `afterModelCall`. Pure Java SDK, no dependency on Spring or runtime-ext.

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

## Documentation

### I. Capability Extensions

- `common/README.md`: Directory description and compilation/packaging process for formal / informal versions.
- Example READMEs: Packaging, startup, and request scripts.
- `docs/README.md`: Self-evolution engine project overview and documentation navigation.
- `docs/02-Deployment-Guide/evoagent-deployment-guide.md`: Self-evolution engine environment installation and dual-container deployment.
- `docs/03-API-Docs/api-evoagent.md`: Self-evolution engine API interface documentation.
- `docs/04-Feature-Usage-Guide/`: Data replay, trajectory evaluation, optimization engine, and self-evolution Agent usage guides.

### II. General Agent

- `docs/Quick Start/`: Covers core features, product introduction, development and operations quick start.
- `docs/Development Guide/`: Includes Redis integration, built-in tools, external integration, development approach, development environment preparation, skill development, and configuration guides.
- `docs/Operations Guide/`: Provides Docker deployment, health check and logging, daily operations, and environment configuration guides.
- `docs/Reference Guide/`: Tool API and environment variable reference.
- `docs/Support and Troubleshooting/`: Troubleshooting, FAQ, technical support, and version changelog.

---

## Build and Verification

Extension depends on `agent-runtime-java` 0.1.1 and `agent-core-java` 0.1.14.

```bash
mvn -f common/agent-runtime-ext-java/pom.xml clean install
mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-remote-a2a-tool-demo/pom.xml clean install
mvn -f common/example/agentcore-ext-deepagent-remote-a2a-demo/pom.xml clean install
mvn -f common/example/multi-deep-research-demo/pom.xml clean install
mvn -f common/agent-core-ext-java/pom.xml -pl :react-rails -am clean install
```

#### Maven Coordinates

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

Dependency requirements: `com.openjiuwen:agent-runtime-java:0.1.1`, `com.openjiuwen:agent-core-java:0.1.14`.

---

## Acknowledgments

Thank you to all contributors who submitted requirements, Issues, Pull Requests, design reviews, code development, and test verification for OpenJiuwen Agent Solution v0.1.0!
