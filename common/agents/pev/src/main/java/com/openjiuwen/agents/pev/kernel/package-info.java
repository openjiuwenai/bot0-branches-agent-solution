/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * PEV decision core — sealed types + pure-function dispatch (Plan→Execute→Verify→Diagnose→Dispatch).
 *
 * <p>This package is the canonical, self-contained PEV kernel. It depends only on the public
 * {@code agent-core-java} jar (zero coupling to any engine adapter or runtime substrate) and
 * carries zero LLM / framework coupling, so the same logic can be unit-tested exhaustively and
 * reused by sibling agent patterns built on the same base.
 *
 * <p>Two pure functions form the decision core:
 * <ul>
 *   <li>{@link PevKernel#diagnoseRootCause} — verify/execution signals → {@link RootCause}
 *       (3-state diagnosis, prioritised by signal certainty: perception-unreliable &gt;
 *       device-failure &gt; plan/answer-error).</li>
 *   <li>{@link PevKernel#toReplanAction} — {@link RootCause} → {@link ReplanAction}
 *       (3-state dispatch: AcceptPartial / LocalReplan / GlobalReplan).</li>
 * </ul>
 *
 * <p>Three sealed hierarchies ({@link NodeResult}, {@link RootCause}, {@link ReplanAction})
 * constrain the supported variants; the Java 17 dispatchers in {@link PevKernel} handle each
 * permitted variant explicitly and reject unsupported runtime values with
 * {@link IllegalArgumentException}. {@link PevKernel.VerifyResult} is a plain record (not sealed,
 * no subtypes), read via accessors rather than variant dispatch.
 *
 * <p><b>Deferred — trace 架构升级边界</b>：当前 observability（PevTrace）是同步单源
 * loop 的终态副产品，one-emit 非增量，单 sink 场景下帕累托最优。若未来出现 mid-loop
 * 增量 OTel / 重放审计 / 多读模型 的真实需求，再评估 event-sourcing 或 pull-based
 * 跃迁；当前 terminal 单 sink + 同步单线程场景下无需跃迁。
 *
 * @since 2026-07
 */
package com.openjiuwen.agents.pev.kernel;
