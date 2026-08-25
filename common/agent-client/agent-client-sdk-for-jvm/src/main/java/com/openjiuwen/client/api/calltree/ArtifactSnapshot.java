/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

import java.util.List;
import java.util.Objects;

/**
 * 单个 A2A Artifact 的当前只读状态。
 *
 * @since 2026-07-27
 */
public record ArtifactSnapshot(String artifactId, List<PartSnapshot> parts,
        boolean complete, long firstSeenOrder) {
    public ArtifactSnapshot {
        Objects.requireNonNull(artifactId, "artifactId");
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
