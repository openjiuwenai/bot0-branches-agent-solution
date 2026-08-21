package com.openjiuwen.studio.dsl.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Multimodal fragment carried between nodes (FEAT-028 MUST). */
public final class MediaPart {
    private final String modality;
    private final String mimeType;
    private final String uri;
    private final String bytesRef;
    private final Map<String, Object> meta;

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

    public String modality() {
        return modality;
    }

    public String mimeType() {
        return mimeType;
    }

    public String uri() {
        return uri;
    }

    public String bytesRef() {
        return bytesRef;
    }

    public Map<String, Object> meta() {
        return meta;
    }
}
