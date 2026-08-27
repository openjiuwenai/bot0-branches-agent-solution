/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpClientFactory;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.foundation.tool.mcp.McpTool;
import com.openjiuwen.core.foundation.tool.mcp.McpToolCard;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * FlowMcp engine — strict 1:1 with Python {@code flow_mcp.FlowMcp}.
 *
 * <p>Uses core {@link McpClient} ({@code sse} / {@code streamable_http}) + {@link McpTool}. Tests inject a stub
 * {@link McpClient} via constructor or {@link com.openjiuwen.studio.dsl.exec.StudioEngineTestOverrides}.
 *
 * @since 2026-08-26
 */
public final class FlowMcpEngine {
    public static final String USER_FIELDS = "userFields";
    /** Python {@code JIUWEN_RUNTIME_KWARGS}. */
    public static final String JIUWEN_RUNTIME_KWARGS = "_jiuwen_runtime_kwargs";

    private static final String ERR_CODE = "errCode";
    private static final String ERR_MESSAGE = "errMessage";
    private static final String RESTFUL_DATA = "data";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String nodeId;
    private final McpClient presetMcpClient;
    private Map<String, Object> conf = Map.of();
    private McpClient client;
    private List<McpToolParam> toolParams = List.of();
    private McpTool api;
    private boolean olderVersion;
    private Map<String, Object> headerParams = Map.of();
    private final ReentrantLock initLock = new ReentrantLock();

    public FlowMcpEngine(String nodeId) {
        this(nodeId, null);
    }

    /** Test constructor — preset MCP client (mirrors Python patch on SSEClientNew). */
    public FlowMcpEngine(String nodeId, McpClient presetMcpClient) {
        this.nodeId = nodeId == null ? "mcp" : nodeId;
        this.presetMcpClient = presetMcpClient;
    }

    /** Python {@code init}. */
    public void init(Map<String, Object> conf) {
        Map<String, Object> c = conf == null ? Map.of() : new LinkedHashMap<>(conf);
        validateConfigs(c);
        this.conf = c;
        this.olderVersion = isOlderVersion(c.get("arguments"));
        this.api = null;
        this.headerParams = Map.of();
        this.client = null;
        this.toolParams = List.of();
        createClient(c);
    }

    public boolean isOlderVersion() {
        return olderVersion;
    }

    public McpClient client() {
        return client;
    }

    public McpTool api() {
        return api;
    }

    /** Python {@code invoke}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        if (client == null) {
            Map<String, Object> uf = new LinkedHashMap<>();
            uf.put("content", "not contain mcp api, not run");
            uf.put("isError", false);
            return Map.of(USER_FIELDS, uf);
        }

        if (api == null) {
            initLock.lock();
            try {
                if (api == null) {
                    initApi();
                }
            } finally {
                initLock.unlock();
            }
        }

        Map<String, Object> inputsData = userFieldsOf(inputs);
        Map<String, Object> apiInputs;
        if (!olderVersion) {
            apiInputs = formatApiInputs(inputsData);
        } else {
            apiInputs = new LinkedHashMap<>(inputsData);
        }

        Map<String, Object> jiuwenKwargs = new LinkedHashMap<>();
        Map<String, String> runtimeAuth = formatRuntimeAuth(session);
        if (!runtimeAuth.isEmpty()) {
            jiuwenKwargs.put("runtime_auth", Map.of("headers", runtimeAuth));
        }
        Object runtimeContextHeader = getRuntimeContextHeader(session);
        if (runtimeContextHeader != null) {
            jiuwenKwargs.put("runtime_context", runtimeContextHeader);
        }
        if (headerParams != null && !headerParams.isEmpty()) {
            Map<String, Object> ra = (Map<String, Object>) jiuwenKwargs.computeIfAbsent("runtime_auth", k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("headers", new LinkedHashMap<String, Object>());
                return m;
            });
            Map<String, Object> headers =
                    (Map<String, Object>) ra.computeIfAbsent("headers", k -> new LinkedHashMap<>());
            headers.putAll(headerParams);
        }
        if (!jiuwenKwargs.isEmpty()) {
            apiInputs = new LinkedHashMap<>(apiInputs);
            apiInputs.put(JIUWEN_RUNTIME_KWARGS, jiuwenKwargs);
        }

        try {
            Object result = api.invoke(apiInputs);
            Map<String, Object> apiOutputs = convertMcpResult(result);
            Map<String, Object> outputs = formatApiOutputs(apiOutputs);
            return Map.of(USER_FIELDS, outputs);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw createErrorResponse(e);
        }
    }

    /** Package-visible for parity tests — Python {@code _format_api_outputs}. */
    @SuppressWarnings("unchecked")
    Map<String, Object> formatApiOutputs(Map<String, Object> outputs) {
        Object errorCodeObj = outputs.getOrDefault(ERR_CODE, FlowMcpStatusCode.WORKFLOW_MCP_EXECUTE_ERROR_CODE);
        int errorCode = errorCodeObj instanceof Number n
                ? n.intValue()
                : FlowMcpStatusCode.WORKFLOW_MCP_EXECUTE_ERROR_CODE;
        if (errorCode != FlowMcpStatusCode.SUCCESS_CODE) {
            Object errorMsg = outputs.getOrDefault(
                    ERR_MESSAGE, "plugin execution error, and no error information is specified");
            if (errorCode < 105000 || errorCode > 105999) {
                errorMsg = "plugin flow execute inner failed, errCode="
                        + errorCode
                        + ", errMessage="
                        + (errorMsg == null ? "null" : errorMsg.getClass().getName());
                errorCode = FlowMcpStatusCode.WORKFLOW_API_EXECUTE_ERROR_CODE;
            }
            throw new NodeExecutionException(
                    nodeId, "jiuwen.mcp", NodeCauseCode.NODE_INVOKE_FAILED, String.valueOf(errorMsg));
        }

        Object outputData = outputs.get(RESTFUL_DATA);
        if (outputData == null) {
            return Map.of("content", List.of(), "isError", false);
        }
        if (outputData instanceof List<?> list) {
            List<Object> responseData = new ArrayList<>();
            for (Object item : list) {
                responseData.add(modelDumpOrSelf(item));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("content", responseData);
            out.put("isError", false);
            return out;
        }
        if (outputData instanceof Map<?, ?> dict) {
            Map<String, Object> asMap = castMap(dict);
            try {
                Object userFields = conf.get("userFields");
                Object outputsCfg = userFields instanceof Map<?, ?> uf ? uf.get("outputs") : null;
                java.util.Set<Object> outputIds = new java.util.HashSet<>();
                if (outputsCfg instanceof List<?> outs) {
                    for (Object o : outs) {
                        if (o instanceof Map<?, ?> m && m.get("id") != null) {
                            outputIds.add(m.get("id"));
                        }
                    }
                }
                if (outputIds.contains("content") && !asMap.containsKey("content")) {
                    Map<String, Object> wrapped = new LinkedHashMap<>();
                    wrapped.put("type", "text");
                    wrapped.put("text", MAPPER.writeValueAsString(asMap));
                    return Map.of("content", List.of(wrapped), "isError", false);
                }
            } catch (Exception ignored) {
                // soft-fail like Python bare except
            }
            return asMap;
        }
        throw FlowMcpErrors.of(
                nodeId,
                FlowMcpStatusCode.WORKFLOW_MCP_OUTPUTS_TYPE_ERROR,
                Map.of(
                        "msg",
                        "Expected list if 'content' field of CallToolResult is passed, "
                                + "or expected dict if 'structuredContent' field of CallToolResult is passed, "
                                + "got type: "
                                + (outputData == null ? "null" : outputData.getClass().getName())));
    }

    /** Package-visible for parity tests — Python {@code _format_api_inputs}. */
    Map<String, Object> formatApiInputs(Map<String, Object> inputs) {
        Map<String, McpToolParam> byName = new LinkedHashMap<>();
        for (McpToolParam p : toolParams) {
            byName.put(p.name(), p);
        }
        Map<String, Object> apiInputs = new LinkedHashMap<>();
        Map<String, Object> headers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            String name = e.getKey();
            McpToolParam param = byName.get(name);
            if (param == null) {
                throw FlowMcpErrors.of(nodeId, FlowMcpStatusCode.WORKFLOW_MCP_INPUTS_ERROR, Map.of("param", name));
            }
            if (McpToolParam.METHOD_HEADERS.equals(param.method())) {
                headers.put(name, TypeTransform.transform(e.getValue(), param.type(), name));
            } else {
                apiInputs.put(name, TypeTransform.transform(e.getValue(), param.type(), name));
            }
        }
        this.headerParams = headers;
        return apiInputs;
    }

    private void initApi() {
        if (client == null) {
            return;
        }
        String toolName = str(conf.get("tool_name"));
        if (toolName.isBlank()) {
            toolName = str(conf.get("toolName"));
        }
        String serverName = str(conf.get("name"));
        try {
            ensureConnected();
            McpToolCard matchedCard;
            if (olderVersion) {
                matchedCard = fetchCardFromServer(toolName, serverName);
            } else {
                matchedCard = McpToolCard.builder()
                        .id(toolName.isBlank() ? "mcp-tool" : toolName)
                        .name(toolName)
                        .serverName(serverName)
                        .description(str(conf.get("description")))
                        .inputParams(buildInputParamsFromArguments())
                        .build();
            }
            this.api = new McpTool(client, matchedCard);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw FlowMcpErrors.execute(nodeId, e.getClass().getSimpleName());
        }
    }

    private McpToolCard fetchCardFromServer(String toolName, String serverName) throws Exception {
        ensureConnected();
        List<Object> toolCards = client.listTools();
        for (Object card : toolCards) {
            if (card instanceof McpToolCard c && toolName.equals(c.getName())) {
                if (c.getId() == null || c.getId().isBlank()) {
                    c.setId(toolName);
                }
                return c;
            }
        }
        return McpToolCard.builder()
                .id(toolName.isBlank() ? "mcp-tool" : toolName)
                .name(toolName)
                .serverName(serverName)
                .description(str(conf.get("description")))
                .inputParams(null)
                .build();
    }

    private Map<String, Object> buildInputParamsFromArguments() {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (McpToolParam param : toolParams) {
            if (McpToolParam.METHOD_HEADERS.equals(param.method())) {
                continue;
            }
            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", param.type());
            prop.put("description", param.description());
            prop.put("default", param.defaultValue());
            properties.put(param.name(), prop);
            if (param.required()) {
                required.add(param.name());
            }
        }
        properties.put(JIUWEN_RUNTIME_KWARGS, Map.of("type", "object"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", true);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> convertMcpResult(Object result) {
        Object callResult = result;
        if (result instanceof Map<?, ?> m) {
            if (m.containsKey("result")) {
                callResult = m.get("result");
            }
        }
        Object data;
        Object structured = reflectGet(callResult, "getStructuredContent", "structuredContent");
        if (structured != null) {
            data = structured;
        } else {
            Object content = reflectGet(callResult, "getContent", "content");
            data = content != null ? content : callResult;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ERR_CODE, 0);
        out.put(ERR_MESSAGE, "success");
        out.put(RESTFUL_DATA, data);
        return out;
    }

    private NodeExecutionException createErrorResponse(Exception error) {
        String errorType = error.getClass().getSimpleName();
        // Python ExceptionGroup → "ExceptionGroup"; otherwise "Exception"
        if (error.getClass().getName().contains("ExceptionGroup")) {
            errorType = "ExceptionGroup";
        } else {
            errorType = "Exception";
        }
        return FlowMcpErrors.execute(nodeId, errorType);
    }

    private void createClient(Map<String, Object> conf) {
        String mcpType = str(conf.get("type"));
        if (!"sse".equals(mcpType) && !"streamable_http".equals(mcpType)) {
            return;
        }
        IrToMcpServerConfig.Converted converted = IrToMcpServerConfig.convert(conf);
        McpServerConfig config = converted.config();
        Map<String, String> headers = config.getAuthHeaders();
        if (headers != null) {
            Map<String, String> extended = IrToMcpServerConfig.extendHeaders(new LinkedHashMap<>(headers), conf);
            headers.clear();
            headers.putAll(extended);
        }
        this.toolParams = converted.toolParams();

        if (presetMcpClient != null) {
            this.client = new RuntimeAwareMcpClient(presetMcpClient, config, toolParams);
            return;
        }
        try {
            McpClient raw = McpClientFactory.create(config);
            this.client = new RuntimeAwareMcpClient(raw, config, toolParams);
        } catch (UnsupportedOperationException e) {
            throw FlowMcpErrors.config(
                    nodeId,
                    FlowMcpStatusCode.WORKFLOW_MCP_UNSUPPORTED_TYPE_ERROR,
                    Map.of("mcp_type", config.getClientType()));
        }
    }

    private void ensureConnected() throws Exception {
        if (client != null) {
            client.connect();
        }
    }

    private void validateConfigs(Map<String, Object> config) {
        String mcpType = str(config.get("type"));
        if (!List.of("sse", "streamable_http", "stdio").contains(mcpType)) {
            throw FlowMcpErrors.config(
                    nodeId, FlowMcpStatusCode.WORKFLOW_MCP_UNSUPPORTED_TYPE_ERROR, Map.of("mcp_type", mcpType));
        }
        if ("sse".equals(mcpType) || "streamable_http".equals(mcpType)) {
            boolean hasUrl = config.containsKey("url") && config.get("url") != null && !str(config.get("url")).isBlank();
            boolean hasTool = (config.containsKey("tool_name") && config.get("tool_name") != null)
                    || (config.containsKey("toolName") && config.get("toolName") != null);
            if (!hasUrl || !hasTool) {
                throw FlowMcpErrors.config(
                        nodeId,
                        FlowMcpStatusCode.WORKFLOW_MCP_FIELD_EMPTY_TYPE_ERROR,
                        Map.of("field", "url or tool_name", "mcp_type", mcpType));
            }
        }
        // validate tool_params methods when present (Python _create_client)
        if ("sse".equals(mcpType) || "streamable_http".equals(mcpType)) {
            Object args = config.get("arguments");
            if (args instanceof List<?>) {
                IrToMcpServerConfig.paramDeserialization(args, true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> formatRuntimeAuth(NodeSessionApi session) {
        Object allHeaders = getWorkflowParam(session, "runtime_auth_headers");
        if (!(allHeaders instanceof Map<?, ?> m)) {
            return Map.of();
        }
        String toolName = str(conf.get("tool_name"));
        if (toolName.isBlank()) {
            toolName = str(conf.get("toolName"));
        }
        Object forTool = m.get(toolName);
        if (!(forTool instanceof Map<?, ?>)) {
            forTool = m.get("default");
        }
        if (!(forTool instanceof Map<?, ?> headers)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return out;
    }

    private Object getRuntimeContextHeader(NodeSessionApi session) {
        if (session == null) {
            return null;
        }
        return getWorkflowParam(session, "api_config");
    }

    private static Object getWorkflowParam(NodeSessionApi session, String key) {
        if (session == null) {
            return null;
        }
        try {
            return session.getGlobalState(key);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Python: {@code not conf.get("arguments")} — missing / empty list / null → older. */
    private static boolean isOlderVersion(Object arguments) {
        if (arguments == null) {
            return true;
        }
        if (arguments instanceof List<?> list) {
            return list.isEmpty();
        }
        // non-list present (legacy thin Map) → treat as newer schema path with no tool_params
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            return castMap(m);
        }
        return new LinkedHashMap<>(inputs);
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static Object modelDumpOrSelf(Object item) {
        if (item == null) {
            return null;
        }
        Object dumped = reflectGet(item, "modelDump", null);
        if (dumped == null) {
            dumped = reflectInvoke(item, "model_dump");
        }
        return dumped != null ? dumped : item;
    }

    private static Object reflectGet(Object target, String getter, String field) {
        if (target == null) {
            return null;
        }
        Object viaGetter = reflectInvoke(target, getter);
        if (viaGetter != null) {
            return viaGetter;
        }
        if (field != null && target instanceof Map<?, ?> m) {
            return m.get(field);
        }
        return null;
    }

    private static Object reflectInvoke(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
