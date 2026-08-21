package com.openjiuwen.studio.dsl.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Inter-node data carrier: fields + optional media (L2 D6). */
public final class NodePayload {
    private final Map<String, Object> fields;
    private final List<MediaPart> media;

    public NodePayload(Map<String, Object> fields, List<MediaPart> media) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNullElse(fields, Map.of())));
        this.media = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNullElse(media, List.of())));
    }

    public static NodePayload ofFields(Map<String, Object> fields) {
        return new NodePayload(fields, List.of());
    }

    public static NodePayload userFields(Map<String, Object> userFields) {
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("userFields", userFields == null ? Map.of() : userFields);
        return ofFields(wrap);
    }

    public Map<String, Object> fields() {
        return fields;
    }

    public List<MediaPart> media() {
        return media;
    }

    /** Merge media list; unsupported modalities kept (D9 default passthrough). */
    public NodePayload withMediaPassthrough(List<MediaPart> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        List<MediaPart> merged = new ArrayList<>(media);
        merged.addAll(extra);
        return new NodePayload(fields, merged);
    }

    public Map<String, Object> toInvokeMap() {
        Map<String, Object> out = new LinkedHashMap<>(fields);
        if (!media.isEmpty()) {
            out.put("__media__", media);
        }
        return out;
    }
}
