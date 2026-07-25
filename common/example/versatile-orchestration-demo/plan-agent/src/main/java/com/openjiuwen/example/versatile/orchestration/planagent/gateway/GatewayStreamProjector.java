/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Projects one A2A streaming event to its {@code custom_rsp_data} payload by discriminating on
 * the artifact text-part's top-level {@code type} field.
 *
 * <ul>
 *   <li>{@code type=llm_reasoning} → {@code think_chunk} envelope
 *       ({@code {event,content,createdTime,latency,plugin,data}}).</li>
 *   <li>{@code type=llm_usage} → ignorable empty {@code {}}.</li>
 *   <li>any other {@code type}, or no {@code type} → passthrough: the parsed JSON object verbatim.</li>
 *   <li>non-JSON text → {@code {"text":<raw>}} (content is never lost).</li>
 *   <li>{@link TaskStatusUpdateEvent} / artifact with no text part → ignorable empty {@code {}}.</li>
 * </ul>
 *
 * <p>The {@code createdTime} default is taken from an injectable {@link LongSupplier} clock so tests
 * are deterministic; production wires {@code System::currentTimeMillis}. Only the first text part of
 * each artifact is projected (the custom-rest framework emits exactly one client frame per A2A event,
 * so one event maps to one {@code custom_rsp_data}).
 *
 * @since 0.2.0
 */
public final class GatewayStreamProjector {
    private final ObjectMapper objectMapper;
    private final LongSupplier clock;

    public GatewayStreamProjector(ObjectMapper objectMapper, LongSupplier clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Compute the {@code custom_rsp_data} payload for one streaming event.
     *
     * @param event the A2A streaming event
     * @return the payload object; never null — an empty map for ignorable events
     */
    public Object customRspDataFor(StreamingEventKind event) {
        if (!(event instanceof TaskArtifactUpdateEvent artifactEvent)) {
            return Map.of();
        }
        Artifact artifact = artifactEvent.artifact();
        if (artifact == null || artifact.parts() == null || artifact.parts().isEmpty()) {
            return Map.of();
        }
        for (Part<?> part : artifact.parts()) {
            if (part instanceof TextPart tp && tp.text() != null) {
                return payloadFor(tp.text());
            }
        }
        return Map.of();
    }

    private Object payloadFor(String text) {
        Object parsed;
        try {
            parsed = objectMapper.readValue(text, Object.class);
        } catch (JsonProcessingException ignored) {
            return textFallback(text);
        }
        if (!(parsed instanceof Map<?, ?>)) {
            return textFallback(text);
        }
        Map<String, Object> obj = toMap(parsed);
        Object type = obj.get("type");
        if ("llm_reasoning".equals(type)) {
            return thinkChunk(obj);
        }
        if ("llm_usage".equals(type)) {
            return Map.of();
        }
        return obj;
    }

    private Map<String, Object> thinkChunk(Map<String, Object> obj) {
        Object payload = obj.getOrDefault("payload", Map.of());
        Map<String, Object> payloadMap = payload instanceof Map<?, ?> ? toMap(payload) : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", "think_chunk");
        out.put("content", payloadMap.getOrDefault("content", ""));
        out.put("createdTime", payloadMap.containsKey("createdTime") ? payloadMap.get("createdTime")
                : clock.getAsLong());
        out.put("latency", payloadMap.getOrDefault("latency", ""));
        out.put("plugin", payloadMap.getOrDefault("plugin", ""));
        out.put("data", payloadMap.getOrDefault("data", Map.of()));
        return out;
    }

    private static Map<String, Object> textFallback(String text) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("text", text);
        return fallback;
    }

    private static Map<String, Object> toMap(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
