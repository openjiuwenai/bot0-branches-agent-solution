/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.mcp;

import java.util.List;

/**
 * In-memory descriptor of a single fixture document.
 *
 * @param uri MCP resource URI advertised to clients
 * @param path classpath-relative path inside the fixture bundle
 * @param title short human-readable title
 * @param summary one-paragraph summary (includes the anchor tag)
 * @param vendors vendor keywords used for {@code search_knowledge_base} matching
 * @param dimensions dimension keywords (e.g. {@code pricing}, {@code context_length})
 * @param mimeType MIME type reported to MCP clients
 */
public record DocumentIndexEntry(
        String uri,
        String path,
        String title,
        String summary,
        List<String> vendors,
        List<String> dimensions,
        String mimeType) {
}
