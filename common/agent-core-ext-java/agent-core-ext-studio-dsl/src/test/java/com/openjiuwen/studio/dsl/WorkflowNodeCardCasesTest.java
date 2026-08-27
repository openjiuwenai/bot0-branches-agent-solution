/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowcard.FlowCardConfig;
import com.openjiuwen.studio.dsl.flowcard.FlowCardEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_case_workflow_card001} card aspects — CardNodeHandler / FlowCardEngine.
 *
 * @since 2026-08-25
 */

class WorkflowNodeCardCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeMap(Object invokeOut) {
        return (Map<String, Object>) invokeOut;
    }

    @Test
    void basicTemplateRendering() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "card",
                        "jiuwen.card",
                        Map.of(
                                "template",
                                "{\"title\": \"{{title}}\", \"content\": \"{{content}}\", \"URL\": \"{{URL}}\"}",
                                "output_mode",
                                "separate")),
                NodeBuildContext.defaults("wf_card"));
        Map<String, Object> out = invokeMap(exec.invoke(
                Map.of(
                        "userFields",
                        Map.of(
                                "title",
                                "标题",
                                "content",
                                "你好，有什么我可以帮助你的吗？",
                                "URL",
                                "相关链接")),
                mock(NodeSessionApi.class),
                null));
        assertThat(out).containsOnlyKeys("result");
        assertThat(out.get("result").toString())
                .contains("标题")
                .contains("你好，有什么我可以帮助你的吗？")
                .contains("相关链接");
    }

    @Test
    void collectsCardOutputsToGlobalState() {
        AtomicReference<Object> global = new AtomicReference<>();
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getGlobalState("card_outputs")).thenAnswer(inv -> global.get());
        doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = inv.getArgument(0);
                    global.set(patch.get("card_outputs"));
                    return null;
                })
                .when(session)
                .updateGlobalState(any());

        ComponentExecutable exec = registry.create(
                AssembledNode.of("card", "jiuwen.card", Map.of("template", "card:{{name}}")),
                NodeBuildContext.defaults("wf"));
        invokeMap(exec.invoke(Map.of("userFields", Map.of("name", "Ada")), session, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stored = (List<Map<String, Object>>) global.get();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).containsEntry("node_type", "card").containsEntry("output", "card:Ada");
    }

    @Test
    void invokeDoesNotEmitStreamFrames() {
        List<Object> written = new ArrayList<>();
        NodeSessionApi session = mock(NodeSessionApi.class);
        doAnswer(inv -> {
                    written.add(inv.getArgument(0));
                    return null;
                })
                .when(session)
                .writeCustomStream(any());
        when(session.getGlobalState("card_outputs")).thenReturn(null);
        doAnswer(inv -> null).when(session).updateGlobalState(any());

        ComponentExecutable exec = registry.create(
                AssembledNode.of("card", "jiuwen.card", Map.of("template", "x")),
                NodeBuildContext.defaults("wf"));
        invokeMap(exec.invoke(Map.of("userFields", Map.of()), session, null));
        assertThat(written).isEmpty();
    }

    @Test
    void streamEmitsChunkedFramesAndWritesPartialToSession() {
        List<Map<String, Object>> written = new ArrayList<>();
        NodeSessionApi session = mock(NodeSessionApi.class);
        doAnswer(inv -> {
                    written.add(inv.getArgument(0));
                    return null;
                })
                .when(session)
                .writeCustomStream(any());

        ComponentExecutable exec = registry.create(
                AssembledNode.of("card", "jiuwen.flowCard", Map.of("template", "hi {{x}}")),
                NodeBuildContext.defaults("wf"));
        List<Map<String, Object>> chunks = new ArrayList<>();
        Iterator<Object> it = exec.stream(Map.of("userFields", Map.of("x", "card")), session, null);
        while (it.hasNext()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> frame = (Map<String, Object>) it.next();
            chunks.add(frame);
        }
        assertThat(chunks).hasSize(7);
        assertThat(chunks).allMatch(f -> FlowCardEngine.NODE_TYPE.equals(f.get("type")));
        assertThat(chunks.get(0).get("payload")).isEqualTo(Map.of("response", "h"));

        assertThat(written.stream().map(m -> m.get("type")).toList())
                .contains(StudioStreamFrames.PARTIAL_CONTENT, StudioStreamFrames.MESSAGE_NODE_END);
        @SuppressWarnings("unchecked")
        Map<String, Object> endData =
                (Map<String, Object>) written.stream()
                        .filter(m -> StudioStreamFrames.MESSAGE_NODE_END.equals(m.get("type")))
                        .findFirst()
                        .orElseThrow()
                        .get("data");
        assertThat(endData.get("result")).isEqualTo("hi card");
        assertThat(endData.get("node_id")).isEqualTo("card");
        assertThat(endData.get("should_interrupt")).isEqualTo(false);
    }

    @Test
    void structOutputUsesNodeOutputPlaceholder() {
        FlowCardEngine engine = new FlowCardEngine(
                "c1",
                FlowCardConfig.fromNodeConfigs(Map.of(
                        "template",
                        "raw",
                        "structOutputTemplate",
                        "struct:{{_NODE_OUTPUT}}",
                        "isStructMessage",
                        true)));
        String out = engine.formatStructuredOutput("raw", Map.of());
        assertThat(out).isEqualTo("struct:raw");
    }

    @Test
    void cardOutputTimestampUsesBeijingOffset() {
        FlowCardEngine engine = new FlowCardEngine("c1", FlowCardConfig.fromNodeConfigs(Map.of("template", "x")));
        String ts = String.valueOf(engine.formatCardOutputRecord("out").get("time_stamp"));
        assertThat(ts).matches(".*[+-]08:00.*");
    }

    @Test
    void blankTemplateRejectedAtCreate() {
        try {
            registry.create(
                    AssembledNode.of("card", "jiuwen.card", Map.of("template", "")),
                    NodeBuildContext.defaults("wf"));
            org.junit.jupiter.api.Assertions.fail("expected NodeExecutionException");
        } catch (NodeExecutionException e) {
            assertThat(e.getMessage()).contains("template");
        }
    }

    @Test
    void collectResolvesStreamInputs() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("card", "jiuwen.card", Map.of("template", "v={{v}}")),
                NodeBuildContext.defaults("wf"));
        List<Map<String, Object>> streamChunks =
                List.of(Map.of("response", "a"), Map.of("response", "b"));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.collect(Map.of("v", streamChunks.iterator()), mock(NodeSessionApi.class), null);
        assertThat(out).containsOnlyKeys("result");
        assertThat(out.get("result")).isEqualTo("v=ab");
    }

    @Test
    void eventTaskCompletion_setsEndInterruptOnConfig() {
        FlowCardConfig config = FlowCardConfig.fromNodeConfigs(Map.of(
                "template",
                "done",
                "event",
                Map.of("type", "task_completion")));
        assertThat(config.endInterrupt()).isTrue();
        Map<String, Object> out = invokeMap(registry.create(
                        AssembledNode.of(
                                "card",
                                "jiuwen.card",
                                Map.of(
                                        "template",
                                        "done",
                                        "event",
                                        Map.of("type", "task_completion"))),
                        NodeBuildContext.defaults("wf"))
                .invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
        assertThat(out).containsOnlyKeys("result");
        assertThat(out.get("result")).isEqualTo("done");
    }
}
