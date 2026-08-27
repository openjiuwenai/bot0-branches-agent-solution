/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Inter-node data carrier: fields + optional media (L2 D6).
 *
 * @since 2026-08-17
 */

public final class NodePayload {
    private final Map<String, Object> fields;
    private final List<MediaPart> media;

    /**
     * NodePayload.
     *
     * @param fields fields
     * @param media media
     */

    public NodePayload(Map<String, Object> fields, List<MediaPart> media) {
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNullElse(fields, Map.of())));
        this.media = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNullElse(media, List.of())));
    }

    /**
     * ofFields.
     *
     * @param fields fields
     * @return result
     */

    public static NodePayload ofFields(Map<String, Object> fields) {
        return new NodePayload(fields, List.of());
    }

    /**
     * userFields.
     *
     * @param userFields userFields
     * @return result
     */

    public static NodePayload userFields(Map<String, Object> userFields) {
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("userFields", userFields == null ? Map.of() : userFields);
        return ofFields(wrap);
    }

    /**
     * fields.
     *
     * @return result
     */

    public Map<String, Object> fields() {
        return fields;
    }

    /**
     * media.
     *
     * @return result
     */

    public List<MediaPart> media() {
        return media;
    }

    /**
     * Merge media list; unsupported modalities kept (D9 default passthrough).
     *
     * @param extra extra
     * @return result
     */

    public NodePayload withMediaPassthrough(List<MediaPart> extra) {
        if (extra == null || extra.isEmpty()) {
        return this;
    }
        List<MediaPart> merged = new ArrayList<>(media);
        merged.addAll(extra);
        return new NodePayload(fields, merged);
    }

    /**
     * toInvokeMap.
     *
     * @return result
     */

    public Map<String, Object> toInvokeMap() {
        Map<String, Object> out = new LinkedHashMap<>(fields);
        if (!media.isEmpty()) {
            out.put("__media__", media);
        }
        return out;
    }
}
