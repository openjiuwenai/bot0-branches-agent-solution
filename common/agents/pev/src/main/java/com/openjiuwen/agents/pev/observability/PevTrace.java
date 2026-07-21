/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.observability;

import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.agents.pev.kernel.ReplanAction;
import com.openjiuwen.agents.pev.kernel.RootCause;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic projection of one {@code PEVAgent.invoke} run (PEV observability — kernel-native trace).
 *
 * <p><b>Design rationale (DSPY/GEPA species B, chosen over direct react-rails port)</b>: PEV's承重
 * truth is a self-contained synchronous state-machine loop; every bearing value (plan / stepResults /
 * VerifyResult / RootCause / ReplanAction) is a local variable of the single invoke method. The trace
 * is therefore a <b>byproduct of the loop</b>, not a parallel埋点 of enforcer transfers. It is emitted
 * once at invoke return (terminal byproduct), and each {@link Phase} wraps PEV's OWN sealed kernel
 * types directly — zero new schema, zero stringly-typed taxonomy.
 *
 * <p><b>Scope = per-PEVAgent-instance</b> (the sink is a PEVAgent field), NOT a process-wide static
 * holder. react-rails needs a static holder because its rails are framework-constructed without an
 * agent handle; PEV owns the loop and holds the sink directly. Instance scope avoids the concurrent-
 * instance contamination + silent-install footgun a process-wide static would import.
 *
 * <p><b>Honest boundary (one-emit, not incremental)</b>: the trace is the terminal byproduct, so a
 * sink cannot react mid-loop (no OTel real-time span event / live counter / early-exit alerting
 * during a multi-second invoke). PEV's synchronous single-thread loop partially digests this
 * (verifier exceptions already enter the Verified phase via VerifyResult.hasThrown, device failures
 * enter via Executed), but the gap is not falsifiably closed — a future async executor would realize
 * it. Documented as a deliberate trade (simplicity + kernel-fit vs incremental).
 *
 * @param phases           ordered state-machine phases executed during invoke (Plan/Execute/Verify/
 *                         Diagnose/Dispatch appended at their transition points; verify-pass short-
 *                         circuits without Diagnose/Dispatch — real branch, not恒 fire全)
 * @param terminalReason   why invoke terminated (PASSED / ACCEPT_PARTIAL / MAX_RETRIES_EXCEEDED /
 *                         INCONCLUSIVE — never {@code null}; see {@link TerminalReason})
 * @param verifyIterations number of verify evaluations performed (deducible from phases but kept
 *                         explicit for O(1) logger/metric consumers)
 * @since 2026-07
 */
public record PevTrace(List<Phase> phases, TerminalReason terminalReason, int verifyIterations) {

    /**
     * One state-machine transition phase. Payload wraps a PEV kernel sealed type directly (zero new
     * schema). Three of the five (Verified / Diagnosed / Dispatched) were previously dark transfers
     * — consumed as invoke-local variables, invisible to outside observers.
     */
    public sealed interface Phase permits Planned, Executed, Verified, Diagnosed, Dispatched {
        /**
         * Stable phase name for logging / routing.
         *
         * @return phase name
         */
        String phaseName();

        /**
         * Stable detail map derived from the wrapped kernel type (for logger / OTel / Micrometer
         * consumers that want structured fields, not stringly-typed payloads).
         *
         * @return derived detail map
         */
        Map<String, Object> detail();
    }

    /** Plan phase — the planner output that drives execution. */
    public record Planned(PevComponents.Plan plan) implements Phase {
        @Override
        public String phaseName() {
            return "PLANNED";
        }

        @Override
        public Map<String, Object> detail() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("phase", phaseName());
            d.put("goal", plan == null ? null : plan.goal());
            return d;
        }
    }

    /** Execute phase — the executor's per-superstep node-result map. */
    public record Executed(Map<String, NodeResult> stepResults) implements Phase {
        @Override
        public String phaseName() {
            return "EXECUTED";
        }

        @Override
        public Map<String, Object> detail() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("phase", phaseName());
            d.put("nodeCount", stepResults == null ? 0 : stepResults.size());
            return d;
        }
    }

    /** Verify phase — the verifier verdict (drives dispatch; previously a dark transfer). */
    public record Verified(PevKernel.VerifyResult verdict) implements Phase {
        @Override
        public String phaseName() {
            return "VERIFIED";
        }

        @Override
        public Map<String, Object> detail() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("phase", phaseName());
            d.put("passed", verdict == null ? null : verdict.isPassed());
            d.put("failedNodeCount", verdict == null ? 0 : verdict.failedNodes().size());
            return d;
        }
    }

    /** Diagnose phase — the root-cause classification (previously a dark transfer). */
    public record Diagnosed(RootCause cause) implements Phase {
        @Override
        public String phaseName() {
            return "DIAGNOSED";
        }

        @Override
        public Map<String, Object> detail() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("phase", phaseName());
            d.put("cause", cause == null ? null : cause.getClass().getSimpleName());
            return d;
        }
    }

    /** Dispatch phase — the replan action chosen (the single most important control-flow fact). */
    public record Dispatched(ReplanAction action) implements Phase {
        @Override
        public String phaseName() {
            return "DISPATCHED";
        }

        @Override
        public Map<String, Object> detail() {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("phase", phaseName());
            d.put("action", action == null ? null : action.getClass().getSimpleName());
            return d;
        }
    }

    /**
     * Why invoke terminated. Covers every reachable terminal path of {@code PEVAgent.invoke} —
     * {@code null} is never emitted (the trace contract).
     */
    public enum TerminalReason {
        /** Verify passed — clean exit. */
        PASSED,
        /** AcceptPartial — degraded terminal (device failure / unrecoverable). */
        ACCEPT_PARTIAL,
        /** Max retries exhausted without AcceptPartial. */
        MAX_RETRIES_EXCEEDED,
        /**
         * LocalReplan had nothing to redo — the verifier reported failed nodes not present in the
         * plan (a verifier/executor contract mismatch), so {@code handleLocalReplan}'s redo set was
         * empty and the loop fell through without a clean PASSED/ACCEPT_PARTIAL/MAX_RETRIES terminal.
         * Honest label for this pre-existing degenerate path; the output is whatever partial
         * completed map assembled. (The underlying "should empty-redo LocalReplan be a degraded
         * terminal?" is a separate control-flow question; INCONCLUSIVE only labels the current
         * behavior truthfully — it does not change {@code invoke}'s output.)
         */
        INCONCLUSIVE
    }
}
