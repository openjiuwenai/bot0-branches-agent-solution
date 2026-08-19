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
        /** Identification hit but required field extraction failed (spec 2.2). */
        CONTRACT_VIOLATION
    }

    public static HandoffClassification notHandoff() {
        return new HandoffClassification(Outcome.NOT_HANDOFF, null);
    }
}
