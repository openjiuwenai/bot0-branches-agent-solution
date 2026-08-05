/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * PEV observability — kernel-native trace of one {@code PEVAgent.invoke} run.
 *
 * <p><b>Design (DSPY/GEPA species B — chosen over direct react-rails port)</b>: PEV's承重 truth is a
 * self-contained synchronous state-machine loop whose every bearing value is an invoke-local variable.
 * The trace is a deterministic projection (terminal byproduct) of that loop, NOT a parallel埋点 of
 * rail-around-enforcer transfers. Each {@link com.openjiuwen.agents.pev.observability.PevTrace.Phase}
 * wraps PEV's own sealed kernel types directly (zero new schema).
 *
 * <p><b>Honest boundaries (deliberate, documented)</b>:
 * <ul>
 *   <li><b>Per-instance scope, not process-wide static</b>: the sink is a PEVAgent field. react-rails
 *       needs a static holder (rails are framework-constructed without an agent handle); PEV owns the
 *       loop and holds the sink directly. This avoids concurrent-instance contamination + the
 *       silent-install footgun a process-wide static would import. <b>Future EDPA / multi-agent host
 *       correlation</b>: if cross-agent trace fan-in is ever needed, instance scope must evolve to an
 *       explicit registry with explicit install — not silently inherit a process-wide static.</li>
 *   <li><b>One-emit, not incremental</b>: the trace is the terminal byproduct, so a sink cannot react
 *       mid-loop (no OTel real-time span / live counter / early-exit alerting during a multi-second
 *       invoke). PEV's synchronous single-thread loop partially digests this (exceptions enter the
 *       Verified phase via VerifyResult.hasThrown, device failures via Executed), but the gap is not
 *       falsifiably closed — a future async executor would realize it. Deliberate trade: simplicity +
 *       kernel-fit vs incremental observability.</li>
 *   <li><b>Does not replace the rail seam</b>: {@code CriteriaVerificationRail} / {@code RootCauseRail}
 *       still read the existing AFTER_INVOKE / AFTER_TOOL_CALL {@code "payload"} key. The trace is
 *       additive (a typed {@code "trace"} key alongside {@code "payload"}); migrating those rails to
 *       consume the typed trace is a separate change (deferred, backward-compatible).</li>
 * </ul>
 *
 * @since 2026-07
 */
package com.openjiuwen.agents.pev.observability;
