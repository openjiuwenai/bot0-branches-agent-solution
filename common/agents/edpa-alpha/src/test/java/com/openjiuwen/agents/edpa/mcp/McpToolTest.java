/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * McpTool record bearing tests.
 *
 * @since 2026-07
 */
class McpToolTest {
    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> new McpTool("", "desc", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpTool("   ", "desc", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpTool(null, "desc", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDescriptionBecomesEmpty() {
        McpTool t = new McpTool("get_x", null, Map.of());
        assertThat(t.description()).isEmpty();
    }

    @Test
    void nullInputSchemaBecomesEmptyMap() {
        McpTool t = new McpTool("get_x", "desc", null);
        assertThat(t.inputSchema()).isNotNull().isEmpty();
    }

    @Test
    void inputSchemaIsDefensivelyCopied() {
        Map<String, Object> schema = new java.util.HashMap<>(Map.of("type", "object", "properties",
                Map.of("identifier", Map.of("type", "string")), "required", List.of("identifier")));
        McpTool t = new McpTool("get_financials", "desc", schema);

        // Map.copyOf → unmodifiable
        assertThatThrownBy(() -> t.inputSchema().put("new", "key")).isInstanceOf(UnsupportedOperationException.class);
        // original mutation does not leak in
        schema.put("type", "mutated");
        assertThat(t.inputSchema().get("type")).isEqualTo("object");
    }
}
