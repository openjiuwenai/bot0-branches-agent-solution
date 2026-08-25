/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single KB hit (Python {@code KBSearchResult}).
 *
 * @since 2026-08-25
 */
public final class KBSearchResult {
    private String text = "";
    private double score;
    private String source = "";
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private String knowledgeBaseId = "";
    private String knowledgeBaseType = "";
    private String fileId = "";
    private String documentName = "";
    private String subtitle = "";
    private int serialNumber;
    private String retrievalId = "";
    private String type = "doc";

    /** @return text */
    public String text() {
        return text;
    }

    /**
     * setText.
     *
     * @param text text
     * @return this
     */
    public KBSearchResult setText(String text) {
        this.text = text == null ? "" : text;
        return this;
    }

    /** @return score */
    public double score() {
        return score;
    }

    /**
     * setScore.
     *
     * @param score score
     * @return this
     */
    public KBSearchResult setScore(double score) {
        this.score = score;
        return this;
    }

    /** @return source */
    public String source() {
        return source;
    }

    /**
     * setSource.
     *
     * @param source source
     * @return this
     */
    public KBSearchResult setSource(String source) {
        this.source = source == null ? "" : source;
        return this;
    }

    /** @return metadata */
    public Map<String, Object> metadata() {
        return metadata;
    }

    /**
     * setMetadata.
     *
     * @param metadata metadata
     * @return this
     */
    public KBSearchResult setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        return this;
    }

    /** @return knowledgeBaseId */
    public String knowledgeBaseId() {
        return knowledgeBaseId;
    }

    /**
     * setKnowledgeBaseId.
     *
     * @param knowledgeBaseId knowledgeBaseId
     * @return this
     */
    public KBSearchResult setKnowledgeBaseId(String knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId == null ? "" : knowledgeBaseId;
        return this;
    }

    /** @return knowledgeBaseType */
    public String knowledgeBaseType() {
        return knowledgeBaseType;
    }

    /**
     * setKnowledgeBaseType.
     *
     * @param knowledgeBaseType knowledgeBaseType
     * @return this
     */
    public KBSearchResult setKnowledgeBaseType(String knowledgeBaseType) {
        this.knowledgeBaseType = knowledgeBaseType == null ? "" : knowledgeBaseType;
        return this;
    }

    /** @return fileId */
    public String fileId() {
        return fileId;
    }

    /**
     * setFileId.
     *
     * @param fileId fileId
     * @return this
     */
    public KBSearchResult setFileId(String fileId) {
        this.fileId = fileId == null ? "" : fileId;
        return this;
    }

    /** @return documentName */
    public String documentName() {
        return documentName;
    }

    /**
     * setDocumentName.
     *
     * @param documentName documentName
     * @return this
     */
    public KBSearchResult setDocumentName(String documentName) {
        this.documentName = documentName == null ? "" : documentName;
        return this;
    }

    /** @return subtitle */
    public String subtitle() {
        return subtitle;
    }

    /**
     * setSubtitle.
     *
     * @param subtitle subtitle
     * @return this
     */
    public KBSearchResult setSubtitle(String subtitle) {
        this.subtitle = subtitle == null ? "" : subtitle;
        return this;
    }

    /** @return serialNumber */
    public int serialNumber() {
        return serialNumber;
    }

    /**
     * setSerialNumber.
     *
     * @param serialNumber serialNumber
     * @return this
     */
    public KBSearchResult setSerialNumber(int serialNumber) {
        this.serialNumber = serialNumber;
        return this;
    }

    /** @return retrievalId */
    public String retrievalId() {
        return retrievalId;
    }

    /**
     * setRetrievalId.
     *
     * @param retrievalId retrievalId
     * @return this
     */
    public KBSearchResult setRetrievalId(String retrievalId) {
        this.retrievalId = retrievalId == null ? "" : retrievalId;
        return this;
    }

    /** @return type doc|faq */
    public String type() {
        return type;
    }

    /**
     * setType.
     *
     * @param type type
     * @return this
     */
    public KBSearchResult setType(String type) {
        this.type = type == null || type.isBlank() ? "doc" : type;
        return this;
    }

    /**
     * toOutputMap.
     *
     * @return result
     */
    public Map<String, Object> toOutputMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", text);
        m.put("text", text);
        m.put("score", score);
        m.put("knowledge_base_id", knowledgeBaseId);
        m.put("knowledge_base_type", knowledgeBaseType);
        m.put("file_id", fileId);
        m.put("document_name", documentName);
        m.put("subtitle", subtitle);
        m.put("serial_number", serialNumber);
        m.put("retrieval_id", retrievalId);
        m.put("type", type);
        m.put("source", source);
        m.put("metadata", metadata);
        return m;
    }
}
