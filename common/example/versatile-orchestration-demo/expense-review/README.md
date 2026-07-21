# Expense Review (versatile-orchestration-demo)

A faithful port of the `expense-review` Workflow agent onto the OpenJiuwen
agent-runtime (`com.openjiuwen:agent-service-*`). Same 8-node Workflow DAG, same
prompts, same `CompanyPolicyTool`, same two flow paths as the
`examples/financial/expense-review-workflow/` original.

This directory holds the **workflow host** (`expense-review`, port `18096`).
Its sibling `../expense-review-main/` holds the **ReAct caller** (`18097`) that
invokes the workflow over remote A2A. The two apps run as separate Spring Boot
processes — the OpenJiuwen host pins one `AgentHandler` per app.

## Architecture

```
expense-review-main  (:18097)              expense-review  (:18096)
  ReActAgent "Main"                          WorkflowAgent (single DAG)
  JiuwenCoreAgentExtHandler                  JiuwenCoreAgentHandler (stock)
  └─ review_expense (remote-A2A tool) ──────▶  A2A server, skill: review_expense
```

The DAG (`buildExpenseReviewWorkflow` in `ExpenseReviewConfiguration`):

```
start → analyze (LLM) → check_policy (Tool: CompanyPolicyTool)
      → audit (LLM) → route (Branch on ${audit.risk_level})
                        ├─ "high"  → approve (Questioner, HITL) → end
                        └─ default → auto_approve (LLM)         → end
```

- **Path B (compliant)** — every item is within policy → `risk_level != "high"`
  → `auto_approve` → `end`. One A2A `SendStreamingMessage` completes with state
  `COMPLETED`.
- **Path A (over-limit)** — some item exceeds its category limit → `risk_level
  == "high"` → `approve` (Questioner) → the stream ends with `INPUT_REQUIRED`
  and a `taskId`; a second request carrying `approved` resumes the workflow →
  `end` → `COMPLETED`.

The interrupt/resume machinery is owned by `WorkflowEventHandler` + the host's
A2A orchestrator — this module contributes nothing to it beyond the DAG shape.

## Build

From the repo root:

```bash
mvn -f examples/versatile-orchestration-demo/pom.xml package -DskipTests
```

This produces both
`expense-review/target/versatile-orch-demo-expense-review-0.2.0-SNAPSHOT.jar` and
`expense-review-main/target/versatile-orch-demo-expense-review-main-0.2.0-SNAPSHOT.jar`.

## Run (two terminals)

Both apps need an OpenAI-compatible endpoint. Set the same env vars in each
terminal:

```bash
export LLM_API_KEY=<key>
export LLM_API_BASE=<openai-compatible-endpoint>   # default http://localhost:4000/v1
export LLM_MODEL=<model>                            # default gpt-4o-mini
```

**Terminal 1 — workflow host (18096):**

```bash
java -jar examples/versatile-orchestration-demo/expense-review/target/versatile-orch-demo-expense-review-0.2.0-SNAPSHOT.jar
```

Smoke-check its agent card:

```bash
curl -s http://127.0.0.1:18096/.well-known/agent-card.json | head
# expect: "name": "expense-review", and skills[].id includes "review_expense"
```

**Terminal 2 — ReAct caller (18097):**

```bash
java -jar examples/versatile-orchestration-demo/expense-review-main/target/versatile-orch-demo-expense-review-main-0.2.0-SNAPSHOT.jar
```

On startup, `RemoteA2aToolInstaller` fetches the workflow's card at
`http://127.0.0.1:18096`, discovers the `review_expense` skill, and installs it
as a tool on the ReAct agent. The caller's card:

```bash
curl -s http://127.0.0.1:18097/.well-known/agent-card.json | head
# expect: "name": "expense-review-main"
```

## Path B — compliant (auto-approve)

POST to the **caller** (18097):

```bash
curl -N -X POST http://127.0.0.1:18097/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"jsonrpc":"2.0","id":"b1","method":"SendStreamingMessage","params":{"message":{"role":"user","parts":[{"type":"text","text":"报销：出租车100元，餐饮200元。请审核。"}],"messageId":"m-b1","contextId":"ctx-b1"}}}'
```

Expected: the caller invokes `review_expense`; the workflow runs
`start→analyze→check_policy→audit→route→auto_approve→end`; the SSE stream
completes with state `COMPLETED` and a Chinese approval summary.

## Path A — over-limit (human approval → resume)

**First request** (over-limit hotel):

```bash
curl -N -X POST http://127.0.0.1:18097/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"jsonrpc":"2.0","id":"a1","method":"SendStreamingMessage","params":{"message":{"role":"user","parts":[{"type":"text","text":"报销：酒店三晚共3000元。请审核。"}],"messageId":"m-a1","contextId":"ctx-a1"}}}'
```

Expected: the workflow reaches the `approve` Questioner; the stream ends with
state `INPUT_REQUIRED` and a `taskId`. Capture it:

```bash
TASK_ID=<taskId from the INPUT_REQUIRED response>
```

**Resume request** (same `contextId` + `taskId`, carrying the approval):

```bash
curl -N -X POST http://127.0.0.1:18097/a2a \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d "{\"jsonrpc\":\"2.0\",\"id\":\"a2\",\"method\":\"SendStreamingMessage\",\"params\":{\"message\":{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"approved\"}],\"messageId\":\"m-a2\",\"contextId\":\"ctx-a1\",\"taskId\":\"$TASK_ID\"}}}"
```

Expected: the workflow resumes at `approve`, flows to `end`, and the stream
completes with state `COMPLETED`.

## Troubleshooting

If a path fails, check in order:

1. **Workflow lookup key.** If the structure/boot tests pass but a runtime run
   logs `Workflow not found: expense-review_1.0`, the `addWorkflows` registration
   key and the `execTask` lookup key disagree. Both are framework-internal and
   covered by agent-core-java's own tests, so a mismatch is unlikely — read
   `com.openjiuwen.core.workflow.WorkflowUtils.generateWorkflowKey` and align
   `WorkflowCard.version` if it occurs.

2. **Path A resume routing.** If the resume request starts a new run instead of
   resuming, the orchestrator may not be feeding the answer as an
   `InteractiveInput`. The fallback is `WorkflowEventHandler.findInterruptedTask`
   via session state keyed by `contextId`/`conversationId` — confirm the resume
   request reuses the same `contextId`. Trace
   `com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator` for
   deeper debugging.

3. **Tool-result namespace.** The `audit` node binds `${check_policy.policy_rules}`.
   If the audit prompt receives `null` for `policy_rules`, `ToolComponent` is
   namespacing the result under `.data` (`ToolComponentOutput.RESTFUL_DATA`);
   switch the `audit` node's input map and prompt to `${check_policy.data.policy_rules}`
   (one-line fix in `buildExpenseReviewWorkflow`).

4. **Caller output truncation.** The ReAct caller uses `maxTokens = 256` (per the
   design spec §5.3; the sibling `plan-agent` uses 512). If Path A/B responses
   look truncated, bump it to `512` in `ExpenseReviewMainConfiguration#buildReActAgent`.

## Tests

- `ExpenseReviewDagStructureTest` — unit; asserts the DAG card identity without
  Spring or an LLM.
- `CompanyPolicyToolTest` — unit; exercises the tool's category limits and edge
  cases.
- `ExpenseReviewBootSmokeTest` / `ExpenseReviewMainBootSmokeTest` — Spring
  context smoke; the `AgentHandler` bean loads with dummy model creds.

Run from the repo root:

```bash
mvn -f examples/versatile-orchestration-demo/pom.xml test
```
