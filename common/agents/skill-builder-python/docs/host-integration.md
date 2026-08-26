# Host Integration

## Ownership boundary

Skill Builder owns the complete business lifecycle and returns typed state.
The host owns task scheduling, database/object-storage adapters, user identity,
HTTP, UI, and any external publish action.

The host must not reimplement Scenario, Author, Repair, Acceptance, or derive a
second delivery decision. Use `SkillBuilderClient.present(execution)` for the
state shown to users.

## Create one host client

Create one `SubprocessAgentRunner` per host process so its concurrency limit is
shared by all workspaces:

```python
from pathlib import Path

from skill_builder import SkillBuilderClient
from skill_builder.adapters import (
    AgentCoreProcessConfig,
    JiuwenboxExecutionPort,
    SubprocessAgentRunner,
)
from skill_builder.spi import (
    CallbackEventSink,
    JsonFileStateStore,
    SkillBuilderAdapters,
)

async def persist_event(event_type, summary, payload):
    # Replace with the host event table or message bus.
    print(event_type, summary)

runner = SubprocessAgentRunner(
    AgentCoreProcessConfig(
        max_concurrency=2,
        timeout_seconds=None,
    )
)
client = SkillBuilderClient(
    adapters=SkillBuilderAdapters(
        state_store=JsonFileStateStore(Path("./state")),
        event_sink=CallbackEventSink(persist_event),
        agent_runner=runner,
        execution_port=JiuwenboxExecutionPort(),
    )
)
```

`timeout_seconds=None` leaves phase deadlines to the existing activity-aware
Agent Core limits. A host-level timeout is a hard operational stop and should
not be used to compensate for model or contract failures.

## Prepare workspace and materials

The host owns upload and material preprocessing. For each workspace it should:

1. allocate a tenant-confined writable root;
2. store original uploads under `inputs/` using safe relative names;
3. reject path traversal and untrusted symbolic links;
4. enforce file count, individual size, total size, and type policy;
5. convert PDF/DOCX/XLSX or other binary documents into traceable Markdown;
6. preserve both the original path and parsed-material path in the material
   index;
7. construct bounded `materials_markdown` and `SkillBuilderInput`.

Skill Builder owns material reading budgets after the input is prepared. It
does not own host upload authentication, malware scanning, object storage, or a
database asset table.

An illustrative workspace is:

```text
workspace-root/
├── inputs/
│   ├── workflow-rules/policy.docx
│   ├── workflow-rules/policy_parsed.md
│   ├── data-examples/sample.csv
│   └── external-sources/<recording-id>/web-recording.md
├── generated-skill/
├── validation/
├── workspace/
├── playwright/
└── .skill-builder/
```

Do not put `validation/`, `.skill-builder/`, `workspace/`, or `playwright/`
inside the exported Skill package.

## Start in a background task

```python
task = asyncio.create_task(client.build(builder_input))
execution = await task
```

Cancelling this task terminates the current Agent Core child process. State
checkpoints already written by `JsonFileStateStore` remain available.

The host must allow only one mutating lifecycle task for a `workspace_id` at a
time. `SubprocessAgentRunner.max_concurrency` limits model-worker concurrency
for the host process; it is not a per-workspace lock. Use a task-table unique
constraint, distributed lease, or StateStore compare-and-set before calling
`build`, `resume`, mutating `run_turn`, or `repair`.

Production hosts normally replace `JsonFileStateStore` with their own
`SkillBuilderStateStore`. The stored document must preserve the public
`SkillBuilderState.to_dict()` representation and reject unsupported schema
versions rather than silently coercing them.

## Public operation mapping

| Host operation | Skill Builder call | Notes |
|---|---|---|
| Start generation | `client.build(builder_input)` | Starts Scenario, optional HITL, Author, Acceptance |
| Recover uncertain/interrupted work | `client.reconcile(builder_input, advance=...)` | Uses persisted state to select the legal next step |
| Load current state | `client.load(workspace_id)` | Requires a StateStore |
| Submit HITL answer | `client.resume(workspace_id, resume_token=..., answer=...)` | Only for `waiting_for_user`; use the exact pending token |
| Continue failed execution | Build a `kind="resume"` recovery message and call `client.reconcile(..., advance=True)` without resetting outputs | Preserve candidate, checkpoints, and inputs |
| Retry failed execution | Build a `kind="retry"` recovery message, call `reset_generated_outputs`, then `client.build(...)` | Fresh extraction; preserve `inputs/` and retained confirmations |
| Validate current package | `client.validate(execution.input, hitl_confirmations=...)` | Does not require a model for pure checks |
| Explicit mechanical repair | `client.repair(execution, instruction=...)` | Only for structured, mechanically repairable diagnostics |
| Read/edit conversation | `client.run_turn(workspace_id, SkillBuilderTurnRequest(...))` | Core applies read/write policy and rollback |
| Register host-side manual edit | `client.invalidate_receipt(workspace_id)` | Clears stale Acceptance identity |
| Export | `client.build_export_archive(execution)` | Host writes bytes to local/object storage |
| Build compatibility publish archive | `client.build_publish_archive(execution, author=...)` | Requires Core `publishable`; still does not publish externally |

HITL resume, failed-run continue, and failed-run retry are three different host
operations. Do not route them to one generic "run again" method.

Recommended failed-run continue implementation:

```python
from dataclasses import replace

current = await client.load(workspace_id)
if current is None or current.status.value != "failed":
    raise Conflict("continue requires a failed execution")

message = client.build_recovery_message(
    current,
    kind="resume",
    user_message=user_message,
)

execution = await client.reconcile(
    replace(current.input, user_message=message),
    options=replace(current.options, run_phase="workflow"),
    hitl_confirmations=current.hitl_confirmations,
    advance=True,
)
```

Continue preserves the current candidate, validation diagnostics, Draft
Workspace, revision/checkpoint state, and `inputs/`. Core resumes a committed
candidate when one exists; otherwise it selects the next legal generation step.

Recommended failed-run retry implementation:

```python
from dataclasses import replace
from skill_builder.host_support import reset_generated_outputs

current = await client.load(workspace_id)
if current is None or current.status.value != "failed":
    raise Conflict("retry requires a failed execution")

message = client.build_recovery_message(
    current,
    kind="retry",
    user_message=user_message,
)
confirmations = current.hitl_confirmations
reset_generated_outputs(current.input.root)

execution = await client.build(
    replace(current.input, user_message=message),
    options=replace(current.options, run_phase="workflow"),
    hitl_confirmations=confirmations,
)
```

Retry removes `generated-skill/`, `validation/`, `playwright/`, generation
checkpoints, state, drafts, revisions, and private context. It preserves
`inputs/`; the host may pass still-valid structured confirmations explicitly.
The host must update any asset records that pointed at removed diagnostic files.

There is no public `client.retry()` method because reset policy belongs to the
host's workspace/storage boundary. Neither continue nor retry may replay an
Agent worker request directly.

All three operations are mutating. The host must acquire the same workspace
lease used by `build` before calling them.

## Suggested host endpoints

| Endpoint | Host behavior |
|---|---|
| `POST /skill-builder/workspaces/{id}/build` | Construct input and start `client.build` in a background task |
| `GET /skill-builder/workspaces/{id}` | `client.load` then `client.present` |
| `POST /skill-builder/workspaces/{id}/hitl/{request_id}/answer` | Persist/validate the answer, acquire lock, call Core `resume` |
| `POST /skill-builder/workspaces/{id}/continue` | Failed-run continue; preserve outputs and call `reconcile` |
| `POST /skill-builder/workspaces/{id}/retry` | Failed-run fresh extraction; reset outputs then call `build` |
| `POST /skill-builder/workspaces/{id}/validate` | Load current execution and call `client.validate` |
| `POST /skill-builder/workspaces/{id}/repair` | Only for a confirmed mechanically repairable diagnostic |
| `POST /skill-builder/workspaces/{id}/turns` | Call `client.run_turn` with an explicit/auto action |
| `GET /skill-builder/workspaces/{id}/export` | Build archive and let the host return/store it |
| `DELETE /skill-builder/workspaces/{id}/active-task` | Cancel and await the host background task |

These are suggested routes. Skill Builder does not provide an HTTP server.

Example validation after loading state:

```python
execution = await client.load(workspace_id)
if execution is None:
    raise KeyError(workspace_id)

execution = await client.validate(
    execution.input,
    hitl_confirmations=execution.hitl_confirmations,
)
```

Example conversational edit:

```python
from skill_builder import SkillBuilderTurnRequest

execution = await client.run_turn(
    workspace_id,
    SkillBuilderTurnRequest(
        message="Update the output template using the supplied policy material.",
        requested_action="edit",
    ),
)
```

## HITL and resume

When no synchronous HITL provider is configured, a real ambiguity returns:

```python
execution.status.value == "waiting_for_user"
execution.pending_request.request
execution.pending_request.resume_token
```

Render `pending_request.request` as provided. After the user completes it:

```python
execution = await client.resume(
    execution.workspace_id,
    resume_token=resume_token,
    answer=answer,
)
```

Do not auto-reject a pending request and do not construct a replacement form in
the host. The token binds the answer to the durable pending decision.

## Display and delivery

```python
view = client.present(execution)
```

Use `view.workspace_status`, `view.validation_status`,
`view.delivery_decision`, `view.summary`, `view.blockers`, and
`view.available_actions` directly.

The complete mapping, including `ready + warn`, is in
[Status and Host Actions](status-and-actions.md).

- `ready`: the current artifact has a valid acceptance receipt.
- `needs_review`: inspection and host-controlled export are allowed, but the
  host must disable automatic publish.
- `failed`: use `execution.failure.code/category/retryable/repairable`; never
  classify exception text.

Skill Builder can construct a safe export archive:

```python
archive = client.build_export_archive(execution)
target.write_bytes(archive.content)
```

Writing it to object storage, returning it to a user, approval, and publishing
are host operations. `build_publish_archive` is retained only as a compatibility
archive builder and does not call a marketplace.

Use `execution.artifact_sha256` as the stable identity of generated Skill
content. ZIP metadata includes construction time in the inherited implementation,
so rebuilding an archive later may produce a different archive SHA without a
change to the accepted Skill artifact.

## Host-side edits

If the host edits `generated-skill/` without using `run_turn`, invalidate the
old receipt immediately:

```python
execution = await client.invalidate_receipt(workspace_id)
view = client.present(execution)
```

Then run `validate` before enabling any publication action. Never retain a
previous `ready` UI state after package content changes.

## Sandbox behavior

Agent Core child processes create Jiuwenbox workspace sessions when
`SKILL_BUILDER_SANDBOX_ENABLED=true`. Final Acceptance uses a separate
short-lived session through `JiuwenboxExecutionPort`.

The Jiuwenbox daemon remains a separate service. LLM keys are inherited through
the child environment and are never written to worker request/result files.

## Recording adapter

Recording is a separate pre-build material-capture lifecycle. The host calls
the public functions in `skill_builder.recording`, registers the final
`web-recording.md` as input material, and then calls `build/reconcile`.

See [Recording Integration](recording-integration.md) for exact calls, suggested
host endpoints, Chromium/display setup, sensitive asset handling, and the
process-local recording limitation.

Recording success must not be mapped to browser Acceptance success.

## Host shutdown and workspace deletion

Before removing a workspace or stopping a host worker:

- cancel and await the active Skill Builder task;
- stop any active Playwright recording in that same process;
- release the host workspace lease;
- request cleanup of any Jiuwenbox sessions owned by the workspace;
- preserve inputs and the latest committed state unless the user explicitly
  requested permanent deletion;
- do not delete object-storage artifacts through Core cleanup code.
