/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

import java.time.Instant;
import java.util.Objects;

/** 一次 invocation 在某一 revision 的完整、不可变调用树视图。 */
public record CallTreeSnapshot(CallTreeNode root, long revision, Completeness completeness,
        SpeakingPhase speakingPhase, NodeKey currentSpeaker, Instant observedAt) {
    public CallTreeSnapshot {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(speakingPhase, "speakingPhase");
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }
}
