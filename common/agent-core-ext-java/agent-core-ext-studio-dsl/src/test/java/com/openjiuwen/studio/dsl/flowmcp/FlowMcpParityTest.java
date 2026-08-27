/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parity tests vs Python {@code test_flow_mcp.py} (mock client paths).
 *
 * @since 2026-08-26
 */

class FlowMcpParityTest {
    private static final List<Map<String, Object>> MOCK_CONTENT =
            List.of(Map.of("type", "text", "text", "mock mcp response"));

    private RecordingMcpClient stubClient;

    @BeforeEach
    void setUp() {
        stubClient = RecordingMcpClient.withContent(MOCK_CONTENT);
    }
    private FlowMcpEngine engine() {
        return new FlowMcpEngine("n1", stubClient);
    }
    private static Map<String, Object> makeConf(boolean olderVersion) {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("type", "sse");
        conf.put("url", "http://localhost:3000/mcp");
        conf.put("name", "weather_mcp_server");
        conf.put("tool_name", "mock_tool");
        conf.put("description", "A mock MCP tool for testing");
        conf.put("headers", Map.of("Authorization", "Bearer test-token"));
        conf.put(
                "auth",
                Map.of("headers", Map.of(), "query", Map.of("key", "xxx"), "scope", "SERVICE"));
        conf.put("pluginDependency", Map.of());
        if (!olderVersion) {
            conf.put(
                    "arguments",
                    List.of(Map.of(
                            "name",
                            "query",
                            "description",
                            "查询内容",
                            "type",
                            "string",
                            "required",
                            true,
                            "method",
                            "Body")));
        }
        return conf;
    }

    @Nested
    class InitAndInvoke {
        @Test
        void initCreatesClient_newVersion() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            assertThat(engine.client()).isNotNull();
            assertThat(engine.isOlderVersion()).isFalse();
        }

        @Test
        void initOlderVersion() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(true));
            assertThat(engine.isOlderVersion()).isTrue();
        }

        @Test
        void directInvoke_newVersion_skipsListTools() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            Map<String, Object> result =
                    engine.invoke(Map.of("userFields", Map.of("query", "hello world")), null, null);
            assertThat(result).containsKey("userFields");
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) result.get("userFields");
            assertThat(uf).containsEntry("isError", false).containsKey("content");
            assertThat(stubClient.listToolsCalls()).isZero();
            assertThat(stubClient.callToolCalls()).isEqualTo(1);
        }

        @Test
        void olderVersion_callsListTools() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(true));
            engine.invoke(Map.of("userFields", Map.of("query", "hello", "extra_field", "x")), null, null);
            assertThat(stubClient.listToolsCalls()).isEqualTo(1);
            assertThat(stubClient.callToolCalls()).isEqualTo(1);
        }

        @Test
        void stdio_returnsDefaultWithoutClient() {
            Map<String, Object> conf = makeConf(false);
            conf.put("type", "stdio");
            FlowMcpEngine engine = engine();
            engine.init(conf);
            Map<String, Object> result = engine.invoke(Map.of("userFields", Map.of("query", "t")), null, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> uf = (Map<String, Object>) result.get("userFields");
            assertThat(String.valueOf(uf.get("content"))).contains("not contain mcp api");
            assertThat(uf).containsEntry("isError", false);
        }
    }

    @Nested
    class FormatAndValidate {
        @Test
        void formatOutputs_list() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            Map<String, Object> out = engine.formatApiOutputs(Map.of(
                    "errCode",
                    0,
                    "data",
                    List.of(Map.of("type", "text", "text", "mock mcp response")),
                    "errMessage",
                    "success"));
            assertThat(out).containsEntry("isError", false);
            assertThat(out.get("content")).isInstanceOf(List.class);
        }

        @Test
        void formatOutputs_dict() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            Map<String, Object> out = engine.formatApiOutputs(
                    Map.of("errCode", 0, "data", Map.of("structured_key", "structured_value"), "errMessage", "success"));
            assertThat(out).containsEntry("structured_key", "structured_value");
        }

        @Test
        void formatOutputs_none() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errCode", 0);
            data.put("data", null);
            data.put("errMessage", "success");
            assertThat(engine.formatApiOutputs(data)).isEqualTo(Map.of("content", List.of(), "isError", false));
        }

        @Test
        void formatOutputs_unexpectedType() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            assertThatThrownBy(() -> engine.formatApiOutputs(Map.of("errCode", 0, "data", "unexpected_string")))
                    .isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void formatApiInputs_ok() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            assertThat(engine.formatApiInputs(Map.of("query", "hello"))).isEqualTo(Map.of("query", "hello"));
        }

        @Test
        void formatApiInputs_unknownParam() {
            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            assertThatThrownBy(() -> engine.formatApiInputs(Map.of("unknown_param", "v")))
                    .isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void validate_unsupportedType() {
            Map<String, Object> conf = makeConf(false);
            conf.put("type", "unsupported");
            assertThatThrownBy(() -> new FlowMcpEngine("n1").init(conf)).isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void validate_missingUrl() {
            Map<String, Object> conf = makeConf(false);
            conf.remove("url");
            assertThatThrownBy(() -> new FlowMcpEngine("n1").init(conf)).isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void validate_missingToolName() {
            Map<String, Object> conf = makeConf(false);
            conf.remove("tool_name");
            assertThatThrownBy(() -> new FlowMcpEngine("n1").init(conf)).isInstanceOf(NodeExecutionException.class);
        }
    }

    @Nested
    class RuntimeAuth {
        @Test
        void runtimeAuthInjectedAndPoppedFromCallArgs() {
            Map<String, Object> global = new LinkedHashMap<>();
            global.put(
                    "runtime_auth_headers",
                    Map.of(
                            "mock_tool",
                            Map.of("X-Custom-Auth", "token123"),
                            "default",
                            Map.of("X-Default", "default_token")));
            com.openjiuwen.core.session.internal.WorkflowSession wf =
                    new com.openjiuwen.core.session.internal.WorkflowSession(
                            "wf",
                            null,
                            "sess",
                            com.openjiuwen.core.session.state.InMemoryState.create(null, global, null, null, null),
                            null);
            NodeSessionApi session =
                    new NodeSessionApi(new com.openjiuwen.core.session.internal.NodeSession(wf, "n1"));

            FlowMcpEngine engine = engine();
            engine.init(makeConf(false));
            engine.invoke(Map.of("userFields", Map.of("query", "hello")), session, null);

            assertThat(stubClient.lastCallArguments()).doesNotContainKey(FlowMcpEngine.JIUWEN_RUNTIME_KWARGS);
            assertThat(stubClient.lastCallArguments()).containsEntry("query", "hello");
        }
    }
}
