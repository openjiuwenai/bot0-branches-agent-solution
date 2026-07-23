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

package com.huawei.ascend.edp.rail;

import com.huawei.ascend.edp.config.ActRuleConfig;
import com.huawei.ascend.edp.config.EdpaTodolist;
import com.huawei.ascend.edp.config.EdpaTodolist.DynamicPath;
import com.huawei.ascend.edp.config.EdpaTodolist.TodoEntry;
import com.huawei.ascend.edp.config.RedisConfig;
import com.huawei.ascend.edp.config.ScriptConstants;
import com.huawei.ascend.edp.config.ToolConstants;
import com.huawei.ascend.edp.enhancer.TodoSessionResolver;
import com.huawei.ascend.edp.todo.RedisTodoStore;

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
import com.openjiuwen.harness.tools.TodoItem;
import com.openjiuwen.harness.tools.TodoStatus;
import com.openjiuwen.harness.tools.TodoTool;

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
    private static final Set<String> BUSINESS_TOOLS = Set.of(ToolConstants.CALL_MCP, ToolConstants.CALL_VERSATILE);

    /**
     * 解析 LLM 原始 JSON 字符串形式 toolArgs（Core 经 ToolCallInputs 暴露给 rail 的是 String）。
     *
     * @return the result
     */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * 持有 DeepAgent 引用，用于在 afterToolCall 中创建 TodoTool（lazy）做dependency closure。
     */
    private final DeepAgent deepAgent;

    private final EdpaTodolist todolist;

    /**
     * Redis Todo 存储（UC-03~UC-11 主路径；可为 null：单测兼容、未启用 Redis 时）。
     */
    private final RedisTodoStore redisTodoStore;

    /**
     * 行为治理配置，提供 max_subtasks 等执行约束。
     */
    private final ActRuleConfig actrule;

    /**
     * TodoTool 实例（lazy 创建，路径与 Core TaskPlanningRail 一致：.todo）。
     */
    private volatile TodoTool todoTool;

    public EdpaTodoRail(DeepAgent deepAgent, EdpaTodolist todolist) {
        this(deepAgent, todolist, null, null);
    }

    public EdpaTodoRail(DeepAgent deepAgent, EdpaTodolist todolist, RedisTodoStore redisTodoStore) {
        this(deepAgent, todolist, redisTodoStore, null);
    }

    public EdpaTodoRail(DeepAgent deepAgent, EdpaTodolist todolist, RedisTodoStore redisTodoStore,
            ActRuleConfig actrule) {
        this.deepAgent = deepAgent;
        this.todolist = todolist;
        this.redisTodoStore = redisTodoStore;
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
     * <p><b>规划前置守卫（bug 修复）</b>：当业务工具（call_mcp / call_versatile）被调用、
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
        boolean sidChanged = false;
        Object prevSid = args.get("session_id");
        if ((prevSid == null || String.valueOf(prevSid).isBlank()) && realSid != null) {
            args.put("session_id", realSid);
            sidChanged = true;
        }

        // catalog_id 参数enriched：todo_create 的 catalog_id 嵌在 tasks[].catalog_id（非顶层），
        // 遍历每个 task 打 meta_data.catalog_id anchor + 补 content/description/skill。
        // dependency closure（afterToolCall buildAnchors）依赖此 meta_data.catalog_id anchor做 catalog_id→UUID 映射。
        boolean enriched = false;
        if (todolist != null && TOOL_TODO_CREATE.equals(toolName)) {
            enriched = enrichTasks(args.get("tasks"));
            if (enriched) {
                LOGGER.info("[EDPA-DIAG] ENRICH tool=todo_create enriched tasks[] with meta_data.catalog_id anchor");
            }
        }

        // 子任务数量上限校验（actrule.max_subtasks）
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
                return;
            }
        }

        if (sidChanged || enriched) {
            // 传 Map：railedExecuteSingleToolCall 会序列化为 toolCall.arguments 供 Core 执行。
            inputs.setToolArgs(args);
            if (sidChanged) {
                LOGGER.info("[EDPA-DIAG] INJECT tool={} session_id={} (real session, path escaped)", toolName, realSid);
            }
        }
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
        ctx.pushSteering("系统强制要求：执行任何业务工具（call_mcp/call_versatile）"
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

        // ★ UC-09：Redis 主路径（仅 EXISTS，不续期 TTL）
        RedisTodoStore activeStore = getActiveRedisTodoStore();
        if (activeStore != null) {
            return activeStore.exists(rawSid);
        }
        try {
            TodoTool tool = getTodoTool().orElse(null);
            if (tool != null) {
                String sid = TodoSessionResolver.sanitizeSessionId(rawSid);
                List<TodoItem> todos = tool.load(sid);
                return todos != null && !todos.isEmpty();
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("hasPlannedTodos check failed: {}", e.getMessage());
        }

        // 兜底：TodoTool 不可用时从 TaskPlanningRail 缓存读
        List<TodoItem> cached = loadFromTaskPlanningCache(rawSid);
        return cached != null && !cached.isEmpty();
    }

    /**
     * ★ 启动竞态修复：优先使用构造注入的 redisTodoStore，为 null 时动态从 RedisConfig 静态持有者获取。
     * <p>原因：Rail 注册时机早于 Spring @Bean redisTodoStore 初始化，
     * 导致构造参数传入的 redisTodoStore 字段始终为 null。</p>
     *
     * @return the result
     */

    private RedisTodoStore getActiveRedisTodoStore() {
        if (this.redisTodoStore != null) {
            return this.redisTodoStore;
        }
        return RedisConfig.getRedisTodoStore();
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
     * <p>存储路径选择（方案 A）：Redis 启用时，dependency closure的 load/save 全部走 Redis，
     * 不再调用 TodoTool 的文件 load/save，消除 EDPA 自身的文件写入路径，避免并发写损坏与文件堆积。
     * Redis 降级时回落文件路径（兼容单测、旧部署）。</p>
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

        String rawSid = ctx.getSession() != null ? ctx.getSession().getSessionId() : null;
        RedisTodoStore activeStore = getActiveRedisTodoStore();
        TodoHandlerContext hc = new TodoHandlerContext(ctx, inputs, rawSid, toolName, isCreate, isModify);
        if (activeStore != null && rawSid != null && !rawSid.isBlank()) {
            handleRedisPath(hc);
            return;
        }
        handleFilePath(hc);
    }

    /**
     * 传递给 handleRedisPath / handleFilePath 的公共参数上下文，避免方法参数超过 5 个（G.MET.01）。
     */
    private record TodoHandlerContext(AgentCallbackContext ctx, ToolCallInputs inputs,
            String rawSid, String toolName, boolean isCreate, boolean isModify) {
    }

    /**
     * Redis path: sync file->Redis, apply dependency closure, check UC-10.
     *
     * @param hc the todo handler context value
     */
    private void handleRedisPath(TodoHandlerContext hc) {
        AgentCallbackContext ctx = hc.ctx();
        ToolCallInputs inputs = hc.inputs();
        String rawSid = hc.rawSid();
        String toolName = hc.toolName();
        boolean isCreate = hc.isCreate();
        boolean isModify = hc.isModify();
        RedisTodoStore activeStore = getActiveRedisTodoStore();
        try {
            List<TodoItem> todos = null;
            TodoTool tool = getTodoTool().orElse(null);
            if (tool != null) {
                String fileSid = resolveSessionId(inputs);
                todos = tool.load(fileSid);
                if (todos != null && !todos.isEmpty()) {
                    activeStore.save(rawSid, todos);
                    LOGGER.info("[EDPA-DIAG] DEP_CLOSURE(REDIS) todo_{} file→Redis sync {} entries, sid={}", toolName,
                            todos.size(), rawSid);
                }
            }
            // fallback: read from Redis if file had no data
            if (todos == null || todos.isEmpty()) {
                todos = activeStore.load(rawSid);
            }
            if (todos == null || todos.isEmpty()) {
                LOGGER.info("[EDPA-DIAG] DEP_CLOSURE(REDIS) todo_{} after todos empty, sid={}, skip", toolName,
                        rawSid);
                if (isModify) {
                    injectFinalAnswerDirective(ctx, rawSid, todos);
                }
                return;
            }
            if (isCreate) {
                applyRedisDependencyClosure(activeStore, rawSid, inputs, todos);
            }
            if (isModify) {
                injectFinalAnswerDirective(ctx, rawSid, todos);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[EDPA-DIAG] DEP_CLOSURE(REDIS) dependency closure failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Apply dependency closure on Redis path: build anchors, resolve deps, save back.
     *
     * @param activeStore the activeStore value
     * @param rawSid the rawSid value
     * @param inputs the inputs value
     * @param todos the todos value
     * @throws IOException the io exception
     */
    private void applyRedisDependencyClosure(RedisTodoStore activeStore, String rawSid,
            ToolCallInputs inputs, List<TodoItem> todos) throws IOException {
        Map<String, String> anchors = buildAnchors(todos);
        Map<String, List<String>> depMap = resolveDependencyMap(anchors, todolist);
        boolean changed = applyDependencies(todos, depMap);
        LOGGER.info("[EDPA-DIAG] DEP_CLOSURE(REDIS) sid={}, todos={}, anchors={}, depChanged={}, depMap={}",
                rawSid, todos.size(), anchors, changed, depMap);
        if (!changed) {
            return;
        }
        activeStore.save(rawSid, todos);
        TodoTool tool = getTodoTool().orElse(null);
        if (tool != null) {
            tool.save(resolveSessionId(inputs), todos);
        }
        LOGGER.info("[EDPA-DIAG] DEP_CLOSURE(REDIS) deps written back to Redis+file "
                + "(catalog_id->UUID replacement complete)");
    }

    /**
     * File fallback path: load/save via TodoTool when Redis is unavailable.
     *
     * @param hc the todo handler context value
     */
    private void handleFilePath(TodoHandlerContext hc) {
        AgentCallbackContext ctx = hc.ctx();
        ToolCallInputs inputs = hc.inputs();
        String rawSid = hc.rawSid();
        String toolName = hc.toolName();
        boolean isCreate = hc.isCreate();
        boolean isModify = hc.isModify();
        TodoTool tool = getTodoTool().orElse(null);
        if (tool == null) {
            if (isModify && getActiveRedisTodoStore() != null) {
                injectFinalAnswerDirective(ctx);
            }
            return;
        }
        String sessionId = resolveSessionId(inputs);
        try {
            List<TodoItem> todos = tool.load(sessionId);
            if (todos == null || todos.isEmpty()) {
                LOGGER.info("[EDPA-DIAG] DEP_CLOSURE(FILE) todo_{} after todos empty, sessionId={}, skip", toolName,
                        sessionId);
                return;
            }
            if (isCreate) {
                Map<String, String> anchors = buildAnchors(todos);
                Map<String, List<String>> depMap = resolveDependencyMap(anchors, todolist);
                boolean changed = applyDependencies(todos, depMap);
                LOGGER.info(
                        "[EDPA-DIAG] DEP_CLOSURE(FILE) sessionId={}, todos={}, anchors={}, depChanged={}, depMap={}",
                        sessionId, todos.size(), anchors, changed, depMap);
                if (changed) {
                    tool.save(sessionId, todos);
                    LOGGER.info("[EDPA-DIAG] DEP_CLOSURE(FILE) deps written back to save "
                            + "(catalog_id->UUID replacement complete)");
                }
            }
            RedisTodoStore syncStore = getActiveRedisTodoStore();
            if (syncStore != null && rawSid != null && !rawSid.isBlank()) {
                List<TodoItem> latest = tool.load(sessionId);
                syncStore.save(rawSid, latest != null ? latest : new ArrayList<>());
            }
            if (isModify) {
                injectFinalAnswerDirective(ctx);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("[EDPA-DIAG] DEP_CLOSURE(FILE) dependency closure failed: {}", e.getMessage(), e);
        }
    }

    /**
     * UC-10：检测全部任务完成后注入 final_answer 指令。
     *
     * <p>调用时机：afterToolCall 中 todo_modify 后触发。
     * 从 Redis 读取 todos，如果全部 COMPLETED/DONE，pushSteering 引导 LLM 输出 final_answer。</p>
     *
     * <p>Redis 降级时 load() 返回空列表，不注入指令，不影响会话继续执行。</p>
     *
     * @param ctx the ctx value
     */

    private void injectFinalAnswerDirective(AgentCallbackContext ctx) {
        RedisTodoStore store = getActiveRedisTodoStore();
        if (store == null) {
            return;
        }
        String rawSid = ctx.getSession() != null ? ctx.getSession().getSessionId() : null;
        if (rawSid == null || rawSid.isBlank()) {
            return;
        }
        try {
            List<TodoItem> todos = store.load(rawSid);
            injectFinalAnswerDirective(ctx, rawSid, todos);
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDPA-DIAG] UC10_CHECK_FAILED session={} error={}", rawSid, e.getMessage());
        }
    }

    /**
     * UC-10 重载：使用预读的 todos，避免 afterToolCall Redis 路径重复 load。
     *
     * @param ctx  回调上下文
     * @param rawSid 原始 sessionId
     * @param todos 预读的 todos（可为 null/空）
     *
     */

    private void injectFinalAnswerDirective(AgentCallbackContext ctx, String rawSid, List<TodoItem> todos) {
        if (getActiveRedisTodoStore() == null || rawSid == null || rawSid.isBlank()) {
            return;
        }
        try {
            if (todos == null || todos.isEmpty()) {
                LOGGER.info("[EDPA-DIAG] UC10_CHECK session={} todos=empty -> skip inject", rawSid);
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
                        rawSid, todos.size(), statusSummary);
                ctx.pushSteering("所有任务已完成。请直接输出最终回答（final_answer），"
                        + "总结执行结果，不要再调用任何工具。");
            } else {
                LOGGER.info("[EDPA-DIAG] UC10_NOT_ALL_COMPLETED session={} todos={} statuses=[{}] -> skip inject",
                        rawSid, todos.size(), statusSummary);
            }
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDPA-DIAG] UC10_CHECK_FAILED session={} error={}", rawSid, e.getMessage());
        }
    }

    private static boolean isCompletedLike(TodoItem todo) {
        if (todo == null || todo.getStatus() == null) {
            return false;
        }
        TodoStatus s = todo.getStatus();
        return s == TodoStatus.COMPLETED || s == TodoStatus.DONE;
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
     * <p>每次工具调用前从Redis加载todo列表，通过pushSteering注入给LLM。
     * 使LLM看到已有任务列表，自然不会重复调用todo_create。</p>
     *
     * <p>频率控制：用签名去重（todoId:status拼接），状态没变化时不重复注入。</p>
     *
     * @param ctx the ctx value
     */

    private void injectActiveTodoStatus(AgentCallbackContext ctx) {
        // ★ 启动竞态修复：构造时 redisTodoStore 可能为 null（Rail 注册早于 Redis Bean 初始化），
        // 动态从 RedisConfig 静态持有者获取最新实例。
        RedisTodoStore store = getActiveRedisTodoStore();
        if (store == null) {
            return;
        }
        String rawSid = ctx.getSession() != null ? ctx.getSession().getSessionId() : null;
        if (rawSid == null || rawSid.isBlank()) {
            return;
        }
        try {
            List<TodoItem> todos = store.load(rawSid);
            if (todos == null || todos.isEmpty()) {
                return;
            }

            // 签名去重：状态没变就不重复注入
            String signature = todos.stream()
                    .map(t -> t.getId() + ":" + (t.getStatus() != null ? t.getStatus().name() : "null"))
                    .reduce("", (a, b) -> a + "," + b);
            String sigKey = "_edp_todo_sig";
            Object prevSig = ctx.getExtra().get(sigKey);
            if (signature.equals(prevSig)) {
                return;
            }
            ctx.getExtra().put(sigKey, signature);

            // 构建todo状态摘要
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

            long active = todos.stream().filter(t -> !isCompletedLike(t) && t.getStatus() != TodoStatus.CANCELLED)
                    .count();
            if (active > 0) {
                sb.append("请使用 todo_modify 推进任务，不要重新 todo_create。");
            }

            ctx.pushSteering(sb.toString());
            LOGGER.info("[EDPA-TODO-INJECT] injected active todo status, sid={}, todos={}, active={}", rawSid,
                    todos.size(), active);
        } catch (IllegalStateException e) {
            LOGGER.warn("[EDPA-TODO-INJECT] failed, sid={}, error={}", rawSid, e.getMessage());
        }
    }
}

