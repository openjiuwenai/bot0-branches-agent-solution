/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.llmchain;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.questioner.QuestionerLlmExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt assembly helpers (Python {@code _get_model_input} / history / format / memory / vision).
 *
 * @since 2026-08-26
 */

final class LlmChainPrompt {
    private static final String MEMORY_MESSAGE = "memory_message";
    private static final Map<String, String> ROLE_MAP =
            Map.of("user", "用户", "assistant", "助手", "system", "系统");
    private static final Map<String, String> MESSAGE_TYPE_TO_ROLE =
            Map.of("human", "user", "ai", "assistant", "system", "system");

    private static final Pattern PLACEHOLDER_FIND = Pattern.compile("\\{\\{([^{}]*)\\}\\}");
    private static final Pattern PLACEHOLDER_SAFE =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$");
    private static final Pattern REPR_CONTENT =
            Pattern.compile("content=('(?:\\\\.|[^'])*'|\"(?:\\\\.|[^\"])*\")", Pattern.DOTALL);
    private static final Pattern FENCED_JSON =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private LlmChainPrompt() {}

    static void processInputs(LlmChainConfig config, Map<String, Object> inputs, ModelContext context) {
        if (!config.enableHistory()) {
            inputs.put("CHAT_HISTORY", "");
            return;
        }
        List<Map<String, Object>> chatHistory = getChatHistory(context);
        StringBuilder full = new StringBuilder();
        for (Map<String, Object> history : truncateHistoryByTurn(chatHistory, config.historySize())) {
            full.append(ROLE_MAP.getOrDefault(str(history.get("role")), "用户"))
                    .append("：")
                    .append(str(history.get("content")))
                    .append('\n');
        }
        inputs.put("CHAT_HISTORY", full.toString());
    }

    static List<BaseMessage> getModelInput(
            LlmChainConfig config,
            Map<String, Object> inputs,
            ModelContext context,
            NodeSessionApi session) {
        String userPrompt;
        try {
            String template = config.userTemplate();
            validatePromptTemplate(template);
            userPrompt = renderPrompt(template, inputs);
        } catch (NodeExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeExecutionException(
                    config.nodeId(),
                    LlmChainConfig.JIUWEN_LLM_TYPE,
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "Failed to assemble llm template",
                    e);
        }

        String systemPrompt = null;
        String systemTemplate = config.systemTemplateOrNull();
        if (systemTemplate != null && !systemTemplate.isBlank()) {
            try {
                validatePromptTemplate(systemTemplate);
                systemPrompt = renderPrompt(systemTemplate, inputs);
            } catch (NodeExecutionException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new NodeExecutionException(
                        config.nodeId(),
                        LlmChainConfig.JIUWEN_LLM_TYPE,
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "Failed to assemble llm template",
                        e);
            }
        }

        List<Map<String, Object>> messageDicts;
        if (config.enableHistory()) {
            messageDicts = getHistory(config, userPrompt, systemPrompt, context);
        } else {
            messageDicts = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messageDicts.add(Map.of("role", "system", "content", systemPrompt));
            }
            messageDicts.add(mutableMsg("user", userPrompt));
        }

        insertMemoryMessage(config, messageDicts, inputs, session);
        messageDicts = applyFormatInstructions(config, messageDicts);
        messageDicts = getVision(config, messageDicts, inputs);
        return toBaseMessages(messageDicts);
    }

    static Map<String, Object> formatResponse(
            LlmChainConfig config, String content, String responseType, String reasoningContent) {
        List<Map<String, Object>> outputs = config.outputs();
        Map<String, Object> result = new LinkedHashMap<>();
        if ("json".equals(responseType)) {
            Map<String, Object> parsed = extractJson(content);
            for (Map<String, Object> output : outputs) {
                String outputId = str(output.get("id"));
                if (outputId.isBlank()) {
                    continue;
                }
                if ("reasoning_content".equals(outputId) && reasoningContent != null) {
                    result.put(outputId, reasoningContent);
                } else if (parsed != null && parsed.containsKey(outputId)) {
                    result.put(outputId, parsed.get(outputId));
                } else {
                    result.put(outputId, content);
                }
            }
        } else {
            for (Map<String, Object> output : outputs) {
                String outputId = str(output.get("id"));
                if (outputId.isBlank()) {
                    continue;
                }
                if ("reasoning_content".equals(outputId) && reasoningContent != null) {
                    result.put(outputId, reasoningContent);
                } else {
                    result.put(outputId, content);
                }
            }
        }
        if (result.isEmpty()) {
            result.put("raw_output", content);
        }
        return result;
    }

    static Map<String, Object> extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        Map<String, Object> direct = QuestionerLlmExtractor.parseJsonObject(stripped);
        if (!direct.isEmpty()) {
            return direct;
        }
        Matcher match = FENCED_JSON.matcher(stripped);
        if (match.find()) {
            Map<String, Object> fenced = QuestionerLlmExtractor.parseJsonObject(match.group(1).strip());
            if (!fenced.isEmpty()) {
                return fenced;
            }
        }
        return null;
    }

    static List<Map<String, Object>> applyFormatInstructions(
            LlmChainConfig config, List<Map<String, Object>> messages) {
        Map<String, Object> responseFormat = config.responseFormat();
        String resType = str(responseFormat.getOrDefault("type", "text"));
        if ("text".equals(resType)) {
            return messages;
        }
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(str(messages.get(i).get("role")))) {
                lastUserIdx = i;
                break;
            }
        }
        if (lastUserIdx < 0) {
            return messages;
        }
        String userContent = str(messages.get(lastUserIdx).get("content"));
        String instruction;
        if ("markdown".equals(resType)) {
            instruction = str(responseFormat.get("markdownInstruction"));
            if (instruction.isBlank()) {
                instruction =
                        "Please return the answer in markdown format.\n"
                                + "- For headings, use number signs (#).\n"
                                + "- For list items, start with dashes (-).\n"
                                + "- To emphasize text, wrap it with asterisks (*).\n"
                                + "- For code or commands, surround them with backticks (`).\n"
                                + "- For quoted text, use greater than signs (>).\n"
                                + "- For links, wrap the text in square brackets [], "
                                + "followed by the URL in parentheses ().\n"
                                + "- For images, use square brackets [] for the alt text, "
                                + "followed by the image URL in parentheses ().\n"
                                + "The question is: ${query}.";
            }
        } else if ("json".equals(resType)) {
            instruction = str(responseFormat.get("jsonInstruction"));
            if (instruction.isBlank()) {
                instruction =
                        "Carefully consider the user's question to ensure your answer "
                                + "is logical and makes sense.\n"
                                + "- Make sure your explanation is concise and easy to understand, "
                                + "not verbose.\n"
                                + "- Strictly return the answer in a valid json format only, and "
                                + "\"DO NOT ADD ANY COMMENTS BEFORE OR AFTER IT\".\n"
                                + "The question is: ${query}.";
            }
        } else {
            return messages;
        }
        String escaped = userContent;
        Map<String, Object> updated = new LinkedHashMap<>(messages.get(lastUserIdx));
        updated.put("content", instruction.replace("${query}", escaped));
        List<Map<String, Object>> out = new ArrayList<>(messages);
        out.set(lastUserIdx, updated);
        return out;
    }

    static void validatePromptTemplate(String template) {
        if (template == null || template.isBlank()) {
        return;
    }
        Matcher matcher = PLACEHOLDER_FIND.matcher(template);
        while (matcher.find()) {
            String placeholder = matcher.group(1).strip();
            if (placeholder.isBlank()
                    || placeholder.contains("__")
                    || !PLACEHOLDER_SAFE.matcher(placeholder).matches()) {
                throw new IllegalArgumentException("Invalid or dangerous placeholder: '" + placeholder + "'");
            }
        }
    }

    static String normalizeTemplatePlaceholders(String template) {
        return PLACEHOLDER_FIND.matcher(template).replaceAll(m -> "{{" + m.group(1).strip() + "}}");
    }

    static String renderPrompt(String template, Map<String, Object> inputs) {
        String result = normalizeTemplatePlaceholders(template);
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            String placeholder = "{{" + e.getKey() + "}}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, String.valueOf(e.getValue()));
            }
        }
        Matcher remaining = PLACEHOLDER_FIND.matcher(result);
        if (remaining.find()) {
            throw new NodeExecutionException(
                    "",
                    LlmChainConfig.JIUWEN_LLM_TYPE,
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "Error parsing the placeholder `" + remaining.group(1) + "`.");
        }
        return result;
    }

    static List<Map<String, Object>> truncateHistoryByTurn(List<Map<String, Object>> chatHistory, int numTurns) {
        if (chatHistory == null || chatHistory.isEmpty() || numTurns <= 0) {
            return List.of();
        }
        int startIndex = 0;
        int userCount = 0;
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            if ("user".equals(str(chatHistory.get(i).get("role")))) {
                userCount++;
                if (userCount == numTurns) {
                    startIndex = i;
                    break;
                }
            }
        }
        if (userCount < numTurns) {
            return new ArrayList<>(chatHistory);
        }
        return new ArrayList<>(chatHistory.subList(startIndex, chatHistory.size()));
    }

    static List<Map<String, Object>> getChatHistory(ModelContext context) {
        if (context == null) {
            return List.of();
        }
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (BaseMessage msg : context.getMessages()) {
                out.add(messageToDict(msg));
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static Map<String, Object> messageToDict(Object msg) {
        if (msg instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (msg instanceof BaseMessage bm) {
            String role = bm.getRole() == null ? "" : bm.getRole();
            role = MESSAGE_TYPE_TO_ROLE.getOrDefault(role, role);
            return mutableMsg(role, bm.getContentAsString());
        }
        return mutableMsg("user", String.valueOf(msg));
    }

    private static List<Map<String, Object>> getHistory(
            LlmChainConfig config, String userPrompt, String systemPrompt, ModelContext context) {
        List<Map<String, Object>> chatHistory = getChatHistory(context);
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(mutableMsg("system", systemPrompt));
        }
        for (Map<String, Object> history : truncateHistoryByTurn(chatHistory, config.historySize())) {
            String role = str(history.getOrDefault("role", "user"));
            String content = str(history.get("content"));
            if (("user".equals(role) || "assistant".equals(role) || "system".equals(role))
                    && content != null
                    && !content.isEmpty()) {
                messages.add(mutableMsg(role, content));
            }
        }
        messages.add(mutableMsg("user", userPrompt));
        return messages;
    }

    static void insertMemoryMessage(
            LlmChainConfig config,
            List<Map<String, Object>> messages,
            Map<String, Object> inputs,
            NodeSessionApi session) {
        Map<String, Object> memoryConf = config.memory();
        Object enable = memoryConf.get("enable");
        if (!(enable instanceof Boolean b && b)) {
            return;
        }
        Object memoryMsg = inputs.get(MEMORY_MESSAGE);
        if (memoryMsg == null && session != null) {
            try {
                memoryMsg = session.getGlobalState(MEMORY_MESSAGE);
            } catch (RuntimeException ignored) {
                // mock
            }
        }
        if (memoryMsg == null && session != null) {
            try {
                Map<String, Object> state = session.dumpState();
                if (state != null) {
                    memoryMsg = state.get(MEMORY_MESSAGE);
                    if (memoryMsg == null) {
                        Object gs = state.get("global_state");
                        if (gs instanceof Map<?, ?> gm) {
                            memoryMsg = gm.get(MEMORY_MESSAGE);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // mock
            }
        }
        String memoryContent = extractMemoryContent(memoryMsg);
        if (memoryContent.isBlank()) {
            return;
        }
        int insertIndex = messages.size();
        for (int idx = messages.size() - 1; idx >= 0; idx--) {
            if ("user".equals(str(messages.get(idx).get("role")))) {
                insertIndex = idx;
                break;
            }
        }
        messages.add(insertIndex, mutableMsg("user", memoryContent));
    }

    static String extractMemoryContent(Object memoryMsg) {
        if (memoryMsg == null) {
        return "";
    }
        if (memoryMsg instanceof Map<?, ?> m) {
            Object c = m.get("content");
            return c == null ? "" : String.valueOf(c);
        }
        if (memoryMsg instanceof BaseMessage bm) {
            String c = bm.getContentAsString();
            return c == null ? "" : c;
        }
        if (!(memoryMsg instanceof String s)) {
            return "";
        }
        if (!s.startsWith("type=")) {
            return s;
        }
        Matcher match = REPR_CONTENT.matcher(s);
        if (!match.find()) {
            return s;
        }
        String quoted = match.group(1);
        if (quoted.length() >= 2) {
            return quoted.substring(1, quoted.length() - 1);
        }
        return quoted;
    }

    static List<Map<String, Object>> getVision(
            LlmChainConfig config, List<Map<String, Object>> messages, Map<String, Object> inputs) {
        if (!config.vlEnable()) {
            return messages;
        }
        List<Map<String, Object>> visionItems = new ArrayList<>();
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            String key = e.getKey().toLowerCase();
            if (key.contains("image_vision")) {
                List<?> urls = e.getValue() instanceof List<?> list ? list : List.of(e.getValue());
                for (Object url : urls) {
                    if (url instanceof String s && !s.isBlank()) {
                        visionItems.add(Map.of("type", "image_url", "image_url", Map.of("url", s)));
                    }
                }
            }
            if (key.contains("video_vision")) {
                List<?> urls = e.getValue() instanceof List<?> list ? list : List.of(e.getValue());
                for (Object url : urls) {
                    if (url instanceof String s && !s.isBlank()) {
                        visionItems.add(Map.of("type", "video_url", "video_url", Map.of("url", s)));
                    }
                }
            }
        }
        if (visionItems.isEmpty()) {
            return messages;
        }
        List<Map<String, Object>> out = new ArrayList<>(messages);
        for (int i = out.size() - 1; i >= 0; i--) {
            if ("user".equals(str(out.get(i).get("role")))) {
                List<Object> content = new ArrayList<>();
                content.add(Map.of("type", "text", "text", str(out.get(i).get("content"))));
                content.addAll(visionItems);
                Map<String, Object> updated = new LinkedHashMap<>(out.get(i));
                updated.put("content", content);
                out.set(i, updated);
                break;
            }
        }
        return out;
    }

    static void resolveVisionUrls(LlmChainConfig config, Map<String, Object> inputs) {
        if (!config.vlEnable()) {
        return;
    }
        for (String key : new ArrayList<>(inputs.keySet())) {
            if (!key.toLowerCase().contains("image_vision")) {
                continue;
            }
            Object urls = inputs.get(key);
            List<?> list = urls instanceof List<?> l ? l : List.of(urls);
            List<Object> resolved = new ArrayList<>();
            for (Object url : list) {
                if (url instanceof String s && !s.isBlank()) {
                    resolved.add(resolveImageUrl(s));
                } else {
                    resolved.add(url);
                }
            }
            inputs.put(key, resolved);
        }
    }

    static String resolveImageUrl(String url) {
        if (url.startsWith("data:")) {
        return url;
    }
        try {
            java.net.http.HttpClient client =
                    java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(30))
                            .build();
            java.net.http.HttpRequest request =
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                            .GET()
                            .timeout(java.time.Duration.ofSeconds(30))
                            .build();
            java.net.http.HttpResponse<byte[]> resp =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400) {
                return url;
            }
            String contentType =
                    resp.headers().firstValue("content-type").orElse("image/jpeg");
            if (contentType.contains(";")) {
                contentType = contentType.split(";", 2)[0].strip();
            }
            String b64 = java.util.Base64.getEncoder().encodeToString(resp.body());
            return "data:" + contentType + ";base64," + b64;
        } catch (IOException | InterruptedException e) {
            return url;
        }
    }

    /**
     * Controller-mode memory retrieve. Without agent_runtime retrieval service this is a gated
     * no-op (same soft-fail as Python when retrieve throws).
     *
     * @param messages messages
     * @param inputs inputs
     * @param session session
     * @since 0.1.0
     */

    static void injectRetrievedMemory(
            List<Map<String, Object>> messages, Map<String, Object> inputs, NodeSessionApi session) {
        if (session == null) {
        return;
    }
        try {
            Object emr = session.getGlobalState("enable_memory_retrieve");
            if (emr == null || Boolean.FALSE.equals(emr) || "".equals(String.valueOf(emr))) {
                return;
            }
            Object scopeId = session.getGlobalState("memory_repo_id");
            if (scopeId == null || String.valueOf(scopeId).isBlank()) {
                return;
            }
            Object gvRaw = session.getGlobalState("global_variables");
            String userId = "";
            if (gvRaw instanceof Map<?, ?> gv) {
                userId = str(gv.get("userId"));
                if (userId.isBlank()) {
                    Object sys = gv.get("sys");
                    if (sys instanceof Map<?, ?> sysMap) {
                        userId = str(sysMap.get("userId"));
                    }
                }
            }
            if (userId.isBlank()) {
                return;
            }
            String query = inputs == null ? "" : str(inputs.get("query"));
            if (query.isBlank()) {
                return;
            }
            // No Java retrieve_memory_prompt port — soft no-op (parity with failed retrieve).
        } catch (RuntimeException ignored) {
            // soft-fail
        }
    }

    static List<BaseMessage> toBaseMessages(List<Map<String, Object>> dicts) {
        List<BaseMessage> out = new ArrayList<>();
        for (Map<String, Object> m : dicts) {
            String role = str(m.get("role"));
            Object content = m.get("content");
            if ("system".equals(role)) {
                out.add(new SystemMessage(String.valueOf(content)));
            } else if ("assistant".equals(role)) {
                out.add(new com.openjiuwen.core.foundation.llm.schema.AssistantMessage(String.valueOf(content)));
            } else {
                UserMessage user = new UserMessage(content instanceof String s ? s : "");
                if (!(content instanceof String)) {
                    user.setContent(content);
                }
                out.add(user);
            }
        }
        return out;
    }

    private static Map<String, Object> mutableMsg(String role, Object content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
