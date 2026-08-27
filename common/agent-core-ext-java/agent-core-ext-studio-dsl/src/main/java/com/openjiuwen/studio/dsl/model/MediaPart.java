/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Multimodal fragment carried between nodes (FEAT-031 MUST).
 *
 * @since 2026-08-17
 */

public final class MediaPart {
    private final String modality;
    private final String mimeType;
    private final String uri;
    private final String bytesRef;
    private final Map<String, Object> meta;

    /**
     * MediaPart.
     *
     * @param modality modality
     * @param mimeType mimeType
     * @param uri uri
     * @param bytesRef bytesRef
     * @param meta meta
     */

    public MediaPart(String modality, String mimeType, String uri, String bytesRef, Map<String, Object> meta) {
        if ((uri == null || uri.isBlank()) && (bytesRef == null || bytesRef.isBlank())) {
        throw new IllegalArgumentException("MediaPart requires uri or bytesRef");
    }
        this.modality = Objects.requireNonNull(modality, "modality");
        this.mimeType = mimeType;
        this.uri = uri;
        this.bytesRef = bytesRef;
        this.meta = meta == null ? Map.of() : Collections.unmodifiableMap(meta);
    }

    /**
     * modality.
     *
     * @return result
     */

    public String modality() {
        return modality;
    }

    /**
     * mimeType.
     *
     * @return result
     */

    public String mimeType() {
        return mimeType;
    }

    /**
     * uri.
     *
     * @return result
     */

    public String uri() {
        return uri;
    }

    /**
     * bytesRef.
     *
     * @return result
     */

    public String bytesRef() {
        return bytesRef;
    }

    /**
     * meta.
     *
     * @return result
     */

    public Map<String, Object> meta() {
        return meta;
    }
}
