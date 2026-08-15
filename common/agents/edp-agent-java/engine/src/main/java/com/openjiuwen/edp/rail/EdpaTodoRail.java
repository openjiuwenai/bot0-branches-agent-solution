/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.openjiuwen.edp.rail;

import com.openjiuwen.edp.config.ActRuleConfig;
import com.openjiuwen.edp.config.EdpaTodolist;
import com.openjiuwen.edp.config.EdpaTodolist.DynamicPath;
import com.openjiuwen.edp.config.EdpaTodolist.TodoEntry;
import com.openjiuwen.edp.config.ScriptConstants;
import com.openjiuwen.edp.config.ToolConstants;
import com.openjiuwen.edp.enhancer.TodoSessionResolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.DeepAgentRail;
import com.openjiuwen.harness.rails.TaskPlanningRail;
import com.openjiuwen.harness.tools.FileTodoStorage;
import com.openjiuwen.harness.tools.KvTodoStorage;
import com.openjiuwen.harness.tools.TodoItem;
import com.openjiuwen.harness.tools.TodoStatus;
import com.openjiuwen.harness.tools.TodoStorage;
import com.openjiuwen.harness.tools.TodoTool;
import com.openjiuwen.spi.store.BaseKVStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * EDPAgent Todo 增强 Rail。
 *
 * <p>命名说明（v2 §10.2）：从 {@code EdpaTodoCatalogRail} 重命名为 {@code EdpaTodoRail}。
 * 「Catalog」是 todo 内部 {@code entries.catalog_id} 字段语义，留在字段层；
 * 承载它的 Rail 用 Todo 命名，体现"这是 Todo 模块"。</p>
 *
 * <p>文件作用（三职责）：</p>
 * <ul>
 *     <li>init() 注入两段 prompt：{@code edpa_todo_summary}（catalog_id 实体目录 + 用法，
 *         priority=88）与 {@code edpa_path_rules}（动态路径规则，priority=30）。</li>
 *     <li>beforeToolCall() 参数enriched：对 todo_create/todo_modify 按 catalog_id 填充
 *         content/description/skill，并把 catalog_id 写入 {@code meta_data.catalog_id} 作dependency closureanchor
 *         （**不设 depends_on**，因被依赖项 UUID 此时未生成，见 v2 §10.4）。</li>
 *     <li>afterToolCall()（todo_create）dependency closure：从 todos 的 meta_data.catalog_id 建
 *         catalog_id→uuid anchors，查 catalog 的 depends_on，替换成 UUID 写回 save。
 *         保证 {@code depends_on} 是合法 DAG（UUID 引用），而非残留的 catalog_id 字符串。</li>
 * </ul>
 *
 * <p>优先级 priority=95：高于 TaskPlanningRail(90)，保证参数enriched + dependency closure save 先于缓存刷新。
 * 框架排序：数字越大越早执行。</p>
 *
 * <p>Core 语义（v2 §10.1）：Core {@code TodoTool} 的 {@code depends_on} 纯展示元数据，
 * 不门控执行、不查环。任务执行顺序由 LLM 按 prompt 自主遵守；本 Rail 只保证这份元数据是合法 DAG。</p>
 *
 * @since 2024-01-01
 *
 */

public class EdpaTodoRail extends DeepAgentRail {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaTodoRail.class);

    /**
     * Task summary prompt section name.
     */
    public static final String TODO_SUMMARY_SECTION = "edpa_todo_summary";

    /**
     * 路径规则 prompt section 名称（路径规则与 catalog 无关，命名保持）。
     */
    public static final String PATH_RULES_SECTION = "edpa_path_rules";

    /**
     * Task summary section priority (ref demo TodoCatalogRail 88).
     */
    private static final int TODO_SUMMARY_PRIORITY = 88;

    /**
     * 路径规则 section 优先级（越小越靠后，作为补充说明放在末段）。
     */
    private static final int PATH_RULES_PRIORITY = 30;

    private static final String TOOL_TODO_CREATE = ToolConstants.TODO_CREATE;
    private static final String TOOL_TODO_MODIFY = ToolConstants.TODO_MODIFY;

    /**
     * 业务工具：执行前必须已规划 todo（v2 §10.11 守卫）。
     *
     * @param ToolConstants.CALL_MCP the ToolConstants.CALL_MCP value
     * @param ToolConstants.CALL_VERSATILE the ToolConstants.CALL_VERSATILE value
     * @return the result
     */
    private static final Set<String> BUSINESS_TOOLS = Set.of(ToolConstants.CALL_MCP, ToolConstants.CALL_VERSATILE,
            ToolConstants.CALL_SUBAGENT);

    /**
     * 解析 LLM 原始 JSON 字符串形式 toolArgs（Core 经 ToolCallInputs 暴露给 rail 的是 String）。
     *
     * @return the result
     */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final DeepAgent deepAgent;

    private final EdpaTodolist todolist;

    /**
     * agent-core TodoStorage（KvTodoStorage 或 FileTodoStorage），lazy 创建。
     * 替代原 RedisTodoStore，通过 deepAgent.getKvStore() 获取共享 KV 存储。
     */
    private volatile TodoStorage todoStorage;

    /**
     * 行为治理配置，提供 max_subtasks 等执行约束。
     */
    private final ActRuleConfig actrule;

    /**
     * TodoTool 实例（lazy 创建，路径与 Core TaskPlanningRail 一致：.todo）。
     */
    private volatile TodoTool todoTool;

    public EdpaTodoRail(DeepAgent deepAgent, EdpaTodolist todolist) {
        this(deepAgent, todolist, null);
    }

    public EdpaTodoRail(DeepAgent deepAgent, EdpaTodolist todolist, ActRuleConfig actrule) {
        this.deepAgent = deepAgent;
        this.todolist = todolist;
        this.actrule = actrule;
    }

    @Override
    /**
     * Priority.
     *
     * @return the result
     */
    public int priority() {
        return 95;
    }

    /**
     * 注入 todo summary + 路径规则 prompt。
     *
     * <p>本 Rail 在 BaseAgent（ReActAgent）上注册，init 传入的是 ReActAgent。
     * prompt section 经 ReActAgent.addPromptBuilderSection 注入。</p>
     *
     * @param agent the agent value
     */
    @Override
    public void init(Object agent) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            LOGGER.warn("EdpaTodoRail.init: agent is not ReActAgent ({}), skip prompt injection",
                    agent == null ? "null" : agent.getClass().getName());
            return;
        }

        if (todolist != null && !todolist.getEntries().isEmpty()) {
            String summary = buildTodoSummaryPrompt(todolist);
            reActAgent.addPromptBuilderSection(TODO_SUMMARY_SECTION, summary, TODO_SUMMARY_PRIORITY);
            LOGGER.info("EdpaTodoRail injected todo summary section '{}', entries={}", TODO_SUMMARY_SECTION,
                    todolist.getEntries().size());
        }

        if (todolist != null && todolist.hasDynamicPaths()) {
            String prompt = buildPathRulesPrompt(todolist.getDynamicPaths());
            reActAgent.addPromptBuilderSection(PATH_RULES_SECTION, prompt, PATH_RULES_PRIORITY);
            LOGGER.info("EdpaTodoRail injected path rules section '{}', paths={}", PATH_RULES_SECTION,
                    todolist.getDynamicPaths().size());
        }
    }

    @Override
    /**
     * Uninit.
     *
     * @param agent the agent value
     */
    public void uninit(Object agent) {
        if (!(agent instanceof ReActAgent reActAgent)) {
            return;
        }
        try {
            reActAgent.getPromptBuilder().removeSection(TODO_SUMMARY_SECTION);
        } catch (IllegalStateException e) {
            LOGGER.debug("EdpaTodoRail.uninit: removeSection '{}' ignored: {}", TODO_SUMMARY_SECTION, e.getMessage());
        }
        try {
            reActAgent.getPromptBuilder().removeSection(PATH_RULES_SECTION);
        } catch (IllegalStateException e) {
            LOGGER.debug("EdpaTodoRail.uninit: removeSection '{}' ignored: {}", PATH_RULES_SECTION, e.getMessage());
        }
    }

    /**
     * 参数enriched：catalog_id → content/description/skill + meta_data anchor；
     * 且对业务工具强制「先规划后执行」守卫（v2 §10.11）。
     *
     * <p>对 todo_create / todo_modify 生效（enriched字段、打anchor）。</p>
     *
     * <p><b>规划前置守卫（bug 修复）</b>：当业务工具（call_mcp / call_versatile / call_subagent）被调用、
     * 而当前会话尚未通过 todo_create 规划任何任务时，跳过本次工具执行并返回 PLAN_FIRST 合成结果，
     * 同时推送 steering 强制 LLM 先用 todo_create 规划。这根治「LLM 跳过规划直接调工具」的提示词不可靠问题
     * （harness SkillUseRail 的"先读 SKILL.md"指令与 EDPA"先规划"指令竞争，纯提示词无法保证）。</p>
     *
     * @param ctx the ctx value
     */
    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();

        // ★ 动态注入当前session的活跃todo状态，让LLM看到已有任务列表
        injectActiveTodoStatus(ctx);

        // 守卫：业务工具执行前必须已规划 todo。
        if (BUSINESS_TOOLS.contains(toolName)) {
            enforcePlanBeforeBusinessTool(ctx, inputs, toolName);
            return;
        }

        if (!TOOL_TODO_CREATE.equals(toolName) && !TOOL_TODO_MODIFY.equals(toolName)) {
            return;
        }

        // Core TaskPlanningRail 的 sessionId(inputs) 读 toolArgs 里的 session_id，LLM 不传则兜底 "default"，
        // 导致所有会话共用 .todo/default/ 互相覆盖。这里注入「转义后的真实 sessionId」，
        // 使 Core 落盘按real session隔离；railedExecuteSingleToolCall 会把改后的 args 回写到 toolCall.arguments。
        Map<String, Object> args = TodoSessionResolver.normalizeArgs(inputs.getToolArgs());
        String realSid = TodoSessionResolver
                .sanitizeSessionId(ctx.getSession() != null ? ctx.getSession().getSessionId() : null);
        boolean sidChanged = injectRealSessionId(args, realSid);

        // 参数兼容：LLM 有时用 updates[].task_id 而非 todos[].id，
        // Core TodoTool 只处理 todos，需将 updates 转换为 todos 格式。
        boolean normalized = normalizeTodoModifyArgs(inputs, args, toolName);

        boolean enriched = enrichAndValidateTasks(inputs, args, toolName, ctx);

        if (sidChanged || enriched || normalized) {
            // 传 Map：railedExecuteSingleToolCall 会序列化为 toolCall.arguments 供 Core 执行。
            inputs.setToolArgs(args);
            if (sidChanged) {
                LOGGER.info("[EDPA-DIAG] INJECT tool={} session_id={} (real session, path escaped)", toolName, realSid);
            }
        }
    }

    /**
     * Inject the real (sanitized) session id into tool args when the LLM omitted session_id.
     *
     * <p>Core TaskPlanningRail reads session_id from toolArgs; if absent it falls back to "default",
     * causing all sessions to share .todo/default/ and overwrite each other. This injects the
     * escaped real sessionId so Core persists per-real-session.</p>
     *
     * @param args    the normalized tool arguments (mutated in place)
     * @param realSid the sanitized real session id, may be null
     * @return true if session_id was injected (args changed)
     */
    private boolean injectRealSessionId(Map<String, Object> args, String realSid) {
        Object prevSid = args.get("session_id");
        if ((prevSid == null || String.valueOf(prevSid).isBlank()) && realSid != null) {
            args.put("session_id", realSid);
            return true;
        }
        return false;
    }

    /**
     * Normalize todo_modify args: convert updates[].task_id to todos[].id format.
     *
     * <p>LLM sometimes uses updates[].task_id instead of todos[].id. Core TodoTool only
     * processes todos, so we need to convert updates to todos format. Also remaps
     * task_id key to id within each item.</p>
     *
     * @param inputs   the tool call inputs
     * @param args     the normalized tool arguments (mutated in place)
     * @param toolName the tool name
     * @return true if args were normalized (updates→todos conversion happened)
     */
    private boolean normalizeTodoModifyArgs(ToolCallInputs inputs, Map<String, Object> args, String toolName) {
        if (!TOOL_TODO_MODIFY.equals(toolName) || !args.containsKey("updates") || args.containsKey("todos")) {
            return false;
        }
        Object updatesObj = args.get("updates");
        if (!(updatesObj instanceof List<?> updates)) {
            return false;
        }
        List<Map<String, Object>> todos = new ArrayList<>();
        for (Object u : updates) {
            if (u instanceof Map<?, ?> raw) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : raw.entrySet()) {
                    String key = "task_id".equals(e.getKey()) ? "id" : String.valueOf(e.getKey());
                    m.put(key, e.getValue());
                }
                todos.add(m);
            }
        }
        args.put("todos", todos);
        inputs.setToolArgs(args);
        LOGGER.info("[EDPA-DIAG] NORMALIZE tool=todo_modify updates->todos items={}", todos.size());
        return true;
    }

    /**
     * Enrich todo_create tasks with catalog_id anchors and validate subtask count limits.
     *
     * <p>For todo_create, walks each task in tasks[] to stamp meta_data.catalog_id anchor and
     * fill content/description/skill via enrichArgs. The anchor is used by afterToolCall
     * buildAnchors for catalog_id->UUID dependency mapping.</p>
     *
     * <p>Also enforces actrule.max_subtasks: if exceeded, sets KEY_SKIP_TOOL and a synthetic
     * MAX_SUBTASKS_EXCEEDED tool result, blocking execution.</p>
     *
     * @param inputs   the tool call inputs
     * @param args     the normalized tool arguments (tasks[] read from here)
     * @param toolName the tool name
     * @param ctx      the agent callback context
     * @return true if tasks were enriched
     */
    private boolean enrichAndValidateTasks(ToolCallInputs inputs, Map<String, Object> args,
            String toolName, AgentCallbackContext ctx) {
        boolean enriched = false;
        if (todolist != null && TOOL_TODO_CREATE.equals(toolName)) {
            enriched = enrichTasks(args.get("tasks"));
            if (enriched) {
                LOGGER.info("[EDPA-DIAG] ENRICH tool=todo_create enriched tasks[] with meta_data.catalog_id anchor");
            }
        }

        if (actrule != null && actrule.getMaxSubtasks() != null && actrule.getMaxSubtasks() > 0
                && TOOL_TODO_CREATE.equals(toolName)) {
            int taskCount = countTasks(args.get("tasks"));
            if (taskCount > actrule.getMaxSubtasks()) {
                LOGGER.info("[EDPA-DIAG] MAX_SUBTASKS tool=todo_create taskCount={} > maxSubtasks={}, blocked",
                        taskCount, actrule.getMaxSubtasks());
                ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);
                String synthetic = "{\"error\":\"MAX_SUBTASKS_EXCEEDED\","
                        + "\"message\":\"子任务数量 " + taskCount + " 超过上限 "
                        + actrule.getMaxSubtasks() + "，请精简任务列表后重试。\"}";
                inputs.setToolResult(synthetic);
                ToolCall tc = inputs.getToolCall();
                String callId = tc != null ? tc.getId() : "";
                inputs.setToolMsg(ToolMessage.builder().content(synthetic).toolCallId(callId).build());
            }
        }

        return enriched;
    }

    /**
     * 遍历 todo_create 的 tasks[]，对每个带 catalog_id 的 task 调 enrichArgs enriched
     * （含 meta_data.catalog_id anchor）。
     * Jackson 解析出的 task 是可变 LinkedHashMap，原地修改后随 args 一起回写。
     *
     * @param tasksObj the tasksObj value
     * @return the result
     */

    @SuppressWarnings("unchecked")
    private boolean enrichTasks(Object tasksObj) {
        if (!(tasksObj instanceof List<?> tasks) || todolist == null) {
            return false;
        }
        boolean enriched = false;
        for (Object t : tasks) {
            if (!(t instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> task = (Map<String, Object>) t;
            String cid = str(task.get("catalog_id")).orElse(null);
            if (cid == null) {
                continue;
            }
            TodoEntry entry = todolist.findByCatalogId(cid);
            if (entry != null) {
                if (enrichArgs(task, entry)) {
                    enriched = true;
                }
            } else {
                LOGGER.info("[EDPA-DIAG] ENRICH task catalog_id={} not found in todolist.entries", cid);
            }
        }
        return enriched;
    }

    /**
     * 规划前置守卫（v2 §10.11，bug 修复）。
     *
     * <p>业务工具（call_mcp / call_versatile）执行前，若当前会话尚未规划任何 todo，
     * 则跳过本次工具执行（_skip_tool），返回 PLAN_FIRST 合成结果，并 pushSteering 强制 LLM 先 todo_create。
     * 机制与 {@code BaseInterruptRail.reject} 一致：设置 _skip_tool + 合成 toolResult/toolMsg，
     * 框架 {@code AbilityManager} 据此跳过真实执行。</p>
     *
     * @param ctx the ctx value
     * @param inputs the inputs value
     * @param toolName the toolName value
     */

    private void enforcePlanBeforeBusinessTool(AgentCallbackContext ctx, ToolCallInputs inputs, String toolName) {
        if (hasPlannedTodos(ctx)) {
            // 清理上一轮 PLAN_FIRST 残留标记（ctx.extra 在同会话工具调用间共享，避免误判后续放行工具为blocked）。
            ctx.getExtra().remove(ScriptConstants.KEY_PLAN_FIRST_BLOCK);
            LOGGER.info("[EDPA-DIAG] PLAN_GUARD tool={} PASS (session planned todo, allow business tool)", toolName);
            return; // 已规划，放行
        }
        LOGGER.info(
                "[EDPA-DIAG] PLAN_GUARD tool={} BLOCK "
                        + "(session not planned, block and steering LLM to call todo_create first)",
                toolName);
        String synthetic = "{\"error\":\"PLAN_FIRST\",\"message\":\"BLOCKED: 业务工具 " + toolName
                + " 被blocked。你必须先调用 todo_create 按 catalog_id 创建任务列表，规划完整执行步骤后，"
                + "才能调用业务工具。请立即调用 todo_create，不要直接回答用户。\"}";
        ctx.getExtra().put(ScriptConstants.KEY_SKIP_TOOL, Boolean.TRUE);

        // 额外打 PLAN_FIRST blocked标记：区分「真blocked(未规划,不发 tool_start/tool_end)」
        // 与「中断接管型工具(Versatile/McpInterruptRail 设 _skip_tool 但已执行真实调用,应发 tool_start/tool_end)」。
        ctx.getExtra().put(ScriptConstants.KEY_PLAN_FIRST_BLOCK, Boolean.TRUE);
        inputs.setToolResult(synthetic);
        ToolCall tc = inputs.getToolCall();
        String callId = tc != null ? tc.getId() : "";
        inputs.setToolMsg(ToolMessage.builder().content(synthetic).toolCallId(callId).build());

        // steering 额外强化（若 ctx 已绑定 steeringQueue 则生效）
        ctx.pushSteering("系统强制要求：执行任何业务工具（call_mcp/call_versatile/call_subagent）"
                + "前必须先用 todo_create 按 catalog_id 创建任务列表。你刚才调用 "
                + toolName + " 被blocked了。请立即调用 todo_create 规划任务，" + "然后再继续。不要跳过规划直接回答。");
    }

    /**
     * 当前会话是否已规划 todo：主路径从 TodoTool 落盘文件读（按转义后的真实 sessionId）；
     * 兜底（TodoTool 不可用，如单元测试 mock 无 workspace）从 TaskPlanningRail 缓存读。
     *
     * <p>不优先读 TaskPlanningRail 缓存：缓存键为原始 sessionId（含冒号），Core loadTodos 时
     * filePath 非法→缓存恒空。落盘键则是注入的转义 sessionId，二者不一致，故绕过缓存直读文件。
     * 仅当 TodoTool 不可用时回落缓存，保证守卫逻辑可被确定性单测驱动；生产环境 workspace
     * 就绪后 TodoTool 可创建，兜底不会触发。</p>
     *
     * @param ctx the ctx value
     * @return the result
     */

    private boolean hasPlannedTodos(AgentCallbackContext ctx) {
        if (deepAgent == null || ctx.getSession() == null) {
            return false;
        }
        String rawSid = ctx.getSession().getSessionId();

        // ★ agent-core TodoStorage：load 后检查非空（TodoStorage 无 exists 方法）
        Optional<TodoStorage> storageOpt = getTodoStorage();
        if (storageOpt.isPresent()) {
            String sid = TodoSessionResolver.sanitizeSessionId(rawSid);
            try {
                List<TodoItem> todos = storageOpt.get().load(sid);
                return todos != null && !todos.isEmpty();
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("hasPlannedTodos storage load failed: {}", e.getMessage());
            }
        }

        // 兜底：TodoStorage 不可用时从 TaskPlanningRail 缓存读
        List<TodoItem> cached = loadFromTaskPlanningCache(rawSid);
        return cached != null && !cached.isEmpty();
    }

    /**
     * lazy 创建 TodoStorage，通过 deepAgent.getKvStore() 获取共享 KV 存储。
     * kvStore 为空时回落到 FileTodoStorage。
     *
     * @return TodoStorage 实例的 Optional，workspace 不可用时返回 Optional.empty()
     */
    private Optional<TodoStorage> getTodoStorage() {
        if (todoStorage != null) {
            return Optional.of(todoStorage);
        }
        try {
            BaseKVStore kvStore = deepAgent.getKvStore();
            if (kvStore != null) {
                todoStorage = new KvTodoStorage(kvStore);
                return Optional.of(todoStorage);
            }
        } catch (IllegalStateException | NullPointerException e) {
            LOGGER.debug("getTodoStorage: kvStore unavailable: {}", e.getMessage());
        }
        try {
            java.nio.file.Path todoDir = deepAgent.getWorkspace().root().resolve(".todo");
            todoStorage = new FileTodoStorage(todoDir);
            return Optional.of(todoStorage);
        } catch (IllegalStateException | NullPointerException e) {
            LOGGER.warn("getTodoStorage: workspace unavailable: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 从已注册的 TaskPlanningRail 缓存读 todos（TodoTool 不可用时的兜底）。
     *
     * @param sid the sid value
     * @return the result
     */

    private List<TodoItem> loadFromTaskPlanningCache(String sid) {
        try {
            for (Object rail : deepAgent.getRegisteredRails()) {
                if (rail instanceof TaskPlanningRail tpr) {
                    return tpr.cachedTodos(sid);
                }
            }
        } catch (IllegalStateException e) {
            LOGGER.debug("EdpaTodoRail.loadFromTaskPlanningCache failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * dependency closure（v2 §10.4）：仅 todo_create 时一次性 bootstrap；
     * todo_modify 后触发 UC-10 全部完成检测。
     *
     * <p>todo_create 执行后所有 todo 的 UUID 已生成，从 {@code meta_data.catalog_id} 建 anchors，
     * 查 catalog 的 depends_on 替换成 UUID 写回 save。todo_modify 时不重推（保留 LLM 的 cancel 等改动）。</p>
     *
     * <p>使用 agent-core 的 TodoStorage 统一存储（KvTodoStorage 走 Redis，FileTodoStorage 走文件），
     * 与 Core TaskPlanningRail 共享同一存储，无需 dual-path sync。</p>
     *
     * @param ctx the ctx value
     */
    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        if (todolist == null || todolist.getEntries().isEmpty() || deepAgent == null) {
            return;
        }
        if (!(ctx.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();

        // create: bootstrap dependency closure; modify: trigger final_answer directive injection check
        boolean isCreate = TOOL_TODO_CREATE.equals(toolName);
        boolean isModify = TOOL_TODO_MODIFY.equals(toolName);
        if (!isCreate && !isModify) {
            return;
        }

        String sessionId = resolveSessionId(inputs);
        Optional<TodoStorage> storageOpt = getTodoStorage();
        if (storageOpt.isEmpty()) {
            if (isModify) {
                injectFinalAnswerDirective(ctx);
            }
            return;
        }
        TodoStorage storage = storageOpt.get();
        try {
            List<TodoItem> todos = storage.load(sessionId);
            if (todos == null || todos.isEmpty()) {
                LOGGER.info("[EDPA-DIAG] DEP_CLOSURE todo_{} after todos empty, sessionId={}, skip", toolName,
                        sessionId);
                return;
            }
            if (isCreate) {
                Map<String, String> anchors = buildAnchors(todos);
                Map<String, List<String>> depMap = resolveDependencyMap(anchors, todolist);
                boolean changed = applyDependencies(todos, depMap);
                LOGGER.info("[EDPA-DIAG] DEP_CLOSURE sessionId={}, todos={}, anchors={}, depChanged={}, depMap={}",
                        sessionId, todos.size(), anchors, changed, depMap);
                if (changed) {
                    storage.save(sessionId, todos);
                    LOGGER.info("[EDPA-DIAG] DEP_CLOSURE deps written back to storage "
                            + "(catalog_id->UUID replacement complete)");
                }
            }
            if (isModify) {
                injectFinalAnswerDirective(ctx, sessionId, todos);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[EDPA-DIAG] DEP_CLOSURE dependency closure failed: {}", e.getMessage(), e);
        }
    }

    /**
     * UC-10：检测全部任务完成后注入 final_answer 指令。
     *
     * <p>调用时机：afterToolCall 中 todo_modify 后触发（TodoStorage 不可用时的兜底重载）。
     * 从 TodoStorage 读取 todos，如果全部 COMPLETED/DONE，pushSteering 引导 LLM 输出 final_answer。</p>
     *
     * @param ctx the ctx value
     */
    private void injectFinalAnswerDirective(AgentCallbackContext ctx) {
        String rawSid = ctx.getSession() != null ? ctx.getSession().getSessionId() : null;
        if (rawSid == null || rawSid.isBlank()) {
            return;
        }
        Optional<TodoStorage> storageOpt = getTodoStorage();
        if (storageOpt.isEmpty()) {
            return;
        }
        String sid = TodoSessionResolver.sanitizeSessionId(rawSid);
        try {
            List<TodoItem> todos = storageOpt.get().load(sid);
            injectFinalAnswerDirective(ctx, sid, todos);
        } catch (IOException | IllegalStateException e) {
            LOGGER.warn("[EDPA-DIAG] UC10_CHECK_FAILED session={} error={}", sid, e.getMessage());
        }
    }

    /**
     * UC-10 重载：使用预读的 todos，避免 afterToolCall 重复 load。
     *
     * @param ctx  回调上下文
     * @param sessionId 会话 ID（已转义）
     * @param todos 预读的 todos（可为 null/空）
     */
    private void injectFinalAnswerDirective(AgentCallbackContext ctx, String sessionId, List<TodoItem> todos) {
        try {
            if (todos == null || todos.isEmpty()) {
                LOGGER.info("[EDPA-DIAG] UC10_CHECK session={} todos=empty -> skip inject", sessionId);
                return;
            }
            boolean allCompleted = todos.stream().allMatch(EdpaTodoRail::isCompletedLike);
            String statusSummary = todos.stream()
                    .map(t -> t.getContent() + "=" + (t.getStatus() == null ? "null" : t.getStatus().name()))
                    .reduce((a, b) -> a + "," + b).orElse("");
            if (allCompleted) {
                LOGGER.info(
                        "[EDPA-DIAG] UC10_ALL_COMPLETED session={} todos={} statuses=[{}] "
                                + "-> inject final_answer directive",
                        sessionId, todos.size(), statusSummary);
                ctx.pushSteering("所有任务已完成。请直接输出最终回答（final_answer），"
                        + "总结执行结果，不要再调用任何工具。");
            } else {
                LOGGER.info("[EDPA-DIAG] UC10_NOT_ALL_COMPLETED session={} todos={} statuses=[{}] -> skip inject",
                        sessionId, todos.size(), statusSummary);
            }
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDPA-DIAG] UC10_CHECK_FAILED session={} error={}", sessionId, e.getMessage());
        }
    }

    private static boolean isCompletedLike(TodoItem todo) {
        if (todo == null || todo.getStatus() == null) {
            return false;
        }
        TodoStatus s = todo.getStatus();
        return s == TodoStatus.COMPLETED || s == TodoStatus.DONE || s == TodoStatus.CANCELLED;
    }

    /**
     * 把 dynamic_paths 列表渲染为路径规则 prompt 文本。
     *
     * <p>v2 §10.5：路径切换简化为只 cancel（Core 不门控依赖，cancel 后执行自动可继续，无需 rewire）。</p>
     *
     * @param paths 路径规则列表
     * @return 路径规则 prompt 文本，paths is empty时返回空串
     *
     */

    public static String buildPathRulesPrompt(List<DynamicPath> paths) {
        if (paths == null || paths.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 动态路径选择规则\n\n");

        // 注：路径选择引导语和决策原则已统一收敛到 planrule.yaml base_protocol 的「动态路径调整」章节，避免重复。

        int index = 1;
        for (DynamicPath path : paths) {
            sb.append("### 路径").append(index).append("：")
                    .append(path.getDescription() != null ? path.getDescription() : path.getPathId()).append("（")
                    .append(path.getPathId()).append("）\n");
            if (path.getTrigger() != null) {
                sb.append("- 触发条件：").append(path.getTrigger()).append("\n");
            }
            sb.append("- 操作：\n");
            if (!path.getSkipSteps().isEmpty()) {
                sb.append("  1. 调用 todo_modify 将 ").append(String.join("、", path.getSkipSteps()))
                        .append(" 对应的任务标记为 cancelled（通过 todo_list 获取其 id 后 cancel）\n");
            }
            if (path.getRedirect() != null) {
                sb.append("  2. ").append(path.getRedirect()).append("，继续后续任务\n");
            }
            sb.append("\n");
            index++;
        }
        return sb.toString();
    }

    // ── dependency closure纯静态逻辑（参考 demo TodoCatalogRail，可单测） ──

    /**
     * 从 todos 的 meta_data.catalog_id 建 {catalog_id: uuid} anchors 映射。
     *
     * @param todos the todos value
     * @return the result
     */

    static Map<String, String> buildAnchors(List<TodoItem> todos) {
        Map<String, String> anchors = new LinkedHashMap<>();
        for (TodoItem item : todos) {
            Map<String, Object> meta = item.getMetaData();
            if (meta != null && meta.containsKey("catalog_id")) {
                String catalogId = String.valueOf(meta.get("catalog_id"));
                anchors.put(catalogId, item.getId());
            }
        }
        return anchors;
    }

    /**
     * 依赖图计算（纯静态）。
     *
     * <p>从 anchors（catalog_id→uuid）+ todolist 还原每个 todo 应有的 depends_on（UUID 形式）。
     * fail-fast：被依赖的 catalog_id 在 anchors 中找不到映射时抛 IllegalStateException，
     * 杜绝 catalog_id 残留进 depends_on。</p>
     *
     * @param anchors the anchors value
     * @param todolist the todolist value
     * @return the result
     */

    static Map<String, List<String>> resolveDependencyMap(Map<String, String> anchors, EdpaTodolist todolist) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : anchors.entrySet()) {
            String cid = e.getKey();
            String uuid = e.getValue();
            TodoEntry entry = todolist.findByCatalogId(cid);
            if (entry == null) {
                continue; // anchors 中的 cid 不在 catalog（自由任务），跳过
            }
            List<String> deps = entry.getDependsOn();
            if (deps.isEmpty()) {
                result.put(uuid, List.of());
                continue;
            }
            List<String> depUuids = new ArrayList<>();
            for (String dep : deps) {
                String depUuid = anchors.get(dep);
                if (depUuid == null) {
                    throw new IllegalStateException("依赖 catalog_id 未找到: " + dep + "（被依赖项尚未创建）");
                }
                depUuids.add(depUuid);
            }
            result.put(uuid, depUuids);
        }
        return result;
    }

    /**
     * 应用依赖图到 TodoItem（唯一触碰 TodoItem 处）。
     *
     * @param todos the todos value
     * @param depMap the depMap value
     * @return true 表示有改动需 save
     */
    static boolean applyDependencies(List<TodoItem> todos, Map<String, List<String>> depMap) {
        boolean changed = false;
        for (TodoItem item : todos) {
            List<String> newDeps = depMap.get(item.getId());
            if (newDeps != null && !newDeps.equals(item.getDependsOn())) {
                item.setDependsOn(newDeps);
                changed = true;
            }
        }
        return changed;
    }

    // ── 钩子内部辅助 ──

    /**
     * sessionId 对齐 Core TaskPlanningRail：从 toolArgs 取 session_id（beforeToolCall 已注入转义后的真实 sessionId）。
     * 必须与 Core 落盘键一致才能读写同一份 .todo/{sessionId}/todo.json。
     *
     * @param inputs the inputs value
     * @return the result
     */

    private static String resolveSessionId(ToolCallInputs inputs) {
        Map<String, Object> args = TodoSessionResolver.normalizeArgs(inputs.getToolArgs());
        Object value = args.get("session_id");
        if (value != null && !String.valueOf(value).isBlank()) {
            return String.valueOf(value);
        }
        return "default";
    }

    /**
     * lazy 创建 TodoTool，路径与 Core TaskPlanningRail 一致（.todo）。
     *
     * @return the result
     */

    private Optional<TodoTool> getTodoTool() {
        if (todoTool != null) {
            return Optional.of(todoTool);
        }
        try {
            String todoPath = deepAgent.getWorkspace().root().resolve(".todo").toString();
            todoTool = new TodoTool(todoPath);
            return Optional.of(todoTool);
        } catch (IllegalStateException e) {
            LOGGER.error("EdpaTodoRail failed to create TodoTool: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 删除 Core TodoTool 落盘的 todo.json 文件（Redis 成为唯一数据源后无需保留）。
     *
     * @param sid the sid value
     */

    private void deleteTodoFile(String sid) {
        try {
            java.nio.file.Path todoRoot = deepAgent.getWorkspace().root().resolve(".todo");
            java.nio.file.Path sessionDir = todoRoot.resolve(sid);
            java.nio.file.Path todoFile = sessionDir.resolve("todo.json");
            java.nio.file.Files.deleteIfExists(todoFile);

            // 目录空了也删
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(sessionDir)) {
                if (s.findAny().isEmpty()) {
                    java.nio.file.Files.deleteIfExists(sessionDir);
                }
            }
        } catch (IOException ignored) {
            LOGGER.warn("EdpaTodoRail.deleteTodoFile failed: {}", ignored.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeArgs(Object rawArgs) {
        if (rawArgs instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }

        // Core 通过 ToolCallInputs 暴露给 rail 的 toolArgs 是 LLM 原始 JSON 字符串（非 Map），
        // 必须解析才能取到 catalog_id / tasks 等字段（修复此前 enrichArgs 恒返回空的 Bug）。
        if (rawArgs instanceof String s && !s.isBlank()) {
            try {
                Map<String, Object> parsed = JSON_MAPPER.readValue(s, Map.class);
                return parsed != null ? parsed : new LinkedHashMap<>();
            } catch (JsonProcessingException ignored) {
                // 解析失败则按空参数处理
            }
        }
        return new LinkedHashMap<>();
    }

    /**
     * Sanitize session id.
     *
     * @param sessionId the sessionId value
     * @return the result
     */
    public static String sanitizeSessionId(String sessionId) {
        String safe = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        return safe.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 参数enriched（v2 §10.4）：填充 content/description/skill，meta_data 打 catalog_id anchor，不设 depends_on。
     *
     * @param args the args value
     * @param entry the entry value
     * @return the result
     */

    @SuppressWarnings("unchecked")
    private static boolean enrichArgs(Map<String, Object> args, TodoEntry entry) {
        boolean changed = false;
        if (entry.getContent() != null && !args.containsKey("content")) {
            args.put("content", entry.getContent());
            changed = true;
        }

        // activeForm 是 todo_create schema 的必填字段（required:["content","activeForm","description"]），
        // LLM 按 catalog_id 模板调用时常只给 catalog_id，缺 activeForm 会被 Core 拒绝（Task missing 'activeForm'）。
        // TodoEntry 无 activeForm，默认取 content 兜底，保证创建成功。
        if (entry.getContent() != null && !args.containsKey("activeForm")) {
            args.put("activeForm", entry.getContent());
            changed = true;
        }
        if (entry.getDescription() != null && !args.containsKey("description")) {
            args.put("description", entry.getDescription());
            changed = true;
        }

        // ★ 不设 depends_on：被依赖项 UUID 此时未生成，依赖在 afterToolCall 由 UUID 还原。

        // meta_data 合并：把 catalog_id（dependency closureanchor）+ skill 一起写入
        Map<String, Object> meta = new LinkedHashMap<>();
        Object existing = args.get("meta_data");
        if (existing instanceof Map<?, ?> m) {
            m.forEach((k, v) -> meta.put(String.valueOf(k), v));
        } else if (existing instanceof String s && !s.isBlank()) {
            LOGGER.debug("EdpaTodoRail.enrichArgs: meta_data is a JSON string, skip parsing");
        } else {
            LOGGER.warn("unexpected meta_data type: {}", existing == null ? "null" : existing.getClass());
        }
        boolean metaChanged = false;
        if (entry.getCatalogId() != null && !meta.containsKey("catalog_id")) {
            meta.put("catalog_id", entry.getCatalogId());
            metaChanged = true;
        }
        if (entry.getSkill() != null && !meta.containsKey("skill")) {
            meta.put("skill", entry.getSkill());
            metaChanged = true;
        }
        if (metaChanged) {
            args.put("meta_data", meta);
            changed = true;
        }
        return changed;
    }

    private static Optional<String> str(Object value) {
        return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
    }

    private static int countTasks(Object tasksObj) {
        if (tasksObj instanceof List<?> tasks) {
            return tasks.size();
        }
        return 0;
    }

    /**
     * 构建 todo summary prompt（参考 demo TodoCatalog.toCatalogSummary）。
     *
     * <p>契约自洽：本段承诺"依赖由系统自动解析，无需手填 depends_on"，
     * 由 afterToolCall 的 resolveDependencyMap 兑现。</p>
     *
     * @param todolist the todolist value
     * @return the result
     */

    private static String buildTodoSummaryPrompt(EdpaTodolist todolist) {
        if (todolist == null || todolist.getEntries().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        // 注：catalog_id 引导文案、使用示例、依赖说明已统一收敛到 planrule.yaml
        // base_protocol 的「用 catalog_id 创建任务列表」「依赖与顺序」章节，避免重复。
        sb.append("## 任务目录指引\n\n");
        sb.append("### 可用 catalog_id\n\n");
        for (TodoEntry entry : todolist.getEntries()) {
            sb.append("- **").append(entry.getCatalogId()).append("**：");
            sb.append(entry.getContent());
            if (entry.getDescription() != null && !entry.getDescription().isEmpty()) {
                sb.append(" — ").append(entry.getDescription());
            }
            if (!entry.getDependsOn().isEmpty()) {
                appendDependsOn(sb, entry.getDependsOn());
            }
            sb.append("\n");
        }

        // 注：「每个会话只创建一次 todo_create」「COMPLETED/DONE 不可再修改」等 todo_modify 调用约束
        // 已统一收敛到 planrule.yaml base_protocol 的「todo_modify 调用约束」第 6、7 条，
        // 避免与 base_protocol 重复，减少 prompt token 占用。
        return sb.toString();
    }

    private static void appendDependsOn(StringBuilder sb, List<String> deps) {
        sb.append("（depends_on: ");
        for (int i = 0; i < deps.size(); i++) {
            sb.append(deps.get(i));
            if (i < deps.size() - 1) {
                sb.append(", ");
            }
        }
        sb.append("）");
    }

    /**
     * 动态注入当前session的活跃todo状态。
     *
     * <p>每次工具调用前从 TodoStorage 加载todo列表，通过pushSteering注入给LLM。
     * 使LLM看到已有任务列表，自然不会重复调用todo_create。</p>
     *
     * <p>频率控制：用签名去重（todoId:status拼接），状态没变化时不重复注入。</p>
     *
     * @param ctx the ctx value
     */
    private void injectActiveTodoStatus(AgentCallbackContext ctx) {
        Optional<TodoStorage> storageOpt = getTodoStorage();
        if (storageOpt.isEmpty()) {
            return;
        }
        String rawSid = ctx.getSession() != null ? ctx.getSession().getSessionId() : null;
        if (rawSid == null || rawSid.isBlank()) {
            return;
        }
        String sid = TodoSessionResolver.sanitizeSessionId(rawSid);
        try {
            List<TodoItem> todos = storageOpt.get().load(sid);
            if (todos == null || todos.isEmpty()) {
                return;
            }
            if (isSignatureUnchanged(ctx, todos)) {
                return;
            }
            String summary = buildTodoStatusSummary(todos);
            ctx.pushSteering(summary);
            long active = todos.stream().filter(t -> !isCompletedLike(t)).count();
            LOGGER.info("[EDPA-TODO-INJECT] injected active todo status, sid={}, todos={}, active={}", sid,
                    todos.size(), active);
        } catch (IOException | IllegalStateException e) {
            LOGGER.warn("[EDPA-TODO-INJECT] failed, sid={}, error={}", sid, e.getMessage());
        }
    }

    /**
     * 签名去重：todoId:status 拼接，状态没变化时不重复注入。
     *
     * @param ctx   回调上下文
     * @param todos 当前 todo 列表
     * @return true 表示签名未变化（跳过注入）
     */
    private boolean isSignatureUnchanged(AgentCallbackContext ctx, List<TodoItem> todos) {
        String signature = todos.stream()
                .map(t -> t.getId() + ":" + (t.getStatus() != null ? t.getStatus().name() : "null"))
                .reduce("", (a, b) -> a + "," + b);
        String sigKey = "_edp_todo_sig";
        Object prevSig = ctx.getExtra().get(sigKey);
        if (signature.equals(prevSig)) {
            return true;
        }
        ctx.getExtra().put(sigKey, signature);
        return false;
    }

    /**
     * 构建 todo 状态摘要文本。
     *
     * @param todos 当前 todo 列表
     * @return 摘要文本
     */
    private String buildTodoStatusSummary(List<TodoItem> todos) {
        StringBuilder sb = new StringBuilder("【当前任务状态】\n");
        for (TodoItem t : todos) {
            String status = t.getStatus() != null ? t.getStatus().name() : "UNKNOWN";
            String mark;
            if (isCompletedLike(t)) {
                mark = "✓";
            } else if ("IN_PROGRESS".equals(status)) {
                mark = "▶";
            } else {
                mark = "○";
            }
            sb.append(mark).append(" ").append(t.getContent() != null ? t.getContent() : t.getId());
            if (isCompletedLike(t)) {
                sb.append(" (已完成，不可修改)");
            }
            sb.append("\n");
        }
        long active = todos.stream().filter(t -> !isCompletedLike(t)).count();
        if (active > 0) {
            sb.append("请使用 todo_modify 推进任务，不要重新 todo_create。");
        }
        return sb.toString();
    }
}

