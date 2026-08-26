# Skill Builder Agent

Skill Builder converts workspace materials into a validated Skill package. It
is a standalone Python Agent project: generation, HITL, acceptance, bounded
repair, state recovery, and archive construction live in this directory. HTTP,
ORM, authentication, object storage, and external publishing remain host
responsibilities.

The migrated source is based on `skillbuilder/refactor/skill-builder-boundaries`
commit `045732d`. The target branch is based on `agent-solution/common` commit
`70ffe929`.

## Runtime topology

```text
Python host/background task
└── SkillBuilderClient                  lifecycle and durable decisions
    ├── SubprocessAgentRunner
    │   └── Agent Core child process    Scenario / Author / Repair
    │       └── Jiuwenbox workspace     separate sandbox service
    ├── JiuwenboxExecutionPort          final Acceptance smoke
    └── State/Event/HITL ports           host-replaceable adapters
```

`SkillBuilderClient` is a Python facade, not a server or process. The default
host example keeps this controller in the host and isolates only Agent Core.

## Install

```bash
cd common/agents/skill-builder-python
python -m venv .venv
.venv/bin/python -m pip install -e '.[agent-openjiuwen-python]'
cp .env.example .env
```

Export the variables from `.env` through the deployment environment. The
package deliberately does not load secret files automatically.

Jiuwenbox must be reachable separately. The default endpoint is
`http://127.0.0.1:8321` and can be changed with
`SKILL_BUILDER_JIUWENBOX_URL`.

Optional recording requires the recording extra and a browser installation:

```bash
.venv/bin/python -m pip install -e '.[recording]'
.venv/bin/python -m playwright install chromium
```

## Host integration

The complete reference host is [examples/host_background.py](examples/host_background.py).
Its essential wiring is:

```python
from skill_builder import SkillBuilderClient
from skill_builder.adapters import (
    AgentCoreProcessConfig,
    JiuwenboxExecutionPort,
    SubprocessAgentRunner,
)
from skill_builder.spi import (
    JsonFileStateStore,
    SkillBuilderAdapters,
)

client = SkillBuilderClient(
    adapters=SkillBuilderAdapters(
        state_store=JsonFileStateStore(state_root),
        agent_runner=SubprocessAgentRunner(AgentCoreProcessConfig()),
        execution_port=JiuwenboxExecutionPort(),
    )
)
```

With `SubprocessAgentRunner`, do not also set `SkillBuilderAdapters.workspace`:
the child process creates the Jiuwenbox workspace adapter from environment
configuration. An in-process host can instead combine
`OpenJiuwenPythonAgentAdapter` with `JiuwenboxWorkspacePort`.

See [Host Integration](docs/host-integration.md) for build, materials, HITL,
recovery, persistence, cancellation, edits, validation, and export behavior.

## Delivery states

| State | Meaning | Host action |
|---|---|---|
| `waiting_for_user` | A real business decision is missing | Render the pending form and call `resume` |
| `ready` | Acceptance passed and the receipt matches the current package | Export is allowed; host may enable its own publish action |
| `needs_review` | Package can be inspected/exported but a blocking external or human decision remains | Never auto-publish |
| `failed` | Generation/runtime did not produce an acceptable candidate | Show the structured failure and available retry action |

Warnings do not create a separate lifecycle state. A `ready` result may contain
non-blocking warnings only when verified usability is unaffected. External
publishing is never executed by this package.

## Project layout

```text
src/skill_builder/
├── api.py                 stable SkillBuilderClient facade
├── application/           lifecycle and acceptance orchestration
├── domain/                state and package contracts
├── ports/                 host extension interfaces
├── adapters/              OpenJiuwen, subprocess, state, and Jiuwenbox adapters
├── agent_worker.py        one Agent Core phase per child process
├── resources/             internal Scenario and Author Skills
└── recording.py           optional Playwright recording support
```

Configuration is documented in [Configuration](docs/configuration.md). The
source boundary and migration decisions are recorded in
[Architecture and Migration](docs/architecture-and-migration.md).
Completed checks are recorded in [Verification](docs/verification.md).

Host-facing contracts:

- [Status and Host Actions](docs/status-and-actions.md)
- [Recording Integration](docs/recording-integration.md)
- [Host Integration](docs/host-integration.md)
- [Configuration](docs/configuration.md)

The optional recording adapter example is
[examples/recording_host.py](examples/recording_host.py).

## Tests

```bash
python -m pytest
python -m build
```

CI tests use deterministic fake Agent runners and do not require a real model
or network. Real-host smoke requires configured LLM credentials and a healthy
Jiuwenbox service.
