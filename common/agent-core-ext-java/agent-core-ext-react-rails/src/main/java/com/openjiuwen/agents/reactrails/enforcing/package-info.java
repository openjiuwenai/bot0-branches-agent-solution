/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Tool-calling enforcement + system-prompt injection — the model-layer channel that detects
 * tool-calling bypass and injects phase-aware guardrails.
 *
 * <p><b>Layering</b>: this package depends on {@code agent-core-java} ({@code foundation.llm}
 * Model/Tool) + the sibling {@code observability} package (it fires {@code RailEvent}s via
 * {@code PromptInjectionState}) + JDK. It must NOT depend on the rail-LOGIC packages
 * ({@code verification} / {@code selfheal}); rails may consume enforcing models, not the reverse.
 *
 * <p><b>Intentional edge (issue #16)</b>: {@code SystemPromptInjectingModel} imports
 * {@code replan.ReplanTool.TOOL_NAME} to keep the {@code ${replan_tool}} prompt placeholder
 * single-source. {@code TOOL_NAME} is a compile-time constant, so javac inlines it and the
 * source-level enforcing→replan edge is invisible to bytecode-level ArchUnit — hence the ArchUnit
 * rule excludes {@code replan} (prohibiting it would pass despite the real edge = false bearing).
 * CI-enforced for verification/selfheal (see {@code com.openjiuwen.agents.reactrails.arch}).
 *
 * @since 2026-07
 */
package com.openjiuwen.agents.reactrails.enforcing;
