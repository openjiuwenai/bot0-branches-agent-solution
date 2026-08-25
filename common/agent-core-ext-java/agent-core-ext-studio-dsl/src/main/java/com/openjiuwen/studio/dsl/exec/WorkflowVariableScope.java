/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-workflow-execution variable table (FEAT-031 / L2 §3.7 D11).
 * Not a cross-instance global bus; child workflows use an independent scope.
 *
 * @since 2026-08-17
 */
public final class WorkflowVariableScope {
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private boolean closed;

    /**
     * put.
     *
     * @param key key
     * @param value value
     */
    public synchronized void put(String key, Object value) {
        ensureOpen();
        vars.put(key, value);
    }

    /**
     * putAll.
     *
     * @param more more
     */
    public synchronized void putAll(Map<String, Object> more) {
        ensureOpen();
        if (more != null) {
            vars.putAll(more);
        }
    }

    /**
     * get.
     *
     * @param key key
     * @return result
     */
    public synchronized Object get(String key) {
        ensureOpen();
        return vars.get(key);
    }

    /**
     * snapshot.
     *
     * @return result
     */
    public synchronized Map<String, Object> snapshot() {
        ensureOpen();
        return Collections.unmodifiableMap(new LinkedHashMap<>(vars));
    }

    /**
     * Workflow execution ended — variables must not remain readable (L2 §3.7).
     */
    public synchronized void close() {
        vars.clear();
        closed = true;
    }

    /**
     * isClosed.
     *
     * @return result
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WorkflowVariableScope closed after workflow execution ended");
        }
    }
}
