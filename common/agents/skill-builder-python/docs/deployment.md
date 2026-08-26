# Deployment

## Deployment model

Skill Builder is currently an installable Python Agent package, not a standalone
HTTP/A2A server. Deploy it inside a Python host background process:

```text
Host API / task worker
└── SkillBuilderClient
    ├── Agent Core child process
    ├── shared/persistent StateStore and workspace
    └── JiuwenboxExecutionPort
            │
            ▼
      separate Jiuwenbox service
```

The host owns HTTP, authentication, tenant isolation, queueing, one-workspace
write locks, HITL/continue/retry endpoints, object storage, and publication.

## Prerequisites

- Linux deployment supported by the selected OpenJiuwen and Jiuwenbox builds;
- Python `>=3.11.4` for the host and Agent Core workers;
- an OpenAI-compatible model endpoint and credential;
- a separately installed Jiuwenbox service reachable from the host;
- persistent, writable workspace and state storage;
- optional Playwright/Chromium for material recording.

`jiuwenbox` is not installed by the Skill Builder wheel. Obtain its approved
distribution separately and verify its module before deployment:

```bash
/opt/jiuwenbox/.venv/bin/python -m jiuwenbox.server.launcher --help
```

## Filesystem layout

One possible production layout is:

```text
/opt/skill-builder-python/             installed application/venv
/etc/skill-builder/skill-builder.env   environment and secret references
/var/lib/skill-builder/workspaces/     persistent workspace roots
/var/lib/skill-builder/state/          state when not stored per workspace
/var/log/skill-builder/                host logs
/var/log/jiuwenbox/                    optional sandbox audit logs
```

Each workspace must be confined to its own directory. Do not use `/`, a user
home, or the repository root as a generated workspace. The host service account
needs write access only to its configured workspace/state roots.

For multi-instance hosts, local `JsonFileStateStore` is insufficient by itself.
Use a shared `SkillBuilderStateStore` with transaction/CAS semantics, a shared
workspace filesystem, and a distributed one-writer lease per `workspace_id`.

## Install from source

```bash
cd common/agents/skill-builder-python
python3.11 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install '.[agent-openjiuwen-python]'
```

For development this is editable installation. For production, build and
install the wheel into the host environment:

```bash
python -m build
/opt/skill-builder-host/.venv/bin/python -m pip install \
  dist/openjiuwen_skill_builder-0.1.0-py3-none-any.whl
/opt/skill-builder-host/.venv/bin/python -m pip install 'openjiuwen==0.1.12'
```

The Agent Core child uses `AgentCoreProcessConfig.python_executable`, which
defaults to the host's `sys.executable`. That interpreter must be able to import
both `skill_builder` and `openjiuwen`.

## Configure environment

Start from `.env.example` and provide real secrets through the deployment
secret manager:

```dotenv
SKILL_BUILDER_LLM_PROVIDER=OpenAI
SKILL_BUILDER_LLM_API_BASE=https://model-gateway.example/v1
SKILL_BUILDER_LLM_API_KEY=<secret-manager-reference>
SKILL_BUILDER_LLM_MODEL=<configured-model>

SKILL_BUILDER_SANDBOX_ENABLED=true
SKILL_BUILDER_JIUWENBOX_URL=http://127.0.0.1:8321
```

The package does not load `.env` automatically. For a local smoke only:

```bash
set -a
. ./.env
set +a
```

Production process managers should use an environment file or secret injection
instead. Never write the real API key into the repository, workspace, worker
request/result files, or event payloads.

See [Configuration](configuration.md) for phase budgets, gate rollout,
Jiuwenbox, and recording variables.

## Start Jiuwenbox

For a same-host deployment, bind Jiuwenbox to loopback:

```bash
/opt/jiuwenbox/.venv/bin/python -m jiuwenbox.server.launcher \
  --listen http://127.0.0.1:8321 \
  --log-level info \
  --save-logs /var/log/jiuwenbox
```

Check readiness:

```bash
curl --fail --silent http://127.0.0.1:8321/health
```

Expected response includes `status=ok`. Check the reported runtime and sandbox
security capability according to the deployment's security baseline.

Do not expose an unauthenticated Jiuwenbox management endpoint to public or
untrusted networks. For a remote/container deployment, use network policy,
service authentication/gateway controls where available, and set:

```dotenv
SKILL_BUILDER_JIUWENBOX_URL=http://jiuwenbox.internal:8321
```

## Start the host

Skill Builder itself has no server start command. The host creates one shared
`SubprocessAgentRunner`, constructs `SkillBuilderClient`, and exposes its own
API/task endpoints. See:

- [Host Integration](host-integration.md)
- [Status and Host Actions](status-and-actions.md)
- [host_background.py](../examples/host_background.py)

A local end-to-end smoke can use the example host:

```bash
.venv/bin/python examples/host_background.py \
  --workspace /tmp/skill-builder-smoke/workspace \
  --workspace-id smoke-workspace \
  --materials examples/materials/role-governance.md \
  --skill-name sample-role-skill \
  --display-name "Sample Role Skill" \
  --description "Generate a sample Skill from generic material" \
  --output /tmp/skill-builder-smoke/sample-role-skill.zip
```

The smoke uses the configured real model and Jiuwenbox. It is opt-in and is not
part of default CI.

## Required host endpoints

The host should expose separate operations for:

| Operation | Required semantics |
|---|---|
| Build | Start a background `client.build` under the workspace lock |
| HITL answer | Persist/validate the answer and call Core `resume` with the pending token |
| Continue failed run | Preserve outputs/checkpoints and call `reconcile` with a `kind="resume"` recovery message |
| Retry failed run | Build a `kind="retry"` message, reset generated outputs, then start a fresh `build`; preserve `inputs/` |
| Validate | Load current execution and call `validate` |
| Repair | Only for confirmed mechanically repairable diagnostics |
| Export | Construct archive; host returns or stores it |
| Cancel | Cancel and await the active host task, then release worker/sandbox resources |

HITL answer, failed-run continue, and failed-run retry are not aliases. Detailed
reference implementations are in [Host Integration](host-integration.md) and
the example host.

## systemd example

Jiuwenbox unit template:

```ini
[Unit]
Description=Jiuwenbox sandbox service for Skill Builder
After=network.target

[Service]
Type=simple
User=skillbuilder
Group=skillbuilder
ExecStart=/opt/jiuwenbox/.venv/bin/python -m jiuwenbox.server.launcher --listen http://127.0.0.1:8321 --log-level info --save-logs /var/log/jiuwenbox
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
```

Host service template (replace `your_host.main` with the actual host module):

```ini
[Unit]
Description=Python host containing Skill Builder Agent
After=network.target jiuwenbox.service
Requires=jiuwenbox.service

[Service]
Type=simple
User=skillbuilder
Group=skillbuilder
WorkingDirectory=/opt/skill-builder-host
EnvironmentFile=/etc/skill-builder/skill-builder.env
ExecStart=/opt/skill-builder-host/.venv/bin/python -m your_host.main
Restart=on-failure
RestartSec=3
TimeoutStopSec=45

[Install]
WantedBy=multi-user.target
```

The host shutdown handler must cancel and await active Skill Builder tasks and
stop process-local recordings before exit.

## Container deployment

Use at least two service boundaries:

```text
host container       SkillBuilder package + OpenJiuwen + host API/worker
jiuwenbox container  sandbox management service
```

Persist host workspaces/state on volumes or external storage. Configure the
host with the Jiuwenbox service URL; do not use `127.0.0.1` when Jiuwenbox is in
another container. No shared filesystem with Jiuwenbox is required by the
current adapter because workspace content is transferred through the client.

The repository does not currently ship a Jiuwenbox image or a complete host
application image. Image selection, OS packages, sandbox privileges, network
policy, and registry provenance must follow the deployment environment's
approved Jiuwenbox distribution.

## Optional recording

```bash
.venv/bin/python -m pip install '.[recording]'
.venv/bin/python -m playwright install chromium
```

Configure headless/viewer or headed/X11 mode as described in
[Recording Integration](recording-integration.md). Active recordings are
process-local. A multi-worker host needs sticky routing for all operations of
one recording or a dedicated recording service.

Recording profiles, storage state, screenshots, downloads, and traces may
contain sensitive session data. Store and expire them separately from the
exported Skill package.

## Health, readiness, and observability

The host readiness check should verify without printing secrets:

- Skill Builder and OpenJiuwen imports;
- required model variables are present;
- workspace/state roots are writable;
- Jiuwenbox `/health` succeeds when sandbox execution is required;
- the host StateStore and workspace lease backend are reachable.

Track phase latency, model request failures, worker exit/timeout, HITL pause
duration, Repair count, validation state, and Jiuwenbox lifecycle. Persisted
state is authoritative; events are progress/diagnostic data.

## Upgrade and rollback

Before upgrade:

1. stop accepting new mutating tasks;
2. wait for or cancel active Agent Core workers;
3. stop active recordings;
4. back up StateStore and persistent workspaces;
5. install the new wheel in a new environment;
6. run package tests and one opt-in smoke;
7. switch the host process to the new environment.

State schema and policy versions are validated. Do not silently load an
unsupported state version. Rollback must restore a compatible wheel and state
backup together.

## Future Python Runtime deployment

When a supported Python Agent Runtime becomes available, Skill Builder can run
as an independently addressable Agent service:

```text
Client -> Python Runtime -> SkillBuilder Runtime Adapter -> SkillBuilderClient
                                                   ├── Agent Core child
                                                   └── Jiuwenbox service
```

The Runtime adapter maps requests, events, HITL, cancellation, and artifacts to
the existing public client. It must not reimplement Scenario, Author, Repair,
Acceptance, or delivery decisions.

If the Runtime already provides service/process lifecycle, do not add a second
`SkillBuilderProcessClient`. Multi-instance Runtime deployment still requires a
shared StateStore, workspace storage, and per-workspace write lease. Recording
still needs sticky routing or a separate recording service.

## Deployment verification

From the project directory:

```bash
python -m pytest
python -m build
```

The default tests use fake Agent runners and do not require a model or network.
Before production, additionally run the host/Jiuwenbox smoke with non-sensitive
test material and verify that no Agent worker or sandbox remains after
completion/cancellation.
