# Configuration

Skill Builder reads runtime configuration from the host process environment.
It deliberately does not load `.env` files automatically. The deployment
system is responsible for loading secrets and non-secret settings before
constructing `SkillBuilderClient`; Agent Core child processes inherit them.

## Installation profiles

```bash
# Validate, load state, and package only
python -m pip install openjiuwen-skill-builder

# Generate with OpenJiuwen Agent Core
python -m pip install 'openjiuwen-skill-builder[agent-openjiuwen-python]'

# Optional Playwright material recording
python -m pip install 'openjiuwen-skill-builder[recording]'
python -m playwright install chromium

# Generation plus recording
python -m pip install 'openjiuwen-skill-builder[full]'
```

The migration keeps the source-verified `openjiuwen==0.1.12`. Upgrade it only
in a separate compatibility-tested change.

## Required model settings

These variables are required for `build`, model-backed `run_turn`, and
`repair`. Core-only load/structural validation/package operations must remain
importable without a configured model.

| Variable | Required | Default | Description |
|---|---|---:|---|
| `SKILL_BUILDER_LLM_API_BASE` | Yes | none | OpenAI-compatible endpoint |
| `SKILL_BUILDER_LLM_API_KEY` | Yes | none | Model credential; environment only |
| `SKILL_BUILDER_LLM_MODEL` | Yes | none | Customer-configured model name |
| `SKILL_BUILDER_LLM_PROVIDER` | No | `OpenAI` | Provider label used by the adapter |
| `SKILL_BUILDER_LLM_TIMEOUT_SECONDS` | No | `120` | One model HTTP request timeout |
| `SKILL_BUILDER_LLM_MAX_TOKENS` | No | `16384` | Default response token budget |
| `SKILL_BUILDER_LLM_MAX_REQUEST_BYTES` | No | `524288` | Serialized request hard budget |
| `SKILL_BUILDER_LLM_REQUEST_HEADROOM_RATIO` | No | `0.8` | Usable fraction of request budget |
| `SKILL_BUILDER_LLM_TEMPERATURE` | No | `0.2` | Sampling temperature |
| `SKILL_BUILDER_LLM_TOP_P` | No | `0.9` | Sampling top-p |

## Phase-specific model settings

| Variable | Example | Description |
|---|---:|---|
| `SKILL_BUILDER_LLM_ENABLE_THINKING` | `auto` | Default thinking control; `auto` omits the parameter |
| `SKILL_BUILDER_LLM_SCENARIO_ENABLE_THINKING` | `false` | Scenario override |
| `SKILL_BUILDER_LLM_AUTHOR_ENABLE_THINKING` | `false` | Author override |
| `SKILL_BUILDER_LLM_REPAIR_ENABLE_THINKING` | `true` | Repair override |
| `SKILL_BUILDER_LLM_SCENARIO_MAX_TOKENS` | `8192` | Scenario response ceiling |
| `SKILL_BUILDER_LLM_AUTHOR_MAX_TOKENS` | `12288` | Author response ceiling |
| `SKILL_BUILDER_LLM_REPAIR_MAX_TOKENS` | `8192` | Repair response ceiling |

Model parameters are customer-configurable. A host must not limit customers to
one model family, but should run compatibility checks for any model-specific
request parameters.

## Jiuwenbox

Jiuwenbox is a separate service. The supplied adapters use it for Agent
workspace operations and final Acceptance execution.

| Variable | Default | Description |
|---|---:|---|
| `SKILL_BUILDER_SANDBOX_ENABLED` | `false` in code; example sets `true` | Enable Jiuwenbox workspace creation in Agent workers |
| `SKILL_BUILDER_JIUWENBOX_URL` | `JIUWENBOX_URL` or `http://127.0.0.1:8321` | Jiuwenbox endpoint |
| `SKILL_BUILDER_JIUWENBOX_TIMEOUT_SECONDS` | `JIUWENBOX_TIMEOUT_SECONDS` or `30` | Client request timeout |
| `SKILL_BUILDER_SANDBOX_COMMAND_TIMEOUT_SECONDS` | `120` | Default Agent workspace command timeout |
| `SKILL_BUILDER_SANDBOX_IO_TIMEOUT_SECONDS` | `20` | Bounded upload/read/download timeout |
| `SKILL_BUILDER_SANDBOX_WRITE_TIMEOUT_SECONDS` | `30` | Bounded write/sync timeout |
| `SKILL_BUILDER_SANDBOX_KEEP` | `false` | Keep phase sandboxes for restricted diagnostics |

Production must not execute generated scripts in the host process as a silent
fallback. If Jiuwenbox is unavailable, Core may still perform checks that do not
execute untrusted code, but required execution evidence remains unverified or
blocking according to the Skill contract.

The feature-neutral Jiuwenbox client also recognizes `JIUWENBOX_URL` and
`JIUWENBOX_TIMEOUT_SECONDS`. Prefer the `SKILL_BUILDER_` names for this Agent so
multiple products can use different instances.

## Agent Core budgets

| Variable | Example | Meaning |
|---|---:|---|
| `SKILL_BUILDER_AGENT_TOTAL_TIMEOUT_SECONDS` | `1200` | Absolute phase ceiling fallback |
| `SKILL_BUILDER_AGENT_CHAT_TIMEOUT_SECONDS` | `120` | Read-only chat deadline |
| `SKILL_BUILDER_AGENT_EDIT_TIMEOUT_SECONDS` | `360` | Transactional edit deadline |
| `SKILL_BUILDER_AGENT_SCENARIO_TIMEOUT_SECONDS` | `240` | Scenario phase deadline |
| `SKILL_BUILDER_AGENT_AUTHOR_TIMEOUT_SECONDS` | `900` | Author phase deadline |
| `SKILL_BUILDER_AGENT_REPAIR_TIMEOUT_SECONDS` | `480` | Repair phase deadline |
| `SKILL_BUILDER_AGENT_REPAIR_RESERVE_TIMEOUT_SECONDS` | `180` | Bounded reserve after a rejected submission |
| `SKILL_BUILDER_AGENT_IDLE_TIMEOUT_SECONDS` | `240` | No-stream-activity deadline |
| `SKILL_BUILDER_AGENT_CHAT_MAX_ITERATIONS` | `6` | Chat single-session safety ceiling |
| `SKILL_BUILDER_AGENT_EDIT_MAX_ITERATIONS` | `12` | Edit single-session safety ceiling |
| `SKILL_BUILDER_AGENT_SCENARIO_MAX_ITERATIONS` | `8` | Scenario single-session safety ceiling |
| `SKILL_BUILDER_AGENT_AUTHOR_MAX_ITERATIONS` | `32` | Author single-session safety ceiling |
| `SKILL_BUILDER_AGENT_REPAIR_MAX_ITERATIONS` | `12` | Repair single-session safety ceiling |
| `SKILL_BUILDER_AUTHOR_SELF_CHECK_MAX_RUNS` | `4` | Author self-check execution ceiling |
| `SKILL_BUILDER_MAX_REPAIR_ATTEMPTS` | `1` | Automatic mechanical Repair count, hard range `0-1` |

Iterations are not retries. Increasing them does not increase Repair attempts
and should not be used to hide repeated no-progress behavior.

`AgentCoreProcessConfig.timeout_seconds` is a separate host hard stop. Keep it
`None` normally so the activity-aware phase deadlines own healthy runs. If set,
timeout or task cancellation terminates the child process.

## Gate rollout

| Variable | Default | Description |
|---|---:|---|
| `SKILL_BUILDER_CAPABILITY_GATE_MODE` | `shadow` | Heuristic capability prose findings |
| `SKILL_BUILDER_DOCUMENTATION_GATE_MODE` | `shadow` | Heuristic documentation findings |
| `SKILL_BUILDER_OFFLINE_PROTOCOL_GATE_MODE` | `shadow` | Generated self-check protocol diagnostics |
| `SKILL_BUILDER_HEURISTIC_GATE_MODE` | `shadow` | Legacy shared fallback |

Typed contract failures, unusable package structure, syntax errors, failed
required replay, and invalid receipts remain blocking regardless of rollout
flags. Move a heuristic from shadow to enforce only after representative
regression testing.

## Recording

Recording is optional input capture and is not browser validation.

| Variable | Default | Description |
|---|---:|---|
| `PLAYWRIGHT_BROWSERS_PATH` | Playwright default | Chromium installation/cache used by the host |
| `WEB_RECORDING_HEADLESS` | `auto` | `auto`, `true/headless/viewer`, or `false/headed/desktop` |
| `WEB_RECORDING_DISPLAY` | `DISPLAY` | X11 display for headed mode |
| `WEB_RECORDING_XAUTHORITY` | `XAUTHORITY` or readable fallback | X11 authorization file |
| `WEB_RECORDING_DISPLAY_PROBE_TIMEOUT_SECONDS` | `3` | Display probe, bounded to 1-10 seconds |
| `WEB_RECORDING_WINDOW_WIDTH` | `1280` | Browser width |
| `WEB_RECORDING_WINDOW_HEIGHT` | `860` | Browser height |

See [Recording Integration](recording-integration.md) for API/UI, asset,
security, and process-lifecycle requirements.

## Host configuration not represented by environment variables

The host must separately configure:

- workspace and state roots;
- one-active-write-task-per-workspace locking or StateStore CAS;
- Agent worker concurrency and host task queue limits;
- material type/size policy and binary preprocessing;
- model data-region, retention, and user-consent policy;
- Jiuwenbox CPU/memory/network policy and health checks;
- event retention and sensitive payload access;
- object storage, export, review, and external publish policy;
- recording URL/domain policy and asset retention.

These are host concerns and must not be encoded as hidden Skill Builder business
rules.

## Secrets

Do not place credentials in source, workspace materials, worker request files,
events, or result JSON. Pass them through the host process environment or its
secret manager. Agent Core children inherit the environment; event and result
serialization must not echo secret values.

Recording browser profiles and `storage-state.json` may also contain session
credentials. Treat them as secrets even though they are files rather than
environment variables.
