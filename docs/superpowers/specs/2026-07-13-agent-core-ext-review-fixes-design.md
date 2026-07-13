# Agent Core Extension Review Fixes Design

## Goal

Make `agent-core-ext-java` a directly consumable, independently buildable extension entry point for
`agent-core-java` without changing an application's agent behavior merely because the extension is on the classpath.

## Maven Boundaries

`agent-core-ext-java` remains a `pom`-packaged consumer facade. It declares `agent-core-java` and `react-rails`
as dependencies so developers can use one Maven coordinate. `react-rails` no longer depends on the higher-level
`pev` agent artifact; its ReAct-specific replanning rail owns the small dispatch decision it needs. All new modules
compile against the current supported `agent-core-java` version, `0.1.13`.

The existing `agent-runtime-ext-java` reactor continues to aggregate `agents`, `agent-core-ext-java`, and existing
adapters. The `agent-core-ext-java` root reactor must also build successfully without first installing `pev`.

## Opt-In Behavior

Spring Boot auto-configuration remains available but is disabled unless `reactrails.enabled=true` is explicitly set.
When disabled, it contributes no verifier bean, BeanPostProcessor, rails, tools, or prompt changes. When enabled, it
preserves the current rail registration behavior.

## Invocation State

Stateful rails reset invocation-scoped counters, histories, pending failure markers, and phase observations in
`beforeInvoke`. Configuration such as maximum retry counts remains stable. This prevents a singleton `ReActAgent`
from carrying one request's budget or observations into another request.

## Prompt Injection State

Prompt injection mode and phase override move from static JVM state to a `PromptInjectionState` instance owned by a
`SystemPromptInjectingModel`. Prompt-producing rails receive that same state explicitly. Auto-configuration obtains
the state from `ReActAgent.peekLlm()` when the configured model is a `SystemPromptInjectingModel`; it only registers
prompt-specific rails when such a model is present. Other rails remain independent of prompt injection.

This design prevents one agent or concurrent request from consuming another agent's prompt override. Manual users
construct prompt-aware rails with the target model's state, making ownership visible in the API.

## Compatibility And Testing

Existing PEV and react-rails behavior remains covered by the current test suite. New regression tests prove:

- auto-configuration is absent by default and active only with `reactrails.enabled=true`;
- rail state resets between invocations;
- two prompt-injecting models do not share modes or overrides;
- `react-rails` has no `pev` dependency and the facade exposes core plus rails;
- the `agent-core-ext-java` root build succeeds against `agent-core-java:0.1.13`.

Verification runs both the extension-root reactor and the original runtime-extension reactor. Real-LLM tests remain
environment-gated and are reported separately when credentials are unavailable.
