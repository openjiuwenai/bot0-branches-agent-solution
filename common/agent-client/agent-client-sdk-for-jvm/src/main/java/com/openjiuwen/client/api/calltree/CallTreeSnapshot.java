/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 一次 invocation 在某一 revision 的完整、不可变调用树视图。 */
public record CallTreeSnapshot(CallTreeNode root, long revision, Completeness completeness,
        SpeakingPhase speakingPhase, NodeKey currentSpeaker, Instant observedAt,
        List<CallTreeDiagnostic> diagnostics) {
    public CallTreeSnapshot {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(completeness, "completeness");
        Objects.requireNonNull(speakingPhase, "speakingPhase");
        observedAt = observedAt == null ? Instant.now() : observedAt;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /** 兼容原六参数构造器。 */
    public CallTreeSnapshot(CallTreeNode root, long revision, Completeness completeness,
            SpeakingPhase speakingPhase, NodeKey currentSpeaker, Instant observedAt) {
        this(root, revision, completeness, speakingPhase, currentSpeaker, observedAt, List.of());
    }
}
