/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter;

import com.openjiuwen.core.session.NodeSessionApi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared CustomSchema frames (partial_content / message_end / workflow_end).
 *
 * @since 2026-08-25
 */

public final class StudioStreamFrames {
    /**
     * PARTIAL_CONTENT.
     * @since 0.1.0
     */
    public static final String PARTIAL_CONTENT = "partial_content";
    /**
     * MESSAGE_NODE_END.
     * @since 0.1.0
     */
    public static final String MESSAGE_NODE_END = "message_end";
    /**
     * WORKFLOW_END.
     * @since 0.1.0
     */
    public static final String WORKFLOW_END = "workflow_end";

    private StudioStreamFrames() {}

    /**
     * emitPartialAndMessageEnd.
     *
     * @param session session
     * @param data data payload
     */

    public static void emitPartialAndMessageEnd(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
        return;
    }
        try {
            session.writeCustomStream(frame(PARTIAL_CONTENT, 0, data));
            session.writeCustomStream(frame(MESSAGE_NODE_END, 1, data));
        } catch (RuntimeException ignored) {
            // mock session
        }
    }

    /**
     * emitWorkflowEnd.
     *
     * @param session session
     * @param data data
     */

    public static void emitWorkflowEnd(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
        return;
    }
        try {
            session.writeCustomStream(frame(WORKFLOW_END, 2, data));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private static Map<String, Object> frame(String type, int index, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("index", index);
        m.put("data", data == null ? Map.of() : data);
        return m;
    }
}
