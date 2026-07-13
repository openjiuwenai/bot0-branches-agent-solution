# Agent Core Extension Review Fixes Design

## Goal

Preserve PEV's existing Plan-Execute-Verify behavior while completing the architectural split introduced by PR #21.
`agent-core-ext-java` must be a pure Java SDK extension project for `agent-core-java`: putting an extension artifact on
the classpath must not activate Spring, register rails or tools, or otherwise change an application's agent behavior.

## Project Boundaries

The three projects under `common` are peers. They do not inherit from or aggregate one another:

```text
common/
|-- agent-runtime-ext-java    Runtime integrations and service adapters
|-- agent-core-ext-java       Pure agent-core SDK extensions
|   `-- react-rails           Independently evolving ReAct rail feature set
`-- agents                    Concrete agent implementations
    `-- pev                   PEV agent, kernel, and PEV-specific rails
```

No `common/pom.xml` is added. Each project is built independently and owns its Maven version, dependency management,
compiler settings, and test configuration. Documentation and CI invoke the three project builds separately.

Within each project, child modules use their local project POM as parent:

- runtime adapter modules use `agent-runtime-ext-java`;
- `react-rails` uses `agent-core-ext-java`;
- `pev` uses `agents`.

`agent-runtime-ext-java` must remove the sibling `../agent-core-ext-java` and `../agents` module entries. Neither
`agent-core-ext-java` nor `agents` declares `agent-runtime-ext-java` as its Maven parent.

## SDK Dependency Direction

`agent-core-ext-java` remains a `pom`-packaged SDK project with `react-rails` as a child feature module. It is not a
facade artifact and does not declare `react-rails` as a dependency. Developers who need this feature depend directly
on the `react-rails` jar.

The supported artifact dependency graph is:

```text
react-rails --> agent-core-java:0.1.13
pev         --> agent-core-java:0.1.13
```

There is no dependency in either direction between `react-rails` and `pev`. This keeps a reusable SDK feature from
depending on a concrete agent implementation and keeps PEV self-contained.

## Spring Removal

Spring is not part of `agent-core-java`, PEV, or their historical test execution. PR #21 therefore removes Spring
integration rather than relocating it. The following react-rails elements are deleted:

- `ReactRailsAutoConfiguration`;
- `ReactRailsProperties`;
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
- the `spring-boot-autoconfigure` dependency and Spring-only tests.

No starter, replacement auto-configuration module, or application-context integration is added. SDK users construct
and register rails explicitly, matching the existing PEV and react-rails unit and real-LLM tests.

## PEV And ReAct Separation

`PevReplanRail` runs on `ReActAgent` but imports the concrete PEV kernel. It is cross-host integration rather than a
requirement of `PEVAgent`, so it and its tests are deleted. The existing generic `ReplanRail` remains in react-rails.

PEV's `PevKernel`, `RootCause`, `ReplanAction`, `NodeResult`, `PEVAgent`, and PEV-specific rails remain in the `pev`
module. They are not copied, renamed, or moved into agent-core-ext. This preserves PEV's diagnosis and dispatch
semantics while eliminating the reverse SDK-to-agent dependency.

## Behavioral Isolation

The remaining react-rails features are activated only by explicit Java construction and registration. Their mutable
operational state must be scoped to a model instance or an `AgentCallbackContext`, not stored in JVM-wide static
channels or shared singleton rail fields. Configuration such as retry limits remains immutable on the rail instance.

This isolation is an SDK correctness requirement, not automatic application integration. It prevents two agents or
two invocations that deliberately use the same feature classes from consuming each other's prompt modes, overrides,
counters, histories, pending failures, or phase state.

## Verification

PEV behavior is the primary acceptance boundary. Verification must cover:

- the full PEV unit and control-flow suite;
- PEV real-LLM tests when credentials are present, with environment-gated skips reported separately;
- `agents` building independently, with no Spring or runtime dependency in the PEV dependency tree;
- `agent-core-ext-java` building independently, with no Spring, PEV, or runtime dependency in the react-rails tree;
- runtime-ext building independently after sibling modules are removed from its reactor;
- remaining react-rails tests, including cross-agent and cross-invocation state isolation;
- formatter, checkstyle, `git diff --check`, and repository documentation paths and commands.

No runtime adapter behavior is changed by this work.
