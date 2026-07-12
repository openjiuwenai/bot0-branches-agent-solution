# Intent Recognition Runtime Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build two Spring Boot A2A runtimes that demonstrate FEAT-020 through a ReAct Agent Tool and a Workflow Component using real configured models.

**Architecture:** A Maven parent aggregates a shared support JAR and two executable runtime JARs. The support JAR builds one immutable A2A AgentCard catalog and one `StandardReranker`-backed recognizer; each runtime mounts the appropriate FEAT-020 adapter behind `JiuwenCoreAgentExtHandler`.

**Tech Stack:** Java 17, Maven, Spring Boot 4.0.6, agent-core-java 0.1.13, agent-runtime-java 0.1.0, agent-runtime-ext-java 0.1.0-SNAPSHOT, official A2A Java SDK.

---

### Task 1: Maven reactor and support contracts

**Files:**
- Create: `common/example/intent-recognition-runtime-demo/pom.xml`
- Create: `common/example/intent-recognition-runtime-demo/demo-support/pom.xml`
- Test: `demo-support/src/test/java/com/openjiuwen/example/intent/support/IntentDemoPropertiesTest.java`
- Test: `demo-support/src/test/java/com/openjiuwen/example/intent/support/IntentDemoContextTest.java`
- Create: `demo-support/src/main/java/com/openjiuwen/example/intent/support/IntentDemoProperties.java`
- Create: `demo-support/src/main/java/com/openjiuwen/example/intent/support/IntentDemoContext.java`

- [ ] Write tests requiring exact YAML-to-core configuration mapping and three standard A2A cards.
- [ ] Run `mvn -pl demo-support test` and confirm compilation fails because support classes do not exist.
- [ ] Implement validated properties and the shared recognizer/card factory with `StandardReranker`.
- [ ] Run support tests and confirm they pass.

### Task 2: ReAct Agent runtime

**Files:**
- Create: `react-agent-runtime/pom.xml`
- Test: `react-agent-runtime/src/test/java/com/openjiuwen/example/intent/react/ReactIntentRuntimeApplicationTest.java`
- Create: `react-agent-runtime/src/main/java/com/openjiuwen/example/intent/react/ReactIntentRuntimeApplication.java`

- [ ] Write a context test requiring a `ReActAgent`, registered `IntentRecognitionTool`, and `JiuwenCoreAgentExtHandler`.
- [ ] Run the module test and confirm it fails because the application is absent.
- [ ] Implement the Spring Boot application, real model configuration, tool registration, and handler bean.
- [ ] Run the module test and confirm it passes without making a model request.

### Task 3: Workflow runtime

**Files:**
- Create: `workflow-runtime/pom.xml`
- Test: `workflow-runtime/src/test/java/com/openjiuwen/example/intent/workflow/WorkflowIntentRuntimeApplicationTest.java`
- Create: `workflow-runtime/src/main/java/com/openjiuwen/example/intent/workflow/WorkflowIntentRuntimeApplication.java`

- [ ] Write a context test requiring a `WorkflowAgent`, an intent workflow, and `JiuwenCoreAgentExtHandler`.
- [ ] Run the module test and confirm it fails because the application is absent.
- [ ] Implement `Start -> IntentRecognitionComponent -> End`, attach it to `WorkflowAgent`, and expose the handler.
- [ ] Invoke the workflow in the test with a deterministic test reranker and verify the selected card.
- [ ] Run the module test and confirm it passes.

### Task 4: Runtime configuration and operator guide

**Files:**
- Create: `react-agent-runtime/src/main/resources/application.yml`
- Create: `workflow-runtime/src/main/resources/application.yml`
- Create: `react-agent-runtime/src/main/resources/a2a-requests/order.json`
- Create: `workflow-runtime/src/main/resources/a2a-requests/order.json`
- Create: `common/example/intent-recognition-runtime-demo/README.md`
- Modify: `common/README.md`

- [ ] Add explicit real model/reranker placeholders and distinct ports.
- [ ] Add equivalent A2A `SendMessage` requests for both runtimes.
- [ ] Document installation, startup, curl commands, expected AgentCard, and failure diagnosis.
- [ ] Run `mvn clean verify` for the full demo reactor.

### Task 5: End-to-end protocol verification

- [ ] Start each application with local test model/reranker endpoints or user-provided real endpoints.
- [ ] Send the documented curl requests to both `/a2a/` endpoints.
- [ ] Verify HTTP success and that both responses select the Order Agent card.
- [ ] Run `git diff --check` and inspect final branch status.
