/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.external.StreamTransformNodeHandler;
import com.openjiuwen.studio.dsl.flowstreamtransform.FlowStreamTransformEngine.StreamMetadata;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code jiuwen/test/cases/workflow_node/test_flow_stream_transform.py}.
 *
 * <p>Async Python specifics are mapped to JVM {@link Iterable}/{@link java.util.Iterator} streams
 * and {@link NodeSessionApi#writeStream(Object)}.
 *
 * @since 2026-08-25
 */
class WorkflowNodeStreamTransformCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }

    private static Map<String, Object> baseTransformer() {
        return Map.of(
                "frame_template",
                Map.of("result", "{{value}}"),
                "variables",
                List.of(Map.of("name", "value", "src_path", "input")));
    }

    private static Map<String, Object> conf(Map<String, Object> extra) {
        Map<String, Object> c = new HashMap<>();
        c.put("transformer", baseTransformer());
        if (extra != null) {
            c.putAll(extra);
        }
        return c;
    }

    private ComponentExecutable create(String id, Map<String, Object> configs) {
        return registry.create(AssembledNode.of(id, "jiuwen.streamTransform", configs), NodeBuildContext.defaults("wf_st"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeMap(Object out) {
        return (Map<String, Object>) out;
    }

    // --- TestFlowStreamTransformInit ---

    @Nested
    class InitCases {
        @Test
        void initWithValidConfig() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(exec.invoke(Map.of("userFields", Map.of()), session, null));
            assertThat(result).containsEntry(StreamTransformNodeHandler.USER_FIELDS, null);
            verify(session, never()).writeStream(any());
        }

        @Test
        void initWithUserFieldsConfig() {
            Map<String, Object> configs = conf(Map.of(
                    "userFields",
                    Map.of(
                            "inputs", List.of(Map.of("id", "custom_input")),
                            "outputs", List.of(Map.of("id", "custom_output")))));
            ComponentExecutable exec = create("st1", configs);
            NodeSessionApi session = mock(NodeSessionApi.class);
            List<Map<String, Object>> stream = List.of(Map.of("input", "test"));
            Map<String, Object> result =
                    invokeMap(exec.invoke(Map.of("userFields", Map.of("custom_input", stream)), session, null));
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) result.get(StreamTransformNodeHandler.USER_FIELDS);
            assertThat(uf).containsKey("custom_output");
        }

        @Test
        void initWithInvalidTransformerConfig() {
            assertThatThrownBy(() -> create("st1", Map.of("transformer", "not_a_dict")))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("'transformer' must be a dict")
                    .hasMessageContaining("pythonErrorCode=" + StreamTransformNodeHandler.CONFIG_ERROR);
        }

        @Test
        void initWithEmptyTransformerConf() {
            ComponentExecutable exec = create("st1", Map.of());
            NodeSessionApi session = mock(NodeSessionApi.class);
            // empty transformer conf: init OK, invoke fails on from_dict
            assertThatThrownBy(() ->
                            exec.invoke(
                                    Map.of("userFields", Map.of("_input", List.of(Map.of("input", "x")))),
                                    session,
                                    null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining("pythonErrorCode=" + StreamTransformNodeHandler.TRANSFORMER_CONFIG_ERROR);
        }
    }

    // --- TestFlowStreamTransformInvoke ---

    @Nested
    class InvokeCases {
        @Test
        void invokeWithNoneStream() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(exec.invoke(Map.of("userFields", Map.of()), session, null));
            assertThat(result).containsEntry(StreamTransformNodeHandler.USER_FIELDS, null);
            verify(session, never()).writeStream(any());
        }

        @Test
        void invokeWithNonAsyncIterable() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            assertThatThrownBy(() ->
                            exec.invoke(
                                    Map.of("userFields", Map.of("_input", "not_async_iterable")),
                                    session,
                                    null))
                    .isInstanceOf(NodeExecutionException.class)
                    .extracting(e -> ((NodeExecutionException) e).causeCode())
                    .isEqualTo(NodeCauseCode.NODE_INVOKE_FAILED);
            assertThatThrownBy(() ->
                            exec.invoke(
                                    Map.of("userFields", Map.of("_input", "not_async_iterable")),
                                    session,
                                    null))
                    .hasMessageContaining("pythonErrorCode=" + StreamTransformNodeHandler.INPUT_INVALID);
        }

        @Test
        void invokeWithValidStream() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            List<Map<String, Object>> stream = List.of(Map.of("input", "test1"), Map.of("input", "test2"));
            Map<String, Object> result =
                    invokeMap(exec.invoke(Map.of("userFields", Map.of("_input", stream)), session, null));
            assertThat(result).containsKey(StreamTransformNodeHandler.USER_FIELDS);
            verify(session, times(2)).writeStream(any());
        }
    }

    // --- TestFlowStreamTransformStreaming ---

    @Nested
    class StreamingCases {
        @Test
        void streamingOutputFormat() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            List<Map<String, Object>> stream = List.of(Map.of("input", "test1"), Map.of("input", "test2"));
            exec.invoke(Map.of("userFields", Map.of("_input", stream)), session, null);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(session, times(2)).writeStream(captor.capture());
            List<Object> calls = captor.getAllValues();
            assertThat(calls.get(0)).isInstanceOf(OutputSchema.class);
            OutputSchema first = (OutputSchema) calls.get(0);
            assertThat(first.getType()).isEqualTo(StreamTransformNodeHandler.STREAM_TYPE_PARTIAL_CONTENT);
            assertThat(first.getIndex()).isEqualTo(0);
            OutputSchema second = (OutputSchema) calls.get(1);
            assertThat(second.getType()).isEqualTo(StreamTransformNodeHandler.STREAM_TYPE_MESSAGE_END);
            assertThat(second.getIndex()).isEqualTo(1);
        }

        @Test
        void streamingWithMetadata() {
            Map<String, Object> configs = conf(Map.of(
                    "node_id", "custom_node_id",
                    "node_type", "CustomType",
                    "node_name", "Custom Node"));
            ComponentExecutable exec = create("st1", configs);
            NodeSessionApi session = mock(NodeSessionApi.class);
            exec.invoke(Map.of("userFields", Map.of("_input", List.of(Map.of("input", "test")))), session, null);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(session, times(1)).writeStream(captor.capture());
            OutputSchema call = (OutputSchema) captor.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) call.getPayload();
            assertThat(payload)
                    .containsEntry("node_id", "custom_node_id")
                    .containsEntry("node_type", "CustomType")
                    .containsEntry("node_name", "Custom Node");
        }
    }

    // --- TestFlowStreamTransformInputFormats ---

    @Nested
    class InputFormatCases {
        @Test
        void dictInputFormat() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(exec.invoke(
                    Map.of("userFields", Map.of("_input", List.of(Map.of("input", "test_value")))),
                    session,
                    null));
            assertThat(result).containsKey(StreamTransformNodeHandler.USER_FIELDS);
            assertThat(result.get(StreamTransformNodeHandler.USER_FIELDS)).isInstanceOf(Map.class);
        }

        @Test
        void jsonStringInputFormat() throws Exception {
            ComponentExecutable exec = create("st1", conf(Map.of("parse_json_strings", true)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("input", "test_value"));
            Map<String, Object> result =
                    invokeMap(exec.invoke(Map.of("userFields", Map.of("_input", List.of(json))), session, null));
            assertThat(result.get(StreamTransformNodeHandler.USER_FIELDS)).isNotNull();
        }

        @Test
        void sseFormatInput() throws Exception {
            ComponentExecutable exec = create("st1", conf(Map.of("parse_json_strings", true)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("input", "test_value"));
            Map<String, Object> result = invokeMap(exec.invoke(
                    Map.of("userFields", Map.of("_input", List.of("data: " + json))), session, null));
            assertThat(result.get(StreamTransformNodeHandler.USER_FIELDS)).isNotNull();
        }

        @Test
        void bytesInputFormat() throws Exception {
            ComponentExecutable exec = create("st1", conf(Map.of("parse_json_strings", true)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("input", "test_value"))
                    .getBytes(StandardCharsets.UTF_8);
            Map<String, Object> result =
                    invokeMap(exec.invoke(Map.of("userFields", Map.of("_input", List.of(bytes))), session, null));
            assertThat(result.get(StreamTransformNodeHandler.USER_FIELDS)).isNotNull();
        }

        @Test
        void parseJsonStringsDisabled() throws Exception {
            ComponentExecutable exec = create("st1", conf(Map.of("parse_json_strings", false)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(Map.of("input", "test_value"));
            Map<String, Object> result =
                    invokeMap(exec.invoke(Map.of("userFields", Map.of("_input", List.of(json))), session, null));
            assertThat(result).containsEntry(StreamTransformNodeHandler.USER_FIELDS, null);
            verify(session, never()).writeStream(any());
        }
    }

    // --- TestFlowStreamTransformErrorHandling ---

    @Nested
    class ErrorHandlingCases {
        @Test
        void invalidTransformerConfigError() {
            ComponentExecutable exec = create("st1", Map.of("transformer", mapOfNullFrameTemplate()));
            NodeSessionApi session = mock(NodeSessionApi.class);
            assertThatThrownBy(() ->
                            exec.invoke(
                                    Map.of("userFields", Map.of("_input", List.of(Map.of("input", "test")))),
                                    session,
                                    null))
                    .isInstanceOf(NodeExecutionException.class)
                    .hasMessageContaining(
                            "pythonErrorCode=" + StreamTransformNodeHandler.TRANSFORMER_CONFIG_ERROR);
        }

        private static Map<String, Object> mapOfNullFrameTemplate() {
            Map<String, Object> t = new HashMap<>();
            t.put("frame_template", null);
            return t;
        }
    }

    // --- TestGetDataOfStreamingWithMetadata ---

    @Nested
    class MetadataHelperCases {
        @Test
        void basicOutput() {
            StreamMetadata metadata =
                    new StreamMetadata("test_node_id", "StreamTransform", "Test Stream Transform", false);
            Map<String, Object> result =
                    StreamTransformNodeHandler.getDataOfStreamingWithMetadata("test_answer", metadata, null);
            assertThat(result)
                    .containsEntry("answer", "test_answer")
                    .containsEntry("node_id", "test_node_id")
                    .containsEntry("node_type", "StreamTransform")
                    .containsEntry("node_name", "Test Stream Transform")
                    .containsEntry("should_interrupt", false)
                    .doesNotContainKey("outputs");
        }

        @Test
        void outputWithOutputs() {
            StreamMetadata metadata =
                    new StreamMetadata("test_node_id", "StreamTransform", "Test Stream Transform", false);
            Map<String, Object> result = StreamTransformNodeHandler.getDataOfStreamingWithMetadata(
                    "test_answer", metadata, Map.of("key", "value"));
            assertThat(result).containsKey("outputs");
            @SuppressWarnings("unchecked")
            Map<String, Object> outputs = (Map<String, Object>) result.get("outputs");
            assertThat(outputs.get(StreamTransformNodeHandler.USER_FIELDS)).isEqualTo(Map.of("key", "value"));
        }

        @Test
        void dictAnswer() {
            StreamMetadata metadata =
                    new StreamMetadata("test_node_id", "StreamTransform", "Test Stream Transform", false);
            Map<String, Object> result = StreamTransformNodeHandler.getDataOfStreamingWithMetadata(
                    Map.of("key", "value"), metadata, null);
            assertThat(result.get("answer")).isEqualTo(Map.of("key", "value"));
        }
    }

    // --- TestBuildFlowStreamTransformError ---

    @Nested
    class BuildErrorCases {
        @Test
        void errorCreation() {
            NodeExecutionException error = StreamTransformNodeHandler.buildFlowStreamTransformError(
                    "n1", StreamTransformNodeHandler.CONFIG_ERROR, "Test error message");
            assertThat(error.causeCode()).isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
            assertThat(error.getMessage()).contains("Test error message");
            assertThat(error.getMessage())
                    .contains("pythonErrorCode=" + StreamTransformNodeHandler.CONFIG_ERROR);
        }
    }

    // --- TestFlowStreamTransformOutputField ---

    @Nested
    class OutputFieldCases {
        @Test
        void directAssignOutput() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(exec.invoke(
                    Map.of("userFields", Map.of("_input", List.of(Map.of("input", "test")))), session, null));
            assertThat(result.get(StreamTransformNodeHandler.USER_FIELDS)).isInstanceOf(Map.class);
        }

        @Test
        void outputFieldAssignment() {
            Map<String, Object> configs =
                    conf(Map.of("userFields", Map.of("outputs", List.of(Map.of("id", "custom_output")))));
            ComponentExecutable exec = create("st1", configs);
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(exec.invoke(
                    Map.of("userFields", Map.of("_input", List.of(Map.of("input", "test")))), session, null));
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) result.get(StreamTransformNodeHandler.USER_FIELDS);
            assertThat(uf).containsKey("custom_output");
        }
    }

    // --- TestFlowStreamTransformCollectTransform (Python test_collect_transform) ---

    @Nested
    class CollectTransformCases {
        @Test
        void collectResolvesTopLevelGeneratorThenEmptyUserFields() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            // Python: top-level AsyncGenerator is joined; no userFields._input → null
            Map<String, Object> result =
                    invokeMap(exec.collect(Map.of("key", List.of("resolved_text")), session, null));
            assertThat(result).containsEntry(StreamTransformNodeHandler.USER_FIELDS, null);
            verify(session, never()).writeStream(any());
        }

        @Test
        void collectWithNullInput() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(exec.collect(null, session, null));
            assertThat(result).containsEntry(StreamTransformNodeHandler.USER_FIELDS, null);
        }

        @Test
        void transformYieldsPartialThenMessageEnd() {
            ComponentExecutable exec = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            List<Map<String, Object>> stream =
                    List.of(Map.of("input", "chunk1"), Map.of("input", "chunk2"));
            Iterator<Object> it =
                    exec.transform(Map.of("userFields", Map.of("_input", stream)), session, null);
            List<Object> frames = new ArrayList<>();
            it.forEachRemaining(frames::add);
            assertThat(frames).hasSize(2);
            assertThat(((OutputSchema) frames.get(0)).getType())
                    .isEqualTo(StreamTransformNodeHandler.STREAM_TYPE_PARTIAL_CONTENT);
            assertThat(((OutputSchema) frames.get(1)).getType())
                    .isEqualTo(StreamTransformNodeHandler.STREAM_TYPE_MESSAGE_END);
            verify(session, never()).writeStream(any());
        }

        @Test
        void parsePythonLiteralDictString() {
            ComponentExecutable exec = create("st1", conf(Map.of("parse_json_strings", true)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            List<String> stream = List.of("{'input': 'from_literal'}");
            Map<String, Object> result =
                    invokeMap(exec.invoke(Map.of("userFields", Map.of("_input", stream)), session, null));
            assertThat(result.get(StreamTransformNodeHandler.USER_FIELDS)).isInstanceOf(Map.class);
            verify(session).writeStream(any());
        }
    }

    // --- TestFlowStreamTransformWorkflowIntegration (component-chain style) ---

    @Nested
    class WorkflowIntegrationCases {
        @Test
        void componentChainIntegration() {
            NodeSessionApi session = mock(NodeSessionApi.class);
            List<Map<String, Object>> streamItems =
                    List.of(Map.of("input", "hello"), Map.of("input", "world"), Map.of("input", "test"));
            Map<String, Object> genResult = Map.of("userFields", Map.of("_input", streamItems));

            ComponentExecutable transformer = create(
                    "flow_stream_transform",
                    conf(Map.of(
                            "node_id", "flow_stream_transform",
                            "node_type", "FlowStreamTransform",
                            "node_name", "Test Flow Stream Transform")));
            Map<String, Object> transformResult = invokeMap(transformer.invoke(genResult, session, null));
            assertThat(transformResult.get(StreamTransformNodeHandler.USER_FIELDS)).isNotNull();

            // StreamCollector analogue
            @SuppressWarnings("unchecked")
            Map<String, Object> collectedUf =
                    (Map<String, Object>) transformResult.get(StreamTransformNodeHandler.USER_FIELDS);
            assertThat(collectedUf).isNotNull();
        }

        @Test
        void componentChainWithJsonStringInput() throws Exception {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> streamItems = List.of(
                    mapper.writeValueAsString(Map.of("input", "test1")),
                    mapper.writeValueAsString(Map.of("input", "test2")));
            ComponentExecutable transformer = create("st1", conf(Map.of("parse_json_strings", true)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result =
                    invokeMap(transformer.invoke(Map.of("userFields", Map.of("_input", streamItems)), session, null));
            assertThat(result).containsKey(StreamTransformNodeHandler.USER_FIELDS);
        }

        @Test
        void componentChainWithCustomFields() {
            List<Map<String, Object>> streamItems = List.of(Map.of("data", "item1"), Map.of("data", "item2"));
            Map<String, Object> configs = new HashMap<>();
            configs.put(
                    "transformer",
                    Map.of(
                            "frame_template",
                            Map.of("result", "{{value}}"),
                            "variables",
                            List.of(Map.of("name", "value", "src_path", "data"))));
            configs.put(
                    "userFields",
                    Map.of(
                            "inputs", List.of(Map.of("id", "custom_input")),
                            "outputs", List.of(Map.of("id", "custom_output"))));
            ComponentExecutable transformer = create("st1", configs);
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(transformer.invoke(
                    Map.of("userFields", Map.of("custom_input", streamItems)), session, null));
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) result.get(StreamTransformNodeHandler.USER_FIELDS);
            assertThat(uf).containsKey("custom_output");
        }

        @Test
        void componentChainEmptyStream() {
            ComponentExecutable transformer = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result = invokeMap(transformer.invoke(
                    Map.of("userFields", Map.of("_input", List.of())), session, null));
            assertThat(result).containsEntry(StreamTransformNodeHandler.USER_FIELDS, null);
        }

        @Test
        void componentChainStreamingOutput() {
            List<Map<String, Object>> streamItems =
                    List.of(Map.of("input", "chunk1"), Map.of("input", "chunk2"), Map.of("input", "chunk3"));
            ComponentExecutable transformer = create("st1", conf(null));
            NodeSessionApi session = mock(NodeSessionApi.class);
            transformer.invoke(Map.of("userFields", Map.of("_input", streamItems)), session, null);

            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(session, times(3)).writeStream(captor.capture());
            List<Object> calls = captor.getAllValues();
            assertThat(((OutputSchema) calls.get(0)).getType())
                    .isEqualTo(StreamTransformNodeHandler.STREAM_TYPE_PARTIAL_CONTENT);
            assertThat(((OutputSchema) calls.get(1)).getType())
                    .isEqualTo(StreamTransformNodeHandler.STREAM_TYPE_PARTIAL_CONTENT);
            assertThat(((OutputSchema) calls.get(2)).getType())
                    .isEqualTo(StreamTransformNodeHandler.STREAM_TYPE_MESSAGE_END);
        }

        @Test
        void componentChainWithMetadataPropagation() {
            Map<String, Object> configs = conf(Map.of(
                    "node_id", "custom_node_id",
                    "node_type", "CustomType",
                    "node_name", "Custom Node Name"));
            ComponentExecutable transformer = create("st1", configs);
            NodeSessionApi session = mock(NodeSessionApi.class);
            transformer.invoke(
                    Map.of("userFields", Map.of("_input", List.of(Map.of("input", "test")))), session, null);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(session).writeStream(captor.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) ((OutputSchema) captor.getValue()).getPayload();
            assertThat(payload)
                    .containsEntry("node_id", "custom_node_id")
                    .containsEntry("node_type", "CustomType")
                    .containsEntry("node_name", "Custom Node Name");
        }

        @Test
        void componentChainWithSseFormatInput() throws Exception {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<String> streamItems = List.of(
                    "data: " + mapper.writeValueAsString(Map.of("input", "test1")),
                    "data: " + mapper.writeValueAsString(Map.of("input", "test2")));
            ComponentExecutable transformer = create("st1", conf(Map.of("parse_json_strings", true)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result =
                    invokeMap(transformer.invoke(Map.of("userFields", Map.of("_input", streamItems)), session, null));
            assertThat(result).containsKey(StreamTransformNodeHandler.USER_FIELDS);
        }

        @Test
        void componentChainWithBytesInput() throws Exception {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<byte[]> streamItems = List.of(
                    mapper.writeValueAsString(Map.of("input", "test1")).getBytes(StandardCharsets.UTF_8),
                    mapper.writeValueAsString(Map.of("input", "test2")).getBytes(StandardCharsets.UTF_8));
            ComponentExecutable transformer = create("st1", conf(Map.of("parse_json_strings", true)));
            NodeSessionApi session = mock(NodeSessionApi.class);
            Map<String, Object> result =
                    invokeMap(transformer.invoke(Map.of("userFields", Map.of("_input", streamItems)), session, null));
            assertThat(result).containsKey(StreamTransformNodeHandler.USER_FIELDS);
        }
    }
}
