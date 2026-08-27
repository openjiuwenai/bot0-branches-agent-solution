/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.graph.pregel.GraphInterrupt;
import com.openjiuwen.core.session.interaction.WorkflowInteraction;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.util.OutboundUrlSafety;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict 1:1 of Python {@code flow_api.FlowApi}.
 *
 * <p>Backend resolution: {@code mockResponse} → ToolRegistry({@code apiId}) → IR HTTP ({@code url}).
 * Tests inject {@link TestBridge} via constructor or {@link com.openjiuwen.studio.dsl.exec.StudioEngineTestOverrides}.
 *
 * @since 2026-08-26
 */

public final class FlowApiEngine {

    /**
     * USER_FIELDS.
     *
     * @since 0.1.0
     */

    public static final String USER_FIELDS = "userFields";

    /**
     * EXCEPTION_ENABLE.
     *
     * @since 0.1.0
     */

    public static final String EXCEPTION_ENABLE = "exceptionEnable";

    /**
     * EXCEPTION_SUPPRESSION.
     *
     * @since 0.1.0
     */

    public static final String EXCEPTION_SUPPRESSION = "exceptionSuppression";

    /**
     * OLD_IR_PLUGIN_RESPONSE.
     *
     * @since 0.1.0
     */

    public static final String OLD_IR_PLUGIN_RESPONSE = "raw_output";

    /**
     * PLUGIN_PARAM_MISS.
     *
     * @since 0.1.0
     */

    public static final String PLUGIN_PARAM_MISS = "plugin_param_miss";

    /**
     * PLUGIN_CALL_CONFIRM.
     *
     * @since 0.1.0
     */

    public static final String PLUGIN_CALL_CONFIRM = "plugin_call_confirm";

    /**
     * Python {@code flow_api.get_auth_token} parity placeholder when IR requires USER-scope
     * {@code X-Auth-Token}. Production hosts must inject a real token via session workflow param
     * {@code runtime_auth_headers} before invoke (see README).
     */

    public static final String PYTHON_PARITY_AUTH_TOKEN_PLACEHOLDER = "defaultUser|0";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * * * Test stub for ainvoke/astream (mirrors patching RestfulApiToolNew).
     */
    public interface TestBridge {
        Map<String, Object> ainvoke(String apiId, Map<String, Object> inputs, Map<String, String> headers);

        default Iterator<Object> astream(String apiId, Map<String, Object> inputs, Map<String, String> headers) {
            return Collections.emptyIterator();
        }
    }

    private final String nodeId;
    private final TestBridge presetBridge;
    private Map<String, Object> conf = Map.of();
    private boolean enableValidate;
    private boolean enableConfirm;
    private boolean streaming;
    private String apiId = "";
    private String apiName = "";
    private List<FlowApiParam> params = List.of();
    private List<FlowApiParam> response = List.of();
    private Map<String, Object> userFieldsConf = Map.of();
    private ToolRegistry toolRegistry;
    private Object mockResponse;
    private WorkflowMetadata metadata = WorkflowMetadata.EMPTY;

    /**
     * FlowApiEngine.
     *
     * @param nodeId nodeId
     * @since 0.1.0
     */

    public FlowApiEngine(String nodeId) {
        this(nodeId, null);
    }

    /**
     * FlowApiEngine.
     *
     * @param nodeId nodeId
     * @param bridge bridge
     * @since 0.1.0
     */

    public FlowApiEngine(String nodeId, TestBridge bridge) {
        this.nodeId = nodeId == null ? "plugin" : nodeId;
        this.presetBridge = bridge;
    }

    /**
     * setToolRegistry.
     *
     * @param toolRegistry toolRegistry
     * @since 0.1.0
     */

    public void setToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Python {@code init} / {@code _init_from_conf}.
     *
     * @param conf conf
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public void init(Map<String, Object> conf) {
        Map<String, Object> c = conf == null ? Map.of() : new LinkedHashMap<>(conf);
        this.conf = c;
        this.enableValidate = bool(c.get("needValidate"));
        this.enableConfirm = bool(c.get("needConfirm"));
        this.streaming = bool(c.get("streaming"));
        Object uf = c.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            this.userFieldsConf = copy;
        } else {
            this.userFieldsConf = Map.of();
        }
        this.metadata = WorkflowMetadata.fromConf(c, nodeId);
        this.mockResponse = c.get("mockResponse");
        this.params = parseParams(c);
        this.response = parseResponse(c);
        this.apiName = firstNonBlank(str(c.get("name")), str(c.get("plugin_name")), nodeId);

        Object idObj = c.get("apiId");
        if (idObj == null || str(idObj).isBlank()) {
            this.apiId = str(c.get("id"));
        } else {
            this.apiId = str(idObj);
            // Python: Runner.resource_mgr.get_tool — fail fast when registry is present
            if (this.mockResponse == null
                    && presetBridge == null
                    && toolRegistry != null
                    && toolRegistry.find(this.apiId).isEmpty()) {
                throw FlowApiErrors.of(
                        nodeId,
                        FlowApiStatusCode.WORKFLOW_API_INIT_ERROR,
                        "cannot find apiId[" + this.apiId + "]");
            }
        }
    }

    /**
     * apiId.
     *
     * @return result
     * @since 0.1.0
     */

    public String apiId() {
        return apiId;
    }

    /**
     * params.
     *
     * @return result
     * @since 0.1.0
     */

    public List<FlowApiParam> params() {
        return params;
    }

    /**
     * response.
     *
     * @return result
     * @since 0.1.0
     */

    public List<FlowApiParam> response() {
        return response;
    }

    /**
     * Python {@code invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        try {
            boolean validated = bool(inputs == null ? null : inputs.get("validated"));
            boolean confirmed = bool(inputs == null ? null : inputs.get("confirmed"));
            Map<String, Object> inputsData = userFieldsOf(inputs);

            Map<String, Object> formattedInputs = formatApiInputs(
                    inputsData,
                    session,
                    enableValidate && !validated,
                    enableConfirm && !confirmed);

            Map<String, String> headers = formatApiHeader(session);
            Map<String, String> auth = getAuthToken(headers);
            if (auth != null) {
                headers = new LinkedHashMap<>(headers);
                headers.putAll(auth);
            }

            Map<String, Object> apiOutputs = ainvokeBackend(formattedInputs, headers);
            Map<String, Object> formatted = formatApiOutputs(apiOutputs);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(USER_FIELDS, formatted);
            return out;
        } catch (NodeExecutionException e) {
            if (errIgnore()) {
                return Map.of(USER_FIELDS, loadJson(conf.get(EXCEPTION_SUPPRESSION)));
            }
            throw e;
        } catch (RuntimeException e) {
            if (errIgnore()) {
                return Map.of(USER_FIELDS, loadJson(conf.get(EXCEPTION_SUPPRESSION)));
            }
            throw FlowApiErrors.of(nodeId, FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR, "");
        } catch (Exception e) {
            if (errIgnore()) {
                return Map.of(USER_FIELDS, loadJson(conf.get(EXCEPTION_SUPPRESSION)));
            }
            throw FlowApiErrors.of(nodeId, FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR, "");
        }
    }

    /**
     * * Python {@code stream} — yields userFields frames (+ optional OutputSchema error on suppress).
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */

    public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
        List<Object> frames = new ArrayList<>();
        try {
            Map<String, Object> in = asMap(inputs);
            Map<String, Object> inputsData = userFieldsOf(in);
            Map<String, Object> apiInputs = formatApiInputs(inputsData, session, false, false);
            Map<String, String> headers = formatApiHeader(session);
            Map<String, String> auth = getAuthToken(headers);
            if (auth != null) {
                headers = new LinkedHashMap<>(headers);
                headers.putAll(auth);
            }
            Iterator<Object> apiOutputs = astreamBackend(apiInputs, headers);
            transformAsyncStreamData(apiOutputs, session).forEachRemaining(frames::add);
        } catch (NodeExecutionException e) {
            if (errIgnore()) {
                frames.add(new OutputSchema(
                        "error", 0, Map.of(USER_FIELDS, loadJson(conf.get(EXCEPTION_SUPPRESSION)))));
            }
            throw e;
        } catch (RuntimeException e) {
            if (errIgnore()) {
                frames.add(new OutputSchema(
                        "error", 0, Map.of(USER_FIELDS, loadJson(conf.get(EXCEPTION_SUPPRESSION)))));
            }
            throw FlowApiErrors.of(nodeId, FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR, "");
        } catch (Exception e) {
            if (errIgnore()) {
                frames.add(new OutputSchema(
                        "error", 0, Map.of(USER_FIELDS, loadJson(conf.get(EXCEPTION_SUPPRESSION)))));
            }
            throw FlowApiErrors.of(nodeId, FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR, "");
        }
        return frames.iterator();
    }

    /**
     * Python {@code _format_api_outputs} (static-friendly for IntentFaqMatcher).
     *
     * @param outputs raw plugin output
     * @param hasResponse whether API declares response schema ({@code if self._api.response})
     */

    @SuppressWarnings("unchecked")
    public Map<String, Object> formatApiOutputs(Object outputs, boolean hasResponse) {
        if (!(outputs instanceof Map<?, ?> m)) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put(OLD_IR_PLUGIN_RESPONSE, outputs);
            return wrap;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        m.forEach((k, v) -> map.put(String.valueOf(k), v));
        if (!map.containsKey("errCode")) {
            return map;
        }
        Object codeObj = map.get("errCode");
        int errCode = codeObj instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(codeObj));
        if (errCode != FlowApiStatusCode.SUCCESS.code()) {
            String errorMsg = str(map.getOrDefault(
                    "errMessage", "plugin execution error, and no error information is specified"));
            if (errCode < 105000 || errCode > 105999) {
                errorMsg = "plugin flow execute inner failed, errCode=" + errCode + ", errMessage=" + errorMsg;
                errCode = FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR.code();
            }
            throw FlowApiErrors.ofCode(nodeId, errCode, errorMsg);
        }
        if (hasResponse) {
            Object data = map.get("data");
            if (!(data instanceof Map<?, ?>)) {
                throw FlowApiErrors.of(nodeId, FlowApiStatusCode.WORKFLOW_API_OUTPUTS_ERROR, "");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<?, ?>) data).forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return map;
    }

    /**
     * Convenience: use engine response schema.
     *
     * @param outputs outputs
     * @return result
     * @since 0.1.0
     */

    public Map<String, Object> formatApiOutputs(Object outputs) {

        /**
         * formatApiOutputs.
         *
         * @param outputs outputs
         * @return result
         * @since 0.1.0
         */

        return formatApiOutputs(outputs, !response.isEmpty());
    }

    /**
     * * Static unwrap used by callers that only have a raw envelope (treat as hasResponse when data present).
     *
     * @param outputs outputs
     * @return result
     * @since 0.1.0
     */

    public static Map<String, Object> formatApiOutputsStatic(Object outputs) {
        FlowApiEngine tmp = new FlowApiEngine("plugin");
        boolean hasResponse = false;
        if (outputs instanceof Map<?, ?> m && m.get("data") instanceof Map<?, ?>) {
            hasResponse = true;
        }
        return tmp.formatApiOutputs(outputs, hasResponse);
    }

    Map<String, Object> formatApiInputs(
            Map<String, Object> inputs,
            NodeSessionApi session,
            boolean needValidate,
            boolean needConfirm) {
        Map<String, Object> in = inputs == null ? Map.of() : inputs;
        if (needValidate && session != null) {
            waitForRequiredParams(in, params, session);
        }
        if (needConfirm && session != null) {
            waitForUserConfirmation(in, params, session);
        }
        if (params.isEmpty()) {
            Map<String, Object> passthrough = new LinkedHashMap<>();
            in.forEach((k, v) -> {
                if (v != null) {
                    passthrough.put(k, v);
                }
            });
            return passthrough;
        }
        Map<String, FlowApiParam> byName = new LinkedHashMap<>();
        for (FlowApiParam p : params) {
            byName.put(p.name(), p);
        }
        Map<String, Object> apiInputs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            String name = e.getKey();
            Object value = e.getValue();
            if (value == null) {
                continue;
            }
            FlowApiParam param = byName.get(name);
            if (param != null) {
                apiInputs.put(param.name(), FlowApiTypeTransform.transform(nodeId, value, param.type(), name));
            } else {
                throw FlowApiErrors.of(
                        nodeId, FlowApiStatusCode.WORKFLOW_API_INPUTS_ERROR, "param is not api params");
            }
        }
        return apiInputs;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> formatApiHeader(NodeSessionApi session) {
        Object allHeaders = getWorkflowParam(session, "runtime_auth_headers");
        if (!(allHeaders instanceof Map<?, ?> m)) {
            return new LinkedHashMap<>();
        }
        Object forApi = m.get(String.valueOf(apiId));
        if (!(forApi instanceof Map<?, ?>)) {
            forApi = m.get("default");
        }
        if (!(forApi instanceof Map<?, ?> headers)) {
            return new LinkedHashMap<>();
        }
        Map<String, String> out = new LinkedHashMap<>();
        headers.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return out;
    }

    /**
     * Python {@code get_auth_token} — dev placeholder unless host already set {@code X-Auth-Token}.
     *
     * @return result
     * @since 0.1.0
     */

    public Map<String, String> getAuthToken() {

        /**
         * getAuthToken.
         *
         * @return result
         * @since 0.1.0
         */

        return getAuthToken(Map.of());
    }

    /**
     * Python {@code get_auth_token}. When {@code existingHeaders} already contains a non-blank
     * {@code X-Auth-Token} (from session {@code runtime_auth_headers}), returns {@code null} so the
     * host value is preserved.
     *
     * @param existingHeaders existingHeaders
     * @return result
     * @since 0.1.0
     */

    public Map<String, String> getAuthToken(Map<String, String> existingHeaders) {
        Object authObj = conf.get("auth");
        if (!(authObj instanceof Map<?, ?> auth) || auth.isEmpty()) {
            return null;
        }
        if (!"USER".equals(String.valueOf(auth.get("scope")))) {
            return null;
        }
        Object targetObj = auth.get("target");
        if (!(targetObj instanceof Map<?, ?> target) || target.isEmpty()) {
            return null;
        }
        if (!"headers".equals(String.valueOf(target.get("domain")))) {
            return null;
        }
        Object keys = target.get("auth_keys");
        if (!(keys instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        boolean has = false;
        for (Object k : list) {
            if ("X-Auth-Token".equals(String.valueOf(k))) {
                has = true;
                break;
            }
        }
        if (!has) {
            return null;
        }
        if (existingHeaders != null) {
            String existing = existingHeaders.get("X-Auth-Token");
            if (existing != null && !existing.isBlank()) {
                return null;
            }
        }
        return Map.of("X-Auth-Token", PYTHON_PARITY_AUTH_TOKEN_PLACEHOLDER);
    }

    private void waitForRequiredParams(Map<String, Object> inputs, List<FlowApiParam> required, NodeSessionApi session) {
        Map<String, String> paramsDict = new LinkedHashMap<>();
        for (FlowApiParam p : required) {
            paramsDict.put(p.name(), p.description());
        }
        Map<String, String> missing = new LinkedHashMap<>();
        for (String name : paramsDict.keySet()) {
            if (!inputs.containsKey(name)) {
                missing.put(name, paramsDict.get(name));
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Object> interrupt = new LinkedHashMap<>();
            interrupt.put("type", PLUGIN_PARAM_MISS);
            interrupt.put("tool_name", apiName);
            interrupt.put("missing_params", missing);
            interact(session, interrupt);
        }
    }

    private void waitForUserConfirmation(
            Map<String, Object> inputs, List<FlowApiParam> required, NodeSessionApi session) {
        Map<String, Object> inputParams = new LinkedHashMap<>();
        for (FlowApiParam p : required) {
            if (inputs.containsKey(p.name())) {
                inputParams.put(p.name(), inputs.get(p.name()));
            }
        }
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("type", PLUGIN_CALL_CONFIRM);
        interrupt.put("tool_name", apiName);
        interrupt.put("parameter_dict", inputParams);
        interact(session, interrupt);
    }

    private static void interact(NodeSessionApi session, Object message) {
        try {
            session.interact(message);
        } catch (RuntimeException e) {
            rethrowGraphInterrupt(e);
            throw e;
        }
    }

    private static void rethrowGraphInterrupt(RuntimeException e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof GraphInterrupt
                    || cur instanceof WorkflowInteraction.GraphInterruptRuntimeWrapper) {
                if (cur instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
            cur = cur.getCause();
        }
    }

    private Map<String, Object> ainvokeBackend(Map<String, Object> inputs, Map<String, String> headers)
            throws Exception {
        TestBridge bridge = presetBridge;
        if (bridge != null) {
            return bridge.ainvoke(apiId, inputs, headers);
        }
        if (mockResponse != null) {

            /**
             * asMap.
             *
             * @param mockResponse mockResponse
             * @return result
             * @since 0.1.0
             */

            return asMap(mockResponse);
        }
        if (apiId != null && !apiId.isBlank() && toolRegistry != null) {
            Tool tool = toolRegistry
                    .find(apiId)
                    .orElseThrow(() -> FlowApiErrors.of(
                            nodeId,
                            FlowApiStatusCode.WORKFLOW_API_INIT_ERROR,
                            "cannot find apiId[" + apiId + "]"));
            Map<String, Object> kwargs = new LinkedHashMap<>();
            if (!headers.isEmpty()) {
                kwargs.put("runtime_auth", Map.of("headers", headers));
            }
            Object raw = tool.invoke(inputs, kwargs);

            /**
             * asMap.
             *
             * @param raw raw
             * @return result
             * @since 0.1.0
             */

            return asMap(raw);
        }

        /**
         * invokeHttp.
         *
         * @param inputs inputs
         * @param headers headers
         * @return result
         * @since 0.1.0
         */

        return invokeHttp(inputs, headers);
    }

    private Iterator<Object> astreamBackend(Map<String, Object> inputs, Map<String, String> headers)
            throws Exception {
        TestBridge bridge = presetBridge;
        if (bridge != null) {
            return bridge.astream(apiId, inputs, headers);
        }
        if (mockResponse != null) {
            Object data = mockResponse;
            if (data instanceof Map<?, ?> m && m.get("data") != null) {
                data = m.get("data");
            }
            return List.of(data).iterator();
        }
        if (apiId != null && !apiId.isBlank() && toolRegistry != null) {
            Tool tool = toolRegistry
                    .find(apiId)
                    .orElseThrow(() -> FlowApiErrors.of(
                            nodeId,
                            FlowApiStatusCode.WORKFLOW_API_INIT_ERROR,
                            "cannot find apiId[" + apiId + "]"));
            Map<String, Object> kwargs = new LinkedHashMap<>();
            if (!headers.isEmpty()) {
                kwargs.put("runtime_auth", Map.of("headers", headers));
            }
            return tool.stream(inputs, kwargs);
        }
        // IR HTTP SSE — 1:1 RestfulApiToolNew.astream (data: lines)
        /**
         * streamHttpSse.
         *
         * @param inputs inputs
         * @param headers headers
         * @return result
         * @since 0.1.0
         */

        return streamHttpSse(inputs, headers);
    }

    private Iterator<Object> streamHttpSse(Map<String, Object> inputs, Map<String, String> headers)
            throws Exception {
        HttpCall call = buildHttpCall(inputs, headers);
        try {
            return RestfulApiSseClient.stream(call.url(), call.method(), call.body(), call.headers(), call.timeoutMs());
        } catch (IOException e) {
            throw FlowApiErrors.of(nodeId, FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeHttp(Map<String, Object> inputs, Map<String, String> headers) throws Exception {
        HttpCall call = buildHttpCall(inputs, headers);
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(call.timeoutMs())).build();
        HttpRequest.Builder rb =
                HttpRequest.newBuilder(URI.create(call.url())).timeout(Duration.ofMillis(call.timeoutMs()));
        call.headers().forEach(rb::header);
        applyMethod(rb, call.method(), call.body());
        HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw FlowApiErrors.of(
                    nodeId,
                    FlowApiStatusCode.WORKFLOW_API_EXECUTE_ERROR,
                    "plugin response code " + resp.statusCode() + " error.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statusCode", resp.statusCode());
        data.put(OLD_IR_PLUGIN_RESPONSE, resp.body());
        data.put("body", resp.body());
        try {
            Map<String, Object> parsed = MAPPER.readValue(resp.body(), MAP_TYPE);
            data.putAll(parsed);
        } catch (JsonProcessingException ignored) {
            // keep raw
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("errCode", 0);
        envelope.put("errMessage", "success");
        envelope.put("data", data);
        return envelope;
    }

    private HttpCall buildHttpCall(Map<String, Object> inputs, Map<String, String> headers) throws Exception {
        String url = urlOf(conf);
        if (url.isBlank()) {
            throw FlowApiErrors.of(
                    nodeId,
                    FlowApiStatusCode.WORKFLOW_API_INIT_ERROR,
                    "Failed to build API from IR config: url, apiId+ToolRegistry, or mockResponse required");
        }
        url = TemplateRenderer.render(url, inputs);
        com.openjiuwen.studio.dsl.util.OutboundUrlSafety.validateOutbound(url);
        String method = str(conf.getOrDefault("method", "POST"));
        if (method.isBlank()) {
            method = "POST";
        }
        long timeoutMs = 10_000L;
        Object t = conf.get("timeoutMs");
        if (t instanceof Number n) {
            timeoutMs = n.longValue();
        }
        String body = "";
        Object bodyCfg = conf.get("body");
        if (bodyCfg != null && !str(bodyCfg).isBlank()) {
            body = TemplateRenderer.render(str(bodyCfg), inputs);
        } else if (!"GET".equalsIgnoreCase(method)) {
            body = MAPPER.writeValueAsString(inputs);
        }
        Map<String, String> hdrs = headers == null ? Map.of() : headers;
        return new HttpCall(url, method, body, hdrs, timeoutMs);
    }

    private record HttpCall(String url, String method, String body, Map<String, String> headers, long timeoutMs) {}

    private static void applyMethod(HttpRequest.Builder rb, String method, String body) {
        String payload = body == null ? "" : body;
        switch (method.toUpperCase(Locale.ROOT)) {
            case "POST" -> rb.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            case "PUT" -> rb.PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            case "DELETE" -> rb.DELETE();
            default -> rb.GET();
        }
    }

    private Iterator<Object> transformAsyncStreamData(Iterator<Object> res, NodeSessionApi session) {
        Map<String, Boolean> requiredFields = getRequiredOutputFields();
        List<Map<String, Object>> outputDefs = getOutputsListFromConf();
        StringBuilder finalOutput = new StringBuilder();
        String streamNodeId = metadata.nodeId();
        String streamNodeType = metadata.nodeType();
        Map<String, Object> streamingMeta = new LinkedHashMap<>();
        streamingMeta.put("node_id", streamNodeId);
        streamingMeta.put("node_type", streamNodeType);
        streamingMeta.put("messages_type", "streaming");

        List<Object> out = new ArrayList<>();
        while (res.hasNext()) {
            Object item = res.next();
            for (Map<String, Object> data : itemStreamData(item, finalOutput)) {
                Map<String, Object> processed = processAndFilterAnswer(data, requiredFields);
                Object answer = processed.get("answer");
                if (answer instanceof Map<?, ?> am && !am.containsKey(OLD_IR_PLUGIN_RESPONSE)) {
                    Map<String, Object> formatted = new LinkedHashMap<>();
                    for (Map<String, Object> def : outputDefs) {
                        Object id = def.get("id");
                        if (id != null) {
                            formatted.put(String.valueOf(id), am.get(String.valueOf(id)));
                        }
                    }
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put(USER_FIELDS, formatted);
                    frame.put("__stream_metadata__", streamingMeta);
                    out.add(frame);
                } else {
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put(USER_FIELDS, answer);
                    frame.put("__stream_metadata__", new LinkedHashMap<>(streamingMeta));
                    out.add(frame);
                }
            }
        }
        Map<String, Object> finishMeta = new LinkedHashMap<>();
        finishMeta.put("node_id", streamNodeId);
        finishMeta.put("node_type", streamNodeType);
        finishMeta.put("messages_type", "finish");
        Map<String, Object> formattedRes = new LinkedHashMap<>();
        for (Map<String, Object> def : outputDefs) {
            Object id = def.get("id");
            if (id != null) {
                formattedRes.put(String.valueOf(id), finalOutput.toString());
            }
        }
        Map<String, Object> finish = new LinkedHashMap<>();
        finish.put(USER_FIELDS, formattedRes);
        finish.put("__stream_metadata__", finishMeta);
        out.add(finish);
        return out.iterator();
    }

    private List<Map<String, Object>> itemStreamData(Object item, StringBuilder finalOutput) {
        List<Map<String, Object>> list = new ArrayList<>();
        Object content = tryGetContent(item);
        Map<String, Object> data = new LinkedHashMap<>();
        if (content != null && !str(content).isBlank()) {
            data.put("answer", content);
            data.putAll(getStreamMetadata());
            finalOutput.append(str(content));
        } else {
            data.put("answer", item);
            data.putAll(getStreamMetadata());
            finalOutput.append(str(item));
        }
        list.add(data);
        return list;
    }

    private static Object tryGetContent(Object item) {
        if (item == null) {
        return null;
    }
        try {
            var m = item.getClass().getMethod("getContent");
            return m.invoke(item);
        } catch (ReflectiveOperationException ignored) {
            try {
                var f = item.getClass().getField("content");
                return f.get(item);
            } catch (ReflectiveOperationException ignored2) {
                return null;
            }
        }
    }

    private Map<String, Object> getStreamMetadata() {
        if (metadata == WorkflowMetadata.EMPTY) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_id", metadata.nodeId());
        m.put("node_name", metadata.nodeName());
        m.put("node_type", metadata.nodeType());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> processAndFilterAnswer(Map<String, Object> data, Map<String, Boolean> requiredFields) {
        Object answer = data.get("answer");
        Map<String, Object> streamingResponse = null;
        if (answer instanceof Map<?, ?> am) {
            Map<String, Object> copy = new LinkedHashMap<>();
            am.forEach((k, v) -> copy.put(String.valueOf(k), v));
            streamingResponse = copy;
        } else if (answer instanceof String s) {
            try {
                Map<String, Object> parsed = MAPPER.readValue(s, MAP_TYPE);
                streamingResponse = parsed;
            } catch (JsonProcessingException ignored) {
                // keep null
            }
        }
        if (requiredFields.containsKey(OLD_IR_PLUGIN_RESPONSE) && requiredFields.size() == 1) {
            data.put("answer", answer);
            return data;
        }
        if (streamingResponse == null) {
            data.put("answer", Map.of(OLD_IR_PLUGIN_RESPONSE, str(answer)));
            return data;
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : streamingResponse.entrySet()) {
            if (Boolean.TRUE.equals(requiredFields.get(e.getKey()))) {
                filtered.put(e.getKey(), e.getValue());
            }
        }
        data.put("answer", filtered);
        return data;
    }

    private Map<String, Boolean> getRequiredOutputFields() {
        Object outputs = userFieldsConf.get("outputs");
        if (!(outputs instanceof List<?> list)) {
            return Map.of();
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m && m.get("id") != null) {
                out.put(String.valueOf(m.get("id")), bool(m.get("required")));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getOutputsListFromConf() {
        Object outputs = userFieldsConf.get("outputs");
        if (!(outputs instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                out.add(copy);
            }
        }
        return out;
    }

    private boolean errIgnore() {
        Object enable = conf.get(EXCEPTION_ENABLE);
        return enable != null && bool(enable);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadJson(Object inputData) {
        if (inputData instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (!(inputData instanceof String s)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(s, MAP_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private static List<FlowApiParam> parseParams(Map<String, Object> c) {
        Object args = c.get("arguments");
        if (!(args instanceof List<?>)) {
            args = c.get("params");
        }
        if (!(args instanceof List<?> list) || list.isEmpty()) {
            Object uf = c.get(USER_FIELDS);
            if (uf instanceof Map<?, ?> um) {
                Object inputs = um.get("inputs");
                if (inputs instanceof List<?> inList) {
                    return parseParamList(inList);
                }
            }
            return List.of();
        }
        return parseParamList(list);
    }

    private static List<FlowApiParam> parseResponse(Map<String, Object> c) {
        Object resp = c.get("response");
        if (!(resp instanceof List<?> list)) {
            return List.of();
        }
        return parseParamList(list);
    }

    private static List<FlowApiParam> parseParamList(List<?> list) {
        List<FlowApiParam> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                if (copy.containsKey("id") && !copy.containsKey("name")) {
                    copy.put("name", copy.get("id"));
                }
                out.add(FlowApiParam.fromDict(copy));
            }
        }
        return out;
    }

    private static String urlOf(Map<String, Object> c) {
        return firstNonBlank(str(c.get("url")), str(c.get("endpoint")));
    }
    private static Object getWorkflowParam(NodeSessionApi session, String key) {
        if (session == null) {
        return null;
    }
        try {
            return session.getGlobalState(key);
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o == null) {
            return new LinkedHashMap<>();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put(OLD_IR_PLUGIN_RESPONSE, o);
        return wrap;
    }

    private static boolean bool(Object o) {
        if (o == null) {
        return false;
    }
        if (o instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
        return v;
    }
        }
        return "";
    }

    /**
     * * Python {@code WorkflowMetadata} subset.
     *
     * @param nodeId nodeId
     * @param nodeType nodeType
     * @param nodeName nodeName
     * @return result
     * @since 0.1.0
     */

    public record WorkflowMetadata(String nodeId, String nodeType, String nodeName) {
        static final WorkflowMetadata EMPTY = new WorkflowMetadata("", "", "");

        static WorkflowMetadata fromConf(Map<String, Object> configs, String fallbackId) {
            Object metaObj = configs.get("metadata");
            Map<String, Object> meta = Map.of();
            if (metaObj instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>();
                m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                meta = copy;
            }
            return new WorkflowMetadata(
                    firstNonBlank(str(meta.get("node_id")), str(configs.get("node_id")), fallbackId),
                    firstNonBlank(
                            str(meta.get("node_type")),
                            str(configs.get("node_type")),
                            str(configs.get("nodeType")),
                            "FlowApi"),
                    firstNonBlank(
                            str(meta.get("node_name")),
                            str(configs.get("node_name")),
                            str(configs.get("name")),
                            fallbackId));
        }
    }
}
