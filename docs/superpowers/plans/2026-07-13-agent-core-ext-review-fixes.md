# Agent Core Extension Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete PR #21's project split without changing PEV behavior, while making agent-core-ext-java a Spring-free SDK project that is independent from runtime-ext and concrete agents.

**Architecture:** `agent-runtime-ext-java`, `agent-core-ext-java`, and `agents` are separate Maven reactors with no parent or aggregation relationship. `react-rails` stays under core-ext but depends only on `agent-core-java`; `pev` stays self-contained under agents. Spring auto-configuration and the ReAct-hosted `PevReplanRail` are removed, while explicitly used rails retain per-model and per-invocation state isolation.

**Tech Stack:** Java 17, Maven, JUnit Jupiter, AssertJ, Mockito, GitCode CLI.

---

## File Map

- `common/agent-runtime-ext-java/pom.xml`: runtime modules only; remove sibling reactor entries.
- `common/agent-core-ext-java/pom.xml`: standalone SDK parent/aggregator for `react-rails`.
- `common/agent-core-ext-java/.devtools/*`: core-ext-owned formatter and checkstyle configuration.
- `common/agent-core-ext-java/react-rails/pom.xml`: child of core-ext; core/test dependencies only.
- `common/agents/pom.xml`: standalone parent/aggregator for concrete agents.
- `common/agents/.devtools/*`: agents-owned formatter and checkstyle configuration.
- `common/agents/pev/pom.xml`: child of agents; core/test dependencies only.
- `common/agent-core-ext-java/react-rails/src/main/java/.../autoconfigure/*`: delete Spring integration.
- `common/agent-core-ext-java/react-rails/src/main/java/.../replan/PevReplanRail.java`: delete cross-host integration.
- `PromptInjectionState.java` and `RailInvocationState.java`: explicit state ownership helpers.
- Stateful react-rails implementations and tests: retain configuration on rail instances, move operational state into model/context ownership.
- Root/common/module README files and `CONTRIBUTING.md`: document separate builds and explicit SDK use.

### Task 1: Separate The Three Maven Reactors

**Files:**
- Modify: `common/agent-runtime-ext-java/pom.xml`
- Modify: `common/agent-core-ext-java/pom.xml`
- Modify: `common/agent-core-ext-java/react-rails/pom.xml`
- Create: `common/agent-core-ext-java/.devtools/checkstyle.xml`
- Create: `common/agent-core-ext-java/.devtools/eclipse-java-formatter.xml`
- Modify: `common/agents/pom.xml`
- Modify: `common/agents/pev/pom.xml`
- Create: `common/agents/.devtools/checkstyle.xml`
- Create: `common/agents/.devtools/eclipse-java-formatter.xml`

- [ ] **Step 1: Capture the incorrect parent and reactor relationships**

Run:

```bash
mvn -q -f common/agent-core-ext-java/pom.xml help:evaluate \
  -Dexpression=project.parent.artifactId -DforceStdout
mvn -q -f common/agents/pom.xml help:evaluate \
  -Dexpression=project.parent.artifactId -DforceStdout
rg -n '<module>\.\./(agents|agent-core-ext-java)</module>' \
  common/agent-runtime-ext-java/pom.xml
```

Expected before the fix: both Maven expressions print `agent-runtime-ext-java`, and `rg` finds two sibling module entries.

- [ ] **Step 2: Make the core-ext root POM standalone**

Remove its `<parent>` and facade dependencies. Define its own coordinates and build management:

```xml
<groupId>com.openjiuwen</groupId>
<artifactId>agent-core-ext-java</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>

<properties>
    <java.version>17</java.version>
    <agent-core-java.version>0.1.13</agent-core-java.version>
    <junit-jupiter.version>6.0.1</junit-jupiter.version>
    <assertj.version>3.27.6</assertj.version>
    <mockito.version>5.20.0</mockito.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<modules>
    <module>react-rails</module>
</modules>
```

Add `dependencyManagement` entries for `agent-core-java`, `junit-jupiter`, `assertj-core`, and `mockito-core`. Add compiler release 17 and Surefire 3.5.4 under `<build><pluginManagement>` so the child inherits versions without acquiring runtime dependencies.

- [ ] **Step 3: Make react-rails a child of core-ext**

Replace the parent block with:

```xml
<parent>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agent-core-ext-java</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
</parent>
```

Point local quality tools at core-ext-owned files:

```xml
<configFile>${project.parent.basedir}/.devtools/eclipse-java-formatter.xml</configFile>
<configLocation>${project.parent.basedir}/.devtools/checkstyle.xml</configLocation>
```

- [ ] **Step 4: Make the agents root POM standalone**

Remove its `<parent>` and define:

```xml
<groupId>com.openjiuwen</groupId>
<artifactId>agents</artifactId>
<version>0.1.0</version>
<packaging>pom</packaging>

<properties>
    <java.version>17</java.version>
    <agent-core-java.version>0.1.13</agent-core-java.version>
    <junit-jupiter.version>6.0.1</junit-jupiter.version>
    <assertj.version>3.27.6</assertj.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
</properties>

<modules>
    <module>pev</module>
</modules>
```

Add dependency management for `agent-core-java`, JUnit, and AssertJ, plus compiler release 17 and Surefire 3.5.4 under plugin management.

- [ ] **Step 5: Make PEV a child of agents**

Replace the PEV parent and quality-tool paths with:

```xml
<parent>
    <groupId>com.openjiuwen</groupId>
    <artifactId>agents</artifactId>
    <version>0.1.0</version>
    <relativePath>../pom.xml</relativePath>
</parent>
```

```xml
<configFile>${project.parent.basedir}/.devtools/eclipse-java-formatter.xml</configFile>
<configLocation>${project.parent.basedir}/.devtools/checkstyle.xml</configLocation>
```

- [ ] **Step 6: Give core-ext and agents their own quality configuration**

Use exact copies of the established repository configuration:

```bash
mkdir -p common/agent-core-ext-java/.devtools common/agents/.devtools
cp common/agent-runtime-ext-java/.devtools/checkstyle.xml \
  common/agent-core-ext-java/.devtools/checkstyle.xml
cp common/agent-runtime-ext-java/.devtools/eclipse-java-formatter.xml \
  common/agent-core-ext-java/.devtools/eclipse-java-formatter.xml
cp common/agent-runtime-ext-java/.devtools/checkstyle.xml \
  common/agents/.devtools/checkstyle.xml
cp common/agent-runtime-ext-java/.devtools/eclipse-java-formatter.xml \
  common/agents/.devtools/eclipse-java-formatter.xml
```

- [ ] **Step 7: Restrict runtime-ext to its own modules**

Its modules block must contain only:

```xml
<modules>
    <module>agent-service-adapters/agent-service-adapters-versatile</module>
    <module>agent-service-adapters/agent-service-adapters-agentcore-ext</module>
</modules>
```

- [ ] **Step 8: Verify the independent Maven models**

Run:

```bash
mvn -f common/agent-core-ext-java/pom.xml -DskipTests package
mvn -f common/agents/pom.xml -DskipTests package
mvn -f common/agent-runtime-ext-java/pom.xml -DskipTests package
rg -n 'agent-runtime-ext-java' common/agent-core-ext-java/pom.xml common/agents/pom.xml
```

Expected: all three packages succeed; the final `rg` exits 1 with no matches.

- [ ] **Step 9: Commit the Maven boundary fix**

```bash
git add common/agent-runtime-ext-java/pom.xml \
  common/agent-core-ext-java/pom.xml common/agent-core-ext-java/react-rails/pom.xml \
  common/agent-core-ext-java/.devtools \
  common/agents/pom.xml common/agents/pev/pom.xml common/agents/.devtools
git commit -m "refactor: separate extension maven reactors"
```

### Task 2: Remove Spring And Cross-Host PEV Integration

**Files:**
- Delete: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/autoconfigure/ReactRailsAutoConfiguration.java`
- Delete: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/autoconfigure/ReactRailsProperties.java`
- Delete: `common/agent-core-ext-java/react-rails/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Delete: `common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/autoconfigure/ReactRailsAutoConfigurationTest.java`
- Delete: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/replan/PevReplanRail.java`
- Delete: `common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/replan/PevReplanRailTest.java`
- Modify: `common/agent-core-ext-java/react-rails/pom.xml`

- [ ] **Step 1: Capture the forbidden integration surface**

Run:

```bash
rg -n 'org\.springframework|ReactRailsAutoConfiguration|ReactRailsProperties|PevReplanRail' \
  common/agent-core-ext-java
```

Expected before deletion: matches in the POM, Java sources, resource registration, and tests.

- [ ] **Step 2: Delete the Spring integration files**

Delete the two production classes, the `AutoConfiguration.imports` resource, and the provisional application-context test listed above. Do not create a starter, replacement installer, or runtime adapter.

- [ ] **Step 3: Remove Spring dependencies**

The react-rails dependencies must contain only this shape:

```xml
<dependencies>
    <dependency>
        <groupId>com.openjiuwen</groupId>
        <artifactId>agent-core-java</artifactId>
    </dependency>
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

- [ ] **Step 4: Delete the ReAct-hosted PEV rail**

Delete `PevReplanRail.java` and `PevReplanRailTest.java`. Do not move PEV kernel types or reproduce their dispatch policy in react-rails. Keep `ReplanRail` and `ReplanTool` unchanged except for already planned state isolation.

- [ ] **Step 5: Verify the forbidden surface is gone**

Run:

```bash
rg -n 'org\.springframework|ReactRailsAutoConfiguration|ReactRailsProperties|PevReplanRail|artifactId>pev' \
  common/agent-core-ext-java
mvn -f common/agent-core-ext-java/pom.xml test
```

Expected: `rg` exits 1 with no matches; the core-ext reactor passes.

- [ ] **Step 6: Commit the integration removal**

```bash
git add common/agent-core-ext-java/react-rails/pom.xml
git add -u -- \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/autoconfigure \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/replan/PevReplanRail.java \
  common/agent-core-ext-java/react-rails/src/main/resources/META-INF/spring \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/replan/PevReplanRailTest.java
git commit -m "refactor: keep react rails framework neutral"
```

### Task 3: Isolate Prompt State By Model And Invocation Thread

**Files:**
- Create: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/enforcing/PromptInjectionState.java`
- Create: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/state/RailInvocationState.java`
- Modify: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/enforcing/SystemPromptInjectingModel.java`
- Modify: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/verification/PreCompletionChecklistRail.java`
- Modify: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/verification/StagnationDetectionRail.java`
- Modify: prompt-aware unit and E2E tests under `src/test/java/com/openjiuwen/agents/reactrails`

- [ ] **Step 1: Preserve the model isolation regression tests**

The provisional worktree already contains the tests that were observed RED against the PR implementation. Retain these assertions in `SystemPromptInjectingModelTest`:

```java
first.setInjectionMode(SystemPromptInjectingModel.InjectionMode.PLAN_MODE);
first.setPhaseOverride("first-model-only");

assertThat(second.getInjectionMode())
        .isEqualTo(SystemPromptInjectingModel.InjectionMode.NONE);
assertThat(second.peekPhaseOverride()).isNull();
```

Also retain `configuredModeAppliesOnInvocationThreads` and `runtimeOverridesAreThreadLocal`. Against the original static fields, model isolation fails because the second model observes the first model's mode and override.

- [ ] **Step 2: Implement the model-owned state channel**

`PromptInjectionState` must have this state model:

```java
public final class PromptInjectionState {
    private volatile InjectionMode configuredMode = SystemPromptInjectingModel.DEFAULT_MODE;
    private final ThreadLocal<State> invocationState =
            ThreadLocal.withInitial(() -> new State(configuredMode));

    public void setConfiguredMode(InjectionMode mode) {
        configuredMode = Objects.requireNonNull(mode, "mode");
        invocationState.get().mode = mode;
    }

    public void setMode(InjectionMode mode) {
        invocationState.get().mode = Objects.requireNonNull(mode, "mode");
    }

    public InjectionMode getMode() {
        return invocationState.get().mode;
    }

    public void setPhaseOverride(String override) {
        invocationState.get().phaseOverride = override;
    }

    public String consumePhaseOverride() {
        State state = invocationState.get();
        String value = state.phaseOverride;
        state.phaseOverride = null;
        return value;
    }

    public void reset() {
        configuredMode = SystemPromptInjectingModel.DEFAULT_MODE;
        invocationState.remove();
    }
}
```

Include `peekPhaseOverride()` and the private `State` holder shown by the approved implementation; reject null modes with `Objects.requireNonNull`.

- [ ] **Step 3: Make SystemPromptInjectingModel own the state**

Remove static mode/override fields and expose instance methods:

```java
private final PromptInjectionState injectionState = new PromptInjectionState();

public PromptInjectionState injectionState() {
    return injectionState;
}

public void setInjectionMode(InjectionMode mode) {
    injectionState.setConfiguredMode(mode);
}

public InjectionMode getInjectionMode() {
    return injectionState.getMode();
}
```

All invoke paths read `injectionState.getMode()` and `injectionState.consumePhaseOverride()`.

- [ ] **Step 4: Inject prompt state explicitly into prompt-aware rails**

Use constructor ownership rather than static access:

```java
public PreCompletionChecklistRail(int maxPlanRounds, PromptInjectionState injectionState) {
    this.maxPlanRounds = maxPlanRounds;
    this.injectionState = Objects.requireNonNull(injectionState, "injectionState");
}

public StagnationDetectionRail(PromptInjectionState injectionState) {
    this.injectionState = Objects.requireNonNull(injectionState, "injectionState");
}
```

Update E2E construction to pass `model.injectionState()` and unit tests to pass a dedicated `new PromptInjectionState()`.

- [ ] **Step 5: Add callback-context state for the two prompt-aware rails**

Create `RailInvocationState` with the typed `AgentCallbackContext.extra` lookup shown in Task 4. Move checklist phase
counters and stagnation histories/counters into private `InvocationState` holders. Retain immutable thresholds and the
injected `PromptInjectionState` on each rail instance.

- [ ] **Step 6: Run focused prompt and context tests**

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -Dtest='SystemPromptInjectingModelTest,PreCompletionChecklistRailTest,StagnationDetectionRailTest,*Prompt*E2eTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all local tests pass, including `phaseCountersDoNotCrossInvocationContexts`,
`outputRepetitionDoesNotCrossInvocationContexts`, and `toolFailuresDoNotCrossInvocationContexts`; real-LLM tests are
skipped when `OPENJIUWEN_API_KEY` is absent.

- [ ] **Step 7: Commit prompt and prompt-aware rail isolation**

```bash
git add common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/enforcing/PromptInjectionState.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/enforcing/SystemPromptInjectingModel.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/state/RailInvocationState.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/verification/PreCompletionChecklistRail.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/verification/StagnationDetectionRail.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/enforcing/SystemPromptInjectingModelTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/verification/PreCompletionChecklistRailTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/verification/StagnationDetectionRailTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/e2e/PreCompletionChecklistRailE2eTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/e2e/SystemPromptInjectLlmE2eTest.java
git commit -m "fix: isolate react rail prompt state"
```

### Task 4: Isolate Mutable Rail State By Callback Context

**Files:**
- Modify: `HistoryCompressorRail.java`, `ReplanRail.java`, `RootCauseRail.java`
- Modify: `CriteriaReplanBridgeRail.java`, `CriteriaVerificationRail.java`
- Modify: corresponding `*RailTest.java` files

- [ ] **Step 1: Retain the cross-invocation regression tests**

Keep these exact test methods:

```text
HistoryCompressorRailTest.compressionBoundaryDoesNotCrossInvocationContexts
ReplanRailTest.replanBudgetDoesNotCrossInvocationContexts
RootCauseRailTest.pendingToolFailureDoesNotCrossInvocationContexts
CriteriaReplanBridgeRailTest.decisionHistoryDoesNotCrossInvocationContexts
CriteriaReplanBridgeRailTest.verifyRetryBudgetDoesNotCrossInvocationContexts
CriteriaVerificationRailTest.decisionHistoryDoesNotCrossInvocationContexts
```

Each test uses one rail instance and two distinct `AgentCallbackContext.extra` maps. The second context must observe fresh counters, histories, and pending markers.

- [ ] **Step 2: Verify the shared context state helper contract**

```java
public static <T> T get(AgentCallbackContext context, String key,
        Class<T> stateType, Supplier<T> factory) {
    Map<String, Object> extra = Objects.requireNonNull(context, "context").getExtra();
    if (extra == null) {
        throw new IllegalStateException("AgentCallbackContext.extra must not be null");
    }
    Object value = extra.computeIfAbsent(key, ignored -> factory.get());
    if (!stateType.isInstance(value)) {
        throw new IllegalStateException("Unexpected invocation state type for key " + key);
    }
    return stateType.cast(value);
}
```

The helper was added in Task 3. Confirm it generates one unique key per rail instance with `owner.getName()` plus an
`AtomicLong` suffix and rejects null `extra` maps or values of an unexpected type.

- [ ] **Step 3: Move operational fields into per-context holders**

Apply this shape to every listed rail:

```java
private final String stateKey = RailInvocationState.newKey(ReplanRail.class);

private InvocationState state(AgentCallbackContext context) {
    return RailInvocationState.get(context, stateKey,
            InvocationState.class, InvocationState::new);
}

private static final class InvocationState {
    private int replanCount;
}
```

Use these exact state holders:

```text
HistoryCompressorRail: lastBoundary
ReplanRail: replanCount
RootCauseRail: hasPendingDegrade, failedTool
CriteriaReplanBridgeRail: decisionHistory
CriteriaVerificationRail: decisionHistory
PreCompletionChecklistRail: callCount, outputHashes, toolNamesCalled, hasPreviousFinalAnswer
StagnationDetectionRail: outputHistory, toolSignatureHistory, consecutiveOutputRepeats,
  toolCycleRepeats, totalStagnations, lastToolRoundSignature, lastFailedTool,
  consecutiveToolFailures
```

Keep immutable configuration such as `maxReplan`, criteria, thresholds, and verifier references on the rail instance.

- [ ] **Step 4: Run all stateful rail tests**

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  -Dtest='HistoryCompressorRailTest,ReplanRailTest,RootCauseRailTest,CriteriaReplanBridgeRailTest,CriteriaVerificationRailTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all tests pass, including the six isolation regressions listed in Step 1.

- [ ] **Step 5: Commit invocation isolation**

```bash
git add common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/replan/HistoryCompressorRail.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/replan/ReplanRail.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/selfheal/RootCauseRail.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/verification/CriteriaReplanBridgeRail.java \
  common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/verification/CriteriaVerificationRail.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/replan/HistoryCompressorRailTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/replan/ReplanRailTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/selfheal/RootCauseRailTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/verification/CriteriaReplanBridgeRailTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/verification/CriteriaVerificationRailTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/e2e/CriteriaBridgeRealLlmE2eTest.java \
  common/agent-core-ext-java/react-rails/src/test/java/com/openjiuwen/agents/reactrails/e2e/ReplanRailRealLlmE2eTest.java
git commit -m "fix: scope react rail state to invocations"
```

### Task 5: Verify PEV Behavior And Update Documentation

**Files:**
- Modify: `README.md`
- Modify: `README.zh.md`
- Modify: `CONTRIBUTING.md`
- Modify: `common/README.md`
- Modify: `common/agent-core-ext-java/react-rails/README.md`
- Modify: `common/agents/pev/README.md`

- [ ] **Step 1: Run the complete PEV suite before documentation changes**

```bash
mvn -f common/agents/pom.xml clean test
```

Expected: all deterministic PEV tests pass. Real-LLM tests either pass with credentials or report JUnit assumption skips without failing the build.

- [ ] **Step 2: Prove PEV retains only its core dependency**

```bash
mvn -f common/agents/pom.xml -pl :pev dependency:tree \
  -Dincludes=com.openjiuwen:agent-core-java,org.springframework.boot:*,com.openjiuwen:agent-runtime-*
```

Expected: `com.openjiuwen:agent-core-java:0.1.13` appears; no Spring or runtime artifact appears.

- [ ] **Step 3: Document separate builds**

Replace the former single runtime-reactor command in the root, Chinese, common, and contributing docs with:

```bash
mvn -f common/agent-core-ext-java/pom.xml clean install
mvn -f common/agents/pom.xml clean install
mvn -f common/agent-runtime-ext-java/pom.xml clean install
```

Document the three directories as peer projects. Do not mention a `common/pom.xml` or a parent relationship between them.

- [ ] **Step 4: Document explicit react-rails usage**

Remove Spring properties, auto-configuration, `PevReplanRail`, and obsolete `agent-patterns` paths. Show direct Maven and Java use:

```xml
<dependency>
    <groupId>com.openjiuwen</groupId>
    <artifactId>react-rails</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
ReplanRail replanRail = new ReplanRail(2);
agent.registerRail(replanRail);
ReplanTool.registerOnto(agent);
```

For prompt-aware rails, show `new PreCompletionChecklistRail(2, model.injectionState())`.

- [ ] **Step 5: Fix the PEV build command and dependency description**

Use:

```bash
mvn -f common/agents/pom.xml -pl :pev -am test
```

State that PEV depends on `agent-core-java:0.1.13`, is Spring-free, and does not depend on react-rails or runtime-ext.

- [ ] **Step 6: Scan documentation for stale architecture**

```bash
rg -n 'agent-patterns/pev|reactrails\.enabled|PevReplanRail|agent-runtime-ext-java.*(parent|父工程)' \
  README.md README.zh.md CONTRIBUTING.md common \
  -g '*.md'
```

Expected: no stale PEV path, reactrails property, deleted rail, or cross-project parent statement remains. Runtime-specific documentation may continue to describe runtime-ext as the parent of its own adapter modules.

- [ ] **Step 7: Commit documentation**

```bash
git add README.md README.zh.md CONTRIBUTING.md common/README.md \
  common/agent-core-ext-java/react-rails/README.md common/agents/pev/README.md
git commit -m "docs: document independent extension projects"
```

### Task 6: Full Verification And Existing PR Update

**Files:** all files changed by Tasks 1-5; no new functional scope.

- [ ] **Step 1: Validate formatting and checkstyle separately**

```bash
mvn -f common/agent-core-ext-java/pom.xml \
  net.revelc.code.formatter:formatter-maven-plugin:2.24.1:validate \
  org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check
mvn -f common/agents/pom.xml \
  net.revelc.code.formatter:formatter-maven-plugin:2.24.1:validate \
  org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check
```

Expected: the separately owned core-ext and agents checks pass. Runtime-ext remains unchanged and is covered by its normal reactor build.

- [ ] **Step 2: Run all three independent reactors**

```bash
mvn -f common/agent-core-ext-java/pom.xml clean test
mvn -f common/agents/pom.xml clean test
mvn -f common/agent-runtime-ext-java/pom.xml clean test
```

Expected: all builds pass; credential-dependent tests are reported as skipped when their environment is absent.

- [ ] **Step 3: Audit dependency boundaries and diff hygiene**

```bash
mvn -f common/agent-core-ext-java/pom.xml -pl :react-rails dependency:tree
mvn -f common/agents/pom.xml -pl :pev dependency:tree
rg -n 'org\.springframework|artifactId>pev|agent-runtime-ext-java' \
  common/agent-core-ext-java -g 'pom.xml' -g '*.java' -g '*.imports'
rg -n 'org\.springframework|agent-runtime-ext-java|artifactId>react-rails' \
  common/agents -g 'pom.xml' -g '*.java' -g '*.imports'
git diff --check upstream/common...HEAD
git status --short --branch
```

Expected: core-ext and agents contain none of the forbidden dependency references; diff check is clean; only intentional files are changed.

- [ ] **Step 4: Confirm additive commit history**

```bash
git log --oneline --decorate upstream/common..HEAD
```

Expected: the existing review/design commits plus the new focused commits are present. Do not rebase, amend published commits, or force push.

- [ ] **Step 5: Push the existing PR branch**

```bash
gitcode auth status
git push https://gitcode.com/yaojun97/agent-solution.git \
  HEAD:gepa/pev-beta-ext-trial
```

Expected: a normal non-force push succeeds.

- [ ] **Step 6: Verify PR #21 points to the local HEAD**

```bash
gitcode pr view 21 -R openJiuwen/agent-solution --json
git rev-parse HEAD
```

Compare the PR source/head SHA in the JSON with `git rev-parse HEAD`. They must match. Re-read PR comments to ensure the architecture review remains visible and report the final test evidence without creating a new PR.
