/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.util.MediaSupport;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * DataAndMediaTest for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
class DataAndMediaTest {
    @Test
    void media_passthrough_preserved() {
        MediaPart img = new MediaPart("image", "image/png", "file:///a.png", null, Map.of());
        assertThat(MediaSupport.mediaOf(Map.of("__media__", List.of(img)))).isEqualTo(List.of(img));
        Map<String, Object> enriched =
                MediaSupport.withConsumableMedia(Map.of("text", "hi"), List.of(img));
        assertThat(enriched).containsEntry("text", "hi").containsEntry("hasMedia", true);
    }

    @Test
    void message_invokeReturnsResultOnly() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec =
                registry.create(
                        AssembledNode.of("m1", "jiuwen.message", Map.of("template", "{{text}}")),
                        NodeBuildContext.defaults("wf"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) exec.invoke(
                Map.of("userFields", Map.of("text", "hi")),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
        assertThat(out).containsEntry("result", "hi").doesNotContainKey("userFields");
    }

    @Test
    void setVariable_writesMappedFields() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        NodeBuildContext ctx = NodeBuildContext.defaults("wf");
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "v1",
                        "jiuwen.setVariable",
                        Map.of(
                                "settings",
                                List.of(Map.of(
                                        "left", Map.of("value", "foo"),
                                        "right", Map.of("value", "bar"))))),
                ctx);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("a", 1)), mock(NodeSessionApi.class), mock(ModelContext.class));
        // Python returns None
        assertThat(out).isEmpty();
        assertThat(ctx.variableScope().get("foo")).isEqualTo("bar");
    }
}
