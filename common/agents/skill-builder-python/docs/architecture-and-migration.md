# Architecture and Migration

## Source baseline

- Source repository: `gcw_fleNWGnn/skillbuilder`
- Source branch: `refactor/skill-builder-boundaries`
- Source commit: `045732d249482d4f23f0480344a165388fe47201`
- Source verification: `415 passed, 4 skipped`
- Target base: `openJiuwen/agent-solution common@70ffe929`

The whole source `marketplace/skill_builder` package was migrated. Legacy host HTTP,
ORM, authentication, database event storage, frontend, object storage, and
external publish code were not migrated.

## Dependency direction

```text
host adapters -> skill_builder public API and ports
skill_builder application -> domain and ports
default adapters -> ports and external OpenJiuwen/Jiuwenbox clients
```

No module in `skill_builder` imports `plugins_market`, FastAPI, SQLAlchemy, or a
host database. Hosts can replace state, events, HITL, workspace, and execution
adapters without changing the lifecycle.

## Preserved business behavior

The migration does not change Scenario compilation, HITL decisions, Author
Build, candidate preflight, final Acceptance, bounded Repair, delivery states,
or package safety rules. Host-specific wording was neutralized without changing
the existing compatibility archive metadata contract.

## Process boundary

`SkillBuilderClient` remains in the host process. `SubprocessAgentRunner`
isolates one Agent Core phase per child process and transports typed results and
JSONL events. The worker invokes the existing `run_skill_builder_agent_core`
function and does not duplicate lifecycle logic.

Jiuwenbox remains a separate daemon. The feature-neutral client, workspace
session, and final Acceptance execution adapter are packaged here so a new host
does not depend on legacy host modules.

## Deferred integrations

A2A, Java adapters, HTTP/SSE servers, Agent Card, browser validation, object
storage, approval, and external publish remain separate follow-up work. They
must wrap the public API rather than introduce another lifecycle controller.
