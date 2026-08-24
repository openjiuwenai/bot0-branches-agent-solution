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

    /** 分类结局：转调命中 / 回交基线 / 命中但字段不全整行抑制。 */
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

    /**
     * 非转调结果（回交 FEAT-002 基线映射）。
     *
     * @return NOT_HANDOFF 分类实例
     */
    public static HandoffClassification notHandoff() {
        return new HandoffClassification(Outcome.NOT_HANDOFF, null);
    }
}
