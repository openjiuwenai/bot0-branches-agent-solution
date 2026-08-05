/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

/**
 * Kind of rail state transition surfaced through {@link RailEvent}. One per observable exit.
 *
 * @since 2026-07
 */
public enum RailEventType {
    /** A terminal was locked via {@code requestForceFinish} (verified or degraded). */
    FORCE_FINISH,
    /** A correction hint was pushed via {@code pushSteering} for retry. */
    STEERING,
    /** A criteria verifier returned its verdict (pass/fail + violations). */
    VERIFY,
    /** A replan counter was incremented (LLM {@code __replan__} or verify-retry). */
    REPLAN_COUNT,
    /** A phase override / mode was set on the prompt-injection state (soft transfer). */
    PHASE_OVERRIDE,
    /** The message history was compressed on replan (boundary moved). */
    CONTEXT_COMPRESSED,
    /** A device-failure (tool exception) was recorded or fired a degrade. */
    DEVICE_FAILURE
}
