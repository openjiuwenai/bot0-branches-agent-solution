# FEAT-020 Intent Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the standalone Java 17 intent extension described by FEAT-020, including the shared recognition core, official A2A v1.0.1 adapter, Agent Tool, Workflow Component, and complete automated tests.

**Architecture:** Compile caller-owned targets into an immutable catalog through a protocol-neutral adapter, score every candidate through the injected `agent-core-java` `Reranker`, aggregate by target, and apply fail-closed score and margin gates. The A2A package snapshots and filters official SDK `AgentCard` objects; thin Tool and Workflow adapters share the same recognizer and result encoder.

**Tech Stack:** Java 17, Maven, agent-core-java 0.1.13, A2A Java SDK 1.0.0.Final, Jackson 2.17, JUnit Jupiter 5, AssertJ.

---

### Task 1: Standalone Maven project and API contracts

**Files:**
- Create: `common/agent-core-ext-java/intent-recognition/pom.xml`
- Create: `common/agent-core-ext-java/intent-recognition/README.md`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/api/*.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/api/IntentRecognizerConfigTest.java`

- [x] Write tests that reject non-finite/non-positive thresholds and limits and verify documented defaults.
- [x] Run the focused tests and confirm they fail because the project/API does not exist.
- [x] Add the Java 17 Maven build, immutable API records/interfaces, reason enum, config builder, and public factory builder.
- [x] Run the focused tests and confirm they pass.

### Task 2: Immutable catalog compiler

**Files:**
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/catalog/IntentCatalog.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/catalog/IntentCatalogCompiler.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/catalog/IntentCatalogCompilerTest.java`

- [x] Write tests for snapshot-before-extraction, duplicate target/candidate rejection, target/candidate limits, immutable collections, order-independent IDs, and deterministic SHA-256 canonical catalog hash.
- [x] Run the focused tests and confirm RED for missing catalog behavior.
- [x] Implement catalog compilation using snapshot objects only, stable ordering, UTF-8 JCS-compatible canonical JSON, and defensive immutable collections.
- [x] Run the focused tests and confirm GREEN.

### Task 3: Reranker recognition, aggregation, errors, tracing, and concurrency

**Files:**
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/reranker/RerankerIntentRecognizer.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/trace/*.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/reranker/RerankerIntentRecognizerTest.java`

- [x] Write tests for NFC/code-point input checks, empty catalog, stable batching, `chunkId` keys, exact scorer map validation, batch failure discard, max-per-target aggregation, score/margin gates, single-target behavior, listener isolation, and semaphore concurrency.
- [x] Run the focused tests and confirm RED for missing recognizer behavior.
- [x] Implement the fail-closed recognizer with immutable inputs, stable batches, exact score validation, deterministic tie handling, trace delivery, and fair semaphore limiting.
- [x] Run the focused tests and confirm GREEN.

### Task 4: Official A2A AgentCard snapshot and eligibility adapter

**Files:**
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/adapter/a2a/A2AAgentCardSnapshots.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/adapter/a2a/A2AEligibilityPolicy.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/adapter/a2a/A2ASecurityRequirementEvaluator.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/adapter/a2a/A2AContentTrustEvaluator.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/adapter/a2a/A2AAgentCardIntentAdapter.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/adapter/a2a/A2AAgentCardIntentAdapterTest.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/adapter/a2a/A2AAgentCardSnapshotsTest.java`

- [x] Write tests that mutate every nested source collection after initialization and verify the official SDK snapshot is unchanged; reject cyclic/non-JSON extension/header values.
- [x] Write tests for interface/version, required extension, inherited media modes, security override, content trust, duplicate skill IDs, deterministic target keys/documents, semantic field exclusion, and over-limit filtering.
- [x] Run the focused tests and confirm RED.
- [x] Rebuild all official nested A2A SDK types and recursively freeze JSON values; implement deterministic eligibility and candidate extraction without custom AgentCard DTOs.
- [x] Run the focused tests and confirm GREEN.

### Task 5: A2A result encoder

**Files:**
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/adapter/a2a/A2AAgentCardResultEncoder.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/api/IntentResultEncoders.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/adapter/a2a/A2AAgentCardResultEncoderTest.java`

- [x] Write a full-card fixture test covering every `AgentCard` record component and asserting `signatures[].protected` exists while `protectedHeader` does not.
- [x] Run the focused test and confirm RED.
- [x] Implement a shared Jackson mapper with the required `AgentCardSignature` MixIn and fixed fallback envelope for encoding failures.
- [x] Run the focused test and confirm GREEN.

### Task 6: Agent Tool adapter

**Files:**
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/tool/IntentRecognitionToolConfig.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/tool/IntentRecognitionTool.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/tool/IntentRecognitionToolTest.java`

- [x] Write tests for fixed `inputs={utterance}`, ignored optional kwargs, immutable dynamic JSON Schema, globally supplied Tool ID, stable visible name, JsonNode result, streaming parity, and encoding fallback.
- [x] Run the focused test and confirm RED.
- [x] Implement the `ToolCard` and `Tool` adapter without session state or AgentCard lifecycle ownership.
- [x] Run the focused test and confirm GREEN.

### Task 7: Workflow Component adapter

**Files:**
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/workflow/IntentRecognitionComponent.java`
- Create: `common/agent-core-ext-java/intent-recognition/src/main/java/com/openjiuwen/ext/intent/workflow/IntentRecognitionExecutable.java`
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/workflow/IntentRecognitionComponentTest.java`

- [x] Write tests for `ComponentComposable` conversion, Map input extraction, field-addressable Map output, malformed input fallback, and exact parity with Tool output.
- [x] Run the focused test and confirm RED.
- [x] Implement the thin Workflow adapter using the same recognizer, encoder, and fallback helper.
- [x] Run the focused test and confirm GREEN.

### Task 8: Framework integration and completion audit

**Files:**
- Test: `common/agent-core-ext-java/intent-recognition/src/test/java/com/openjiuwen/ext/intent/integration/IntentFrameworkIntegrationTest.java`
- Modify: `common/agent-core-ext-java/intent-recognition/README.md`

- [x] Add integration tests for `Runner.resourceMgr()` registration, two same-name/different-ID tools, DeepAgent config acceptance, Workflow graph mounting, and shared recognizer output parity.
- [x] Run all tests with `mvn clean verify` under a Java 17 runtime and fix every failure.
- [x] Run dependency analysis and confirm no A2A client/server or custom HTTP implementation is present.
- [x] Compare every FEAT-020 requirement and test matrix row against a concrete source file and passing test.
- [x] Run `git diff --check` and inspect the complete diff for unrelated changes.
