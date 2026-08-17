/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 按 Artifact identity 物化根 Agent 业务输出；不受调用树容量/降级策略影响。 */
final class RootOutputReducer {
    private final Map<String, MaterializedArtifact> artifacts = new LinkedHashMap<>();
    private boolean observed;

    synchronized void accept(ProtocolArtifact artifact) {
        if (!isRootBusinessArtifact(artifact)) {
            return;
        }
        observed = true;
        MaterializedArtifact current = artifacts.computeIfAbsent(
                artifact.artifactId(), ignored -> new MaterializedArtifact());
        if (!artifact.append()) {
            current.parts.clear();
        }
        current.parts.addAll(artifact.parts());
        current.complete |= artifact.lastChunk();
    }

    synchronized void replaceWithTaskSnapshot(List<ProtocolArtifact> snapshot) {
        artifacts.clear();
        observed = true;
        if (snapshot == null) {
            return;
        }
        for (ProtocolArtifact artifact : snapshot) {
            accept(artifact);
        }
    }

    synchronized Optional<String> currentText() {
        if (!observed) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (MaterializedArtifact artifact : artifacts.values()) {
            for (ProtocolPart part : artifact.parts) {
                appendText(text, part);
            }
        }
        return Optional.of(text.toString());
    }

    static Optional<String> materializeTaskSnapshot(List<ProtocolArtifact> snapshot) {
        RootOutputReducer reducer = new RootOutputReducer();
        reducer.replaceWithTaskSnapshot(snapshot);
        return reducer.currentText();
    }

    private static boolean isRootBusinessArtifact(ProtocolArtifact artifact) {
        return artifact != null && !artifact.agentEventDeclared() && !artifact.controllerOutput();
    }

    private static void appendText(StringBuilder text, ProtocolPart part) {
        if (part instanceof ProtocolPart.Text value) {
            text.append(value.text());
            return;
        }
        if (part instanceof ProtocolPart.Data value && value.data() instanceof Map<?, ?> data) {
            Object content = data.get("content");
            if (content != null) {
                text.append(content);
            }
        }
    }

    private static final class MaterializedArtifact {
        private final List<ProtocolPart> parts = new ArrayList<>();
        private boolean complete;
    }
}
