# Recording Integration

## Scope

`skill_builder.recording` is an optional material-capture runtime. It records a
user-demonstrated web workflow and produces Markdown that Scenario can consume.

It is not:

- part of the normal `SkillBuilderClient.build` lifecycle;
- a Jiuwenbox sandbox;
- live browser verification of a generated Skill;
- a host HTTP/UI implementation;
- persistent recording coordination across host process restarts.

## Install

```bash
python -m pip install 'openjiuwen-skill-builder[recording]'
python -m playwright install chromium
```

Run `playwright install chromium` with the same Python environment and
`PLAYWRIGHT_BROWSERS_PATH` used by the host process.

## Configuration

| Variable | Default | Purpose |
|---|---:|---|
| `PLAYWRIGHT_BROWSERS_PATH` | Playwright default | Shared Chromium cache/location |
| `WEB_RECORDING_HEADLESS` | `auto` | `auto`, `true/headless/viewer`, or `false/headed/desktop` |
| `WEB_RECORDING_DISPLAY` | `DISPLAY` | X11 display for headed mode |
| `WEB_RECORDING_XAUTHORITY` | `XAUTHORITY` or readable user fallback | X11 authorization file |
| `WEB_RECORDING_DISPLAY_PROBE_TIMEOUT_SECONDS` | `3` | X11 capability probe, bounded to 1-10 seconds |
| `WEB_RECORDING_WINDOW_WIDTH` | `1280` | Recording viewport/window width |
| `WEB_RECORDING_WINDOW_HEIGHT` | `860` | Recording viewport/window height |

In headless/viewer mode, the host shows frames from
`capture_recording_frame()` and sends explicit actions. In headed mode, the
user can operate the visible browser and the host can still poll snapshots.

## Host ownership

Skill Builder recording core owns:

- URL syntax validation for HTTP/HTTPS;
- Playwright context creation;
- page event, screenshot, download, and trace capture;
- best-effort masking of password-like input in the Markdown event record;
- generation of the final `web-recording.md` material;
- normal stop and Playwright cleanup.

The host adapter owns:

- authenticated start/frame/action/stop endpoints and UI;
- workspace and tenant authorization;
- URL, DNS, proxy, domain, and network egress policy;
- Chromium installation and display/X11 configuration;
- asset records, object storage, retention, and access control;
- graceful stop during task cancellation, workspace deletion, and shutdown;
- inclusion of the generated Markdown in the next material bundle.

The built-in URL check only accepts HTTP/HTTPS syntax. It is not an SSRF or
domain allowlist. Production hosts must enforce their own network policy before
calling `start_recording` or a `navigate` action.

## Public API

```python
from skill_builder.recording import (
    RecordingAction,
    capture_recording_frame,
    get_active_recording,
    perform_recording_action,
    recording_snapshot,
    start_recording,
    stop_recording,
)
```

### Start

```python
recording, capability = await start_recording(
    root=workspace_root,
    workspace_id=workspace_id,
    start_url="https://approved.example/app",
    title="Invoice approval demonstration",
    goal="Show the normal review and submission flow",
)

response = {
    **recording_snapshot(recording),
    "display_capability": capability,
}
```

Only one active recording is allowed per `workspace_id` in one host process.

### Poll status and frame

```python
recording = get_active_recording(workspace_id)
status = recording_snapshot(recording) if recording is not None else None

png = await capture_recording_frame(
    workspace_id=workspace_id,
    recording_id=recording_id,
)
```

The host can return `png` as an authenticated image response. Do not publish it
as a public static asset by default.

### Perform a viewer action

Supported actions are `click`, `type`, `press`, `scroll`, `navigate`, and
`refresh`:

```python
recording = await perform_recording_action(
    root=workspace_root,
    workspace_id=workspace_id,
    recording_id=recording_id,
    action=RecordingAction(action="click", x=420, y=260),
)

recording = await perform_recording_action(
    root=workspace_root,
    workspace_id=workspace_id,
    recording_id=recording_id,
    action=RecordingAction(action="type", text="example input"),
)
```

The host must obtain explicit user authorization before sending risky or
irreversible actions. The recording API demonstrates user operations; it does
not grant business authorization.

### Stop and register material

```python
recording, markdown = await stop_recording(
    root=workspace_root,
    workspace_id=workspace_id,
    recording_id=recording_id,
)

material_path = workspace_root / recording.material_path
assert material_path.read_text(encoding="utf-8") == markdown
```

The final material path is:

```text
inputs/external-sources/<recording_id>/web-recording.md
```

The host registers this path as a Markdown material and includes it in the
material index/`materials_markdown` passed to `SkillBuilderInput`. Screenshots,
downloads, trace, profile, and storage state are diagnostic assets, not model
material unless the host deliberately preprocesses and authorizes them.

## Workspace outputs

```text
playwright/
├── profile/
├── storage-state.json
└── recordings/<recording_id>/
    ├── recording.md
    ├── metadata.json
    ├── screenshots/
    ├── downloads/
    └── trace.zip

inputs/external-sources/<recording_id>/
└── web-recording.md
```

`storage-state.json`, screenshots, downloads, and traces may contain session
cookies, personal data, internal URLs, or visible secrets. Restrict access,
encrypt storage where required, and define deletion/retention policy.

Password-like typed values are masked in the Markdown event record on a
best-effort basis. This does not redact screenshots or downloaded files.

## Suggested host endpoints

| Host endpoint | Recording call | Response |
|---|---|---|
| `POST /workspaces/{id}/recording` | `start_recording` | snapshot plus display capability |
| `GET /workspaces/{id}/recording` | `get_active_recording` + `recording_snapshot` | current snapshot or empty |
| `GET /workspaces/{id}/recording/frame` | `capture_recording_frame` | authenticated PNG |
| `POST /workspaces/{id}/recording/actions` | `perform_recording_action` | updated snapshot |
| `DELETE /workspaces/{id}/recording` | `stop_recording` | completed snapshot and material path |

These are suggested host routes, not routes implemented by this package.

Map `RecordingError.status_code` to the host transport only after authentication
and auditing. Do not expose raw Playwright exceptions or local paths to end
users.

## Process lifecycle limitation

Active Playwright objects are held in the current process in
`_ACTIVE_WEB_RECORDINGS`. A different process cannot resume an active recording
after a host restart. Therefore the host must:

- route all operations for one active recording to the same process;
- stop active recordings during graceful shutdown;
- mark in-progress asset records interrupted after an unexpected restart;
- clean deployment-level orphan browser processes and temporary assets;
- start a new recording rather than claiming the interrupted one continued.

This limitation affects recording capture only. Generated Markdown already
written to `inputs/` remains ordinary durable input material.

## Relationship to browser validation

Recording answers: "What workflow did the user demonstrate?"

Browser validation answers: "Can the generated Skill execute its declared
browser capability correctly in an approved external environment?"

The first is implemented here. The second is a separate future Acceptance
adapter and must not be inferred from recording success.
