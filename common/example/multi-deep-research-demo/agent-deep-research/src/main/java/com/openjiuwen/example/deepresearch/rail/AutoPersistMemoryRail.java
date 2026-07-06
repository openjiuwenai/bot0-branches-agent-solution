/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.rail;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.InvokeInputs;
import com.openjiuwen.harness.rails.MemoryRail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MemoryRail extension that deterministically persists each substantive final answer
 * via the {@code afterInvoke} lifecycle hook.
 *
 * <p>The stock {@link MemoryRail} exposes {@code write_memory} as a tool but relies on
 * the LLM to call it before delivering the natural-language answer. In practice the
 * LLM frequently skips this step (prompt-based persistence rules are advisory, not
 * enforced). This subclass sidesteps that by writing the (question, answer) pair to
 * disk from Java once the DeepAgent invoke returns, using the same
 * {@code MemoryRail.invokeMemoryTool("write_memory", ...)} path so the write goes
 * through the same {@code MemoryToolContext} the tools use.
 *
 * <p>Only persists on {@code result_type == "answer"} — error / interrupt / early
 * finish paths are skipped so we never persist half-baked runs.
 */
public class AutoPersistMemoryRail extends MemoryRail {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MIN_QUERY_LEN = 4;
    private static final int SLUG_MAX_LEN = 40;
    private static final String STEERING_PREFIX = "[STEERING]";

    @Override
    public void afterInvoke(AgentCallbackContext ctx) {
        if (ctx == null || toolContext == null) {
            return;
        }
        if (!(ctx.getInputs() instanceof InvokeInputs invokeInputs)) {
            return;
        }

        Map<String, Object> result = invokeInputs.getResult();
        if (result == null || !"answer".equals(String.valueOf(result.get("result_type")))) {
            return;
        }
        Object output = result.get("output");
        if (output == null || String.valueOf(output).isBlank()) {
            return;
        }
        String query = resolveOriginalQuery(ctx, invokeInputs);
        if (query == null || query.strip().length() < MIN_QUERY_LEN) {
            return;
        }

        String date = LocalDate.now().format(DATE_FORMAT);
        String slug = slugify(query);
        String outputStr = String.valueOf(output);
        // core-java's write_memory tool flattens paths to basename, so the file
        // lands at memory/ root regardless. Prefix "answer-" to disambiguate from
        // LLM-authored scratchpads (which use the "notes-" prefix by convention).
        String filename = "answer-" + date + "-" + slug + ".md";

        // (1) Full memory record — indexed by MemoryIndexManager for cross-session recall.
        String memoryContent = renderReport(query, outputStr, date, invokeInputs.getConversationId());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("path", filename);
        args.put("content", memoryContent);
        args.put("append", false);
        try {
            invokeMemoryTool("write_memory", args);
        } catch (Exception ignored) {
            // Fail-open: persistence failure must never break the invoke result.
        }

        // (2) Human-readable report — dropped next to the chart PNGs so a reviewer can
        // open one directory and see both the narrative and the visualisations.
        if (owner != null) {
            try {
                Path reportsDir = owner.getWorkspace().root().resolve("reports");
                Files.createDirectories(reportsDir);
                Files.writeString(reportsDir.resolve(filename), outputStr, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Fail-open: report copy is best-effort.
            }
        }
    }

    /**
     * Return the original human question for this conversation.
     *
     * <p>In a2a interrupt/resume flows every tool call becomes a new {@code streamQuery},
     * so {@link InvokeInputs#getQuery()} carries whatever the last resume payload was
     * (typically a sub-agent's tool response), not the human's original ask. Walk the
     * model context's message history and pick the first genuine user turn — skipping
     * {@code [STEERING]} pseudo-user messages that the runtime injects after each tool
     * result. Fall back to {@code invokeInputs.getQuery()} so persistence still runs
     * even when the context is unavailable.
     */
    private static String resolveOriginalQuery(AgentCallbackContext ctx, InvokeInputs invokeInputs) {
        ModelContext context = ctx.getContext();
        if (context != null) {
            List<BaseMessage> messages = context.getMessages();
            if (messages != null) {
                for (BaseMessage message : messages) {
                    if (message == null || !"user".equals(message.getRole())) {
                        continue;
                    }
                    String content = message.getContentAsString();
                    if (content == null) {
                        continue;
                    }
                    String stripped = content.strip();
                    if (stripped.isEmpty() || stripped.startsWith(STEERING_PREFIX)) {
                        continue;
                    }
                    return stripped;
                }
            }
        }
        return invokeInputs.getQuery();
    }

    private static String slugify(String query) {
        String cleaned = query.strip().replaceAll("[\\s\\p{Punct}]+", "-");
        cleaned = cleaned.replaceAll("^-+|-+$", "");
        if (cleaned.length() > SLUG_MAX_LEN) {
            cleaned = cleaned.substring(0, SLUG_MAX_LEN);
        }
        return cleaned.isEmpty() ? "answer" : cleaned;
    }

    private static String renderReport(String query, String answer, String date, String conversationId) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 自动持久化对话记录\n\n");
        sb.append("- 日期：").append(date).append("\n");
        if (conversationId != null && !conversationId.isBlank()) {
            sb.append("- 会话 ID：").append(conversationId).append("\n");
        }
        sb.append("\n## 用户问题\n\n").append(query.strip()).append("\n");
        sb.append("\n## Agent 回答\n\n").append(answer.strip()).append("\n");
        return sb.toString();
    }
}
