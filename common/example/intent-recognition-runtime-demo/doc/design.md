# Intent Recognition Runtime Demo Design

## Goal

Provide two independently executable Spring Boot applications that expose the FEAT-020 intent recognition module through the agent-runtime A2A endpoint:

- a ReAct Agent application using `IntentRecognitionTool`;
- a Workflow Agent application whose workflow contains `IntentRecognitionComponent`.

Both applications use real chat-model and reranker endpoints configured in `application.yml`.

## Structure

```text
intent-recognition-runtime-demo
|-- demo-support
|-- react-agent-runtime
`-- workflow-runtime
```

`demo-support` owns configuration types, the artificial standard A2A AgentCard catalog, and construction of the shared `IntentRecognizer<AgentCard>`. It has no HTTP entrypoint.

Each runtime is a separate executable JAR. Both use `agent-service-app` for the A2A server and `JiuwenCoreAgentExtHandler` to bridge requests to agent-core-java.

## Request Flows

The ReAct runtime registers `IntentRecognitionTool` in both `Runner.resourceMgr()` and the ReAct Agent ability manager. A real LLM receives the user query, calls the tool with `{"utterance":"..."}`, observes the complete selected A2A AgentCard, and returns a concise answer.

The Workflow runtime creates a `WorkflowAgent` containing one workflow:

```text
Start -> IntentRecognitionComponent -> End
```

The Workflow Agent uses the configured real LLM to select the workflow. The intent node uses the configured real `StandardReranker` and returns the complete selected A2A AgentCard.

## Configuration

Each executable has an `application.yml` containing:

- chat model provider, API key, API base, model name, SSL verification, timeout, temperature, and top-p;
- reranker API key, API base, model name, timeout, and extra request body;
- intent score threshold, margin threshold, candidate batch size, and utterance length;
- runtime port and agent prompt.

Missing model endpoints or model names fail application initialization with the exact property name. Secrets are empty placeholders for local completion.

## Verification

Unit tests verify property validation/mapping, construction of the three-card artificial catalog, Tool registration, Workflow graph execution, and Handler host types without calling external services.

Manual verification starts both JARs and sends A2A `SendMessage` requests with `curl`. Successful responses must show that both paths match the same target AgentCard for the same utterance. Remote model and reranker calls are intentionally part of this manual end-to-end check.
