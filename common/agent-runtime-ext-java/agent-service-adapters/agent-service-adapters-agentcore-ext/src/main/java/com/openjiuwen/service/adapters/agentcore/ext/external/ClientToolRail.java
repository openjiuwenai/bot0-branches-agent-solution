/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolCallInterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request-scoped rail that exposes client tools to one Agent execution.
 *
 * @since 2026-07-24
 */
public final class ClientToolRail extends BaseInterruptRail {
    private static final String CLIENT_TOOLS = "clientTools";
    private static final String INTERRUPT = "_interrupt";
    private static final String REMOTE_TOOL_INPUTS = "runtime.remoteToolInputs";
    private static final String REMOTE_TOOL_RESULTS = "runtime.remoteToolResults";
    private static final String CLIENT_TOOL_KIND = "client_tool";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final String conversationId;
    private final SessionMatchMode matchMode;
    private final Pattern derivedSessionPattern;
    private final List<ToolInfo> visibleTools;
    private final Set<String> visibleToolNames;
    private final Map<String, String> pendingCallsById;

    private ClientToolRail(String conversationId, SessionMatchMode matchMode, List<ToolInfo> visibleTools,
            Map<String, String> pendingCallsById) {
        super(interceptNames(visibleTools, pendingCallsById));
        this.conversationId = requireText(conversationId, "conversationId");
        this.matchMode = Objects.requireNonNull(matchMode, "matchMode");
        this.derivedSessionPattern = Pattern.compile("^" + Pattern.quote(conversationId) + "_[0-9]+$");
        this.visibleTools = List.copyOf(visibleTools);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        visibleTools.forEach(tool -> names.add(tool.getName()));
        this.visibleToolNames = Set.copyOf(names);
        this.pendingCallsById = Map.copyOf(pendingCallsById);
        setPriority(70);
    }

    /**
     * Binds client-tool behavior to the Agent for one Handler call.
     *
     * @param agent target Agent instance
     * @param request current serve request
     * @return binding that unregisters the exact rail on close
     */
    public static Binding bind(Object agent, ServeRequest request) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(request, "request");
        RequestContext context = prepare(request);
        if (context.isEmpty()) {
            return Binding.noop();
        }
        if (agent instanceof DeepAgent deepAgent) {
            synchronized (deepAgent) {
                deepAgent.ensureInitialized();
                return installLocked(deepAgent.getAgent(), deepAgent, SessionMatchMode.DEEP_AGENT_DERIVED, context);
            }
        }
        if (agent instanceof BaseAgent baseAgent) {
            synchronized (baseAgent) {
                return installLocked(baseAgent, baseAgent, SessionMatchMode.EXACT, context);
            }
        }
        throw new IllegalArgumentException("Unsupported agent type: " + agent.getClass().getName());
    }

    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        if (!belongsToCurrentRequest(ctx) || !(ctx.getInputs() instanceof ModelCallInputs inputs)
                || visibleTools.isEmpty()) {
            return;
        }
        List<ToolInfo> current = inputs.getTools() == null ? List.of() : inputs.getTools();
        Set<String> currentNames = new LinkedHashSet<>();
        for (ToolInfo tool : current) {
            if (tool != null && tool.getName() != null) {
                currentNames.add(tool.getName());
            }
        }
        for (String name : visibleToolNames) {
            if (currentNames.contains(name)) {
                throw new IllegalArgumentException("Client tool conflicts with model tool: " + name);
            }
        }
        List<ToolInfo> merged = new ArrayList<>(current);
        merged.addAll(visibleTools);
        inputs.setTools(merged);
    }

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!belongsToCurrentRequest(ctx) || !(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        ToolCall toolCall = inputs.getToolCall();
        if (toolCall == null) {
            return;
        }
        String toolName = inputs.getToolName();
        String toolCallId = toolCall.getId();
        String pendingToolName = toolCallId == null ? null : pendingCallsById.get(toolCallId);
        boolean isPendingCall = pendingToolName != null;
        boolean isNewVisibleCall = visibleToolNames.contains(toolName);
        if (!isPendingCall && !isNewVisibleCall) {
            return;
        }
        requireText(toolName, "toolName");
        requireText(toolCallId, "toolCallId");
        if (isPendingCall && !pendingToolName.equals(toolName)) {
            throw new IllegalArgumentException("Pending client tool identity mismatch");
        }
        super.beforeToolCall(ctx);
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object resumeInput) {
        String toolCallId = requireText(toolCall == null ? null : toolCall.getId(), "toolCallId");
        String toolName = requireText(toolCall == null ? null : toolCall.getName(), "toolName");
        String pendingToolName = pendingCallsById.get(toolCallId);
        if (pendingToolName != null) {
            if (!pendingToolName.equals(toolName) || resumeInput == null) {
                throw new IllegalArgumentException("Invalid pending client tool resume");
            }
            return reject(resumeInput);
        }
        InterruptRequest request = InterruptRequest.builder()
                .message("Client tool invocation required: " + toolName)
                .context(Map.of(
                        "_interrupt_kind", CLIENT_TOOL_KIND,
                        "arguments", parseArguments(toolCall.getArguments())))
                .build();
        return interrupt(ToolCallInterruptRequest.fromToolCall(request, toolCall));
    }

    private static Binding installLocked(BaseAgent target, Object lockOwner, SessionMatchMode matchMode,
            RequestContext context) {
        Set<String> serverToolNames = new LinkedHashSet<>();
        target.getAbilityManager().listToolInfo().stream().map(ToolInfo::getName)
                .filter(Objects::nonNull).forEach(serverToolNames::add);
        for (ToolInfo visibleTool : context.visibleTools()) {
            if (serverToolNames.contains(visibleTool.getName())) {
                throw new IllegalArgumentException("Client tool conflicts with server tool: " + visibleTool.getName());
            }
        }
        ClientToolRail rail = new ClientToolRail(context.conversationId(), matchMode,
                context.visibleTools(), context.pendingCallsById());
        target.registerRail(rail);
        return new Binding(target, rail, lockOwner, false);
    }

    private static RequestContext prepare(ServeRequest request) {
        Map<String, Object> metadata = request.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getMetadata());
        List<ToolInfo> visibleTools = parseVisibleTools(metadata.get(CLIENT_TOOLS));
        Map<String, String> pendingCallsById = parsePending(metadata.get(INTERRUPT));
        convertTargetedResults(request, metadata, pendingCallsById);
        if (visibleTools.isEmpty() && pendingCallsById.isEmpty()) {
            return RequestContext.empty();
        }
        String conversationId = requireText(request.getConversationId(), "conversationId");
        return new RequestContext(conversationId, visibleTools, pendingCallsById);
    }

    private static List<ToolInfo> parseVisibleTools(Object rawTools) {
        if (rawTools == null) {
            return List.of();
        }
        if (!(rawTools instanceof List<?> tools)) {
            throw new IllegalArgumentException("clientTools must be an array");
        }
        List<ToolInfo> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Object rawTool : tools) {
            if (!(rawTool instanceof Map<?, ?> tool)) {
                throw new IllegalArgumentException("clientTools entries must be objects");
            }
            String name = requireText(stringValue(tool.get("name")), "clientTools[].name");
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate client tool name: " + name);
            }
            Object rawSchema = tool.get("inputSchema");
            Map<String, Object> schema = rawSchema == null ? Map.of() : copyStringMap(rawSchema, "inputSchema");
            result.add(ToolInfo.builder()
                    .name(name)
                    .description(stringValue(tool.get("description")))
                    .parameters(schema)
                    .build());
        }
        return List.copyOf(result);
    }

    private static Map<String, String> parsePending(Object rawInterrupt) {
        if (rawInterrupt == null) {
            return Map.of();
        }
        if (!(rawInterrupt instanceof Map<?, ?> interrupt)) {
            throw new IllegalArgumentException("_interrupt must be an object");
        }
        if (interrupt.get("items") instanceof List<?> items) {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("_interrupt.items must not be empty");
            }
            boolean hasClientTool = items.stream().anyMatch(ClientToolRail::isClientToolItem);
            boolean allClientTool = items.stream().allMatch(ClientToolRail::isClientToolItem);
            if (!allClientTool) {
                if (hasClientTool) {
                    throw new IllegalArgumentException("Mixed interrupt kinds are not supported");
                }
                return Map.of();
            }
            LinkedHashMap<String, String> pending = new LinkedHashMap<>();
            for (Object item : items) {
                addPending(pending, (Map<?, ?>) item);
            }
            return Map.copyOf(pending);
        }
        if (!isClientToolItem(interrupt)) {
            return Map.of();
        }
        LinkedHashMap<String, String> pending = new LinkedHashMap<>();
        addPending(pending, interrupt);
        return Map.copyOf(pending);
    }

    private static void addPending(Map<String, String> pending, Map<?, ?> item) {
        String toolCallId = requireText(stringValue(item.get("toolCallId")), "toolCallId");
        String toolName = requireText(stringValue(item.get("toolName")), "toolName");
        if (pending.putIfAbsent(toolCallId, toolName) != null) {
            throw new IllegalArgumentException("Duplicate pending toolCallId: " + toolCallId);
        }
    }

    private static boolean isClientToolItem(Object item) {
        return item instanceof Map<?, ?> map
                && map.get("context") instanceof Map<?, ?> context
                && CLIENT_TOOL_KIND.equals(context.get("_interrupt_kind"));
    }

    private static void convertTargetedResults(ServeRequest request, Map<String, Object> metadata,
            Map<String, String> pendingCallsById) {
        if (pendingCallsById.isEmpty()) {
            return;
        }
        Object rawInputs = metadata.get(REMOTE_TOOL_INPUTS);
        if (rawInputs == null) {
            if (pendingCallsById.size() > 1) {
                throw new IllegalArgumentException("Client tool result targets do not match pending calls");
            }
            return;
        }
        Map<String, Object> targetedInputs = copyStringMap(rawInputs, REMOTE_TOOL_INPUTS);
        if (!targetedInputs.keySet().equals(pendingCallsById.keySet())) {
            throw new IllegalArgumentException("Client tool result targets do not match pending calls");
        }
        metadata.remove(REMOTE_TOOL_INPUTS);
        metadata.put(REMOTE_TOOL_RESULTS, targetedInputs);
        request.setMetadata(metadata);
    }

    private boolean belongsToCurrentRequest(AgentCallbackContext ctx) {
        if (ctx == null || ctx.getSession() == null || ctx.getSession().getSessionId() == null) {
            return false;
        }
        String sessionId = ctx.getSession().getSessionId();
        if (matchMode == SessionMatchMode.EXACT) {
            return conversationId.equals(sessionId);
        }
        return derivedSessionPattern.matcher(sessionId).matches();
    }

    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(arguments, MAP_TYPE);
            if (parsed == null) {
                throw new IllegalArgumentException("Client tool arguments must be a JSON object");
            }
            return new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Client tool arguments must be a JSON object", exception);
        }
    }

    private static Set<String> interceptNames(List<ToolInfo> visibleTools, Map<String, String> pendingCallsById) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (visibleTools != null) {
            visibleTools.stream().filter(Objects::nonNull).map(ToolInfo::getName)
                    .filter(name -> name != null && !name.isBlank()).forEach(names::add);
        }
        if (pendingCallsById != null) {
            pendingCallsById.values().stream().filter(Objects::nonNull)
                    .filter(name -> !name.isBlank()).forEach(names::add);
        }
        return names;
    }

    private static Map<String, Object> copyStringMap(Object raw, String field) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private record RequestContext(String conversationId, List<ToolInfo> visibleTools,
            Map<String, String> pendingCallsById) {
        private static RequestContext empty() {
            return new RequestContext("", List.of(), Map.of());
        }

        private boolean isEmpty() {
            return visibleTools.isEmpty() && pendingCallsById.isEmpty();
        }
    }

    private enum SessionMatchMode {
        EXACT,
        DEEP_AGENT_DERIVED
    }

    /**
     * Exact request rail registration. Closing it is idempotent.
     */
    public static final class Binding implements AutoCloseable {
        private static final Binding NOOP = new Binding(null, null, null, true);

        private final BaseAgent target;
        private final ClientToolRail rail;
        private final Object lockOwner;
        private final boolean isNoop;
        private boolean isClosed;

        private Binding(BaseAgent target, ClientToolRail rail, Object lockOwner, boolean isNoop) {
            this.target = target;
            this.rail = rail;
            this.lockOwner = lockOwner;
            this.isNoop = isNoop;
        }

        private static Binding noop() {
            return NOOP;
        }

        @Override
        public void close() {
            if (isNoop || isClosed) {
                return;
            }
            synchronized (lockOwner) {
                if (!isClosed) {
                    target.unregisterRail(rail);
                    isClosed = true;
                }
            }
        }
    }
}
