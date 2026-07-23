/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a host-specific REST protocol to and from the runtime A2A contract.
 *
 * @since 0.1.0
 */
public interface CustomRestProtocolAdapter {
    /**
     * Converts the host request into an A2A send command.
     *
     * @param context immutable host request context
     * @return A2A send command
     */
    A2ASendCommand toA2ARequest(Context context);

    /**
     * Projects an A2A Task to the customer response. Implementations must not expose the
     * framework-generated internal context id.
     *
     * @param task A2A task returned by the runtime
     * @param context immutable host request context
     * @return host-specific response
     */
    Object fromA2ATask(Task task, Context context);

    /**
     * Projects a streaming event to the customer response. Implementations must not expose the
     * framework-generated internal context id.
     *
     * @param event A2A streaming event
     * @param context immutable host request context
     * @return host-specific SSE event
     */
    SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context);

    /**
     * Projects a request failure to the host response format.
     *
     * @param error stable Custom REST error
     * @param context immutable host request context
     * @return host-specific error response
     */
    Object fromError(CustomRestError error, Context context);

    /**
     * Projects a streaming failure to the host SSE format.
     *
     * @param error stable Custom REST error
     * @param context immutable host request context
     * @return host-specific SSE error event
     */
    SseEvent fromStreamError(CustomRestError error, Context context);

    record A2ASendCommand(MessageSendParams params, String conversationId, boolean stream) {
    }

    record SseEvent(String event, Object data) {
    }

    record Context(
            Map<String, List<String>> headers,
            Map<String, String> pathVariables,
            Map<String, List<String>> queryParams,
            Map<String, Object> body) {
        public Context {
            headers = immutableMultiMap(headers);
            pathVariables = pathVariables == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(pathVariables));
            queryParams = immutableMultiMap(queryParams);
            body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
        }

        private static Map<String, List<String>> immutableMultiMap(Map<String, List<String>> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, List<String>> copy = new LinkedHashMap<>();
            source.forEach((key, values) -> copy.put(key, values == null ? List.of() : List.copyOf(values)));
            return Collections.unmodifiableMap(copy);
        }
    }

    record CustomRestError(int httpStatus, String code, String message) {
    }
}
