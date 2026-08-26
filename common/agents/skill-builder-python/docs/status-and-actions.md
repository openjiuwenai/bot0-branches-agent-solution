# Status and Host Actions

## One source of truth

Hosts must render Skill Builder state from:

```python
view = client.present(execution)
```

Do not infer lifecycle state from event text, the presence of a directory, a
worker exit code, or an exception string. Events describe progress; persisted
state and the current artifact receipt determine delivery.

The projection has four related dimensions:

- `workspace_status`: where the lifecycle currently is;
- `validation_status`: the Acceptance result for the current candidate;
- `delivery_decision`: whether the current candidate is deliverable;
- `publishable`: Core's local publish eligibility for the current artifact.

These values are related but are not interchangeable.

## Workspace status

| `workspace_status` | Meaning | Host behavior |
|---|---|---|
| `queued` | Work has been accepted but has not advanced yet | Show queued; reject another mutating task for the same workspace |
| `running` | Core or an Agent Core phase is running | Stream events; allow host cancellation; disable edit and publish |
| `waiting_for_user` | A real business decision is missing | Render `pending_request.request`; submit through `resume` |
| `draft_ready` | A candidate exists without a current publishable receipt | Allow inspect, edit, validate, and draft export when returned in `available_actions` |
| `needs_review` | The package can be inspected/exported, but a human or external boundary blocks automatic publication | Display blockers and review scope; disable automatic publish |
| `ready` | Acceptance is bound to the current artifact | Allow export; host publishing still follows host approval policy |
| `failed` | The current operation did not produce an acceptable result | Display structured failure and only the actions returned by Core |

`waiting_for_user` is not a failure. There is no running Agent Core child while
the user is deciding.

## Validation status

| `validation_status` | Meaning | Typical display |
|---|---|---|
| `not_run` | The current candidate has not been accepted | Neutral "Not validated" |
| `pass` | No blocking Acceptance finding | Success |
| `warn` | Only non-blocking findings remain | Success with visible warnings |
| `fail` | At least one finding invalidates usability or Acceptance | Blocking error |

A warning is not a lifecycle status. `workspace_status=ready` and
`validation_status=warn` is valid when the warning does not affect verified
usability.

Do not convert every warning into `needs_review`, and do not downgrade a
blocking finding to a warning in the host.

## Delivery decision

| `delivery_decision` | Meaning | Automatic publish |
|---|---|---|
| `draft_ready` | Draft delivery boundary only | No |
| `ready` | Current candidate has a valid delivery decision | Only when `publishable=True` and host approval allows it |
| `needs_review` | Human/external confirmation is still required | No |
| `blocked` | Delivery requirements are not met | No |
| `failed` | Execution failed | No |

`publishable` is calculated by Core as:

```text
delivery_decision == ready AND current artifact receipt is valid
```

It does not publish anything and does not bypass host approval, tenant policy,
malware/license scanning, or marketplace review.

## External verification not run

Classify an unrun external check by its role in the Skill promise:

1. Core behavior, package structure, offline logic, and safe fallback have
   already been verified; live API/browser evidence is additional. The result
   may remain `ready + warn`, with the unverified scope shown to the user.
2. The Skill's core promise is the live API/browser operation and no trusted
   substitute proves it usable. The result must be `needs_review`; export for
   inspection is allowed, automatic publish is not.

Recording a browser workflow is input evidence. It is not live browser
verification of the generated Skill.

## Available actions

Always render the exact `view.available_actions` returned by Core. The current
implementation derives actions as follows:

| Condition | Returned actions |
|---|---|
| `waiting_for_user` | `resume` only |
| `running` | none |
| Any other state | `inspect` |
| Current artifact exists | additionally `edit`, `export`, `validate` |
| `failed` | additionally `retry` |
| `publishable=True` | additionally `publish` |

`ExecutionAction.REPAIR` exists as a public enum, but the current
`available_actions` property does not automatically emit it. A diagnostic UI
may offer explicit Repair only after checking the structured failure/finding
and confirming it is mechanically repairable. It must then call:

```python
repaired = await client.repair(execution, instruction=instruction)
```

Do not offer Repair for business ambiguity, capability direction, fixture
semantics, or missing external proof.

There is no public `client.retry()` method. If Core returns the `retry` action,
the host starts a new `build` or calls `reconcile` according to its task policy.
It must not replay the last Agent Core request directly.

## HITL mapping

For `waiting_for_user`:

```python
pending = execution.pending_request
form = pending.request
resume_token = pending.resume_token
```

The host must:

- render the supplied form rather than synthesize a different decision;
- bind the request to the authenticated user and workspace;
- prevent duplicate submissions;
- call `client.resume(workspace_id, resume_token=..., answer=...)`;
- keep the pending request available when an answer is rejected as incomplete.

## Failure mapping

Use the structured value on `execution.failure`:

```text
code
category
retryable
repairable
user_message
developer_message
details
```

Show `user_message` to the user. Store `developer_message` and `details` in
restricted diagnostics. Never classify retry/repair eligibility by parsing
tracebacks or localized text.

## Manual edits and receipt invalidation

When the host or a user changes `generated-skill/` outside `run_turn`, call:

```python
execution = await client.invalidate_receipt(workspace_id)
```

Then render the returned projection and run `validate` before publication. A
previous `ready` state must never be reused after artifact content changes.

Use `execution.artifact_sha256` as the stable identity of Skill content. ZIP
construction metadata may change the archive SHA without changing this artifact
identity.

## Suggested host DTO

```python
view = client.present(execution)
payload = {
    "workspaceStatus": view.workspace_status,
    "draftStatus": view.draft_status,
    "validationStatus": view.validation_status,
    "deliveryDecision": view.delivery_decision.value,
    "publishable": view.publishable,
    "summary": view.summary,
    "blockers": list(view.blockers),
    "availableActions": [item.value for item in view.available_actions],
    "acceptance": view.acceptance,
    "artifactFiles": list(view.artifact_files),
    "failure": execution.failure.to_dict() if execution.failure else None,
    "pendingRequest": (
        execution.pending_request.to_dict()
        if execution.pending_request is not None
        else None
    ),
}
```

The host may rename JSON fields, but it must not recalculate their business
meaning.
