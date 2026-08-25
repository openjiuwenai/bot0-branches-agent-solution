/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import com.openjiuwen.studio.dsl.adapter.PassthroughStudioNode;
import com.openjiuwen.studio.dsl.model.MediaPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Attach multimodal parts for model/plugin consumption (FEAT MUST).
 *
 * @since 2026-08-17
 */
public final class MediaSupport {
    private MediaSupport() {}
    /**
     * mediaOf.
     * @param inputs inputs
     */
    public static List<MediaPart> mediaOf(Map<String, Object> inputs) {
        return PassthroughStudioNode.extractMedia(inputs);
    }

    /**
     * Inject media into userFields so downstream model/plugin can read.
     */
    public static Map<String, Object> withConsumableMedia(Map<String, Object> userFields, List<MediaPart> media) {
        Map<String, Object> uf = new LinkedHashMap<>(userFields == null ? Map.of() : userFields);
        if (media != null && !media.isEmpty()) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (MediaPart m : media) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("modality", m.modality());
                p.put("mimeType", m.mimeType());
                p.put("uri", m.uri());
                p.put("bytesRef", m.bytesRef());
                p.put("meta", m.meta());
                parts.add(p);
            }
            uf.put("__mediaParts__", parts);
            uf.put("__llmContentParts__", toLlmContentParts(primaryText(uf), media));
            uf.put("hasMedia", true);
            uf.put("mediaConsumed", true);
            // Make PromptTemplate.format / query fields see media refs.
            Object q = uf.getOrDefault("query", uf.get("text"));
            uf.put("query", appendMediaRefs(q == null ? "" : String.valueOf(q), media));
        }
        return uf;
    }

    /**
     * OpenAI-style multimodal content parts for LLMExecutable / Model clients that accept list content.
     */
    public static List<Map<String, Object>> toLlmContentParts(String text, List<MediaPart> media) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", "text");
            t.put("text", text);
            parts.add(t);
        }
        if (media != null) {
            for (MediaPart m : media) {
                parts.add(toContentPart(m));
            }
        }
        return parts;
    }

    static Map<String, Object> toContentPart(MediaPart m) {
        Map<String, Object> part = new LinkedHashMap<>();
        String modality = m.modality() == null ? "" : m.modality().toLowerCase(Locale.ROOT);
        String mime = m.mimeType() == null ? "" : m.mimeType().toLowerCase(Locale.ROOT);
        if (modality.contains("image") || mime.startsWith("image/")) {
            part.put("type", "image_url");
            Map<String, Object> image = new LinkedHashMap<>();
            image.put("url", m.uri() != null ? m.uri() : m.bytesRef());
            part.put("image_url", image);
        } else if (modality.contains("audio") || mime.startsWith("audio/")) {
            part.put("type", "input_audio");
            Map<String, Object> audio = new LinkedHashMap<>();
            audio.put("data", m.uri() != null ? m.uri() : m.bytesRef());
            audio.put("format", mime.isBlank() ? "wav" : mime);
            part.put("input_audio", audio);
        } else {
            part.put("type", "text");
            part.put("text", "[media modality=" + m.modality() + " uri=" + m.uri() + "]");
        }
        return part;
    }

    /**
     * Flatten userFields into root inputs so core PromptTemplate can resolve {{query}} etc.
     */
    public static Map<String, Object> flattenForPrompt(Map<String, Object> inputs, Map<String, Object> userFields) {
        Map<String, Object> in = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        if (userFields != null) {
            in.put("userFields", userFields);
            for (Map.Entry<String, Object> e : userFields.entrySet()) {
                in.putIfAbsent(e.getKey(), e.getValue());
            }
            Object parts = userFields.get("__llmContentParts__");
            if (parts != null) {
                in.put("__llmContentParts__", parts);
            }
        }
        return in;
    }

    private static String primaryText(Map<String, Object> uf) {
        Object q = uf.getOrDefault("query", uf.getOrDefault("text", uf.get("prompt")));
        return q == null ? "" : String.valueOf(q);
    }

    private static String appendMediaRefs(String text, List<MediaPart> media) {
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        for (MediaPart m : media) {
            sb.append("\n[media:")
                    .append(m.modality())
                    .append(":")
                    .append(m.uri() != null ? m.uri() : m.bytesRef())
                    .append(']');
        }
        return sb.toString();
    }
}
