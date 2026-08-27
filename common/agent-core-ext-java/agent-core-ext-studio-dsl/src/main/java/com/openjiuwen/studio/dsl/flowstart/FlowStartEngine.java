/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowstart;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.adapter.control.SubRequestScope;
import com.openjiuwen.studio.dsl.store.ConversationValsStore;
import com.openjiuwen.studio.dsl.util.DeepCopies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict 1:1 with Python {@code jiuwen/extension/workflow_node/start.py} {@code Start.invoke}.
 *
 * <p>Studio host adaptations (documented): {@code userFields} unwrap; {@code buildWorkflowId} fallback;
 * {@code ConversationValsStore} instead of Redis client; no native {@code set_outputs} — writes
 * {@code MEMORY_VARIABLE.*} / node state best-effort.
 *
 * @since 2026-08-26
 */

public final class FlowStartEngine {
    private final FlowStartConfig config;
    private final String buildWorkflowId;
    private final ConversationValsStore valsStore;

    /**
     * FlowStartEngine.
     * @param nodeId nodeId
     * @param config config
     * @param buildWorkflowId buildWorkflowId
     * @param valsStore valsStore
     * @since 0.1.0
     */
    public FlowStartEngine(
            String nodeId, FlowStartConfig config, String buildWorkflowId, ConversationValsStore valsStore) {
        this.config = config == null ? FlowStartConfig.fromNodeConfigs(Map.of(), nodeId) : config;
        this.buildWorkflowId = buildWorkflowId;
        this.valsStore = valsStore;
    }

    /**
     * Python {@code Start.invoke}.
     *
     * @param inputs invoke inputs (may wrap {@code userFields})
     * @param session session
     * @return assembled output map ({@code systemFields}/{@code userFields}/{@code memory})
     */

    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> configs = config.raw();
        Map<String, Object> in = inputs == null ? Map.of() : inputs;
        Map<String, Object> working = workingInputs(in);

        FlowStartAssignmentSupport.validateRequired(configs, working);
        Map<String, Object> inputsWithDefaults = FlowStartAssignmentSupport.fillDefaults(configs, working);

        String workflowId = resolveWorkflowId(in, session);
        String conversationId = resolveConversationId(in, session);

        Object assignmentInputs = FlowStartAssignmentSupport.assignmentInputs(configs);
        Map<String, Object> sessionVarDefs = FlowStartAssignmentSupport.extractSessionVarDefs(assignmentInputs);

        if (session != null && !sessionVarDefs.isEmpty()) {
            session.updateGlobalState(Map.of("_session_var_defs", sessionVarDefs));
        }

        Map<String, Object> redisSessionVars = loadRedisSessionVars(workflowId, conversationId, sessionVarDefs);
        Map<String, Object> userInputMemory = FlowStartAssignmentSupport.userInputMemoryVar(working);
        Map<String, Object> memory = new LinkedHashMap<>(sessionVarDefs);
        memory.putAll(redisSessionVars);
        memory.putAll(userInputMemory);

        if (session != null) {
            Map<String, Object> ufWithMemory = new LinkedHashMap<>(inputsWithDefaults);
            ufWithMemory.put("memory", memory);
            // Python set_outputs({userFields: {...memory}}); NodeSessionApi has no set_outputs — best-effort
            try {
                session.updateState(Map.of("userFields", ufWithMemory));
            } catch (RuntimeException ignored) {
                // soft
            }
            Map<String, Object> memoryGlobal = new LinkedHashMap<>();
            Set<String> predefinedKeys = FlowStartAssignmentSupport.predefinedMemoryKeys(assignmentInputs);
            memory.forEach((k, v) -> {
                if (predefinedKeys.contains(k)) {
                    memoryGlobal.put("MEMORY_VARIABLE." + k, v);
                }
            });
            if (!memoryGlobal.isEmpty()) {
                session.updateGlobalState(memoryGlobal);
            }
            Map<String, Object> startUserFields = FlowStartAssignmentSupport.startUserFieldsFromOutputs(configs);
            if (!startUserFields.isEmpty()) {
                session.updateGlobalState(Map.of("start_user_fields", startUserFields));
            }
        }

        if (!userInputMemory.isEmpty()) {
            saveRedisSessionVars(workflowId, conversationId, sessionVarDefs, userInputMemory);
        }

        Map<String, Object> assembled = assembleOutput(inputsWithDefaults, in, session);
        assembled.put("memory", memory);
        return assembled;
    }

    static Map<String, Object> workingInputs(Map<String, Object> inputs) {
        Object uf = inputs.get(FlowStartAssignmentSupport.USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            return FlowStartAssignmentSupport.stringKeyed(m);
        }
        Map<String, Object> flat = new LinkedHashMap<>(inputs);
        flat.remove(FlowStartAssignmentSupport.SYSTEM_FIELDS);
        flat.remove(FlowStartAssignmentSupport.USER_FIELDS);
        return flat;
    }

    String resolveWorkflowId(Map<String, Object> inputs, NodeSessionApi session) {
        // Python: get_workflow_param(workflow_id) → session.get_workflow_id() → global_variables.appId
        if (session != null) {
            Object gs = session.getGlobalState("workflow_id");
            if (gs != null && !String.valueOf(gs).isBlank()) {
                return String.valueOf(gs);
            }
            try {
                String wid = session.getWorkflowId();
                if (wid != null && !wid.isBlank()) {
                    return wid;
                }
            } catch (RuntimeException ignored) {
                // mock
            }
        }
        Object fromGlobal = readGlobalVar(inputs, "appId");
        if (fromGlobal != null && !String.valueOf(fromGlobal).isBlank()) {
            return String.valueOf(fromGlobal);
        }
        // Studio host: NodeBuildContext.workflowId fallback
        if (buildWorkflowId != null && !buildWorkflowId.isBlank()) {
            return buildWorkflowId;
        }
        return "";
    }

    String resolveConversationId(Map<String, Object> inputs, NodeSessionApi session) {
        // Python: get_workflow_param(conversation_id) → session.get_session_id() → global_variables.conversationId
        if (session != null) {
            Object gs = session.getGlobalState("conversation_id");
            if (gs != null && !String.valueOf(gs).isBlank()) {
                return String.valueOf(gs);
            }
            try {
                String sid = session.getSessionId();
                if (sid != null && !sid.isBlank()) {
                    return sid;
                }
            } catch (RuntimeException ignored) {
                // mock
            }
        }
        Object fromGlobal = readGlobalVar(inputs, "conversationId");
        if (fromGlobal != null && !String.valueOf(fromGlobal).isBlank()) {
            return String.valueOf(fromGlobal);
        }
        Object top = inputs.get("conversationId");
        return top == null ? "" : String.valueOf(top);
    }

    private static Object readGlobalVar(Map<String, Object> inputs, String key) {
        Object gv = inputs.get("global_variables");
        if (gv instanceof Map<?, ?> m && m.get(key) != null) {
            return m.get(key);
        }
        Object uf = inputs.get(FlowStartAssignmentSupport.USER_FIELDS);
        if (uf instanceof Map<?, ?> ufm) {
            Object nested = ufm.get("global_variables");
            if (nested instanceof Map<?, ?> m2) {
                return m2.get(key);
            }
        }
        return null;
    }

    /**
     * Python {@code _get_redis_session_vars}: empty store → {@code {}}; when present, fill missing keys
     * from {@code valuesDefine} defaults.
     *
     * @param workflowId workflowId
     * @param conversationId conversationId
     * @param valuesDefine valuesDefine
     * @return result
     * @since 0.1.0
     */

    Map<String, Object> loadRedisSessionVars(
            String workflowId, String conversationId, Map<String, Object> valuesDefine) {
        if (blank(workflowId) || blank(conversationId) || valuesDefine == null || valuesDefine.isEmpty()) {
            return Map.of();
        }
        if (valsStore == null) {
            return Map.of();
        }
        Map<String, Object> stored =
                valsStore.getMap(FlowStartAssignmentSupport.redisKey(workflowId, conversationId));
        if (stored == null || stored.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        valuesDefine.forEach((k, def) -> out.put(k, stored.containsKey(k) ? stored.get(k) : def));
        return out;
    }

    void saveRedisSessionVars(
            String workflowId,
            String conversationId,
            Map<String, Object> valuesDefine,
            Map<String, Object> memoryVars) {
        if (blank(workflowId)
                || blank(conversationId)
                || valuesDefine == null
                || valuesDefine.isEmpty()
                || valsStore == null) {
        return;
    }
        String key = FlowStartAssignmentSupport.redisKey(workflowId, conversationId);
        Map<String, Object> newVars = new LinkedHashMap<>();
        for (String k : valuesDefine.keySet()) {
            if (memoryVars.containsKey(k)) {
                newVars.put(k, memoryVars.get(k));
            }
        }
        // Python: old_values = _get_redis_session_vars(...); only merge when old_values non-empty
        Map<String, Object> oldFiltered = loadRedisSessionVars(workflowId, conversationId, valuesDefine);
        if (!oldFiltered.isEmpty()) {
            Map<String, Object> merged = new LinkedHashMap<>(oldFiltered);
            merged.putAll(newVars);
            newVars = merged;
        }
        valsStore.setMap(key, newVars, FlowStartAssignmentSupport.conversationTtlSeconds());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> assembleOutput(
            Map<String, Object> inputsWithDefaults, Map<String, Object> rawInputs, NodeSessionApi session) {
        Map<String, Object> inputsCopy = new LinkedHashMap<>(inputsWithDefaults);
        inputsCopy.remove("sys");

        Map<String, Object> envs = readRequestVars(session);
        for (Map.Entry<String, Object> e : envs.entrySet()) {
            String k = e.getKey();
            if (!inputsCopy.containsKey(k) || inputsCopy.get(k) == null) {
                inputsCopy.put(k, e.getValue());
            }
        }

        Map<String, Object> ioState = readIoState(session);
        Map<String, Object> sys = new LinkedHashMap<>();
        Object ioSys = null;
        Object gv = ioState.get("global_variables");
        if (gv instanceof Map<?, ?> gvm) {
            ioSys = gvm.get("sys");
        }
        if (ioSys == null && session != null) {
            Object g = session.getGlobalState("global_variables");
            if (g instanceof Map<?, ?> gvm) {
                ioSys = gvm.get("sys");
            }
        }
        if (ioSys instanceof Map<?, ?> sm) {
            sys.putAll(DeepCopies.map(FlowStartAssignmentSupport.stringKeyed(sm)));
        }
        Object historyObj = sys.get("conversationHistory");
        List<Object> history = new ArrayList<>();
        if (historyObj instanceof List<?> hl) {
            history.addAll(hl);
        }
        history.add(Map.of("role", "user", "content", String.valueOf(inputsCopy.getOrDefault("query", ""))));
        sys.put("conversationHistory", history);

        Object resolvedQuery = inputsCopy.getOrDefault("query", "");
        if (resolvedQuery == null || "".equals(resolvedQuery)) {
            Object ioQ = ioState.get("query");
            if (ioQ != null && !"".equals(ioQ)) {
                resolvedQuery = ioQ;
            } else {
                Object rawQ = rawInputs.get("query");
                resolvedQuery = rawQ == null ? "" : rawQ;
            }
        }

        Object dialogueHistory = ioState.get("dialogueHistory");
        if (!(dialogueHistory instanceof List<?>)) {
            dialogueHistory = List.of();
        }

        Map<String, Object> systemFields = new LinkedHashMap<>();
        systemFields.put("query", resolvedQuery);
        systemFields.put("dialogueHistory", dialogueHistory);
        systemFields.put("sys", sys);
        systemFields.put("conversationHistory", sys.get("conversationHistory"));

        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put(FlowStartAssignmentSupport.SYSTEM_FIELDS, systemFields);
        wrap.put(FlowStartAssignmentSupport.USER_FIELDS, inputsCopy);
        return wrap;
    }

    /**
     * Python {@code get_workflow_param(session, REQUEST_VARIABLES)} — key {@code _request}.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */
    static Map<String, Object> readRequestVars(NodeSessionApi session) {
        if (session == null) {
            return Map.of();
        }
        try {
            Map<String, Object> fromScope = SubRequestScope.readRequest(session);
            if (fromScope != null && !fromScope.isEmpty()) {
                return fromScope;
            }
        } catch (RuntimeException ignored) {
            // soft
        }
        for (String key : List.of(SubRequestScope.REQUEST_KEY, SubRequestScope.REQUEST_KEY_ALT, "REQUEST_VARIABLES")) {
            Object v = session.getGlobalState(key);
            if (v instanceof Map<?, ?> m) {
                return FlowStartAssignmentSupport.stringKeyed(m);
            }
        }
        return Map.of();
    }

    /**
     * Best-effort io_state: {@code getGlobalState("io_state")} or empty. Python uses
     * {@code get_state_info(session, "io_state")}.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    static Map<String, Object> readIoState(NodeSessionApi session) {
        if (session == null) {
            return Map.of();
        }
        try {
            Object io = session.getGlobalState("io_state");
            if (io instanceof Map<?, ?> m) {
                return FlowStartAssignmentSupport.stringKeyed(m);
            }
            Object state = session.getState("io_state");
            if (state instanceof Map<?, ?> m2) {
                return FlowStartAssignmentSupport.stringKeyed(m2);
            }
        } catch (RuntimeException ignored) {
            // soft
        }
        return Map.of();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
