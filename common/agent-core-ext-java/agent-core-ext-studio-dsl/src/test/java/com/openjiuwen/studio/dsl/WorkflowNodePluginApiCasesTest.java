/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.external.PluginNodeHandler;
import com.openjiuwen.studio.dsl.support.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowapi.FlowApiEngine;
import com.openjiuwen.studio.dsl.flowapi.FlowApiStatusCode;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Java port of {@code test_flow_api.py} — strict 1:1 via {@link FlowApiEngine}.
 *
 * @since 2026-08-25
 */
class WorkflowNodePluginApiCasesTest {
    private static final Map<String, Object> MOCK_WEATHER =
            Map.of("latitude", 39.9, "longitude", 116.4, "current_weather", "true");
    private static final Map<String, Object> MOCK_API_SUCCESS =
            Map.of("errCode", 0, "errMessage", "success", "data", MOCK_WEATHER);
    /** Non-empty response schema → Python unwraps {@code data}. */
    private static final List<Map<String, Object>> RESPONSE_SCHEMA =
            List.of(Map.of("name", "latitude", "description", "lat"));

    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
    }

    @AfterEach
    void tearDown() {
        StudioEngineTestSupport.clear();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    private NodeBuildContext ctxWithTools(InMemoryToolRegistry tools) {
        return new NodeBuildContext(
                "wf_api",
                0,
                5,
                null,
                c -> null,
                tools,
                registry);
    }

    private static Map<String, Object> confWithResponse(Map<String, Object> extra) {
        Map<String, Object> c = new HashMap<>();
        c.put("response", RESPONSE_SCHEMA);
        if (extra != null) {
            c.putAll(extra);
        }
        return c;
    }

    @Test
    void mockResponse_unwrapsErrCodeDataEnvelope() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "weather_api",
                        "jiuwen.plugin",
                        confWithResponse(Map.of("mockResponse", MOCK_API_SUCCESS))),
                StudioEngineTestSupport.context("wf"));
        Map<String, Object> fields =
                uf(exec.invoke(Map.of("userFields", Map.of("latitude", 39.9042)), mock(NodeSessionApi.class), null));
        assertThat(fields).containsAllEntriesOf(MOCK_WEATHER);
        assertThat(fields).doesNotContainKey("errCode");
    }

    @Test
    void mockResponse_plainMap_passthrough() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("p", "jiuwen.flowApi", Map.of("mockResponse", Map.of("temp", 25))),
                StudioEngineTestSupport.context("wf"));
        assertThat(uf(exec.invoke(Map.of("userFields", Map.of()), null, null))).containsEntry("temp", 25);
    }

    @Test
    void mockResponse_emptyResponse_returnsFullEnvelope() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of("p", "jiuwen.plugin", Map.of("mockResponse", MOCK_API_SUCCESS)),
                StudioEngineTestSupport.context("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), null, null));
        assertThat(fields).containsEntry("errCode", 0);
        assertThat(fields.get("data")).isEqualTo(MOCK_WEATHER);
    }

    @Test
    void formatApiOutputs_errorCodeThrows() {
        Map<String, Object> err = new java.util.LinkedHashMap<>();
        err.put("errCode", 105001);
        err.put("errMessage", "internal error");
        err.put("data", null);
        assertThatThrownBy(() -> PluginNodeHandler.formatApiOutputs(err))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("105001");
    }

    @Test
    void formatApiOutputs_outerErrorRemappedToExecuteError() {
        Map<String, Object> err = new java.util.LinkedHashMap<>();
        err.put("errCode", 1);
        err.put("errMessage", "boom");
        assertThatThrownBy(() -> PluginNodeHandler.formatApiOutputs(err))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("pythonErrorCode=" + FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR.code());
    }

    @Test
    void formatApiOutputs_successReturnsData() {
        assertThat(PluginNodeHandler.formatApiOutputs(
                        Map.of("errCode", 0, "errMessage", "success", "data", Map.of("temp", 25))))
                .isEqualTo(Map.of("temp", 25));
    }

    @Test
    void exceptionSuppression_returnsFallback() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "p",
                        "jiuwen.plugin",
                        confWithResponse(Map.of(
                                "mockResponse",
                                Map.of("errCode", 105001, "errMessage", "boom"),
                                "exceptionEnable",
                                true,
                                "exceptionSuppression",
                                "{\"fallback\": \"default_value\"}"))),
                StudioEngineTestSupport.context("wf"));
        Map<String, Object> fields = uf(exec.invoke(Map.of("userFields", Map.of()), null, null));
        assertThat(fields).containsEntry("fallback", "default_value");
        assertThat(fields).doesNotContainKey("exceptionSuppressed");
    }

    @Test
    void toolRegistry_invokeWeatherLike() throws Exception {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        ToolCard card = ToolCard.builder().id("weather_api").name("get_weather").description("wx").build();
        tools.register("weather_api", new Tool(card) {
            @Override
            public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return MOCK_API_SUCCESS;
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return List.of().iterator();
            }
        });
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "flow_api_node",
                        "jiuwen.plugin",
                        confWithResponse(Map.of("apiId", "weather_api", "name", "get_weather"))),
                ctxWithTools(tools));
        Map<String, Object> fields = uf(exec.invoke(
                Map.of(
                        "userFields",
                        Map.of("latitude", 39.9042, "longitude", 116.4074, "current_weather", "true")),
                mock(NodeSessionApi.class),
                null));
        assertThat(fields).containsEntry("latitude", 39.9).containsEntry("current_weather", "true");
    }

    @Test
    void needValidate_missingParams_interacts() {
        NodeSessionApi session = mock(NodeSessionApi.class);
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "p",
                        "jiuwen.plugin",
                        confWithResponse(Map.of(
                                "mockResponse",
                                MOCK_API_SUCCESS,
                                "needValidate",
                                true,
                                "arguments",
                                List.of(Map.of(
                                        "name", "city",
                                        "description", "city name",
                                        "type", "string"))))),
                StudioEngineTestSupport.context("wf"));
        // missing city → interact; mock session returns null → continue then may still call API
        // GraphInterrupt not thrown by mock → invoke continues after interact
        uf(exec.invoke(Map.of("userFields", Map.of()), session, null));
        verify(session).interact(any());
    }

    @Test
    void getAuthToken_userScopeAddsHeader() {
        FlowApiEngine engine = new FlowApiEngine("n1");
        engine.init(Map.of(
                "mockResponse",
                Map.of("ok", true),
                "auth",
                Map.of(
                        "scope",
                        "USER",
                        "target",
                        Map.of("domain", "headers", "auth_keys", List.of("X-Auth-Token")))));
        assertThat(engine.getAuthToken()).containsEntry("X-Auth-Token", FlowApiEngine.PYTHON_PARITY_AUTH_TOKEN_PLACEHOLDER);
        assertThat(engine.getAuthToken(Map.of("X-Auth-Token", "tenant|42"))).isNull();
    }

    @Test
    void stream_yieldsStreamingThenFinish() {
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "p",
                        "jiuwen.plugin",
                        Map.of(
                                "mockResponse",
                                Map.of("errCode", 0, "data", Map.of("chunk", "hi")),
                                "userFields",
                                Map.of("outputs", List.of(Map.of("id", "out", "required", true))))),
                StudioEngineTestSupport.context("wf"));
        Iterator<Object> it = exec.stream(Map.of("userFields", Map.of()), mock(NodeSessionApi.class), null);
        List<Object> frames = new java.util.ArrayList<>();
        it.forEachRemaining(frames::add);
        assertThat(frames).isNotEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> last = (Map<String, Object>) frames.get(frames.size() - 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) last.get("__stream_metadata__");
        assertThat(meta).containsEntry("messages_type", "finish");
    }
}
