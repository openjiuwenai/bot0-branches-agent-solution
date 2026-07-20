/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured observation of one rail state transition (issue #15 — every transfer needs an
 * observable exit). Strongly-typed sealed hierarchy: each permitted record carries the
 * bearing payload of one exit, and {@link #details()} exposes it as a stable map for
 * listeners that only want the structured fields.
 *
 * <p>Emitted at the origin point of the transition (not via post-hoc ctx peek) because several
 * soft transfers bypass {@code ctx.extra} — {@code setPhaseOverride}/{@code setMode} go through
 * a ThreadLocal, per-step {@code replanCount++} is a private field, and verify violations are a
 * local variable — none of which a priority-peeking observer rail can see.
 *
 * @since 2026-07
 */
public sealed interface RailEvent {
    /**
     * Return the type of the rail state transition.
     *
     * @return the transition type
     */
    RailEventType type();

    /**
     * Return the name of the rail that emitted the event (for logger-name / routing).
     *
     * @return the emitting rail name
     */
    String railName();

    /**
     * Return the bearing payload as a stable map (output/verified/degraded/count/tool/...).
     *
     * @return the bearing payload map
     */
    Map<String, Object> details();

    /**
     * Terminal locked via {@code requestForceFinish}.
     *
     * @param railName emitting rail
     * @param verified true for Exit-1 (verified), false for Exit-3 / degrade paths
     * @param result the forceFinish result map (output, criteria_verified, degraded, ...)
     */
    record ForceFinishEvent(String railName, boolean verified, Map<String, Object> result)
            implements RailEvent {
        @Override
        public RailEventType type() {
            return RailEventType.FORCE_FINISH;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>(result == null ? Map.of() : result);
            d.put("verified", verified);
            return d;
        }
    }

    /**
     * Correction hint pushed via {@code pushSteering} for retry.
     *
     * <p><b>queueBound semantics (honest boundary)</b>: {@code queueBound} captures the value of
     * {@code AgentCallbackContext.hasSteeringQueue()} at fire time — whether the ctx's steering
     * queue field is non-null. It is the exact complement of agent-core's {@code pushSteering}
     * null-guard: {@code queueBound=true} means {@code pushSteering} did NOT silently return
     * (issue #13 silent drop) and DID invoke {@code SteeringQueue.pushSteering};
     * {@code queueBound=false} means the host never provisioned a queue, so the hint was dropped.
     *
     * <p><b>Not equivalent to "hint enqueued"</b>: {@code SteeringQueue} is an interface; a host
     * implementation other than the default {@code LoopQueues} (e.g. one with dedup / capacity /
     * conditional filtering) could drop inside {@code pushSteering} even when
     * {@code queueBound=true}. That is a host-implementation contract beyond react-rails'
     * observation. Under the default {@code LoopQueues} (unbounded {@code ArrayDeque.add}),
     * {@code queueBound=true} is equivalent to enqueued.
     *
     * @param railName emitting rail
     * @param source what triggered the steer (CRITERIA / STAGNATION_OUTPUT / STAGNATION_TOOLCYCLE)
     * @param hintExcerpt truncated hint text (bearing excerpt, not full text)
     * @param queueBound fire-time {@code ctx.hasSteeringQueue()}: true=queue bound (pushSteering
     *     does not silently return), false=host did not provision a queue (silent drop, issue #13)
     */
    record SteeringEvent(String railName, String source, String hintExcerpt, boolean queueBound)
            implements RailEvent {
        @Override
        public RailEventType type() {
            return RailEventType.STEERING;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("source", source);
            d.put("hintExcerpt", hintExcerpt);
            d.put("queueBound", queueBound);
            return d;
        }
    }

    /**
     * Criteria verifier verdict.
     *
     * @param railName emitting rail
     * @param passed true if PASS, false if FAIL
     * @param violationCount number of unmet criteria
     * @param violations unmet-criterion reasons
     */
    record VerifyEvent(String railName, boolean passed, int violationCount, List<String> violations)
            implements RailEvent {
        @Override
        public RailEventType type() {
            return RailEventType.VERIFY;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("passed", passed);
            d.put("violationCount", violationCount);
            d.put("violations", violations);
            return d;
        }
    }

    /**
     * Replan counter incremented.
     *
     * @param railName emitting rail
     * @param count current count after increment
     * @param source LLM (__replan__) or VERIFY_RETRY (bridge shared budget)
     * @param maxReplan configured budget
     */
    record ReplanCountEvent(String railName, int count, String source, int maxReplan)
            implements RailEvent {
        @Override
        public RailEventType type() {
            return RailEventType.REPLAN_COUNT;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("count", count);
            d.put("source", source);
            d.put("maxReplan", maxReplan);
            return d;
        }
    }

    /**
     * Phase override / mode set on the prompt-injection state (soft transfer via ThreadLocal).
     *
     * @param railName emitting rail
     * @param mode PLAN / BUILD / BREAK_TOOL / BREAK_STAGNATION / BREAK_LOOP / null
     * @param overrideExcerpt truncated override text
     */
    record PhaseOverrideEvent(String railName, String mode, String overrideExcerpt)
            implements RailEvent {
        @Override
        public RailEventType type() {
            return RailEventType.PHASE_OVERRIDE;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("mode", mode);
            d.put("overrideExcerpt", overrideExcerpt);
            return d;
        }
    }

    /**
     * Message history compressed on replan.
     *
     * @param railName emitting rail
     * @param beforeMsgCount message count before compression
     * @param afterMsgCount message count after compression
     */
    record ContextCompressedEvent(String railName, int beforeMsgCount, int afterMsgCount)
            implements RailEvent {
        @Override
        public RailEventType type() {
            return RailEventType.CONTEXT_COMPRESSED;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("beforeMsgCount", beforeMsgCount);
            d.put("afterMsgCount", afterMsgCount);
            return d;
        }
    }

    /**
     * Device-failure (tool exception) recorded or fired a degrade.
     *
     * @param railName emitting rail
     * @param tool failed tool name
     * @param phase MARKED (pending degrade) or FIRED (degrade forceFinish issued)
     */
    record DeviceFailureEvent(String railName, String tool, String phase) implements RailEvent {
        @Override
        public RailEventType type() {
            return RailEventType.DEVICE_FAILURE;
        }

        @Override
        public Map<String, Object> details() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("tool", tool);
            d.put("phase", phase);
            return d;
        }
    }
}
