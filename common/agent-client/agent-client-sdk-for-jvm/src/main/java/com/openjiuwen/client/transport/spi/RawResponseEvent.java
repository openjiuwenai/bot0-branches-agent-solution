/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.spi;

import com.openjiuwen.client.api.EndpointType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * A logical wire response unit observed before SDK projection.
 * For SSE, {@code body} is the complete logical event block reconstructed with LF line endings
 * (including {@code id:}, {@code event:}, and {@code data:} lines). For unary calls it is the
 * complete response body. This is best-effort data and may be dropped when the configured queue is full;
 * {@code droppedBefore} marks observations dropped before this event was accepted.
 */
public record RawResponseEvent(
        String invocationRef,
        String conversationId,
        String taskRef,
        EndpointType endpointType,
        Source source,
        int httpStatus,
        Map<String, List<String>> headers,
        String body,
        String eventId,
        boolean replayed,
        Instant receivedAt,
        long droppedBefore) {
    /**
     * Backward-compatible constructor for events created before drop markers were added.
     *
     * @param invocationRef invocation ref
     * @param conversationId conversation id
     * @param taskRef task ref
     * @param endpointType endpoint type
     * @param source source
     * @param httpStatus http status
     * @param headers headers
     * @param body body
     * @param eventId event id
     * @param replayed replayed flag
     * @param receivedAt received time
     */
    public RawResponseEvent(String invocationRef, String conversationId, String taskRef,
            EndpointType endpointType, Source source, int httpStatus,
            Map<String, List<String>> headers, String body, String eventId,
            boolean replayed, Instant receivedAt) {
        this(invocationRef, conversationId, taskRef, endpointType, source, httpStatus,
                headers, body, eventId, replayed, receivedAt, 0L);
    }

    public RawResponseEvent {
        if (headers == null || headers.isEmpty()) {
            headers = Map.of();
        } else {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            headers.forEach((key, value) -> copy.put(key,
                    value == null ? List.of() : List.copyOf(value)));
            headers = Map.copyOf(copy);
        }
        body = body == null ? "" : body;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        if (droppedBefore < 0) {
            throw new IllegalArgumentException("droppedBefore must not be negative");
        }
    }

    /**
     * Returns a copy carrying the number of observations dropped before this event.
     *
     * @return copy with drop count
     */
    public RawResponseEvent withDroppedBefore(long count) {
        return new RawResponseEvent(invocationRef, conversationId, taskRef, endpointType, source,
                httpStatus, headers, body, eventId, replayed, receivedAt, count);
    }

    /**
     * Origin of the observed response unit.
     *
     * @since 2026-07-27
     */
    public enum Source {
        CREATE_STREAM,
        CREATE_UNARY,
        RESUME_STREAM,
        RESUME_UNARY,
        SUBSCRIBE,
        GET_TASK
    }
}
