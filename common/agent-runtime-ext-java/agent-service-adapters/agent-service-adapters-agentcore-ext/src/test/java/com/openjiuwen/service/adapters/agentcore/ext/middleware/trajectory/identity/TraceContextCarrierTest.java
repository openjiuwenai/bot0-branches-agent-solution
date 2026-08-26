/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

/**
 * TraceContextCarrier 的单元测试：直查/副索引/右锚定/最长前缀反解、currentRunId 回填、
 * 惰性 TTL 清理与会话重置清除。
 */
class TraceContextCarrierTest {
    private static final String CONV = "11111111-2222-3333-4444-555555555555";
    private static final String BATCH = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final TraceContextCarrier carrier = TraceContextCarrier.create(86400L);

    @Test
    void findsByExactConversationId() {
        carrier.put(CONV, entry("trace-1"));
        Optional<TraceContextCarrier.Entry> found = carrier.find(CONV);
        assertThat(found).isPresent();
        assertThat(found.get().getTraceId()).isEqualTo("trace-1");
    }

    @Test
    void findsViaSecondaryIndex() {
        carrier.put(CONV, entry("trace-1"));
        carrier.putSecondary(CONV + "_call_1", CONV);
        assertThat(carrier.find(CONV + "_call_1")).isPresent();
    }

    @Test
    void rightAnchorResolvesCombinedBatchContextId() {
        // 组合形态 {conversationId}_{batchId}_{toolCallId}：从右起第一个 UUID 段是 batchId
        carrier.put(CONV, entry("trace-anchor"));
        String combined = CONV + "_" + BATCH + "_call_abc123";
        Optional<TraceContextCarrier.Entry> found = carrier.find(combined);
        assertThat(found).isPresent();
        assertThat(found.get().getTraceId()).isEqualTo("trace-anchor");
    }

    @Test
    void fallsBackToLongestPrefixWhenToolCallIdLooksLikeUuid() {
        carrier.put(CONV, entry("trace-prefix"));
        // toolCallId 也是 UUID 形态时右锚会错锚，走最长前缀兜底
        String combined = CONV + "_x_" + BATCH;
        Optional<TraceContextCarrier.Entry> found = carrier.find(combined);
        assertThat(found).isPresent();
        assertThat(found.get().getTraceId()).isEqualTo("trace-prefix");
    }

    @Test
    void emptyForUnknownOrBlank() {
        assertThat(carrier.find("unknown")).isEmpty();
        assertThat(carrier.find(null)).isEmpty();
        assertThat(carrier.find("  ")).isEmpty();
    }

    @Test
    void backfillsCurrentRunId() {
        carrier.put(CONV, entry("trace-1"));
        assertThat(carrier.find(CONV).get().getCurrentRunId()).isEmpty();
        carrier.updateCurrentRunId(CONV, "task-9#2");
        assertThat(carrier.find(CONV).get().getCurrentRunId()).contains("task-9#2");
    }

    @Test
    void removeClearsEntryAndSecondaryKeys() {
        carrier.put(CONV, entry("trace-1"));
        carrier.putSecondary(CONV + "_call_1", CONV);
        carrier.remove(CONV);
        assertThat(carrier.find(CONV)).isEmpty();
        assertThat(carrier.find(CONV + "_call_1")).isEmpty();
    }

    @Test
    void lazyTtlEvictsExpiredEntries() {
        TraceContextCarrier shortLived = TraceContextCarrier.create(1L);
        TraceContextCarrier.Entry stale = new TraceContextCarrier.Entry(
                "trace-old", false, "a2a", "t", Instant.now().minusSeconds(120));
        shortLived.put(CONV, stale);
        assertThat(shortLived.find(CONV)).isEmpty();
    }

    private static TraceContextCarrier.Entry entry(String traceId) {
        return new TraceContextCarrier.Entry(traceId, false, "a2a", "tenant-1", Instant.now());
    }
}
