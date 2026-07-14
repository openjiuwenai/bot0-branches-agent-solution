/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalize an A2A {@link TaskArtifactUpdateEvent} into EDPA {@code {"event":...,"data":{...}}}
 * envelopes so the framework's {@code SseEvent} parser can consume it.
 *
 * <p>Each text part of the artifact becomes one envelope. Two shapes occur in practice:
 * <ul>
 *   <li><b>LLM-produced</b> ({@code llm_output}/{@code llm_usage}/{@code llm_reasoning}):
 *       {@code {"type":"llm_output","index":0,"payload":{...}}} — has {@code type}, no {@code event}.
 *       Lifted to {@code {"event":"llm_output","data":{<whole text object>}}}.</li>
 *   <li><b>versatile-simulated</b>: {@code {"event":"message","data":{...}}} — already EDPA-shaped;
 *       passed through verbatim.</li>
 * </ul>
 * Non-JSON text falls back to {@code {"event":"message","data":{"text":<raw>}}} so content is never lost.
 * Non-text parts ({@link org.a2aproject.sdk.spec.DataPart}, {@link org.a2aproject.sdk.spec.FilePart})
 * are silently skipped — this pipeline only carries text.
 *
 * @since 2026-07-09
 */
public final class A2aArtifactNormalizer {
    private A2aArtifactNormalizer() {
    }

    /**
     * Normalize an A2A artifact-update event into EDPA envelope JSON strings, one per text part.
     *
     * @param mapper the JSON mapper used to parse and re-serialize each text part
     * @param event  the A2A artifact-update event carrying the artifact parts
     * @return one {@code {"event","data"}} JSON string per text part of the artifact — possibly empty
     *         if the artifact has no text parts.
     */
    public static List<String> normalize(ObjectMapper mapper, TaskArtifactUpdateEvent event) {
        Artifact artifact = event.artifact();
        if (artifact == null || artifact.parts() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Part<?> part : artifact.parts()) {
            if (part instanceof TextPart tp && tp.text() != null) {
                out.add(toEnvelope(mapper, tp.text()));
            }
        }
        return out;
    }

    /**
     * Lift the text object into an {@code {"event","data"}} envelope.
     * <ul>
     *   <li>has {@code type} → {@code {"event":<type>,"data":<whole text object>}}</li>
     *   <li>already has {@code event} → pass through verbatim (already EDPA-shaped)</li>
     *   <li>neither → {@code {"event":"message","data":<whole text object>}}</li>
     * </ul>
     * Non-JSON text → {@code {"event":"message","data":{"text":<raw>}}}.
     *
     * @param mapper the JSON mapper used to parse and re-serialize the text
     * @param text   the raw text of a single artifact text part
     * @return the {@code {"event","data"}} envelope JSON string
     */
    private static String toEnvelope(ObjectMapper mapper, String text) {
        try {
            Object parsed = mapper.readValue(text, Object.class);
            if (!(parsed instanceof Map<?, ?>)) {
                return fallback(mapper, text);
            }
            Map<String, Object> obj = asMap(parsed);
            Object type = obj.get("type");
            if (type != null) {
                return envelope(mapper, String.valueOf(type), obj);
            }
            if (obj.get("event") != null) {
                return mapper.writeValueAsString(obj);
            }
            return envelope(mapper, "message", obj);
        } catch (JsonProcessingException ignored) {
            return fallback(mapper, text);
        }
    }

    private static String envelope(ObjectMapper mapper, String event, Object data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("event", event);
        out.put("data", data);
        try {
            return mapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            return "{\"event\":\"" + event + "\",\"data\":{}}";
        }
    }

    private static String fallback(ObjectMapper mapper, String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", text);
        return envelope(mapper, "message", data);
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        return Map.of();
    }
}
