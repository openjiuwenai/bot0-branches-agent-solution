/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.adapter.control.AggregateNodeHandler;
import com.openjiuwen.studio.dsl.adapter.interact.FlowInputUtils;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java ports of {@code jiuwen/test/cases/workflow_node} first-batch pure-node cases:
 * aggregate / message / input utils / end / exception.
 *
 * <p>Python sources: {@code test_flow_aggregate_cases.py}, {@code test_flow_message_cases.py},
 * {@code test_flow_input.py} (unit), ExceptionInfo abort path.
 *
 * @since 2026-08-25
 */
class WorkflowNodeCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    // --- test_flow_aggregate_cases.py ---

    @Nested
    class AggregateCases {
        @Test
        void firstNonNullStringsPicksFirstNonEmpty() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "agg1",
                            "jiuwen.aggregate",
                            Map.of("mode", "first-non-null", "groups", Map.of("merged", List.of("x", "y")))),
                    NodeBuildContext.defaults("wf_agg"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("x", "", "y", "second")), null, null)))
                    .containsEntry("merged", "second");
        }

        @Test
        void firstNonNullNonStringPicksFirstNotNone() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("agg1", "jiuwen.aggregate", Map.of("groups", Map.of("n", List.of("a", "b")))),
                    NodeBuildContext.defaults("wf_agg"));
            Map<String, Object> allNull = new HashMap<>();
            allNull.put("a", null);
            allNull.put("b", null);
            assertThat(uf(exec.invoke(Map.of("userFields", allNull), null, null)).get("n")).isNull();
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("a", 2, "b", 7)), null, null)))
                    .containsEntry("n", 2);
        }

        @Test
        void groupsAsListOfDicts() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "agg1",
                            "jiuwen.aggregate",
                            Map.of(
                                    "groups",
                                    List.of(Map.of("id", "o1", "value_list", List.of("p", "q"))))),
                    NodeBuildContext.defaults("wf_agg"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("p", "", "q", "v")), null, null)))
                    .containsEntry("o1", "v");
        }

        @Test
        void dictValuesSameShapeOk() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("agg1", "jiuwen.aggregate", Map.of("groups", Map.of("d", List.of("a", "b")))),
                    NodeBuildContext.defaults("wf_agg"));
            assertThat(uf(exec.invoke(
                            Map.of("userFields", Map.of("a", Map.of("k", 0), "b", Map.of("k", 1))), null, null)))
                    .containsEntry("d", Map.of("k", 0));
        }

        @Test
        void dictValuesTypeMismatchRaises() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("agg1", "jiuwen.aggregate", Map.of("groups", Map.of("d", List.of("a", "b")))),
                    NodeBuildContext.defaults("wf_agg"));
            assertThatThrownBy(
                            () ->
                                    exec.invoke(
                                            Map.of(
                                                    "userFields",
                                                    Map.of("a", Map.of("k", 1), "b", Map.of("k", "s"))),
                                            null,
                                            null))
                    .isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void scalarTypeMismatchRaises() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("agg1", "jiuwen.aggregate", Map.of("groups", Map.of("m", List.of("a", "b")))),
                    NodeBuildContext.defaults("wf_agg"));
            assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of("a", 1, "b", "x")), null, null))
                    .isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void unsupportedModeRaises() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "agg1",
                            "jiuwen.aggregate",
                            Map.of("mode", "merge-all", "groups", Map.of("x", List.of("a")))),
                    NodeBuildContext.defaults("wf_agg"));
            assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of("a", 1)), null, null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("unsupported aggregate mode");
        }

        @Test
        void flatInputsWhenNoUserFieldsKey() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("agg1", "jiuwen.aggregate", Map.of("groups", Map.of("o", List.of("a", "b")))),
                    NodeBuildContext.defaults("wf_agg"));
            assertThat(uf(exec.invoke(Map.of("a", "", "b", "z"), null, null))).containsEntry("o", "z");
        }

        @Test
        void invalidConfRaises() {
            assertThatThrownBy(
                            () ->
                                    new AggregateNodeHandler()
                                            .create(
                                                    AssembledNode.of("a", "jiuwen.aggregate", Map.of()),
                                                    NodeBuildContext.defaults("wf")))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("conf is required");
        }
    }

    // --- test_flow_message_cases.py (node-level) ---

    @Nested
    class MessageCases {
        @Test
        void invokeFromUserFields() {
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getWorkflowId()).thenReturn("message_unit_wf");
            when(session.getExecutableId()).thenReturn("msg1");
            AtomicReference<Object> global = new AtomicReference<>();
            when(session.getGlobalState("message_outputs")).thenAnswer(inv -> global.get());
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        global.set(patch.get("message_outputs"));
                        return null;
                    })
                    .when(session)
                    .updateGlobalState(any());

            ComponentExecutable exec = registry.create(
                    AssembledNode.of("msg1", "jiuwen.message", Map.of("template", "您好，{{name}}！")),
                    NodeBuildContext.defaults("message_unit_wf"));
            Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of("name", "李四")), session, null));
            assertThat(fields.get("result")).isEqualTo("您好，李四！");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stored = (List<Map<String, Object>>) global.get();
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0)).containsEntry("node_type", "message").containsEntry("output", "您好，李四！");
            assertThat(stored.get(0)).containsEntry("host_workflow_id", "message_unit_wf");
            assertThat(stored.get(0)).containsEntry("message_component_id", "msg1");
        }

        @Test
        void invokeFlatDictWhenNoUserFields() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("msg1", "jiuwen.message", Map.of("template", "计数={{count}}")),
                    NodeBuildContext.defaults("wf"));
            assertThat(uf(exec.invoke(Map.of("count", "1"), mock(NodeSessionApi.class), null)).get("result"))
                    .isEqualTo("计数=1");
        }

        @Test
        void streamFramesAndEndFrame() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("msg1", "jiuwen.message", Map.of("template", "前缀{{mid}}后缀")),
                    NodeBuildContext.defaults("wf"));
            List<Map<String, Object>> chunks = new ArrayList<>();
            Iterator<Object> it = exec.stream(Map.of("userFields", Map.of("mid", "内容")), mock(NodeSessionApi.class), null);
            while (it.hasNext()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> frame = (Map<String, Object>) it.next();
                chunks.add(frame);
            }
            assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
            for (int i = 0; i < chunks.size() - 1; i++) {
                assertThat(chunks.get(i).get("type")).isEqualTo(StudioStreamFrames.PARTIAL_CONTENT);
            }
            assertThat(chunks.get(chunks.size() - 1).get("type")).isEqualTo(StudioStreamFrames.MESSAGE_NODE_END);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) chunks.get(chunks.size() - 1).get("data");
            assertThat(payload.get("result")).isEqualTo("前缀内容后缀");
            assertThat(payload.get("enable_history")).isEqualTo(true);
        }

        @Test
        void streamEnableHistoryFalseString() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "msg1",
                            "jiuwen.message",
                            Map.of("template", "固定句", "enable_history", "false")),
                    NodeBuildContext.defaults("wf"));
            Iterator<Object> it = exec.stream(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null);
            Map<String, Object> last = null;
            while (it.hasNext()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> frame = (Map<String, Object>) it.next();
                last = frame;
            }
            assertThat(last).isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) last.get("data");
            assertThat(payload.get("enable_history")).isEqualTo(false);
        }

        @Test
        void streamStructTemplateOnEndFrame() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "msg1",
                            "jiuwen.message",
                            Map.of(
                                    "template",
                                    "正文块",
                                    "isStructMessage",
                                    true,
                                    "struct_output_template",
                                    "包装开始：{{_NODE_OUTPUT}}：包装结束")),
                    NodeBuildContext.defaults("wf"));
            Iterator<Object> it = exec.stream(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null);
            Map<String, Object> last = null;
            while (it.hasNext()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> frame = (Map<String, Object>) it.next();
                last = frame;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) last.get("data");
            assertThat(payload.get("struct_answer")).isEqualTo("包装开始：正文块：包装结束");
            assertThat(payload.get("is_struct_message")).isEqualTo(true);
        }

        @Test
        void eventTaskCompletionSetsEndInterrupt() {
            Map<String, Object> fields =
                    uf(
                            registry
                                    .create(
                                            AssembledNode.of(
                                                    "m",
                                                    "jiuwen.message",
                                                    Map.of(
                                                            "template",
                                                            "占位",
                                                            "event",
                                                            Map.of("type", "task_completion"))),
                                            NodeBuildContext.defaults("wf"))
                                    .invoke(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null));
            assertThat(fields.get("should_interrupt")).isEqualTo(true);
        }

        @Test
        void missingTemplateRaises() {
            assertThatThrownBy(
                            () ->
                                    registry.create(
                                            AssembledNode.of("m", "jiuwen.message", Map.of()),
                                            NodeBuildContext.defaults("wf")))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("template");
        }

        @Test
        void emptyTemplateRaises() {
            assertThatThrownBy(
                            () ->
                                    registry.create(
                                            AssembledNode.of("m", "jiuwen.message", Map.of("template", "")),
                                            NodeBuildContext.defaults("wf")))
                    .isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void fromFieldsMatchesDictConfigInvoke() {
            ComponentExecutable a = registry.create(
                    AssembledNode.of("m", "jiuwen.message", Map.of("template", "答：{{q}}", "output_mode", "text")),
                    NodeBuildContext.defaults("wf"));
            ComponentExecutable b = registry.create(
                    AssembledNode.of("m", "jiuwen.message", Map.of("template", "答：{{q}}", "outputMode", "text")),
                    NodeBuildContext.defaults("wf"));
            Map<String, Object> in = Map.of("userFields", Map.of("q", "杭州天气？"));
            assertThat(uf(a.invoke(in, mock(NodeSessionApi.class), null)).get("result"))
                    .isEqualTo(uf(b.invoke(in, mock(NodeSessionApi.class), null)).get("result"))
                    .isEqualTo("答：杭州天气？");
        }
    }

    // --- test_flow_input.py unit helpers ---

    @Nested
    class InputUtilsCases {
        @Test
        void needInputsMessage() {
            Map<String, Object> config =
                    Map.of(
                            "userFields",
                            Map.of(
                                    "inputs",
                                    List.of(Map.of(
                                            "id", "name", "type", "string", "required", true, "description", "用户姓名"))));
            String message = FlowInputUtils.buildInputsMessage(config);
            assertThat(message).contains("\"name\"").contains("\"actualType\":\"string\"");
        }

        @Test
        void parseUserResponse() {
            assertThat(FlowInputUtils.parseUserResponse("name:张三\nage:25"))
                    .containsEntry("name", "张三")
                    .containsEntry("age", "25");
            assertThat(FlowInputUtils.parseUserResponse("{\"name\": \"李四\", \"age\": \"30\"}"))
                    .containsEntry("name", "李四")
                    .containsEntry("age", "30");
            assertThat(FlowInputUtils.parseUserResponse("")).isEmpty();
            assertThat(FlowInputUtils.parseUserResponse(null)).isEmpty();
        }

        @Test
        void validateInputs() {
            Map<String, Object> config =
                    Map.of(
                            "userFields",
                            Map.of(
                                    "inputs",
                                    List.of(
                                            Map.of("id", "name", "type", "string", "required", true),
                                            Map.of("id", "age", "type", "number", "required", false),
                                            Map.of("id", "active", "type", "boolean", "required", false))));
            FlowInputUtils.validateInputs(Map.of("name", "张三", "age", "25", "active", "true"), config);
            assertThatThrownBy(() -> FlowInputUtils.validateInputs(Map.of("age", "25"), config))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }

        @Test
        void inputFirstPassAndResume() {
            AtomicReference<Map<String, Object>> stateBucket = new AtomicReference<>(new HashMap<>());
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getState(any())).thenAnswer(inv -> {
                Object key = inv.getArgument(0);
                if (key == null) {
                    return stateBucket.get();
                }
                return stateBucket.get().get(String.valueOf(key));
            });
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        Map<String, Object> cur = new HashMap<>(stateBucket.get());
                        cur.putAll(patch);
                        stateBucket.set(cur);
                        return null;
                    })
                    .when(session)
                    .updateState(any());

            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "i",
                            "jiuwen.input",
                            Map.of(
                                    "userFields",
                                    Map.of(
                                            "inputs",
                                            List.of(Map.of("id", "name", "required", true, "type", "string"))))),
                    NodeBuildContext.defaults("wf"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of()), session, null)).get("hangState"))
                    .isEqualTo("INPUT_REQUIRED");
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("name", "Bob")), session, null)))
                    .containsEntry("name", "Bob")
                    .containsEntry("inputReceived", true);
        }
    }

    // --- end / exception node-level ---

    @Nested
    class EndAndExceptionCases {
        @Test
        void endMapsPrefixTemplateAndIdempotent() {
            NodeSessionApi session = mock(NodeSessionApi.class);
            AtomicReference<Object> done = new AtomicReference<>();
            when(session.getState("end_invoke_executed")).thenAnswer(inv -> done.get());
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> patch = inv.getArgument(0);
                        done.set(patch.get("end_invoke_executed"));
                        return null;
                    })
                    .when(session)
                    .updateState(any());

            ComponentExecutable exec = registry.create(
                    AssembledNode.of("e", "jiuwen.end", Map.of("responseTemplate", "out={{val}}")),
                    NodeBuildContext.defaults("wf"));
            Map<String, Object> first =
                    uf(exec.invoke(Map.of("userFields", Map.of("val", "second", "#end_x", 1)), session, null));
            assertThat(first.get("response")).isEqualTo("out=second");
            assertThat(first.get("x")).isEqualTo(1);
            assertThat(first.get("__terminal__")).isEqualTo(true);

            @SuppressWarnings("unchecked")
            Map<String, Object> second =
                    (Map<String, Object>) exec.invoke(Map.of("userFields", Map.of("val", "again")), session, null);
            assertThat(second).isEmpty();
        }

        @Test
        void exceptionAbortEmitsAndThrows() {
            NodeSessionApi session = mock(NodeSessionApi.class);
            when(session.getGlobalState("__abort__")).thenReturn(null);
            List<Map<String, Object>> frames = new ArrayList<>();
            doAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> f = inv.getArgument(0);
                        frames.add(f);
                        return null;
                    })
                    .when(session)
                    .writeCustomStream(any());

            ComponentExecutable exec =
                    registry.create(AssembledNode.of("ex", "jiuwen.exception", Map.of()), NodeBuildContext.defaults("wf"));
            assertThatThrownBy(
                            () -> exec.invoke(Map.of("userFields", Map.of("error_code", 500, "message", "boom")), session, null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("boom");
            assertThat(frames).isNotEmpty();
            assertThat(frames.get(0).get("type")).isEqualTo("workflow_exception");
        }

        @Test
        void exceptionSoftContinues() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "ex",
                            "jiuwen.exception",
                            Map.of("handleType", "soft", "defaultOutputs", Map.of("fallback", true))),
                    NodeBuildContext.defaults("wf"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("message", "x")), null, null)))
                    .containsEntry("fallback", true)
                    .containsKey("exception");
        }
    }
}
