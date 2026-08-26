/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowmcp.FlowMcpEngine;
import com.openjiuwen.studio.dsl.flowmcp.RecordingMcpClient;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java port of mock paths from {@code test_flow_mcp.py} via {@link FlowMcpEngine}.
 *
 * @since 2026-08-26
 */
class WorkflowNodeMcpCasesTest {
    private static final List<Map<String, Object>> MOCK_CONTENT =
            List.of(Map.of("type", "text", "text", "mock mcp response"));

    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
        FlowMcpEngine.installTestClient(RecordingMcpClient.withContent(MOCK_CONTENT));
    }

    @AfterEach
    void tearDown() {
        FlowMcpEngine.clearTestClient();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        return (Map<String, Object>) out.get("userFields");
    }

    private static Map<String, Object> mcpConf() {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("type", "sse");
        conf.put("url", "http://localhost:3000/mcp");
        conf.put("name", "weather_mcp_server");
        conf.put("tool_name", "mock_tool");
        conf.put("description", "mock");
        conf.put(
                "arguments",
                List.of(Map.of(
                        "name", "query", "description", "q", "type", "string", "required", true, "method", "Body")));
        return conf;
    }

    @Nested
    class HandlerPath {
        @Test
        void invokePutsContentAndIsError() {
            ComponentExecutable exec =
                    registry.create(AssembledNode.of("m", "jiuwen.mcp", mcpConf()), NodeBuildContext.defaults("wf"));
            Map<String, Object> fields =
                    uf(exec.invoke(Map.of("userFields", Map.of("query", "hello world")), null, null));
            assertThat(fields).containsEntry("isError", false).containsKey("content");
        }

        @Test
        void invalidConfigSurfaces() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("m", "jiuwen.mcp", Map.of("tool", "x")), NodeBuildContext.defaults("wf"));
            assertThatThrownBy(() -> exec.invoke(Map.of("userFields", Map.of()), null, null))
                    .isInstanceOf(NodeExecutionException.class);
        }

        @Test
        void startMcpEnd_linear() {
            AssembledWorkflow wf = new AssembledWorkflow(
                    "wf_mcp_linear",
                    List.of(
                            AssembledNode.of("start", "jiuwen.start", Map.of()),
                            AssembledNode.of("flow_mcp_node", "jiuwen.mcp", mcpConf()),
                            AssembledNode.of("end", "jiuwen.end", Map.of("responseTemplate", "mcp ok"))));
            Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                            registry,
                            wf,
                            NodeBuildContext.defaults("wf_mcp_linear"),
                            Map.of("userFields", Map.of("query", "hello world")),
                            null,
                            null);
            Map<String, Object> fields = uf(out);
            assertThat(fields).containsEntry("isError", false);
            assertThat(String.valueOf(fields.get("answer"))).contains("mcp ok");
        }

        @Test
        void aliasFlowMcp() {
            ComponentExecutable exec = registry.create(
                    AssembledNode.of("m", "jiuwen.flowMcp", mcpConf()), NodeBuildContext.defaults("wf"));
            assertThat(uf(exec.invoke(Map.of("userFields", Map.of("query", "q")), null, null)))
                    .containsEntry("isError", false);
        }
    }
}
