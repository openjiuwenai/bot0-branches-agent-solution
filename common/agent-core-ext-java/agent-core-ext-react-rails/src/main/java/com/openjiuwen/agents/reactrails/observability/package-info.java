/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Rail observability — structured {@code RailEvent} transition events + telemetry fan-out
 * (issue #15: every rail state transfer needs an observable exit).
 *
 * <p><b>Intra-module leaf</b>: this package depends only on {@code agent-core-java}
 * ({@code core.singleagent.rail} / {@code core.singleagent.agents} / {@code core.common.logging})
 * + JDK. It must NOT depend on sibling rail packages ({@code verification} / {@code replan} /
 * {@code selfheal}) nor on {@code enforcing}. The rail and enforcing packages depend on this
 * package (they emit events and read {@code ObservingRail} forceFinish-result keys); the reverse
 * edge is forbidden and CI-enforced (see {@code com.openjiuwen.agents.reactrails.arch}).
 *
 * <p>The {@code observability → core.singleagent.agents.ReActAgent} edge (via
 * {@code ReactRailsObservability.install}) is an intentional agent-core edge — ReActAgent is this
 * extension's host agent — not an intra-module leak.
 *
 * @since 2026-07
 */
package com.openjiuwen.agents.reactrails.observability;
