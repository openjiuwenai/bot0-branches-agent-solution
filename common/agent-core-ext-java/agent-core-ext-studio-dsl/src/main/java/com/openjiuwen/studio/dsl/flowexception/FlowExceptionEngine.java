/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowexception;

import com.openjiuwen.core.session.NodeSessionApi;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python {@code ExceptionInfo} / {@code flow_exception.py}.
 *
 * @since 2026-08-26
 */

public final class FlowExceptionEngine {
    /**
     * USER_FIELDS.
     * @since 0.1.0
     */
    public static final String USER_FIELDS = "userFields";
    /**
     * WORKFLOW_EXCEPTION.
     * @since 0.1.0
     */
    public static final String WORKFLOW_EXCEPTION = "workflow_exception";
    /**
     * ABORT_FLAG.
     * @since 0.1.0
     */
    public static final String ABORT_FLAG = "__abort__";
    /**
     * EXCEPTION_NODE_ID_KEY.
     * @since 0.1.0
     */
    public static final String EXCEPTION_NODE_ID_KEY = "jiuwen_exception_node_id";

    private final FlowExceptionConfig config;

    /**
     * FlowExceptionEngine.
     * @param config config
     * @since 0.1.0
     */
    public FlowExceptionEngine(FlowExceptionConfig config) {
        this.config = config;
    }

    /**
     * Batch invoke — always aborts. First abort writes {@code workflow_exception}; concurrent aborts
     * skip the frame (Python {@code __abort__} mutex).
     *
     * @param inputs inputs
     * @param session session
     */

    public void invoke(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> userFields = extractUserFields(inputs);
        if (session != null && alreadyAborted(session)) {
            throw abort(userFields);
        }
        markAborted(session);
        Map<String, Object> frameData = frameData(userFields);
        trace(session, frameData);
        writeExceptionFrame(session, frameData);
        throw abort(userFields);
    }

    /**
     * Stream — write frame, yield {@code {}}, then abort (no {@code __abort__} mutex on stream path).
     *
     * @param inputs inputs
     * @param session session
     * @return iterator that yields one empty map then throws
     */

    public Iterator<Object> stream(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> userFields = extractUserFields(inputs);
        Map<String, Object> frameData = frameData(userFields);
        trace(session, frameData);
        writeExceptionFrame(session, frameData);
        WorkflowAbortException abort = abort(userFields);
        return new Iterator<>() {
            private boolean yielded;

            /**
             * hasNext.
             *
             * @return result
             * @since 0.1.0
             */

            @Override
            public boolean hasNext() {
                if (!yielded) {
                return true;
            }
                throw abort;
            }

            /**
             * next.
             *
             * @return result
             * @since 0.1.0
             */

            @Override
            public Object next() {
                if (!yielded) {
                    yielded = true;
                    return Map.of();
                }
                throw abort;
            }
        };
    }

    /**
     * Python: {@code inputs.get(USER_FIELDS) or {}} — flat inputs without {@code userFields} → empty.
     *
     * @param inputs inputs
     * @return user fields
     */

    public static Map<String, Object> extractUserFields(Map<String, Object> inputs) {
        if (inputs == null) {
            return Map.of();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private Map<String, Object> frameData(Map<String, Object> userFields) {
        Map<String, Object> data = new LinkedHashMap<>(userFields);
        data.put(EXCEPTION_NODE_ID_KEY, config.nodeId());
        return data;
    }

    private WorkflowAbortException abort(Map<String, Object> userFields) {
        return new WorkflowAbortException(
                userFields, config.nodeId(), config.nodeName(), config.nodeType());
    }

    private static boolean alreadyAborted(NodeSessionApi session) {
        try {
            return session.getGlobalState(ABORT_FLAG) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void markAborted(NodeSessionApi session) {
        if (session == null) {
        return;
    }
        try {
            session.updateGlobalState(Map.of(ABORT_FLAG, true));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private static void writeExceptionFrame(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
        return;
    }
        try {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", WORKFLOW_EXCEPTION);
            frame.put("index", 0);
            frame.put("data", data);
            session.writeCustomStream(frame);
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private static void trace(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
        return;
    }
        try {
            session.trace(data);
        } catch (RuntimeException ignored) {
            // mock / API optional
        }
    }
}
