/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.model;

import java.time.Instant;
import java.util.Map;

/**
 * Carries BusResponseProjection data.
 *
 * @since 2026-07-22
 */
public record BusResponseProjection(String eventId, String eventType, String tenantId, String correlationId,
        String taskId, Instant occurredAt, Map<String, Object> data, String traceId, String sourceServiceId,
        String targetServiceId, String routeHandle, String idempotencyKey, String causationMessageId,
        String projectionKind, long revision) {

    /**
     * Creates a new instance.
     *
     * @param eventId
     *            the eventId value
     * @param eventType
     *            the eventType value
     * @param tenantId
     *            the tenantId value
     * @param correlationId
     *            the correlationId value
     * @param taskId
     *            the taskId value
     * @param occurredAt
     *            the occurredAt value
     * @param data
     *            the data value
     */
    public BusResponseProjection(String eventId, String eventType, String tenantId, String correlationId, String taskId,
            Instant occurredAt, Map<String, Object> data) {
        this(eventId, eventType, tenantId, correlationId, taskId, occurredAt, data, null, null, null, null, null, null,
                inferKind(eventType), 0L);
    }

    /**
     * Creates a new instance.
     *
     * @param eventId
     *            the eventId value
     * @param eventType
     *            the eventType value
     * @param tenantId
     *            the tenantId value
     * @param correlationId
     *            the correlationId value
     * @param taskId
     *            the taskId value
     * @param occurredAt
     *            the occurredAt value
     * @param data
     *            the data value
     * @param traceId
     *            the traceId value
     * @param sourceServiceId
     *            the sourceServiceId value
     * @param targetServiceId
     *            the targetServiceId value
     * @param routeHandle
     *            the routeHandle value
     * @param idempotencyKey
     *            the idempotencyKey value
     */
    public BusResponseProjection(String eventId, String eventType, String tenantId, String correlationId, String taskId,
            Instant occurredAt, Map<String, Object> data, String traceId, String sourceServiceId,
            String targetServiceId, String routeHandle, String idempotencyKey) {
        this(eventId, eventType, tenantId, correlationId, taskId, occurredAt, data, traceId, sourceServiceId,
                targetServiceId, routeHandle, idempotencyKey, null, inferKind(eventType),
                number(data == null ? null : data.get("revision")));
    }

    private static String inferKind(String eventType) {
        int index = eventType == null ? -1 : eventType.lastIndexOf('_');
        return index < 0 ? eventType : eventType.substring(index + 1);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
