# OpenJiuwen Agent Solution v0.1.0 Release Note

Release Date: July 30, 2026

---

Welcome to OpenJiuwen Agent Solution v0.1.0! This release covers three major components: **Platform Capabilities**, **Agent Engine**, and **EvoAgent Self-Evolution Engine**:

- **Platform Capabilities** (openJiuwen agent-solution) provides core capabilities including heterogeneous agent framework compatibility, standardized client invocation, local tool registration and invocation, and custom REST API service entry points;
- **Agent Engine** (EDPAgent Java) addresses the Java technology stack requirements of vertical industries such as finance, delivering an enterprise-grade Agent engine with comprehensive governance capabilities built on top of OpenJiuwen DeepAgent, meeting stringent requirements for security, controllability, and observability;
- **EvoAgent Self-Evolution Engine** provides a closed-loop capability of "Data Replay → Trajectory Evaluation → Optimization Engine", performing quality assessment based on Agent real-world execution trajectories, and continuously improving Prompts and Skills through the optimization engine to enable Agent self-driven evolution.

---

## New Features

### I. Platform Capabilities

The extension uses the runtime's `AgentHandler` SPI, `A2ARemoteAgentCardRegistry`, and Spring Boot auto-configuration as integration entry points. HTTP integration, A2A protocol, remote card discovery and communication, and session orchestration are provided by agent-runtime-java. The execution core is provided by agent-core-java. Client invocation and local tool governance are provided by agent-client.

#### 1. Versatile Intent Workflow Adaptation

Supports Versatile and Versatile intent workflow adaptation, enabling intent-based routing distribution by selecting endpoints URL templates based on intent, and enhancing SSE response minimal result node extraction. SSE response parsing supports `result-node-name` minimal result node extraction, extracting the final result when `node_name` matches and `node_type` is `"End"`.

#### 2. Custom REST API Service Entry Point

The runtime provides a custom REST edge adapter on top of standard Agent service semantics, enabling callers to access the same hosted Agent in a business REST/SSE format:

- **Custom REST Service Entry Point**: Allows users to map existing REST API patterns to standard Agent service calls through custom extensions.
- **Request Mapping SPI**: Users define custom `RestRequestMapper` and `RestResponseMapper` for request normalization and response projection.
- **Synchronous/Streaming Message Invocation**: Supports synchronous JSON responses and SSE streaming responses.
- **A2A Semantic Normalization**: Each submission is normalized to a standard Agent service call, with Task, error, and tenant semantics normalized to the standardized Agent service entry point.
- **Single Entry Point, Single Path**: In the current version, a single runtime instance hosts only one Agent and allows only one REST path pattern.

#### 3. Heterogeneous Agent Framework Compatibility Extension

Building on the `agentcore-ext` and `Versatile` adapter, extends the heterogeneous framework compatibility scope to support the AgentScope framework:

- Supports wrapping a locally built `ReActAgent` from the host, mapping `Mono<Msg>` / `Flux<AgentEvent>` to runtime query, stream, failure, and pause semantics.
- Supports wrapping a locally built `HarnessAgent` from the host, maintaining the same runtime protocol as ReAct through public API calls and state reads.
- Verified three types of pause/resume: message stop, manual confirmation, and single external pending tool.

#### 4. Skill Hub Subscription

Adds support for subscribing to Skills via Skill Hub, with default support for OpenJiuwen Skill Hub and extensibility to custom Skill Hubs via SPI:

- The runtime reads Agent skill selection configuration during deployment or startup (distinguishing required / optional semantics).
- Accesses the Skill Hub through a replaceable Skill Hub SPI to download skill packages declared by the Agent.
- Supports download integrity verification: verifies SHA-256 when the Skill Hub supports digests; otherwise performs standard checks such as file non-empty, fully readable, and required files present.
- Required skill failures in configuration/authentication/authorization/lookup or startup-phase handoff block Agent ready; download or integrity verification failures allow degraded entry to ready with retry outside the request path.
- Optional skill acquisition failures can be skipped to continue startup, with desensitized diagnostics output.
- Credentials and sensitive information are not written to logs, error responses, or telemetry data.

#### 5. Standardized Agent Client Invocation

Provides a standard client facade for business applications, declaring invocation mode at creation time and returning invocation correlation and task status projection:

- **Three Invocation Modes**: `BLOCKING` (one-shot response window), `STREAMING` (full-link streaming capability), `ASYNC` (submit and observe asynchronously).
- **Conversation Passing**: Supports business applications passing or delegating generation of `conversationId`; conversation sovereignty belongs to the business application.
- **Invocation Echo**: Each invocation returns `conversationId`, `invocationRef`, idempotency key, invocation mode, status projection, and recovery clue; business applications are not required to hold `taskId`.
- **Status Observation**: Supports query, re-subscription, cancellation, continue-waiting-for-input, and UNKNOWN recovery based on `invocationRef`.
- **Idempotency and Retry**: Both creation-type invocations and continue-waiting-for-input-type invocations have independent idempotency semantics; retries do not cause duplicate side effects.
- **Error Classification**: Distinguishes network errors, routing errors, server errors, business failures, cancellations, rejections, accepted-unknown, and streaming capability unavailable.

#### 6. Client Local Tool Registration and Invocation

Adds standardized SPI and registration management for local tools, supporting invocation driven by remote agents:

- **Local Tool SPI Registration**: Business applications register local tool descriptions and handlers via SPI during integration development, including stable `toolId`, name, description, input/output schema, authorization policy, and audit policy.
- **Observation / Action Classification**: Observation is read-only (auto-executed); Action produces side effects (requires authorization approval).
- **Default Non-Exposure**: Without explicit declaration by the business application, the client does not expose any local tools to the server.
- **ToolExposurePolicy**: Supports conversation-level and invocation-level exposable tool scope declarations; invocation-level policies can narrow or override conversation-level policies.
- **ToolView Generation and Reporting**: Attaches the current ToolView to real invocation requests, computed jointly by the local tool catalog, exposure policy, and tool availability.
- **Remote-Driven Invocation**: The server can only request client tools visible in the ToolView through governed messages and cannot directly access client local resources.
- **Result Submission**: Tool execution results are proactively submitted to the runtime via Gateway as internal client recovery requests.

#### 7. Runtime Client-Side Tool Response

Adds support for request handling with client-side tools. The runtime suspends the current Task when Agent execution requires a client local tool, projects the tool request through the response, and verifies the recovery relationship to continue the original Task after the client submits the tool outcome:

- **ToolView Acceptance and Task-Level Binding**: The runtime receives the current ToolView from the standard client invocation and binds it to the current Task execution context.
- **Client Tool Invocation Suspension**: When Agent execution produces a client local tool invocation, the runtime suspends the current Task and returns the tool request projection through the current invocation response.
- **Non-Completed Status**: While waiting for client tool results, the Task must not be marked as completed.
- **Continuation Recovery**: The client submits tool results via a standard continuation invocation; the runtime verifies the correlation and resumes the original Task.
- **Client Exception Pass-Through**: Outcomes such as undeclared tools, insufficient permissions, invalid parameters, execution failures, or timeouts are fed as tool results into the recovery execution chain.

#### 8. ReActAgent Cognitive Capability Enhancement (react-rails)

Adds the `react-rails` module, supplementing the agent-core-java ReActAgent with three cognitive rails to address the capability gap of the native ReActAgent, which only has a reason+act loop (no verify, no replan awareness, no graceful degradation on tool failure):

- **CriteriaVerificationRail (external-judge gate)**: `afterModelCall` detects the final answer, calls `CriteriaVerifier.verify()` to validate against success criteria; on PASS, `forceFinish(verified=true)`; on FAIL, `forceFinish(degraded=true, unmet=[...])`; provides a rule-based deterministic verifier by default (zero LLM).
- **ReplanRail (replan count/limit escalate)**: `afterModelCall` detects `__replan__` tool_call and counts; when maxReplan is exceeded, `forceFinish(degraded)`, preventing the LLM from repeatedly switching strategies without convergence.
- **RootCauseRail (device-failure degrade)**: `onToolException` marks pendingDegrade; the next round's `afterModelCall` triggers `forceFinish(degraded)`, enabling honest degradation termination when device failure retries are ineffective.
- **forceFinish Gate Load-Bearing**: All three rails short-circuit the ReActAgent loop via `requestForceFinish(Map)` in the `afterModelCall` hook (bytecode offset 225/700 verified).

Pure Java SDK, no dependency on Spring or runtime-ext; rails and tools are explicitly registered by the application.

### II. Agent Engine

EDPAgent Java v0.1.0 is the first official Java release of EDPAgent (Enterprise-grade Dynamic Planning Agent). Following the successful release of the Python version, this release addresses the Java technology stack requirements of vertical industries such as finance, delivering an enterprise-grade Agent engine with comprehensive governance capabilities built on top of OpenJiuwen DeepAgent, implementing the core capabilities required for enterprise-grade Agents and meeting the stringent requirements of the financial industry for security, controllability, and observability.

#### 1. ReAct Mechanism Upgraded to DeepAgent Mechanism

Based on the DeepAgent reasoning loop paradigm, implements a closed-loop agent architecture of "plan — execute — observe — reflect", replacing the traditional single-turn ReAct pattern, supporting task state management, dynamic path adjustment, automatic dependency resolution, and planning pre-check hard interception.

#### 2. Interception and Control Mechanism

Forms a processing chain through multiple interceptors executed in priority order, creating a comprehensive behavioral governance and security control system covering task cancellation, state maintenance, execution limits, tool invocation, interrupt handling, logging, event push, and utterance rendering throughout the entire process.

#### 3. ask_user (User Information Follow-up) Tool

A key human-machine collaboration mechanism that involves users in confirmation at critical decision points, preventing business risks caused by Agent speculation. Supports interrupt persistence, rich parameter configuration, mandatory scenario constraints, and automatic execution recovery after interruption.

#### 4. call_mcp (Generic Script Invocation) Tool

Invokes scripts via MCP SSE service, providing a security-isolated Python script execution sandbox environment, supporting active-standby auto-switch, Token authentication, automatic data pass-through writing, and invocation count limits.

#### 5. Versatile Workflow Invocation

Delegates complex business processes to external workflow services for execution, achieving separation of responsibilities between Agent and business systems. Supports REST/A2A dual invocation modes, interrupt-resume, result normalization, and data pass-through reading.

#### 6. cancel_task (Cancel Current Task) Tool

Provides task cancellation capability, supporting users to terminate the currently executing business process at any time.

#### 7. Inter-Tool Data Channel Pass-Through

Implements direct structured data passing between tools through a session-level key-value storage mechanism, without relying on LLM retransmission, avoiding data loss, format errors, or hallucination injection. Supports multi-level scope isolation and concurrency safety.

#### 8. Task Planning

Provides complete task planning and lifecycle management based on the Todo state machine, supporting multi-step execution of complex business processes, task templates, state updates, dynamic path rules, and cross-turn persistence.

#### 9. Rule-Based Business Governance

Multi-layer governance mechanism constraining Agent behavioral boundaries, ensuring Agents execute business safely and controllably within authorized scope, meeting financial industry compliance requirements. Supports both framework default configuration and scenario configuration modes, providing hierarchical control over business scope, tool whitelists, invocation count limits, subtask quantity limits, execution step limits, and compliance.

#### 10. Chain of Thought

Implements visualization of the Agent's thinking process, providing users with smooth and natural thinking process feedback through fine-grained frame control and stage utterance configuration, enhancing interaction experience. Supports both real streaming and fixed utterance display modes.

#### 11. Utterance Management

Unified utterance configuration, variable substitution, and scenario-level override mechanism, ensuring Agent output utterances are consistent, controllable, and compliant through a layered utterance system. Supports three-level configuration of general utterances, scenario utterances, and Skill utterances, with compliance exit constraints.

#### 12. Isolated Execution Environment

Ensures the security and stability of the Agent execution environment through multi-layer isolation mechanisms, meeting enterprise-grade deployment requirements.

### III. EvoAgent Self-Evolution Engine

EvoAgent v0.1.0 provides a closed-loop capability of "Data Replay → Trajectory Evaluation → Optimization Engine", performing quality assessment based on Agent real-world execution trajectories, and continuously improving Prompts and Skills through the optimization engine to enable Agent self-driven evolution.

#### 1. Data Replay

Collects Agent execution trajectories, performs cleaning and structural normalization, providing high-quality input for evaluation and optimization:

- **Trajectory Collection**: Replays structured trajectories from Agent execution logs / Agent-reported OpenTelemetry-format data; supports both log mode and standard (OTel) mode, with isomorphic record formats produced by both modes.
- **Trajectory Cleaning**: Normalizes heterogeneous trajectories into standard conversation format, removing metadata not needed for evaluation.

#### 2. Trajectory Evaluation

Provides metric evaluators and LLM evaluators to assess and score trajectory quality, identifying Skill / Prompt optimization points:

- **Metric Evaluator**: Supports F1, precision, keyword matching, semantic similarity, and other metric evaluations.
- **LLM Evaluator**: Performs multi-dimensional scoring of task completion, trajectory quality, and security, with support for Skill attribution and actionable optimization recommendation output.

#### 3. Optimization Engine

Executes Prompt optimization and Skill optimization based on evaluation results, writing optimization results back to the target Agent:

- **Skill Optimizer**: Optimizes Skill documentation through reflection → aggregation → selection → application; supports SkillOpt/TF-GRPO algorithms.
- **Prompt Optimizer**: Supports automatic iterative prompt optimization based on evaluation feedback, with verification through business Agent hot-update testing.

#### 4. Self-Evolution Agent

Implements the full business Agent self-evolution process through native agent capabilities:

- **Business Agent Self-Evolution**: Implements the full process of dataset import → trajectory evaluation → strategy optimization → sandbox Rollout verification through native agent capabilities.

---

## Testing and Quality

### I. Platform Capabilities

Extension modules and example projects are covered by unit tests and integration tests. Test coverage includes:

- **Versatile Adapter**: HTTP request mapping, SSE response parsing, interrupt detection, and URL template replacement.
- **Custom REST Entry Point**: Request mapping SPI, synchronous/streaming invocation, A2A semantic normalization, and Task query/cancellation.
- **Heterogeneous Framework Compatibility**: AgentScope ReActAgent / HarnessAgent normal completion, failure terminal states, pause/resume, and cancellation boundaries.
- **Skill Hub Subscription**: required / optional skill download, integrity verification, degraded first-time effect, and credential desensitization.
- **Client Invocation**: BLOCKING / STREAMING / ASYNC invocation modes, idempotent retry, UNKNOWN recovery, and error classification.
- **Local Tools**: SPI registration, ToolExposurePolicy, ToolView reporting, Observation auto-execution, Action authorization approval, and rejection scenarios.
- **Client-Side Tool Response**: ToolView-attached invocation, streaming/non-streaming tool request suspension, continuation recovery, query and cancellation during waiting.
- **react-rails Cognitive Rails**: Three-rail control flow hard interruption (mutation-RED), real ReActAgent + real LLM e2e data channel, forceFinish gate offset real consumption.

### II. Agent Engine

- **Unit Test Coverage**: Covers core module unit tests.
- **End-to-End Testing**:
  - **Integration Testing**:
    1. Installation guide testing from user perspective, verifying Docker packaging and startup methods.
    2. Functional verification of feature lists, including both normal and abnormal scenario validation.
    3. DFx dimensions including performance, reliability, maintainability, security, resilience, stability, scalability, and observability.
  - **Scenario Testing**: Test scope covers XX wealth management scenario test cases, building a Mock environment for setup, sending curl commands to the backend agent, evaluating test case execution results via SSE information, completing multi-product purchase, re-recommendation after purchase cancellation, cancellation at various stages in the process, transfer exceptions, boundary values, and wealth management recommendations.

### III. EvoAgent Self-Evolution Engine

Covers unit tests, integration tests, and end-to-end tests. Test coverage includes:

- **Data Replay**: log / standard collection pipeline, trajectory cleaning normalization and filtering.
- **Trajectory Evaluation**: metric evaluator, LLM evaluator, dataset upload and batch evaluation.
- **Optimization Engine**: SkillOpt / TF-GRPO, Prompt managed-doc optimization, verification gating and cancellation rollback.
- **Self-Evolution Agent**: dataset import → trajectory evaluation → strategy optimization → sandbox Rollout verification full process.

---

## Bug Fixes

This is an initial release version and does not involve historical bug fixes.

---

## Documentation

### I. Platform Capabilities

- `common/README.md`: Directory description and compilation/packaging process for formal / informal versions.
- Example READMEs: Packaging, startup, and request scripts.

### II. Agent Engine

- `docs/Quick Start/`: Covers core features, product introduction, development and operations quick start.
- `docs/Development Guide/`: Includes Redis integration, built-in tools, external integration, development approach, development environment preparation, skill development, and configuration guides.
- `docs/Operations Guide/`: Provides Docker deployment, health check and logging, daily operations, and environment configuration guides.
- `docs/Reference Guide/`: Tool API and environment variable reference.
- `docs/Support and Troubleshooting/`: Troubleshooting, FAQ, technical support, and version changelog.

### III. EvoAgent Self-Evolution Engine

- `docs/README.md`: Project overview and documentation navigation.
- `docs/02-Deployment-Guide/evoagent-deployment-guide.md`: Environment installation and dual-container deployment.
- `docs/03-API-Docs/api-evoagent.md`: API interface documentation.
- `docs/04-Feature-Usage-Guide/`: Data replay, trajectory evaluation, optimization engine, and self-evolution Agent usage guides.

---

## Known Limitations

### I. Platform Capabilities

- Client local tools are not exposed by default; business applications must explicitly declare ToolExposurePolicy.
- The current version supports only one Agent per runtime instance; multi-Agent deployments should use multiple runtime instances or upper-layer routing.
- The runtime does not directly access client local tools, DOM, plugins, files, local ports, or business UI.

### II. Agent Engine

Not applicable

### III. EvoAgent Self-Evolution Engine

- EvoAgent and EvoAgentAdapter must both be available; trajectory collection and Skill / managed-doc read/write depend on the Adapter.
- Evaluation / optimization tasks are stored in service process memory by default; old `job_id` cannot be queried after service restart.
- Prompt optimization currently optimizes only one Prompt document per task.
- Trajectory cleaning rules are fixed; custom cleaning rules via configuration are not currently supported.
- Trajectory collection log / standard modes are mutually exclusive; switching requires restarting the Adapter.

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

- **Platform Capabilities**: Thanks to all contributors who built the Versatile HTTP/SSE adapter, AgentCore remote A2A tool injection, and dual-Agent demo, making extensions easier to use with clearer boundaries. Your feedback is the driving force behind the continuous evolution of openJiuwen agent-solution.
- **Agent Engine**: The release of EDPAgent Java marks the implementation of the enterprise-grade universal dynamic planning agent on the Java technology stack, providing a secure, controllable, and observable Agent engine foundation for the intelligent transformation of the financial and other industries. Your professional contributions are the solid foundation for EDPAgent to meet enterprise-grade requirements in vertical industries such as finance.
- **EvoAgent Self-Evolution Engine**: Your feedback is the driving force behind the continuous evolution of the Agent self-evolution engine (EvoAgent).
