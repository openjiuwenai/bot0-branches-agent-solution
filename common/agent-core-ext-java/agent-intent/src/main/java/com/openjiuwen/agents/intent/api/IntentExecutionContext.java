/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.agents.intent.model.IntentCatalogSnapshot;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentMessageSnapshot;
import com.openjiuwen.agents.intent.model.IntentResultArguments;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.IntentToolCallSnapshot;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable single-call data shared by the matcher and selected result function.
 */
public final class IntentExecutionContext {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final IntentSuiteConfig config;
    private final IntentCatalogSnapshot catalogSnapshot;
    private final Map<String, Object> toolInputs;
    private final Map<String, Object> toolKwargs;
    private final String routingSemantic;
    private final Object latestUserInput;
    private final List<IntentMessageSnapshot> conversation;

    private IntentDefinition selectedIntent;

    private IntentExecutionContext(IntentSuiteConfig config, IntentCatalogSnapshot catalogSnapshot,
            Map<String, Object> toolInputs, Map<String, Object> toolKwargs, String routingSemantic,
            Object latestUserInput, List<IntentMessageSnapshot> conversation) {
        this.config = config;
        this.catalogSnapshot = catalogSnapshot;
        this.toolInputs = toolInputs;
        this.toolKwargs = toolKwargs;
        this.routingSemantic = routingSemantic;
        this.latestUserInput = latestUserInput;
        this.conversation = conversation;
    }

    static IntentExecutionContext create(IntentSuiteConfig config, IntentCatalogSnapshot catalogSnapshot,
            Map<String, Object> inputs, Map<String, Object> kwargs) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(catalogSnapshot, "catalogSnapshot");
        Map<String, Object> copiedInputs = immutableShallowCopy(inputs, "inputs");
        Map<String, Object> copiedKwargs = immutableShallowCopy(kwargs, "kwargs");
        String semantic = requireSemantic(copiedInputs.get("semantic"));
        List<IntentMessageSnapshot> messages = snapshotMessages(copiedKwargs.get("context"));
        Object latestInput = latestUserInput(messages);
        return new IntentExecutionContext(config, catalogSnapshot, copiedInputs, copiedKwargs, semantic, latestInput,
                messages);
    }

    void select(IntentDefinition intent) {
        if (selectedIntent != null) {
            throw new IllegalStateException("selectedIntent is already set");
        }
        selectedIntent = Objects.requireNonNull(intent, "intent");
    }

    /**
     * Returns the static suite configuration.
     *
     * @return suite configuration
     */
    public IntentSuiteConfig config() {
        return config;
    }

    /**
     * Returns the catalog snapshot fixed for this call.
     *
     * @return catalog snapshot
     */
    public IntentCatalogSnapshot catalogSnapshot() {
        return catalogSnapshot;
    }

    /**
     * Returns immutable intent Tool inputs.
     *
     * @return Tool inputs
     */
    public Map<String, Object> toolInputs() {
        return toolInputs;
    }

    /**
     * Returns immutable Tool kwargs. Values may contain active runtime references.
     *
     * @return Tool kwargs
     */
    public Map<String, Object> toolKwargs() {
        return toolKwargs;
    }

    /**
     * Returns the semantic supplied to the intent Tool.
     *
     * @return routing semantic
     */
    public String routingSemantic() {
        return routingSemantic;
    }

    /**
     * Returns the latest user content snapshot, if present.
     *
     * @return latest user input snapshot or null
     */
    public Object latestUserInput() {
        return latestUserInput;
    }

    /**
     * Returns the active conversation snapshot.
     *
     * @return immutable messages
     */
    public List<IntentMessageSnapshot> conversation() {
        return conversation;
    }

    /**
     * Returns the selected intent. It is empty during matcher execution.
     *
     * @return selected intent
     */
    public Optional<IntentDefinition> selectedIntent() {
        return Optional.ofNullable(selectedIntent);
    }

    /**
     * Returns immutable arguments bound to the selected intent.
     *
     * @return result function arguments
     */
    public IntentResultArguments resultArguments() {
        if (selectedIntent == null) {
            throw new IllegalStateException("resultArguments are unavailable before intent selection");
        }
        return selectedIntent.resultArguments();
    }

    private static String requireSemantic(Object value) {
        if (!(value instanceof String semantic) || semantic.isBlank()) {
            throw new IllegalArgumentException("inputs.semantic must be a non-blank string");
        }
        return semantic;
    }

    private static Map<String, Object> immutableShallowCopy(Map<String, Object> source, String name) {
        Objects.requireNonNull(source, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static List<IntentMessageSnapshot> snapshotMessages(Object contextValue) {
        if (!(contextValue instanceof ModelContext modelContext)) {
            return List.of();
        }
        List<BaseMessage> messages = modelContext.getMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream().filter(Objects::nonNull).map(IntentExecutionContext::snapshotMessage).toList();
    }

    private static IntentMessageSnapshot snapshotMessage(BaseMessage message) {
        List<IntentToolCallSnapshot> toolCalls = List.of();
        if (message instanceof AssistantMessage assistant && assistant.getToolCalls() != null) {
            toolCalls = assistant.getToolCalls().stream().filter(Objects::nonNull)
                    .map(IntentExecutionContext::snapshotToolCall).toList();
        }
        String toolCallId = message instanceof ToolMessage toolMessage ? toolMessage.getToolCallId() : null;
        return new IntentMessageSnapshot(message.getRole(), snapshotValue(message.getContent()), message.getName(),
                snapshotMetadata(message.getMetadata()), toolCalls, toolCallId);
    }

    private static IntentToolCallSnapshot snapshotToolCall(ToolCall toolCall) {
        return new IntentToolCallSnapshot(toolCall.getId(), toolCall.getName(), toolCall.getArguments());
    }

    private static Map<String, Object> snapshotMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        metadata.forEach((key, value) -> snapshot.put(key, snapshotValue(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object latestUserInput(List<IntentMessageSnapshot> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            IntentMessageSnapshot message = messages.get(index);
            if ("user".equals(message.role())) {
                return message.contentSnapshot();
            }
        }
        return null;
    }

    private static Object snapshotValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float number) {
            return finiteNumber(number.doubleValue(), value);
        }
        if (value instanceof Double number) {
            return finiteNumber(number, value);
        }
        if (value instanceof List<?> list) {
            List<Object> snapshot = new ArrayList<>(list.size());
            list.forEach(item -> snapshot.add(snapshotValue(item)));
            return Collections.unmodifiableList(snapshot);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            map.forEach((key, item) -> snapshot.put(String.valueOf(key), snapshotValue(item)));
            return Collections.unmodifiableMap(snapshot);
        }
        Object converted = OBJECT_MAPPER.convertValue(value, Object.class);
        if (converted == value) {
            return String.valueOf(value);
        }
        return snapshotValue(converted);
    }

    private static Object finiteNumber(double number, Object original) {
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("message snapshot numbers must be finite");
        }
        return original;
    }
}
