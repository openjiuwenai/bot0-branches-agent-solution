/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowsetvariable;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.utils.SessionUtils;
import com.openjiuwen.studio.dsl.exec.WorkflowVariableScope;
import com.openjiuwen.studio.dsl.store.ConversationValsStore;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code LoopSetVariable} / {@code loop_set_variable.py}.
 *
 * <p>Python {@code invoke} returns {@code None}; this engine applies side effects and returns the
 * applied var map for callers that need it (Handler discards and returns empty).
 *
 * @since 2026-08-26
 */

public final class FlowSetVariableEngine {
    public static final String USER_FIELDS = "userFields";

    /**
     * Python {@code GLOBAL_REF_PREFIX}.
     */
    public static final String MEMORY_PREFIX = "MEMORY_VARIABLE.";

    /**
     * Python {@code MEMORY_VAR_INDICATOR}.
     */
    public static final String MEMORY_VAR_INDICATOR = "memory";

    /**
     * Python {@code REDIS_GLOBAL_VALS_NAME}.
     */
    public static final String REDIS_GLOBAL_VALS_NAME = "global.vals";
    public static final String SESSION_VAR_DEFS = "_session_var_defs";
    public static final String REQUEST_KEY = "_REQUEST";
    public static final String REQUEST_KEY_ALT = "_request";
    private static final long DEFAULT_CONVERSATION_TTL_SECONDS = 3L * 24 * 3600;

    private final FlowSetVariableConfig config;
    private final WorkflowVariableScope scope;
    private final String workflowId;
    private final ConversationValsStore valsStore;

    public FlowSetVariableEngine(
            FlowSetVariableConfig config,
            WorkflowVariableScope scope,
            String workflowId,
            ConversationValsStore valsStore) {
        this.config = config;
        this.scope = scope;
        this.workflowId = workflowId;
        this.valsStore = valsStore == null ? ConversationValsStores.get() : valsStore;
    }

    /**
     * Apply mappings — Python {@code LoopSetVariable.invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @return applied fields (Handler returns empty for Python {@code None})
     */

    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> vars = new LinkedHashMap<>();
        if (scope != null) {
            vars.putAll(scope.snapshot());
        }
        Map<String, Object> inputUserFields = userFieldsOf(inputs);

        Map<String, Object> sessionVarDefs = loadSessionVarDefs(session);
        Map<String, Object> redisPatch = new LinkedHashMap<>();
        Map<String, Object> requestPatch = new LinkedHashMap<>();
        Map<String, Object> globalPatch = new LinkedHashMap<>();

        for (Map.Entry<String, Object> e : config.variableMapping().entrySet()) {
            String left = e.getKey();
            Object right = e.getValue();
            String operator = resolveOperator(left);
            applyOne(
                    vars,
                    inputUserFields,
                    redisPatch,
                    requestPatch,
                    globalPatch,
                    sessionVarDefs,
                    session,
                    left,
                    right,
                    operator);
        }

        if (scope != null) {
            scope.putAll(vars);
        }
        persistLocal(session, vars);
        persistGlobal(session, globalPatch);
        persistRequest(session, requestPatch);
        persistRedis(session, inputs, sessionVarDefs, redisPatch);

        return vars;
    }

    private String resolveOperator(String left) {
        Map<String, String> ops = config.operatorMapping();
        String op = ops.get(left);
        if (op != null) {
            return op;
        }
        String stripped = stripRef(left);
        return ops.getOrDefault(stripped, ops.getOrDefault("${" + stripped + "}", ""));
    }

    private void applyOne(
            Map<String, Object> vars,
            Map<String, Object> inputUserFields,
            Map<String, Object> redisPatch,
            Map<String, Object> requestPatch,
            Map<String, Object> globalPatch,
            Map<String, Object> sessionVarDefs,
            NodeSessionApi session,
            String left,
            Object right,
            String operator) {
        if (left == null || left.isBlank()) {
        return;
    }
        String leftRefStr = SessionUtils.extractOriginKey(left);
        if (leftRefStr == null || leftRefStr.isBlank()) {
            leftRefStr = stripRef(left);
        }

        Object value = generateValue(vars, inputUserFields, session, right);

        // MEMORY_VARIABLE.* → update_global (Python); bare prefix accepted for Studio settings IR
        if (leftRefStr.startsWith(MEMORY_PREFIX)) {
            value = applyOperator(vars, inputUserFields, session, left, leftRefStr, value, operator, true);
            globalPatch.put(leftRefStr, value);
            String varName = leftRefStr.substring(MEMORY_PREFIX.length());
            vars.put(leftRefStr, value);
            vars.put(varName, value);
            if (sessionVarDefs.containsKey(varName)) {
                redisPatch.put(varName, value);
            }
            return;
        }

        // ${_request.xxx} — Studio/SubWorkflow global_state bucket (legacy REQUEST_VARIABLES path)
        if (leftRefStr.startsWith("_request.") || leftRefStr.startsWith("_REQUEST.")) {
            value = applyOperator(vars, inputUserFields, session, left, leftRefStr, value, operator, false);
            String reqName = leftRefStr.substring(leftRefStr.indexOf('.') + 1);
            requestPatch.put(reqName, value);
            vars.put(reqName, value);
            vars.put(leftRefStr, value);
            return;
        }

        String[] keys = leftRefStr.split("\\.", -1);
        if (keys.length == 0 || (keys.length == 1 && keys[0].isBlank())) {
            throw new IllegalArgumentException("key[" + left + "] not supported format");
        }

        value = applyOperator(vars, inputUserFields, session, left, leftRefStr, value, operator, false);
        Object outputData = generateOutput(slice(keys, 1), value);

        String simple = keys[keys.length - 1];
        vars.put(simple, value);
        vars.put(leftRefStr, value);
        if (keys.length == 1) {
            vars.put(keys[0], value);
        }

        if (isSessionVar(leftRefStr, sessionVarDefs, keys)) {
            Object redisVal = extractMemoryValue(outputData, simple);
            redisPatch.put(simple, redisVal == null ? value : redisVal);
        }
    }

    private Object applyOperator(
            Map<String, Object> vars,
            Map<String, Object> inputUserFields,
            NodeSessionApi session,
            String left,
            String leftRefStr,
            Object rightValue,
            String operator,
            boolean memoryGlobal) {
        String op = operator == null ? "" : operator;
        return switch (op) {
            case "increment" ->
                    toLong(readCurrent(vars, inputUserFields, session, left, leftRefStr, memoryGlobal)) + 1;
            case "decrement" ->
                    toLong(readCurrent(vars, inputUserFields, session, left, leftRefStr, memoryGlobal)) - 1;
            case "empty" -> null;
            case "empty_str" -> "";
            case "empty_arr" -> List.of();
            default -> rightValue;
        };
    }

    /**
     * Current value for increment/decrement.
     *
     * <p>Python uses {@code generate_value(session, left)} when {@code left} is a {@code ${...}} ref;
     * Studio IR often uses bare keys ({@code "n"}), which must be read from vars/scope.
     *
     * @param vars vars
     * @param inputUserFields inputUserFields
     * @param session session
     * @param left left
     * @param leftRefStr leftRefStr
     * @param memoryGlobal memoryGlobal
     * @return result
     * @since 0.1.0
     */

    private static Object readCurrent(
            Map<String, Object> vars,
            Map<String, Object> inputUserFields,
            NodeSessionApi session,
            String left,
            String leftRefStr,
            boolean memoryGlobal) {
        if (memoryGlobal) {
            return firstNonNull(
                    sessionGlobal(session, leftRefStr),
                    vars.get(leftRefStr),
                    vars.get(simpleName(leftRefStr)));
        }
        if (SessionUtils.isRefPath(left)) {
            return generateValue(vars, inputUserFields, session, left);
        }
        return firstNonNull(
                vars.get(leftRefStr),
                lookupInput(inputUserFields, leftRefStr),
                vars.get(simpleName(leftRefStr)),
                lookupInput(inputUserFields, simpleName(leftRefStr)),
                vars.get(left));
    }

    private static String simpleName(String ref) {
        if (ref == null) {
        return "";
    }
        return ref.contains(".") ? ref.substring(ref.lastIndexOf('.') + 1) : ref;
    }

    /**
     * Python {@code LoopSetVariableComponent.generate_value}.
     *
     * @param vars vars
     * @param session session
     * @param value value
     * @return result
     * @since 0.1.0
     */
    static Object generateValue(Map<String, Object> vars, NodeSessionApi session, Object value) {
        return generateValue(vars, Map.of(), session, value);
    }

    /**
     * Python {@code LoopSetVariableComponent.generate_value}.
     *
     * @param vars vars
     * @param inputUserFields inputUserFields
     * @param session session
     * @param value value
     * @return result
     * @since 0.1.0
     */
    static Object generateValue(
            Map<String, Object> vars, Map<String, Object> inputUserFields, NodeSessionApi session, Object value) {
        if (value instanceof String s && SessionUtils.isRefPath(s)) {
            String ref = SessionUtils.extractOriginKey(s);
            Object g = sessionGlobal(session, ref);
            if (g != null) {
                return g;
            }
            Object resolved = lookupVar(vars, inputUserFields, ref);
            return resolved != null ? resolved : value;
        }
        if (value instanceof String s) {
            String t = s.trim();
            if (t.startsWith("${") && t.endsWith("}")) {
                String ref = stripRef(t);
                Object resolved = lookupVar(vars, inputUserFields, ref);
                return resolved != null ? resolved : value;
            }
        }
        return value;
    }

    private static Object lookupVar(Map<String, Object> vars, Map<String, Object> inputUserFields, String ref) {
        if (vars.containsKey(ref)) {
        return vars.get(ref);
    }
        Object fromInput = lookupInput(inputUserFields, ref);
        if (fromInput != null) {
            return fromInput;
        }
        String simple = ref.contains(".") ? ref.substring(ref.lastIndexOf('.') + 1) : ref;
        if (vars.containsKey(simple)) {
            return vars.get(simple);
        }
        return lookupInput(inputUserFields, simple);
    }

    private static Object lookupInput(Map<String, Object> inputUserFields, String key) {
        if (inputUserFields == null || key == null || key.isBlank()) {
        return null;
    }
        return inputUserFields.get(key);
    }

    /**
     * Python {@code LoopSetVariableComponent.generate_output}.
     *
     * @param keys keys
     * @param value value
     * @return result
     * @since 0.1.0
     */
    @SuppressWarnings("unchecked")
    static Object generateOutput(String[] keys, Object value) {
        Object output = value;
        for (int i = keys.length - 1; i >= 0; i--) {
            Map<String, Object> nested = new LinkedHashMap<>(1);
            nested.put(keys[i], output);
            output = nested;
        }
        return output;
    }

    static boolean isSessionVar(String leftRefStr, Map<String, Object> sessionVarDefs, String[] keys) {
        if (sessionVarDefs == null || sessionVarDefs.isEmpty()) {
        return false;
    }
        if (keys.length >= 4 && MEMORY_VAR_INDICATOR.equals(keys[2])) {
            return sessionVarDefs.containsKey(keys[keys.length - 1]);
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Object extractMemoryValue(Object outputData, String varName) {
        if (!(outputData instanceof Map<?, ?> od)) {
        return outputData;
    }
        Object uf = od.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> ufm) {
            Object memory = ufm.get(MEMORY_VAR_INDICATOR);
            if (memory instanceof Map<?, ?> mm) {
                return mm.get(varName);
            }
        }
        Object direct = od.get(varName);
        return direct != null ? direct : od;
    }

    private void persistRedis(
            NodeSessionApi session,
            Map<String, Object> inputs,
            Map<String, Object> sessionVarDefs,
            Map<String, Object> redisPatch) {
        if (redisPatch.isEmpty() || sessionVarDefs.isEmpty()) {
        return;
    }
        String wf = workflowId == null || workflowId.isBlank() ? "wf" : workflowId;
        String conversationId = resolveConversationId(inputs, session);
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String key = redisKey(wf, conversationId);
        Map<String, Object> merged = new LinkedHashMap<>();
        try {
            Map<String, Object> old = valsStore.getMap(key);
            if (old != null) {
                merged.putAll(old);
            }
        } catch (RuntimeException ignored) {
            // soft-fail like Python
        }
        merged.putAll(redisPatch);
        try {
            valsStore.setMap(key, merged, conversationTtlSeconds());
        } catch (RuntimeException ignored) {
            // soft-fail
        }
    }

    static String redisKey(String workflowId, String conversationId) {
        return REDIS_GLOBAL_VALS_NAME + "." + workflowId + "." + conversationId;
    }
    static long conversationTtlSeconds() {
        String env = System.getenv("CONVERSATION_VARIABLE_STORE_TIME");
        if (env == null || env.isBlank()) {
            env = System.getProperty("studio.dsl.conversation.ttl.seconds");
        }
        if (env == null || env.isBlank()) {
            return DEFAULT_CONVERSATION_TTL_SECONDS;
        }
        try {
            return Long.parseLong(env.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_CONVERSATION_TTL_SECONDS;
        }
    }

    private static void persistGlobal(NodeSessionApi session, Map<String, Object> globalPatch) {
        if (session == null || globalPatch.isEmpty()) {
        return;
    }
        try {
            session.updateGlobalState(Map.copyOf(globalPatch));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    private static void persistRequest(NodeSessionApi session, Map<String, Object> requestPatch) {
        if (session == null || requestPatch.isEmpty()) {
        return;
    }
        try {
            Map<String, Object> merged = new LinkedHashMap<>();
            Object existing = session.getGlobalState(REQUEST_KEY);
            if (!(existing instanceof Map<?, ?>)) {
                existing = session.getGlobalState(REQUEST_KEY_ALT);
            }
            if (existing instanceof Map<?, ?> m) {
                m.forEach((k, v) -> merged.put(String.valueOf(k), v));
            }
            merged.putAll(requestPatch);
            session.updateGlobalState(Map.of(REQUEST_KEY, merged, REQUEST_KEY_ALT, merged));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private static void persistLocal(NodeSessionApi session, Map<String, Object> vars) {
        if (session == null) {
        return;
    }
        try {
            session.updateState(Map.copyOf(vars));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadSessionVarDefs(NodeSessionApi session) {
        if (session == null) {
            return Map.of();
        }
        try {
            Object defs = session.getGlobalState(SESSION_VAR_DEFS);
            if (defs instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (RuntimeException ignored) {
            // mock
        }
        return Map.of();
    }

    private static String resolveConversationId(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> uf = userFieldsOf(inputs);
        Object c = uf.get("conversationId");
        if (c != null && !String.valueOf(c).isBlank()) {
            return String.valueOf(c);
        }
        if (inputs != null && inputs.get("conversationId") != null) {
            return String.valueOf(inputs.get("conversationId"));
        }
        if (session != null) {
            try {
                Object g = session.getGlobalState("conversationId");
                if (g != null && !String.valueOf(g).isBlank()) {
                    return String.valueOf(g);
                }
                String s = session.getSessionId();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            } catch (RuntimeException ignored) {
                // mock
            }
        }
        return "";
    }

    static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
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

    static String stripRef(String left) {
        String s = left.trim();
        if (s.startsWith("${") && s.endsWith("}")) {
            s = s.substring(2, s.length() - 1);
        }
        return s;
    }

    private static Object sessionGlobal(NodeSessionApi session, String key) {
        if (session == null || key == null) {
        return null;
    }
        try {
            return session.getGlobalState(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) {
            if (v != null) {
        return v;
    }
        }
        return null;
    }

    private static String[] slice(String[] keys, int from) {
        if (from >= keys.length) {
        return new String[0];
    }
        String[] out = new String[keys.length - from];
        System.arraycopy(keys, from, out, 0, out.length);
        return out;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
        return n.longValue();
    }
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
