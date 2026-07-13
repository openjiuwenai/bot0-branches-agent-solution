# PR 21 Full Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve the actionable pre-existing review feedback and verify the complete PR 21 feature set after the Maven boundary refactor.

**Architecture:** Keep the three agreed peer Maven projects and explicit SDK registration model unchanged. Validate behavior at two boundaries: PEV as a self-contained Agent and react-rails as explicit ReActAgent extensions, then compare the revised branch against the original PR head so intentional removals are distinguished from accidental feature loss.

**Tech Stack:** Java 17, Maven 3.9+, JUnit 6, AssertJ, GitCode CLI

---

### Task 1: Resolve the pre-existing review findings

**Files:**
- Modify: `common/agents/pev/src/test/java/com/openjiuwen/agents/pev/agent/PEVAgentControlFlowTest.java`
- Modify: `common/agents/pev/src/main/java/com/openjiuwen/agents/pev/agent/PEVAgent.java`
- Modify: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/enforcing/ToolCallingEnforcingModel.java`
- Modify: `common/agent-core-ext-java/react-rails/src/main/java/com/openjiuwen/agents/reactrails/enforcing/SystemPromptInjectingModel.java`

- [x] Add a control-flow test where the verifier throws a `RuntimeException` subtype other than `IllegalArgumentException` or `IllegalStateException` and assert PEV degrades without retry.
- [x] Run the focused test and verify it fails because the exception escapes.
- [x] Catch `RuntimeException` in `PEVAgent.verify`, update the inaccurate sealed-dispatch Javadoc, and rerun the focused test.
- [x] Rename the Model override parameters and Javadocs to match `agent-core-java:0.1.13` without changing argument order or behavior.
- [x] Run PEV and react-rails unit tests, formatter validation, and Checkstyle.

### Task 2: Verify preservation of the original PR feature set

**Files:**
- Review: all production and test files under `common/agents/pev`
- Review: all production and test files under `common/agent-core-ext-java/react-rails`
- Review: the diff from original PR head `3a3e135` to the revised branch

- [x] Inventory every original PEV, rail, Model, test, and resource from `3a3e135`.
- [x] Classify every removal or behavior change as intentional architecture correction or unintended feature loss.
- [x] Inspect PEV plan/execute/verify/diagnose/dispatch and rail callback behavior against its tests and README contracts.
- [x] Inspect all react-rails registration, state isolation, force-finish, prompt injection, tool probing, and failure degradation paths.
- [x] Add focused regression tests before fixing any newly discovered behavior defect.

### Task 3: Verify project and dependency boundaries

**Files:**
- Verify: `common/agent-core-ext-java/pom.xml`
- Verify: `common/agents/pom.xml`
- Verify: `common/agent-runtime-ext-java/pom.xml`

- [x] Run independent `clean test` for all three Maven projects.
- [x] Confirm react-rails and PEV resolve `agent-core-java:0.1.13` and have no forbidden cross-project or Spring dependency.
- [x] Run stale-reference scans and `git diff --check`.

### Task 4: Publish the review result

**Files:**
- Commit: all verified fixes and review documentation

- [x] Push the source branch using a normal fast-forward push.
- [x] Reply to the pre-existing review discussion with the implemented fixes and the reason `ModelClientFactory` is not applicable.
- [x] Reply to the architecture review discussion with the full-PR feature preservation and verification summary.
- [x] Confirm PR 21 head equals local HEAD and report any remaining external-LLM or CI limitation.
