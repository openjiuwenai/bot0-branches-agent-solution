/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import java.util.List;

/** 调用树归并所需的完整 Artifact 语义。 */
record ProtocolArtifact(String artifactId, List<ProtocolPart> parts, boolean append,
        boolean lastChunk, AgentEvent agentEvent, boolean agentEventDeclared, boolean controllerOutput) {
    ProtocolArtifact {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
