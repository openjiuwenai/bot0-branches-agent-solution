package com.huawei.ascend.client.internal;

import com.huawei.ascend.client.state.spi.ClientStateStore;
import com.huawei.ascend.client.tool.spi.ToolExecutionRecord;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ClientStateStore} 的内存实现（默认）。基于 {@link ConcurrentHashMap} 保证并发下的幂等原子性。
 */
public final class InMemoryStateStore implements ClientStateStore {

    private final ConcurrentHashMap<String, ToolExecutionRecord> records = new ConcurrentHashMap<>();
    private final Set<String> submitted = ConcurrentHashMap.newKeySet();

    @Override
    public Optional<ToolExecutionRecord> findRecord(String toolCallId) {
        return Optional.ofNullable(records.get(toolCallId));
    }

    @Override
    public ToolExecutionRecord saveRecordIfAbsent(String toolCallId, ToolExecutionRecord record) {
        ToolExecutionRecord existing = records.putIfAbsent(toolCallId, record);
        return (existing != null) ? existing : record;
    }

    @Override
    public void markSubmitted(String toolCallId) {
        submitted.add(toolCallId);
    }

    @Override
    public boolean isSubmitted(String toolCallId) {
        return submitted.contains(toolCallId);
    }
}
