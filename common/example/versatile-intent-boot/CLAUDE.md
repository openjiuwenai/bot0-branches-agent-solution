# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Scope

`versatile-intent-boot` is a Spring Boot **deployment example** for a three-layer intent-recognition workflow: L1 (粗分类) → L2 (细分类) → downstream (业务执行), with layers forwarding to each other over the A2A protocol. It demonstrates the L2 design doc `Feat-Func-002-versatile-intent-workflow-adapter-compatibility.md` (§5.5.3 / §6.2). Each process embeds a `MockVersatileController` that returns canned SSE, so the full chain runs locally without a real Versatile service.

This module is part of the larger `agent-solution-zyw` monorepo. It depends on two external artifacts that must be installed locally first: `com.openjiuwen:agent-service-app:0.1.0` and `com.openjiuwen:agent-service-adapters-versatile:0.1.0` (built from `common/agent-runtime-ext-java` etc., see repo-root `CONTRIBUTING.md`). Do **not** reimplement HTTP ingress, A2A protocol handling, remote card discovery, or session orchestration here — those belong to `agent-runtime-java`. This repo only wires adapters and example behavior.

## Build & Test

```bash
mvn package                 # compile + unit/integration tests
mvn -DskipTests package     # package only (produces target/versatile-intent-boot-0.1.0.jar used by e2e scripts)
mvn test                    # run all tests
mvn -Dtest=RouteCacheAutoConfigurationTest test   # run a single test class
```

Java 17+, Maven 3.9+. Spring Boot parent is 4.0.6.

## End-to-end scripts

`scripts/` contains two self-contained e2e harnesses that start the required processes, wait on health checks, send requests, assert responses/logs, and clean up. They auto-`mvn package` if the jar is missing; pass `SKIP_BUILD=1` to reuse an existing jar. Logs land in `target/{layer1,layer2,downstream,gateway,default-wf}.log`.

- `scripts/local-e2e.sh` — Local HTTP forwarding mode (`a2a-gateway.enabled=false`). Covers §6.2.1 (two-layer + downstream), §6.2.3 (interrupt), §6.2.4 (L2 ambiguous self-heal).
- `scripts/local-e2e-a2a-gateway.sh` — A2A Gateway mode. 5 processes across 2 rounds: full chain, L2 ambiguous self-heal, and direct-chain SSE passthrough; also asserts header propagation (token / B3 / X-Biz-Tag) and multi-turn route cache.
- `scripts/local-e2e-llm-intent.sh` — LLM intent demo mode (real LLM + DeepAgent downstream). Requires API keys, runs three scenarios with multi-turn conversation and reclassification, includes mock gateway passthrough for business card routing. (真实 LLM，需 API Key，不进 CI).
- `scripts/cli-llm-intent.py` — 纯标准库 Python CLI 客户端（参考 a2a-samples `helloworld/test_client.py`）。`start` 子命令读 `.env`（`LLM_API_KEY` 等，见 `.env.example`，`.env` 已 gitignore）一键拉起 6 进程栈并退出时清理；`card`/`scenario a|b|c|all`/`chat` 假定栈已运行，经 `POST /v1/query` 驱动 L1（请求体与 shell 的 `send_q` 一致，`--stream` 走 SSE）。

Override ports/timeouts via `L1_PORT` / `L2_PORT` / `DOWNSTREAM_PORT` / `GATEWAY_PORT` / `DEFAULT_WF_PORT` / `HEALTH_TIMEOUT_SECONDS`.

## Architecture

### Profiles map to deployment layers

One jar, three layers selected by Spring profile (`spring.profiles.active`, defaults to `layer1`):

| Profile | Port | Role |
|---------|------|------|
| `layer1` | 8081 | L1 coarse intent classification → maps intents to L2 agent cards |
| `layer2` | 8082 | L2 fine classification → maps to downstream |
| `downstream` | 8083 | Terminal business node (no `intents`/`intent-agent-mapping`) |

Supporting profiles: `dev` (points `versatile.url-template` at local mock), `mock-versatile` (activates `MockVersatileController` + shared `card-resolver.local-mapping`), `mock-a2a-gateway` (mock gateway on 8084), `a2a-gateway-test` (enables gateway mode pointing at local mock). Profiles are combined, e.g. `dev,mock-versatile,layer1`.

### Two forwarding modes (mutually exclusive)

Selected by `openjiuwen.service.a2a-gateway.enabled`:
- **Local HTTP** (default, `false`): `LocalHttpRemoteAgentCaller` POSTs directly to `card-resolver.local-mapping[agentCard]` `/a2a/{agentId}`.
- **A2A Gateway** (`true`): `A2AGatewayRemoteAgentCaller` routes through `a2a-gateway.base-url` + `/a2a/{agentCard}`, with token / B3 trace / X-Biz-Tag header propagation.

The gateway beans register `@AutoConfiguration(before = A2AAutoConfiguration.class)` so they win the `@ConditionalOnMissingBean` slot over the runtime's `Default*` beans. The same pattern is used elsewhere — **bean registration order is load-bearing** (see below).

### AutoConfiguration ordering is load-bearing

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` lists configs in a deliberate order. The `AgentHandler` slot is claimed by whichever eligible config runs first via `@ConditionalOnMissingBean(AgentHandler.class)`:

1. `DirectChainAutoConfiguration` (before `VersatileIntentAutoConfiguration`) — when `direct-chain.enabled=true`, `DirectChainVersatileAgentHandler` claims the slot.
2. `RouteCacheAutoConfiguration` (before `VersatileIntentAutoConfiguration`) — when `route-cache.enabled=true`, `CachedVersatileAgentHandler` (a decorator) claims it.
3. `LlmIntentAutoConfiguration` (after `RouteCacheAutoConfiguration`) — when `intent-llm.enabled=true`, `LlmIntentAgentHandler` claims the slot.
4. `VersatileIntentAutoConfiguration` — bare `VersatileAgentHandler`, only when route-cache and LLM intent are off (`matchIfMissing=true`).

So enabling direct-chain automatically displaces route-cache without toggling `route-cache.enabled`. LLM intent configuration claims the slot after route-cache, allowing both features to coexist (L1 can use route-cache with LLM intent classification). When adding a new `AgentHandler` variant, place its auto-config **before** `VersatileIntentAutoConfiguration` in the imports file and guard with `@ConditionalOnMissingBean`. Keep `@AutoConfiguration(before = ...)` annotations consistent with that ordering.

### Feature packages

Each feature is a self-contained package with its own `*AutoConfiguration` + `*Properties`, enabled by a property under `openjiuwen.service.versatile.*` or `openjiuwen.example.*`:

- `routecache/` — Multi-turn route cache (L1 only). Caches the next-hop `agent_id` per `conversationId`; subsequent turns skip L1 by synthesizing an `a2a_delegate` interrupt. In-process `ConcurrentHashMap` + TTL, no Redis. Invalidated on TTL or `clearSession`. `CachedVersatileAgentHandler` decorates the base handler.
- `directchain/` — Intercepts `a2a_delegate` at a middle layer and tunnels the SSE directly to the target's versatile endpoint via the gateway, bypassing business handlers for terminal business cards. `DirectChainAutoConfiguration` is ordered first so it preempts route-cache.
- `a2a/` — Gateway-mode `RemoteAgentCaller` / `RemoteAgentCardResolver` and Local-HTTP caller + `card-resolver.local-mapping` registrar.
- `intentllm/` — LLM 意图分类 `AgentHandler` 变体（classify 模式），`intent-llm.enabled=true` 时抢 `AgentHandler` 槽位（与 Versatile 二选一，即意图对接 SPI）。`LlmIntentAgentHandler` 按 `conversationId` 在内存累积用户输入历史，每轮把"历史+当前"经 `LlmIntentPromptBuilder` 喂给 LLM，从而能分类 `上海`/`继续订酒店` 等后续消息；`clearSession` 清除历史。`LlmIntentAutoConfiguration` 在 route-cache bean 存在时用 `CachedVersatileAgentHandler` 包裹，故 L1 可同时启用 route-cache。imports 中置于 RouteCacheAutoConfiguration 之后。LLM 意图演示（`local-e2e-llm-intent.sh`）为支持跨工作流跳转，L1 显式 `route-cache.enabled=false`（每轮带历史重分类），见 `README.md` 取舍说明。
- `mock/` — `MockVersatileController` (canned Versatile SSE keyed by `(agentId, query)`) and `MockA2AGatewayController` (forwarding proxy for tests). 后者经 `MockA2aGatewayProperties`（`openjiuwen.example.mock-a2a-gateway.*`）支持 `routing` 覆盖与 `passthrough-cards`：列出的末端业务卡走 A2A 原生透传（JSON-RPC 原样转发到目标 `/a2a/`，保留 `INPUT_REQUIRED`/shadow-task 恢复），其余卡走 `/v1/query` 翻译。`MockA2AGatewayController` 的 Spring 构造函数标 `@Autowired`（多构造函数场景下显式指定注入点）。

### Configuration prefix

Runtime config lives under `openjiuwen.service` (versatile, a2a-gateway, card-resolver). This module's own features use `openjiuwen.service.versatile.route-cache` and `openjiuwen.example.direct-chain`. See `README.md` for the full YAML reference.

## Conventions

- Match the naming, style, and Javadoc density of the package you edit (Huawei copyright header, `@since` tags on types).
- Keep features behind their own `*Properties` + `@ConditionalOnProperty` — never wire a feature unconditionally.
- Keep public config keys, Maven coordinates, and behavior changes deliberate and documented; update `README.md` when changing startup flow, config keys, or request scripts.
- Keep changes focused; avoid unrelated refactors in contribution PRs.
