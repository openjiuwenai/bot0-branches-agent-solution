/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.rail;

import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 束宽/预算 rail——graph-loop 的资源管理器（S4 预算存量的 rail 化）。
 *
 * <p>管理三个维度的资源上限：
 * <ul>
 *   <li><b>束宽 W</b>：最大同时活跃分支数（fork 超过 W → 拒绝新 fork）</li>
 *   <li><b>总 fork 次数</b>：防止无限扇出（全局上限，无论是否收敛）</li>
 *   <li><b>预算 token 池</b>：所有分支共享的 token 上限（跨分支公平性）</li>
 * </ul>
 *
 * <p><b>系统论依据</b>（graph-loop-systems-dynamics）：
 * S4 预算存量 = 单调递减硬界，所有防死循环的硬闸。
 * 单分支 loop 只需 token 上限；graph 多分支需要**共享资源管理**。
 *
 * <p><b>Hook 位置</b>：beforeToolCall 拦截 fork_subtask（束宽/总次数），
 * afterInvoke 检查 token 池（预算耗尽 → forceFinish）。
 *
 * <p><b>Honest boundary</b>：
 * <ul>
 *   <li>MVP 不做 ε 硬配额（强制非优势分支的预算比例）——需要收敛信号配合</li>
 *   <li>Token 池是估计值（由 LLM usage 累计），非实时精确</li>
 *   <li>不管理单分支的 token（由宿主 ReActAgent 自身的 maxIterations 管控）</li>
 * </ul>
 *
 * @since 2026-08
 */
public class BudgetRail extends AgentRail {

    /** fork 工具名（与 GraphLoopRails 注册名一致）。 */
    public static final String FORK_TOOL_NAME = "fork_subtask";

    /** extra 标记：本次 fork 调用经本 rail 放行（拒绝/异常路径不归还计数）。 */
    static final String ALLOWED_EXTRA_KEY = "_budget_fork_allowed";

    private final int maxBeamWidth;
    private final int maxTotalForks;
    private final long maxTotalTokens;

    private final AtomicInteger activeBranches = new AtomicInteger(0);
    private final AtomicInteger totalForks = new AtomicInteger(0);
    private volatile long totalTokensUsed = 0;
    private volatile int rejections = 0;

    /** D4 三因拆真话的模板渲染器（prompts/budget-fork-*.txt 外置，MR !66）。 */
    private final com.openjiuwen.agents.loopforest.search.PromptTemplates templates =
            new com.openjiuwen.agents.loopforest.search.PromptTemplates();

    /**
     * 构造 BudgetRail。
     *
     * @param maxBeamWidth  最大同时活跃分支数（束宽 W）
     * @param maxTotalForks 最大总 fork 次数
     * @param maxTotalTokens 所有分支共享的 token 上限（0=不限）
     */
    public BudgetRail(int maxBeamWidth, int maxTotalForks, long maxTotalTokens) {
        this.maxBeamWidth = maxBeamWidth;
        this.maxTotalForks = maxTotalForks;
        this.maxTotalTokens = maxTotalTokens;
    }

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        Object inputsObj = ctx.getInputs();
        if (!(inputsObj instanceof ToolCallInputs inputs)) {
            return;
        }
        // 只拦截 fork_subtask
        if (!FORK_TOOL_NAME.equals(inputs.getToolName())) {
            return;
        }
        // 检查束宽
        if (activeBranches.get() >= maxBeamWidth) {
            rejectFork(ctx, inputs, "beamwidth");
            return;
        }
        // 检查总 fork 次数
        if (totalForks.get() >= maxTotalForks) {
            rejectFork(ctx, inputs, "exhausted");
            return;
        }
        // 检查 token 池
        if (maxTotalTokens > 0 && totalTokensUsed >= maxTotalTokens) {
            rejectFork(ctx, inputs, "tokens");
            return;
        }
        // 允许 fork——计数 + 打放行标记（仅放行调用会在完成/异常时归还槽位，
        // 拒绝路径从未占用——防止 afterToolCall 无条件归还造成计数下溢）
        activeBranches.incrementAndGet();
        totalForks.incrementAndGet();
        Map<String, Object> extra = ctx.getExtra();
        if (extra != null) {
            extra.put(ALLOWED_EXTRA_KEY, Boolean.TRUE);
        }
    }

    @Override
    public void afterToolCall(AgentCallbackContext ctx) {
        releaseIfAllowed(ctx);
    }

    @Override
    public void onToolException(AgentCallbackContext ctx) {
        // 异常路径 AFTER_TOOL_CALL 不触发（RailExecutor 字节码实证：仅正常完成才 fire）
        // ——必须在此归还槽位，否则每次 fork 异常泄漏 1 槽，泄漏满 W 次分叉永久瘫痪
        releaseIfAllowed(ctx);
    }

    /**
     * 归还放行过的 fork 槽位（幂等——按 ALLOWED 标记判定，拒绝/重复归还无效）。
     *
     * @param ctx 回调上下文
     */
    private void releaseIfAllowed(AgentCallbackContext ctx) {
        Object inputsObj = ctx.getInputs();
        if (!(inputsObj instanceof ToolCallInputs inputs)
                || !FORK_TOOL_NAME.equals(inputs.getToolName())) {
            return;
        }
        Map<String, Object> extra = ctx.getExtra();
        if (extra == null || Boolean.TRUE != extra.remove(ALLOWED_EXTRA_KEY)) {
            return; // 拒绝路径或已归还——不重复减
        }
        activeBranches.decrementAndGet();
    }

    /**
     * 记录 token 使用量（宿主在 LLM 调用后调用）。
     *
     * @param tokens 本轮 token 用量
     */
    public void recordTokens(long tokens) {
        totalTokensUsed += tokens;
    }

    /**
     * 预算是否已耗尽。
     *
     * @return true 如果 token 池已超限
     */
    public boolean isBudgetExhausted() {
        return maxTotalTokens > 0 && totalTokensUsed >= maxTotalTokens;
    }

    /**
     * 获取当前活跃分支数。
     *
     * @return 活跃分支计数
     */
    public int getActiveBranches() {
        return activeBranches.get();
    }

    /**
     * 获取总 fork 次数。
     *
     * @return fork 总计数
     */
    public int getTotalForks() {
        return totalForks.get();
    }

    /**
     * 获取被拒绝的 fork 次数。
     *
     * @return 拒绝计数
     */
    public int getRejections() {
        return rejections;
    }

    /**
     * 获取已使用的 token 总量。
     *
     * @return token 使用计数
     */
    public long getTotalTokensUsed() {
        return totalTokensUsed;
    }

    private void rejectFork(AgentCallbackContext ctx, ToolCallInputs inputs, String reasonKey) {
        rejections++;
        // 跳过 fork 执行 + 告知模型原因（这不是零提及场景——资源限制需要可理解）
        Map<String, Object> extra = ctx.getExtra();
        if (extra != null) {
            extra.put("_skip_tool", Boolean.TRUE);
        }
        String message = renderRejection(reasonKey);
        inputs.setToolResult(Map.of(
                "error", message,
                "suggestion", "continue the current branch with what has been collected"));
        // ToolMessage 协议（content, toolCallId, name）——缺 toolMsg 时被跳过的
        // tool_call 无响应消息，下一个请求 API 400（与 VetoRail 同病灶，字节码实证）
        String callId = inputs.getToolCall() != null
                ? inputs.getToolCall().getId() : null;
        inputs.setToolMsg(new ToolMessage(message, callId, inputs.getToolName()));
    }

    /**
     * D4 三因拆真话渲染：beamwidth=暂时性（槽位会释放，永不出现终局措辞）；
     * exhausted/tokens=永久性（二次拒绝起附终局标记）；数字只在可承重处出现
     * （beamwidth 的 {active}/{width} 与 exhausted 的 {used}/{cap}；tokens 是
     * 估计值不带数字——诚实）。
     *
     * @param reasonKey beamwidth / exhausted / tokens
     * @return 渲染后拒绝文案
     */
    private String renderRejection(String reasonKey) {
        String reasonLine;
        switch (reasonKey) {
            case "beamwidth" -> reasonLine = templates.render("budget-fork-reason-beamwidth.txt",
                    Map.of("active", String.valueOf(activeBranches.get()),
                            "width", String.valueOf(maxBeamWidth)));
            case "exhausted" -> reasonLine = templates.render("budget-fork-reason-exhausted.txt",
                    Map.of("used", String.valueOf(totalForks.get()),
                            "cap", String.valueOf(maxTotalForks)));
            case "tokens" -> reasonLine = templates.render("budget-fork-reason-tokens.txt",
                    Map.of());
            default -> reasonLine = "Fork not started: resource limit reached.";
        }
        String finalityNote = ("exhausted".equals(reasonKey) || "tokens".equals(reasonKey))
                && rejections >= 2
                        ? templates.render("budget-fork-final-note.txt", Map.of())
                        : "";
        return templates.render("budget-fork-rejection.txt",
                Map.of("reasonLine", reasonLine.trim(), "finalityNote", finalityNote));
    }
}
