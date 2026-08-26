/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求级/会话级 trace 上下文载体（第二批标识归一）。按 conversationId 主索引持有每个会话的
 * trace 标识与执行上下文，供标识 filter 写入、执行树装饰器回填 run_id、出站传播读取。
 *
 * <p>与第一批 {@code HttpContextBridge} 的请求级生命周期不同：条目按会话保留、不随请求结束销毁
 * （服务多轮续接的 trace_id 复用），仅做惰性 TTL 清理（默认 24h，见
 * {@code openjiuwen.service.trajectory.link.carrier-ttl-seconds}）与会话重置时的显式清除。
 *
 * <p>组合 contextId 反解（多成员批形如 `{conversationId}_{batchId}_{toolCallId}`）：
 * ⓪ 先按完整 contextId 直查主索引；① 从右锚定——取从右起第一个 36 位 UUID 形态段为 batchId，
 * 其前缀即 conversationId（conversationId 常也是 UUID，从左锚会错锚；toolCallId 为
 * {@code call_xxx} 形态非 UUID）；② 兜底按已注册 conversationId 集合做最长前缀匹配。
 *
 * @since 2026-08-26
 */
public final class TraceContextCarrier {
    private static final int UUID_SEGMENT_LENGTH = 36;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> secondaryIndex = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    private TraceContextCarrier(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Creates a carrier with the given entry TTL.
     *
     * @param ttlSeconds lazy-eviction TTL for entries
     * @return the carrier
     */
    public static TraceContextCarrier create(long ttlSeconds) {
        return new TraceContextCarrier(ttlSeconds);
    }

    /**
     * Stores or replaces the entry for a conversation.
     *
     * @param conversationId conversation key
     * @param entry        trace context entry
     */
    public void put(String conversationId, Entry entry) {
        if (conversationId != null && entry != null) {
            entries.put(conversationId, entry);
        }
    }

    /**
     * Registers a secondary key ({@code conversationId_toolCallId}) pointing at the conversation.
     *
     * @param combinedKey    combined secondary key
     * @param conversationId owning conversation id
     */
    public void putSecondary(String combinedKey, String conversationId) {
        if (combinedKey != null && conversationId != null) {
            secondaryIndex.put(combinedKey, conversationId);
        }
    }

    /**
     * Finds the entry for a (possibly combined) context id via {@link #resolveKey}.
     *
     * @param contextId bare or combined context id
     * @return the entry, or empty
     */
    public Optional<Entry> find(String contextId) {
        evictExpired();
        // 滑动 TTL：命中即刷新 receivedAt——持续活跃的会话不因建档时刻超期而 trace 分叉
        return resolveKey(contextId).map(entries::get).map(entry -> {
            entry.touch();
            return entry;
        });
    }

    /**
     * Resolves the canonical carrier key for a (possibly combined) context id: exact
     * match first, then secondary index, then right-anchored parse, finally longest-prefix
     * match. Lets callers write back under the canonical key instead of aliasing the
     * combined form into the main index.
     *
     * @param contextId bare or combined context id
     * @return the canonical key, or empty when nothing matches
     */
    public Optional<String> resolveKey(String contextId) {
        if (contextId == null || contextId.isBlank()) {
            return Optional.empty();
        }
        if (entries.containsKey(contextId)) {
            return Optional.of(contextId);
        }
        String viaSecondary = secondaryIndex.get(contextId);
        if (viaSecondary != null && entries.containsKey(viaSecondary)) {
            return Optional.of(viaSecondary);
        }
        Optional<String> anchored = rightAnchorConversationId(contextId);
        if (anchored.isPresent() && entries.containsKey(anchored.get())) {
            return anchored;
        }
        return longestPrefixKey(contextId);
    }

    /**
     * Stores the entry only when the key has none (degraded writes must not overwrite
     * a concurrently written good entry).
     *
     * @param conversationId conversation key
     * @param entry          trace context entry
     */
    public void putIfAbsent(String conversationId, Entry entry) {
        if (conversationId != null && entry != null) {
            entries.putIfAbsent(conversationId, entry);
        }
    }

    /**
     * Backfills the current run id ({taskId}#{roundSeq}) at round open.
     *
     * @param conversationId conversation key
     * @param runId        current run id
     */
    public void updateCurrentRunId(String conversationId, String runId) {
        if (conversationId == null || runId == null) {
            return;
        }
        Entry entry = entries.get(conversationId);
        if (entry != null) {
            entry.currentRunId = runId;
        }
    }

    /**
     * Clears the entry (and its secondary keys) for a conversation reset.
     *
     * @param conversationId conversation key
     */
    public void remove(String conversationId) {
        if (conversationId == null) {
            return;
        }
        entries.remove(conversationId);
        secondaryIndex.values().removeIf(conversationId::equals);
    }

    private Optional<String> longestPrefixKey(String contextId) {
        String best = null;
        int bestLen = 0;
        for (String key : entries.keySet()) {
            if (key.length() > bestLen && contextId.startsWith(key + "_")) {
                best = key;
                bestLen = key.length();
            }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<String> rightAnchorConversationId(String contextId) {
        String[] segments = contextId.split("_");
        for (int i = segments.length - 1; i > 0; i--) {
            if (isUuidSegment(segments[i])) {
                String prefix = String.join("_", Arrays.copyOf(segments, i));
                return prefix.isBlank() ? Optional.empty() : Optional.of(prefix);
            }
        }
        return Optional.empty();
    }

    private static boolean isUuidSegment(String segment) {
        if (segment.length() != UUID_SEGMENT_LENGTH) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean dashPosition = i == 8 || i == 13 || i == 18 || i == 23;
            if (dashPosition != (c == '-') && (dashPosition || !isHex(c))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private void evictExpired() {
        long cutoff = Instant.now().getEpochSecond() - ttlSeconds;
        entries.values().removeIf(entry -> entry.receivedAt.getEpochSecond() < cutoff);
    }

    /**
     * Trace context entry for one conversation. {@code currentRunId} is backfilled at
     * round open by the run-tree decorator; {@code parentRunId} is set when the ingress
     * carried a parent run id in metadata.
     *
     * @since 2026-08-26
     */
    public static final class Entry {
        private final String traceId;
        private final boolean degraded;
        private final String ingressChannel;
        private final String tenantId;
        private volatile Instant receivedAt;
        private volatile String parentRunId;
        private volatile String currentRunId;

        /**
         * Creates an entry.
         *
         * @param traceId        W3C trace id (32-char lowercase hex)
         * @param degraded       whether the trace id was generated as a fallback
         * @param ingressChannel ingress channel (a2a / rest / bus)
         * @param tenantId       tenant id
         * @param receivedAt     when the entry was recorded
         */
        public Entry(String traceId, boolean degraded, String ingressChannel, String tenantId, Instant receivedAt) {
            this.traceId = traceId;
            this.degraded = degraded;
            this.ingressChannel = ingressChannel;
            this.tenantId = tenantId;
            this.receivedAt = receivedAt;
        }

        /**
         * Returns the trace id.
         *
         * @return trace id
         */
        public String getTraceId() {
            return traceId;
        }

        /**
         * Returns whether the trace id was fallback-generated.
         *
         * @return degraded flag
         */
        public boolean isDegraded() {
            return degraded;
        }

        /**
         * Returns the ingress channel.
         *
         * @return ingress channel
         */
        public String getIngressChannel() {
            return ingressChannel;
        }

        /**
         * Returns the tenant id.
         *
         * @return tenant id
         */
        public String getTenantId() {
            return tenantId;
        }

        /**
         * Returns when the entry was recorded (last activity after sliding-TTL refresh).
         *
         * @return received-at instant
         */
        public Instant getReceivedAt() {
            return receivedAt;
        }

        /**
         * Refreshes the last-activity timestamp (sliding TTL).
         */
        public void touch() {
            receivedAt = Instant.now();
        }

        /**
         * Returns the parent run id, when the ingress carried one.
         *
         * @return parent run id
         */
        public Optional<String> getParentRunId() {
            return Optional.ofNullable(parentRunId);
        }

        /**
         * Sets the parent run id.
         *
         * @param parentRunId parent run id
         */
        public void setParentRunId(String parentRunId) {
            this.parentRunId = parentRunId;
        }

        /**
         * Returns the current run id (backfilled at round open).
         *
         * @return current run id
         */
        public Optional<String> getCurrentRunId() {
            return Optional.ofNullable(currentRunId);
        }

        /**
         * Sets the current run id (used when an entry is replaced by a fresher one
         * that should inherit the round marker).
         *
         * @param currentRunId current run id
         */
        public void setCurrentRunId(String currentRunId) {
            this.currentRunId = currentRunId;
        }
    }
}
