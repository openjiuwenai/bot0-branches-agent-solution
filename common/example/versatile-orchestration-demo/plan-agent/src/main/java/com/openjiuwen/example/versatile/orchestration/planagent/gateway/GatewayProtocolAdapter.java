/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Custom REST protocol adapter that speaks the platform gateway envelope on the wire and delegates
 * to the adapter's existing A2A pipeline ({@code RequestHandler → A2AAgentExecutor → VersatileAgentHandler}).
 *
 * <p><b>Request ({@link #toA2ARequest}):</b> gateway envelope
 * ({@code role_name/input{query}/agent_id/conversation_id/stream/custom_data}) → A2A
 * {@link MessageSendParams}; {@code conversation_id} (body, path-variable fallback) drives the
 * conversation lock + resume; {@code stream} defaults to true. HTTP query params
 * ({@code ?type=...&workspace_id=...}) are flattened to a scalar-per-key map and carried in
 * metadata under {@code "query"} (e.g. {@code {"type":"controller","workspace_id":"12"}}).
 *
 * <p><b>Streaming response ({@link #fromA2AStreamEvent}):</b> one gateway envelope per A2A event,
 * returned as {@code SseEvent(null, compactJsonString)}. The null event name makes Spring emit a
 * byte-exact {@code data:<json>\n\n} frame; the JSON is compact and UTF-8 (non-ASCII raw) so each
 * event is a single data line. {@code custom_rsp_data} comes from {@link GatewayStreamProjector}.
 *
 * <p><b>Blocking response / errors ({@link #fromA2ATask} / {@link #fromError}):</b> the envelope
 * is returned as a {@link Map} so Spring sets {@code application/json}. Streaming errors
 * ({@link #fromStreamError}) use the byte-exact string form like stream events.
 *
 * @since 0.2.0
 */
public final class GatewayProtocolAdapter implements CustomRestProtocolAdapter {
    private final ObjectMapper objectMapper;
    private final GatewayEnvelope envelope;
    private final GatewayStreamProjector projector;

    public GatewayProtocolAdapter(ObjectMapper objectMapper) {
        this(objectMapper, System::currentTimeMillis);
    }

    /**
     * Test-friendly constructor with an injectable clock for deterministic {@code createdTime}.
     *
     * @param clock clock
     * @param objectMapper objectMapper
     */
    GatewayProtocolAdapter(ObjectMapper objectMapper, LongSupplier clock) {
        this.objectMapper = objectMapper;
        this.envelope = new GatewayEnvelope(objectMapper);
        this.projector = new GatewayStreamProjector(objectMapper, clock);
    }

    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        GatewayRequest request = objectMapper.convertValue(context.body(), GatewayRequest.class);
        String query = (request.input() != null && request.input().query() != null)
                ? request.input().query() : "";

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart(query))
                .messageId(UUID.randomUUID().toString())
                .build();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("body", context.body());
        metadata.put("agent_id", agentId(context, request));
        metadata.put("role_name", request.roleName());
        metadata.put("role_id", request.roleId());
        metadata.put("timeout", request.timeout());
        metadata.put("custom_data", request.customData());
        metadata.put("query", flattenQueryParams(context.queryParams()));
        MessageSendParams params = MessageSendParams.builder().message(message).metadata(metadata).build();

        boolean stream = streamFlag(context.body());
        String conversationId = conversationId(context, request);
        return new A2ASendCommand(params, conversationId, stream);
    }

    @Override
    public Object fromA2ATask(Task task, Context context) {
        return envelope.envelope(true, agentId(context), conversationId(context), "",
                firstArtifactText(task));
    }

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        Object customRspData = projector.customRspDataFor(event);
        String json = envelope.toJson(envelope.envelope(true, agentId(context), conversationId(context), "",
                customRspData));
        return new SseEvent(null, json);
    }

    @Override
    public Object fromError(CustomRestError error, Context context) {
        return envelope.envelope(false, agentId(context), conversationId(context), error.message(),
                errorData(error.message()));
    }

    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        String json = envelope.toJson(envelope.envelope(false, agentId(context), conversationId(context),
                error.message(), errorData(error.message())));
        return new SseEvent(null, json);
    }

    private static Map<String, Object> errorData(String message) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("message", message == null ? "" : message);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("event", "error");
        data.put("data", inner);
        return data;
    }

    private static String firstArtifactText(Task task) {
        if (task == null || task.artifacts() == null) {
            return "";
        }
        for (Artifact artifact : task.artifacts()) {
            if (artifact == null || artifact.parts() == null) {
                continue;
            }
            for (Part<?> part : artifact.parts()) {
                if (part instanceof TextPart tp && tp.text() != null) {
                    return tp.text();
                }
            }
        }
        return "";
    }

    private static String agentId(Context context, GatewayRequest request) {
        return stringValue(request.agentId(), context.pathVariables().get("agent_id"));
    }

    private static String agentId(Context context) {
        Object fromBody = context.body().get("agent_id");
        return stringValue(fromBody == null ? null : String.valueOf(fromBody),
                context.pathVariables().get("agent_id"));
    }

    private static String conversationId(Context context, GatewayRequest request) {
        return stringValue(request.conversationId(), context.pathVariables().get("conversation_id"));
    }

    private static String conversationId(Context context) {
        Object fromBody = context.body().get("conversation_id");
        return stringValue(fromBody == null ? null : String.valueOf(fromBody),
                context.pathVariables().get("conversation_id"));
    }

    private static boolean streamFlag(Map<String, Object> body) {
        Object stream = body.get("stream");
        if (stream == null) {
            return true;
        }
        if (stream instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(stream));
    }

    /**
     * Flattens the multi-valued HTTP query params to a scalar-per-key map (first value wins).
     *
     * @param queryParams queryParams
     * @return Map<String,String>
     */
    private static Map<String, String> flattenQueryParams(Map<String, List<String>> queryParams) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (queryParams == null) {
            return flat;
        }
        queryParams.forEach((key, values) ->
                flat.put(key, values == null || values.isEmpty() ? "" : values.get(0)));
        return flat;
    }

    private static String stringValue(Object preferred, String fallback) {
        if (preferred != null && !String.valueOf(preferred).isBlank()) {
            return String.valueOf(preferred);
        }
        return fallback != null ? fallback : "";
    }
}
