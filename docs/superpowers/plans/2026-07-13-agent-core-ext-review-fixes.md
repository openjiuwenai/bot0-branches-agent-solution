# Agent Core Extension Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `agent-core-ext-java` directly consumable and independently buildable while ensuring extension code is explicit opt-in and all invocation state is isolated.

**Architecture:** The facade POM exposes core plus rails, `react-rails` drops its higher-level PEV dependency, rail decisions use `AgentCallbackContext.extra`, and prompt injection uses an instance-owned thread-local channel shared explicitly by a model and its prompt-aware rails.

**Tech Stack:** Java 17, Maven, JUnit Jupiter, AssertJ, Spring Boot auto-configuration tests, GitCode CLI.

---

### Task 1: Synchronize The PR With Its Target

**Files:** `upstream/common`, `common/agent-runtime-ext-java/pom.xml`

- [ ] Fetch `common`, merge it with `git merge --no-edit upstream/common`, and preserve target release versions plus the PR module entries.
- [ ] Verify `git merge-base --is-ancestor upstream/common HEAD` exits 0 and there are no unmerged paths.

### Task 2: Fix Maven Consumption And Dependency Direction

**Files:**
- Modify: `common/agent-core-ext-java/pom.xml`
- Modify: `common/agent-core-ext-java/react-rails/pom.xml`
- Modify: `common/agents/pev/pom.xml`
- Modify: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/replan/PevReplanRail.java`

- [ ] Run `mvn -f common/agent-core-ext-java/pom.xml test -DskipTests` and retain the current missing-PEV failure as RED evidence.
- [ ] Replace PEV action types in `PevReplanRail` with the equivalent direct decision: correlated tool failure requests degraded finish; other valid replans push global steering; over-limit behavior remains unchanged.
- [ ] Add facade dependencies on `agent-core-java` and `react-rails`; define `agent-core-java.version=0.1.13`; remove the `react-rails -> pev` dependency.
- [ ] Run `rg -n 'artifactId>pev' common/agent-core-ext-java` and the standalone Maven command. Expect no dependency match and exit 0.

### Task 3: Make Spring Behavior Explicit Opt-In

**Files:**
- Modify: `common/agent-core-ext-java/react-rails/pom.xml`
- Modify: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/autoconfigure/ReactRailsAutoConfiguration.java`
- Create: `common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/autoconfigure/ReactRailsAutoConfigurationTest.java`

- [ ] Add an `ApplicationContextRunner` test proving classpath presence creates no verifier, rails, or replan tool.
- [ ] Add a second test with `reactrails.enabled=true` proving the verifier and replan tool are registered.
- [ ] Run the focused test and observe the default-disabled assertion fail because `matchIfMissing=true`.
- [ ] Remove `matchIfMissing=true`, update property documentation to default false, rerun, and expect both tests to pass.

Desired assertions:

```java
assertThat(context).doesNotHaveBean(CriteriaVerifier.class);
assertThat(agent.getAbilityManager().get(ReplanTool.TOOL_NAME)).isNull();
```

### Task 4: Isolate Prompt Injection State

**Files:**
- Create: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/enforcing/PromptInjectionState.java`
- Modify: `SystemPromptInjectingModel.java`, `PreCompletionChecklistRail.java`, `StagnationDetectionRail.java`, and auto-configuration
- Test: `SystemPromptInjectingModelTest.java` and prompt-aware rail tests

- [ ] Add a failing test creating two models, setting mode and override on the first model, and asserting the second remains `NONE` with no override.
- [ ] Implement `PromptInjectionState` with an instance-owned `ThreadLocal<State>`, including set/get mode, set/peek/consume override, and reset.
- [ ] Make each model own a state and expose `injectionState()`; prompt-aware rails receive that state explicitly.
- [ ] In auto-configuration, use `agent.peekLlm()` and only attach prompt rails when it is a `SystemPromptInjectingModel`.
- [ ] Update existing prompt tests and run enforcing/checklist/stagnation tests until GREEN.

### Task 5: Isolate Rail State Per Invocation

**Files:** all stateful rail implementations and their corresponding tests

- [ ] Add regression tests that drive one rail with two distinct `AgentCallbackContext` objects. Prove replan counts, criteria history, compression boundaries, pending failures, checklist phases, and stagnation counters do not cross contexts.
- [ ] Run focused rail tests and observe shared-field failures.
- [ ] Move operational state into a namespaced `ctx.getExtra().computeIfAbsent(...)` holder. Configuration remains in final instance fields; business decisions read only current-context state.
- [ ] Run `mvn -f common/agent-runtime-ext-java/pom.xml -pl :react-rails -am -Dtest='*RailTest' -Dsurefire.failIfNoSpecifiedTests=false test` and expect GREEN.

Representative state lookup:

```java
private InvocationState state(AgentCallbackContext ctx) {
    return (InvocationState) ctx.getExtra().computeIfAbsent(STATE_KEY, ignored -> new InvocationState());
}
```

### Task 6: Documentation And Full Verification

**Files:** `common/agent-core-ext-java/react-rails/README.md` plus changed tests

- [ ] Document the facade dependency, explicit `reactrails.enabled=true`, and instance state wiring.
- [ ] Run formatter validation, checkstyle, and `git diff --check`.
- [ ] Run `mvn -f common/agent-core-ext-java/pom.xml test`.
- [ ] Run `mvn -f common/agent-runtime-ext-java/pom.xml test`.
- [ ] Run the facade dependency tree and prove it contains `agent-core-java:0.1.13` and `react-rails`, with no `react-rails -> pev` edge.
- [ ] Report environment-gated real-LLM skips separately.

### Task 7: Append Commits And Update The Existing PR

- [ ] Audit status, diff, and test evidence.
- [ ] Create an additive implementation commit named `fix: isolate agent core extension behavior`.
- [ ] Push with `git push https://gitcode.com/yaojun97/agent-solution.git HEAD:gepa/pev-beta-ext-trial`; do not force push.
- [ ] Read PR #21 through `gitcode pr view --json` and verify its head SHA equals the local implementation commit.
