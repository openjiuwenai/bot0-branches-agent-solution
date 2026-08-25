/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.store.ConversationValsStore;
import com.openjiuwen.studio.dsl.store.ConversationValsStores;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.start — Studio Start aligned with Python {@code jiuwen.extension.workflow_node.start.Start}.
 *
 * @since 2026-08-17
 */
public final class StartNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.start";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new StartExecutable(node, ctx == null ? null : ctx.workflowId(), ConversationValsStores.get());
    }

    static final class StartExecutable extends AbstractStudioNode {
        private final String buildWorkflowId;
        private final ConversationValsStore valsStore;

        StartExecutable(AssembledNode node, String buildWorkflowId, ConversationValsStore valsStore) {
            super(node);
            this.buildWorkflowId = buildWorkflowId;
            this.valsStore = valsStore == null ? ConversationValsStores.get() : valsStore;
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> configs = node.configs() == null ? Map.of() : node.configs();
            Map<String, Object> working = workingInputs(inputs);

            StartAssignmentSupport.validateRequired(configs, working);
            Map<String, Object> inputsWithDefaults = StartAssignmentSupport.fillDefaults(configs, working);

            String workflowId = resolveWorkflowId(inputs, session);
            String conversationId = resolveConversationId(inputs, session);

            Object assignmentInputs = StartAssignmentSupport.assignmentInputs(configs);
            Map<String, Object> sessionVarDefs = StartAssignmentSupport.extractSessionVarDefs(assignmentInputs);

            if (session != null && !sessionVarDefs.isEmpty()) {
                session.updateGlobalState(Map.of("_session_var_defs", sessionVarDefs));
            }

            Map<String, Object> redisSessionVars =
                    loadRedisSessionVars(workflowId, conversationId, sessionVarDefs);
            Map<String, Object> userInputMemory = StartAssignmentSupport.userInputMemoryVar(working);
            Map<String, Object> memory = new LinkedHashMap<>(sessionVarDefs);
            memory.putAll(redisSessionVars);
            memory.putAll(userInputMemory);

            if (session != null) {
                Map<String, Object> ufWithMemory = new LinkedHashMap<>(inputsWithDefaults);
                ufWithMemory.put("memory", memory);
                // Best-effort session side effects; NodeSessionApi may not expose io_state set_outputs.
                Map<String, Object> memoryGlobal = new LinkedHashMap<>();
                Set<String> predefinedKeys = StartAssignmentSupport.predefinedMemoryKeys(assignmentInputs);
                memory.forEach((k, v) -> {
                    if (predefinedKeys.contains(k)) {
                        memoryGlobal.put("MEMORY_VARIABLE." + k, v);
                    }
                });
                if (!memoryGlobal.isEmpty()) {
                    session.updateGlobalState(memoryGlobal);
                }
                Map<String, Object> startUserFields = StartAssignmentSupport.startUserFieldsFromOutputs(configs);
                if (!startUserFields.isEmpty()) {
                    session.updateGlobalState(Map.of("start_user_fields", startUserFields));
                }
            }

            if (!userInputMemory.isEmpty()) {
                saveRedisSessionVars(workflowId, conversationId, sessionVarDefs, userInputMemory);
            }

            Map<String, Object> assembled = assembleOutput(inputsWithDefaults, inputs, session);
            assembled.put("memory", memory);
            return NodePayload.ofFields(assembled);
        }

        private Map<String, Object> workingInputs(Map<String, Object> inputs) {
            Map<String, Object> uf = userFieldsOf(inputs);
            if (!uf.isEmpty()) {
                return new LinkedHashMap<>(uf);
            }
            Map<String, Object> flat = new LinkedHashMap<>(inputs);
            flat.remove(StartAssignmentSupport.SYSTEM_FIELDS);
            flat.remove(StartAssignmentSupport.USER_FIELDS);
            return flat;
        }

        private String resolveWorkflowId(Map<String, Object> inputs, NodeSessionApi session) {
            if (buildWorkflowId != null && !buildWorkflowId.isBlank()) {
                return buildWorkflowId;
            }
            Object fromGlobal = readGlobalVar(inputs, "appId");
            if (fromGlobal != null) {
                return String.valueOf(fromGlobal);
            }
            if (session != null) {
                Object gs = session.getGlobalState("workflow_id");
                if (gs != null) {
                    return String.valueOf(gs);
                }
            }
            return "";
        }

        private String resolveConversationId(Map<String, Object> inputs, NodeSessionApi session) {
            Object fromGlobal = readGlobalVar(inputs, "conversationId");
            if (fromGlobal != null) {
                return String.valueOf(fromGlobal);
            }
            if (session != null) {
                Object gs = session.getGlobalState("conversation_id");
                if (gs != null) {
                    return String.valueOf(gs);
                }
            }
            Object top = inputs.get("conversationId");
            return top == null ? "" : String.valueOf(top);
        }

        private Object readGlobalVar(Map<String, Object> inputs, String key) {
            Object gv = inputs.get("global_variables");
            if (gv instanceof Map<?, ?> m && m.get(key) != null) {
                return m.get(key);
            }
            Map<String, Object> uf = userFieldsOf(inputs);
            Object nested = uf.get("global_variables");
            if (nested instanceof Map<?, ?> m2) {
                return m2.get(key);
            }
            return null;
        }

        private Map<String, Object> loadRedisSessionVars(
                String workflowId, String conversationId, Map<String, Object> valuesDefine) {
            if (workflowId == null
                    || workflowId.isBlank()
                    || conversationId == null
                    || conversationId.isBlank()
                    || valuesDefine == null
                    || valuesDefine.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> stored =
                    valsStore.getMap(StartAssignmentSupport.redisKey(workflowId, conversationId));
            Map<String, Object> out = new LinkedHashMap<>();
            valuesDefine.forEach((k, def) -> out.put(k, stored.containsKey(k) ? stored.get(k) : def));
            return out;
        }

        private void saveRedisSessionVars(
                String workflowId,
                String conversationId,
                Map<String, Object> valuesDefine,
                Map<String, Object> memoryVars) {
            if (workflowId == null
                    || workflowId.isBlank()
                    || conversationId == null
                    || conversationId.isBlank()
                    || valuesDefine == null
                    || valuesDefine.isEmpty()) {
                return;
            }
            String key = StartAssignmentSupport.redisKey(workflowId, conversationId);
            Map<String, Object> newVars = new LinkedHashMap<>();
            for (String k : valuesDefine.keySet()) {
                if (memoryVars.containsKey(k)) {
                    newVars.put(k, memoryVars.get(k));
                }
            }
            Map<String, Object> old = loadRedisSessionVars(workflowId, conversationId, valuesDefine);
            if (!old.isEmpty()) {
                Map<String, Object> merged = new LinkedHashMap<>(old);
                merged.putAll(newVars);
                newVars = merged;
            }
            valsStore.setMap(key, newVars, StartAssignmentSupport.conversationTtlSeconds());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> assembleOutput(
                Map<String, Object> inputsWithDefaults, Map<String, Object> rawInputs, NodeSessionApi session) {
            Map<String, Object> inputsCopy = new LinkedHashMap<>(inputsWithDefaults);
            inputsCopy.remove("sys");

            Object requestVars = null;
            if (session != null) {
                requestVars = session.getGlobalState("_REQUEST");
                if (requestVars == null) {
                    requestVars = session.getGlobalState("REQUEST_VARIABLES");
                }
            }
            if (requestVars instanceof Map<?, ?> envs) {
                for (Map.Entry<?, ?> e : envs.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    if (!inputsCopy.containsKey(k) || inputsCopy.get(k) == null) {
                        inputsCopy.put(k, e.getValue());
                    }
                }
            }

            Map<String, Object> sys = new LinkedHashMap<>();
            Object ioSys = null;
            if (session != null) {
                Object gv = session.getGlobalState("global_variables");
                if (gv instanceof Map<?, ?> gvm) {
                    ioSys = gvm.get("sys");
                }
            }
            if (ioSys instanceof Map<?, ?> sm) {
                sys.putAll(StartAssignmentSupport.stringKeyed(sm));
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
                Object rawQ = rawInputs.get("query");
                resolvedQuery = rawQ == null ? "" : rawQ;
            }

            Map<String, Object> systemFields = new LinkedHashMap<>();
            systemFields.put("query", resolvedQuery);
            systemFields.put("dialogueHistory", List.of());
            systemFields.put("sys", sys);
            systemFields.put("conversationHistory", sys.get("conversationHistory"));

            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put(StartAssignmentSupport.SYSTEM_FIELDS, systemFields);
            wrap.put(StartAssignmentSupport.USER_FIELDS, inputsCopy);
            return wrap;
        }
    }
}
