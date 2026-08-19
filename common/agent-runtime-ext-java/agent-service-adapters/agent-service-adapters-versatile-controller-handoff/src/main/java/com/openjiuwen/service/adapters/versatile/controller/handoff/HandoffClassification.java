/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

/**
 * Classification result of a controller output line.
 *
 * @since 2026-08-19
 */
public record HandoffClassification(Outcome outcome, IntentHandoff handoff) {

    public enum Outcome {
        /** Full identification hit — consume as intent handoff. */
        HANDOFF,
        /** No hit — hand back to the FEAT-002 baseline mapping. */
        NOT_HANDOFF,
        /** Identification hit but required field extraction missing (production SSE
         * interleaves incomplete signal frames, e.g. intent echo with {@code text}
         * set and no {@code summary} key) — suppress the line entirely: no
         * processing, no baseline forwarding, no error (spec 2.2, confirmed
         * 2026-08-19). */
        IGNORED
    }

    public static HandoffClassification notHandoff() {
        return new HandoffClassification(Outcome.NOT_HANDOFF, null);
    }
}
