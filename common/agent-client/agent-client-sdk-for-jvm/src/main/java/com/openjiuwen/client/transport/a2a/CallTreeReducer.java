/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.calltree.ArtifactSnapshot;
import com.openjiuwen.client.api.calltree.CallTreeNode;
import com.openjiuwen.client.api.calltree.CallTreeDiagnostic;
import com.openjiuwen.client.api.calltree.CallTreeSnapshot;
import com.openjiuwen.client.api.calltree.Completeness;
import com.openjiuwen.client.api.calltree.DataPartSnapshot;
import com.openjiuwen.client.api.calltree.NodeKey;
import com.openjiuwen.client.api.calltree.PartSnapshot;
import com.openjiuwen.client.api.calltree.SpeakingPhase;
import com.openjiuwen.client.api.calltree.TextPartSnapshot;
import com.openjiuwen.client.internal.calltree.LatestValuePublisher;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Flow;

/** 单 invocation 的调用树归并器；所有调用由拥有它的 Channel 串行驱动。 */
final class CallTreeReducer implements AutoCloseable {
    private static final int MAX_NODES = 256;
    private static final int MAX_ORPHAN_EDGES = 512;
    private static final int MAX_ORPHAN_SIGNALS = 512;
    private static final long MAX_TREE_BYTES = 8L * 1024L * 1024L;
    private static final long MAX_ARTIFACT_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_DIAGNOSTICS = 64;

    private final InvocationMode mode;
    private final Map<NodeKey, MutableNode> nodes = new LinkedHashMap<>();
    private final Map<NodeKey, List<PendingDelegation>> orphanEdges = new LinkedHashMap<>();
    private final Map<NodeKey, List<PendingSignal>> orphanSignals = new LinkedHashMap<>();
    private final Map<String, String> artifactOwnerTaskIds = new LinkedHashMap<>();
    private final List<CallTreeDiagnostic> diagnostics = new ArrayList<>();
    private final LatestValuePublisher<CallTreeSnapshot> publisher = new LatestValuePublisher<>();
    private MutableNode root;
    private String rootTaskId;
    private long revision;
    private Completeness completeness;
    private SpeakingPhase speakingPhase = SpeakingPhase.UNKNOWN;
    private NodeKey currentSpeaker;
    private int orphanEdgeCount;
    private int orphanSignalCount;
    private long treeBytes;
    private long orphanBytes;
    private boolean closed;

    CallTreeReducer(InvocationMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.completeness = mode == InvocationMode.STREAMING
                ? Completeness.LIVE : Completeness.UNAVAILABLE_FOR_MODE;
    }

    synchronized void bindRoot(String taskId) {
        if (taskId == null || taskId.isBlank() || root != null) {
            return;
        }
        rootTaskId = taskId;
        root = new MutableNode(new NodeKey(null, taskId));
        nodes.put(root.key, root);
        publish();
    }

    synchronized void accept(ProtocolArtifact artifact) {
        if (root == null || artifact == null) {
            return;
        }
        if (artifact.controllerOutput()) {
            if (!claimArtifact(root, artifact.artifactId())) {
                publish();
                return;
            }
            speakingPhase = SpeakingPhase.ROOT_SPEAKING;
            currentSpeaker = root.key;
            publish();
            return;
        }
        AgentEvent event = artifact.agentEvent();
        if (event == null) {
            if (artifact.agentEventDeclared()) {
                completeness = Completeness.DEGRADED;
                diagnose(CallTreeDiagnostic.AGENT_EVENT_INVALID,
                        "agentEvent is missing required fields or uses an unsupported type", artifact.artifactId());
                publish();
                return;
            }
            mergeArtifact(root, artifact);
            speakingPhase = SpeakingPhase.ROOT_SPEAKING;
            currentSpeaker = root.key;
            publish();
            return;
        }
        if (!event.valid()) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.AGENT_EVENT_INVALID,
                    "agentEvent is missing required fields or uses an unsupported type", artifact.artifactId());
            publish();
            return;
        }
        if (mode != InvocationMode.STREAMING && completeness == Completeness.UNAVAILABLE_FOR_MODE) {
            completeness = Completeness.PARTIAL;
        }
        switch (event.type()) {
            case "delegation" -> acceptDelegation(event, artifact);
            case "output" -> acceptOutput(event, artifact);
            case "status" -> acceptStatus(event, artifact);
            default -> {
                return;
            }
        }
        publish();
    }

    private void acceptDelegation(AgentEvent event, ProtocolArtifact artifact) {
        MutableNode source = existingNode(event.source());
        if (source == null) {
            bufferEdge(event, artifact);
            return;
        }
        if (!claimArtifact(source, artifact.artifactId())) {
            return;
        }
        MutableNode target = createNode(event.target());
        if (target == null || source == target || createsCycle(source, target)) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.ILLEGAL_CYCLE, "delegation would create a cycle", artifact.artifactId());
            return;
        }
        if (target.parent != null && target.parent != source) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.MULTIPLE_PARENTS, "target already belongs to another parent",
                    artifact.artifactId());
            return;
        }
        target.parent = source;
        if (!source.children.contains(target)) {
            source.children.add(target);
        }
        if (target.delegationIntent == null) {
            target.delegationIntent = firstText(artifact.parts()).orElse(null);
        }
        speakingPhase = SpeakingPhase.WAITING_DESCENDANTS;
        drainOrphans(target);
    }

    private void acceptOutput(AgentEvent event, ProtocolArtifact artifact) {
        MutableNode source = existingNode(event.source());
        if (source == null) {
            bufferSignal(event, artifact);
            return;
        }
        mergeArtifact(source, artifact);
        speakingPhase = source == root ? SpeakingPhase.ROOT_SPEAKING : SpeakingPhase.DESCENDANT_SPEAKING;
        currentSpeaker = source.key;
    }

    private void mergeArtifact(MutableNode source, ProtocolArtifact artifact) {
        if (!claimArtifact(source, artifact.artifactId())) {
            return;
        }
        MutableArtifact current = source.artifacts.get(artifact.artifactId());
        if (current == null) {
            current = new MutableArtifact(artifact.artifactId(), source.artifacts.size());
            source.artifacts.put(artifact.artifactId(), current);
        }
        long replacedBytes = artifact.append() ? 0 : current.bytes;
        long artifactBudget = MAX_ARTIFACT_BYTES - (artifact.append() ? current.bytes : 0);
        long treeBudget = MAX_TREE_BYTES - (treeBytes - replacedBytes);
        long available = Math.max(0, Math.min(artifactBudget, treeBudget));
        BoundedParts bounded = boundedParts(artifact.parts(), available);
        if (!artifact.append()) {
            current.parts.clear();
            treeBytes -= current.bytes;
            current.bytes = 0;
        }
        current.parts.addAll(bounded.parts());
        current.bytes += bounded.bytes();
        treeBytes += bounded.bytes();
        current.complete = current.complete || artifact.lastChunk();
        if (bounded.truncated()) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.RESOURCE_LIMIT, "artifact or tree byte limit exceeded",
                    artifact.artifactId());
        }
    }

    private void acceptStatus(AgentEvent event, ProtocolArtifact artifact) {
        MutableNode source = existingNode(event.source());
        if (source == null) {
            bufferSignal(event, artifact);
            return;
        }
        if (artifact != null && !claimArtifact(source, artifact.artifactId())) {
            return;
        }
        if (!isTerminal(source.state) || isTerminal(event.state())) {
            source.state = event.state();
        }
    }

    private boolean claimArtifact(MutableNode source, String artifactId) {
        return claimArtifact(source.key.taskId(), artifactId);
    }

    private boolean claimArtifact(String taskId, String artifactId) {
        String ownerTaskId = artifactOwnerTaskIds.putIfAbsent(artifactId, taskId);
        if (ownerTaskId != null && !ownerTaskId.equals(taskId)) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.ARTIFACT_ID_CONFLICT,
                    "artifactId is already owned by task " + ownerTaskId, artifactId);
            return false;
        }
        return true;
    }

    private MutableNode existingNode(AgentEvent.AgentRef ref) {
        NodeKey key = new NodeKey(ref.agentId(), ref.taskId());
        MutableNode found = nodes.get(key);
        if (found != null) {
            return found;
        }
        if (ref.taskId().equals(rootTaskId)) {
            nodes.remove(root.key);
            root.key = key;
            nodes.put(key, root);
            if (currentSpeaker != null && currentSpeaker.taskId().equals(rootTaskId)) {
                currentSpeaker = key;
            }
            return root;
        }
        return null;
    }

    private MutableNode createNode(AgentEvent.AgentRef ref) {
        MutableNode found = existingNode(ref);
        if (found != null) {
            return found;
        }
        if (nodes.size() >= MAX_NODES) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.RESOURCE_LIMIT, "call tree node limit exceeded", null);
            return null;
        }
        MutableNode created = new MutableNode(new NodeKey(ref.agentId(), ref.taskId()));
        nodes.put(created.key, created);
        return created;
    }

    private void bufferEdge(AgentEvent event, ProtocolArtifact artifact) {
        if (orphanEdgeCount >= MAX_ORPHAN_EDGES) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.RESOURCE_LIMIT, "orphan edge limit exceeded", artifact.artifactId());
            return;
        }
        if (!claimArtifact(event.source().taskId(), artifact.artifactId())) {
            return;
        }
        BufferedArtifact buffered = bufferArtifact(artifact);
        NodeKey source = key(event.source());
        orphanEdges.computeIfAbsent(source, ignored -> new ArrayList<>())
                .add(new PendingDelegation(event, buffered.artifact(), buffered.bytes()));
        orphanEdgeCount++;
        orphanBytes += buffered.bytes();
    }

    private void bufferSignal(AgentEvent event, ProtocolArtifact artifact) {
        if (orphanSignalCount >= MAX_ORPHAN_SIGNALS) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.RESOURCE_LIMIT, "orphan signal limit exceeded",
                    artifact != null ? artifact.artifactId() : null);
            return;
        }
        if (artifact != null && !claimArtifact(event.source().taskId(), artifact.artifactId())) {
            return;
        }
        BufferedArtifact buffered = artifact == null
                ? new BufferedArtifact(null, 0) : bufferArtifact(artifact);
        NodeKey source = key(event.source());
        orphanSignals.computeIfAbsent(source, ignored -> new ArrayList<>())
                .add(new PendingSignal(event, buffered.artifact(), buffered.bytes()));
        orphanSignalCount++;
        orphanBytes += buffered.bytes();
    }

    private BufferedArtifact bufferArtifact(ProtocolArtifact artifact) {
        long available = Math.max(0, Math.min(MAX_ARTIFACT_BYTES, MAX_TREE_BYTES - treeBytes - orphanBytes));
        long bytes = protocolBytes(artifact.parts());
        List<ProtocolPart> parts = artifact.parts();
        if (bytes > available) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.RESOURCE_LIMIT, "orphan/tree byte limit exceeded", artifact.artifactId());
            parts = List.of();
            bytes = 0;
        }
        ProtocolArtifact copy = new ProtocolArtifact(artifact.artifactId(), parts, artifact.append(),
                artifact.lastChunk(), artifact.agentEvent(), artifact.agentEventDeclared(),
                artifact.controllerOutput());
        return new BufferedArtifact(copy, bytes);
    }

    private static long protocolBytes(List<ProtocolPart> parts) {
        long bytes = 0L;
        for (ProtocolPart part : parts) {
            long next = part instanceof ProtocolPart.Text text
                    ? utf8Bytes(text.text())
                    : part instanceof ProtocolPart.Data data ? estimateBytes(data.data()) : 0L;
            bytes = saturatedAdd(bytes, next);
        }
        return bytes;
    }

    private void drainOrphans(MutableNode node) {
        List<PendingSignal> signals = orphanSignals.remove(node.key);
        if (signals != null) {
            orphanSignalCount -= signals.size();
            for (PendingSignal signal : signals) {
                orphanBytes -= signal.bytes();
                if ("output".equals(signal.event().type())) {
                    acceptOutput(signal.event(), signal.artifact());
                } else {
                    acceptStatus(signal.event(), signal.artifact());
                }
            }
        }
        List<PendingDelegation> edges = orphanEdges.remove(node.key);
        if (edges != null) {
            orphanEdgeCount -= edges.size();
            for (PendingDelegation edge : edges) {
                orphanBytes -= edge.bytes();
                acceptDelegation(edge.event(), edge.artifact());
            }
        }
    }

    private static NodeKey key(AgentEvent.AgentRef ref) {
        return new NodeKey(ref.agentId(), ref.taskId());
    }

    private static boolean createsCycle(MutableNode source, MutableNode target) {
        for (MutableNode current = source; current != null; current = current.parent) {
            if (current == target) {
                return true;
            }
        }
        return false;
    }

    synchronized void markRecovered(boolean replayed) {
        if (mode == InvocationMode.STREAMING && completeness != Completeness.DEGRADED) {
            completeness = replayed ? Completeness.RECOVERED_REPLAYED : Completeness.RECOVERED_CURRENT_STATE;
            publish();
        }
    }

    synchronized void markPartialRecovery() {
        if (mode == InvocationMode.STREAMING && completeness != Completeness.DEGRADED) {
            completeness = Completeness.PARTIAL;
            publish();
        }
    }

    synchronized void updateRootState(com.openjiuwen.client.api.TaskState state) {
        if (root == null || state == null) {
            return;
        }
        String value = state.name().toLowerCase(java.util.Locale.ROOT);
        if (!isTerminal(root.state) || isTerminal(value)) {
            root.state = value;
            publish();
        }
    }

    synchronized Optional<CallTreeSnapshot> current() {
        return publisher.latest();
    }

    Flow.Publisher<CallTreeSnapshot> publisher() {
        return publisher;
    }

    private void publish() {
        if (root == null) {
            return;
        }
        revision++;
        publisher.submit(new CallTreeSnapshot(snapshot(root), revision, completeness,
                speakingPhase, currentSpeaker, Instant.now(), diagnostics));
    }

    private void diagnose(String code, String message, String artifactId) {
        if (diagnostics.size() >= MAX_DIAGNOSTICS) {
            return;
        }
        CallTreeDiagnostic diagnostic = new CallTreeDiagnostic(code, message, artifactId);
        if (!diagnostics.contains(diagnostic)) {
            diagnostics.add(diagnostic);
        }
    }

    private static CallTreeNode snapshot(MutableNode node) {
        List<ArtifactSnapshot> artifacts = node.artifacts.values().stream()
                .map(value -> new ArtifactSnapshot(value.id, value.parts, value.complete, value.order)).toList();
        List<CallTreeNode> children = node.children.stream().map(CallTreeReducer::snapshot).toList();
        return new CallTreeNode(node.key, node.delegationIntent, node.state, artifacts, children);
    }

    private static BoundedParts boundedParts(List<ProtocolPart> parts, long available) {
        List<PartSnapshot> result = new ArrayList<>();
        long used = 0L;
        boolean truncated = false;
        for (ProtocolPart part : parts) {
            if (part instanceof ProtocolPart.Text text) {
                String value = truncateUtf8(text.text(), available - used);
                if (!value.isEmpty() || text.text().isEmpty()) {
                    result.add(new TextPartSnapshot(value));
                }
                long bytes = utf8Bytes(value);
                used += bytes;
                truncated |= bytes < utf8Bytes(text.text());
            } else if (part instanceof ProtocolPart.Data data) {
                long bytes = estimateBytes(data.data());
                if (bytes <= available - used) {
                    result.add(new DataPartSnapshot(data.data()));
                    used += bytes;
                } else {
                    truncated = true;
                }
            } else {
                // ProtocolPart sealed hierarchy is exhaustive; this branch is unreachable
            }
        }
        return new BoundedParts(List.copyOf(result), used, truncated);
    }

    private static String truncateUtf8(String value, long available) {
        if (value == null || available <= 0) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= available) {
            return value;
        }
        int end = (int) Math.min(available, bytes.length);
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }

    private static long estimateBytes(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof CharSequence chars) {
            return utf8Bytes(chars.toString());
        }
        if (value instanceof Map<?, ?> map) {
            long size = 2L;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                size = saturatedAdd(size, utf8Bytes(String.valueOf(entry.getKey())));
                size = saturatedAdd(size, estimateBytes(entry.getValue()));
            }
            return size;
        }
        if (value instanceof Collection<?> collection) {
            long size = 2L;
            for (Object item : collection) {
                size = saturatedAdd(size, estimateBytes(item));
            }
            return size;
        }
        if (value instanceof Iterable<?> iterable) {
            long size = 2L;
            for (Object item : iterable) {
                size = saturatedAdd(size, estimateBytes(item));
            }
            return size;
        }
        if (value.getClass().isArray()) {
            long size = 2L;
            for (int i = 0; i < Array.getLength(value); i++) {
                size = saturatedAdd(size, estimateBytes(Array.get(value, i)));
            }
            return size;
        }
        return utf8Bytes(String.valueOf(value));
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Optional<String> firstText(List<ProtocolPart> parts) {
        return parts.stream().filter(ProtocolPart.Text.class::isInstance)
                .map(ProtocolPart.Text.class::cast).map(ProtocolPart.Text::text).findFirst();
    }

    private static boolean isTerminal(String state) {
        return "completed".equals(state) || "failed".equals(state) || "canceled".equals(state)
                || "rejected".equals(state);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (orphanEdgeCount > 0 || orphanSignalCount > 0) {
            completeness = Completeness.DEGRADED;
            diagnose(CallTreeDiagnostic.UNRESOLVED_ORPHAN,
                    "call tree closed with unresolved orphan edges or signals", null);
            publish();
        }
        publisher.close();
    }

    private static final class MutableNode {
        private NodeKey key;
        private String delegationIntent;
        private String state;
        private MutableNode parent;
        private final List<MutableNode> children = new ArrayList<>();
        private final Map<String, MutableArtifact> artifacts = new LinkedHashMap<>();

        private MutableNode(NodeKey key) {
            this.key = key;
        }
    }

    private static final class MutableArtifact {
        private final String id;
        private final long order;
        private final List<PartSnapshot> parts = new ArrayList<>();
        private boolean complete;
        private long bytes;

        private MutableArtifact(String id, long order) {
            this.id = id;
            this.order = order;
        }
    }

    private record PendingDelegation(AgentEvent event, ProtocolArtifact artifact, long bytes) {
    }

    private record PendingSignal(AgentEvent event, ProtocolArtifact artifact, long bytes) {
    }

    private record BoundedParts(List<PartSnapshot> parts, long bytes, boolean truncated) {
    }

    private record BufferedArtifact(ProtocolArtifact artifact, long bytes) {
    }
}
