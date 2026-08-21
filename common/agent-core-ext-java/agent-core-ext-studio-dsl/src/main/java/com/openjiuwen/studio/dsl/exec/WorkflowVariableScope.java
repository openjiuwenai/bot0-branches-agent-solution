package com.openjiuwen.studio.dsl.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-workflow-execution variable table (FEAT-031 / L2 §3.7 D11).
 * Not a cross-instance global bus; child workflows use an independent scope.
 */
public final class WorkflowVariableScope {
    private final Map<String, Object> vars = new LinkedHashMap<>();
    private boolean closed;

    public synchronized void put(String key, Object value) {
        ensureOpen();
        vars.put(key, value);
    }

    public synchronized void putAll(Map<String, Object> more) {
        ensureOpen();
        if (more != null) {
            vars.putAll(more);
        }
    }

    public synchronized Object get(String key) {
        ensureOpen();
        return vars.get(key);
    }

    public synchronized Map<String, Object> snapshot() {
        ensureOpen();
        return Collections.unmodifiableMap(new LinkedHashMap<>(vars));
    }

    /** Workflow execution ended — variables must not remain readable (L2 §3.7). */
    public synchronized void close() {
        vars.clear();
        closed = true;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("WorkflowVariableScope closed after workflow execution ended");
        }
    }
}
