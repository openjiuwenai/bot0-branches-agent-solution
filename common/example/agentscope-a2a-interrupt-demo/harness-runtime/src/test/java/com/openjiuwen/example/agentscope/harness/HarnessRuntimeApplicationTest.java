/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentscope.harness;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests the Harness schema-only external tool definition.
 *
 * @since 2026-07-20
 */
class HarnessRuntimeApplicationTest {
    @Test
    @SuppressWarnings("unchecked")
    void registersExternalLookupAsSchemaOnlyTool() {
        Toolkit toolkit = HarnessRuntimeApplication.externalToolkit();

        assertThat(toolkit.isExternalTool("external_lookup")).isTrue();
        assertThat(toolkit.getToolSchemas())
            .extracting(ToolSchema::getName)
            .containsExactly("external_lookup");

        ToolSchema schema = toolkit.getToolSchemas().get(0);
        assertThat(schema.getParameters()).containsEntry(
            "required",
            List.of("customer_id", "attribute"));
        assertThat((Map<String, Object>) schema.getParameters().get("properties"))
            .containsOnlyKeys("customer_id", "attribute");
    }
}
