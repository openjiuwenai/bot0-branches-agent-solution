/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowend;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Sync port of Python End {@code _mix_coordinate} (batch invoke/stream vs stream collect/transform).
 *
 * @since 2026-08-26
 */
public final class FlowEndMixCoordinator {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private final Map<String, Map<String, Object>> mixData = new LinkedHashMap<>();
    private int pushCount;
    private boolean renderComplete;
    private boolean mixEnabled;
    private boolean expectMix = true;

    public void setExpectMix(boolean expectMix) {
        this.expectMix = expectMix;
    }

    public void setMix() {
        if (!expectMix) {
            return;
        }
        mixEnabled = true;
    }

    public boolean isMix() {
        return mixEnabled;
    }

    public void reset() {
        lock.lock();
        try {
            mixData.clear();
            pushCount = 0;
            renderComplete = false;
            mixEnabled = false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param key {@code batch} or {@code stream}
     * @return merged inputs, merged outputs, isRenderer
     */
    public MixResult coordinate(String key, Map<String, Object> inputs, Map<String, Object> outputs) {
        if (renderComplete) {
            return new MixResult(inputs, outputs, false);
        }
        if (!mixEnabled) {
            return new MixResult(inputs, outputs, true);
        }

        lock.lock();
        try {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("inputs", inputs == null ? Map.of() : new LinkedHashMap<>(inputs));
            slot.put("outputs", outputs == null ? Map.of() : new LinkedHashMap<>(outputs));
            mixData.put(key, slot);
            pushCount++;
            condition.signalAll();

            if ("batch".equals(key)) {
                // Wait for stream path to finish rendering (Python timeout 3s)
                if (!renderComplete) {
                    try {
                        condition.await(3, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (renderComplete) {
                    return new MixResult(inputs, outputs, false);
                }
                // Timeout fallback: batch renders alone
                MapPair merged = merge();
                renderComplete = true;
                mixEnabled = false;
                condition.signalAll();
                return new MixResult(merged.inputs, merged.outputs, true);
            }

            // stream path always renders (Python)
            MapPair merged = merge();
            renderComplete = true;
            mixEnabled = false;
            condition.signalAll();
            return new MixResult(merged.inputs, merged.outputs, true);
        } finally {
            lock.unlock();
        }
    }

    public void markRenderComplete() {
        lock.lock();
        try {
            renderComplete = true;
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private MapPair merge() {
        Map<String, Object> streamIn = mapOf(mixData.get("stream"), "inputs");
        Map<String, Object> batchIn = mapOf(mixData.get("batch"), "inputs");
        Map<String, Object> streamOut = mapOf(mixData.get("stream"), "outputs");
        Map<String, Object> batchOut = mapOf(mixData.get("batch"), "outputs");
        Map<String, Object> in = new LinkedHashMap<>(streamIn);
        in.putAll(batchIn);
        Map<String, Object> out = new LinkedHashMap<>(streamOut);
        out.putAll(batchOut);
        return new MapPair(in, out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> slot, String key) {
        if (slot == null) {
            return Map.of();
        }
        Object v = slot.get(key);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((a, b) -> copy.put(String.valueOf(a), b));
            return copy;
        }
        return Map.of();
    }

    public record MixResult(Map<String, Object> inputs, Map<String, Object> outputs, boolean isRenderer) {}

    private record MapPair(Map<String, Object> inputs, Map<String, Object> outputs) {}
}
