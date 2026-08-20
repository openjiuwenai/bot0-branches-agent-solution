/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.internal;

import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.spi.Governance;
import com.openjiuwen.client.state.spi.ClientStateStore;
import com.openjiuwen.client.tool.spi.LocalTool;
import com.openjiuwen.client.tool.spi.LocalToolDescriptor;
import com.openjiuwen.client.tool.spi.LocalToolRegistry;
import com.openjiuwen.client.tool.spi.ToolExecutionContext;
import com.openjiuwen.client.tool.spi.ToolExecutionRecord;
import com.openjiuwen.client.tool.spi.ToolInvocation;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本地工具调度器（FEAT-007 内核）。串联"去重 → 参数校验 → 策略门禁 → 审批 → 受限执行 → 结果落库"。
 *
 * <p>核心不变量：对同一 {@code toolCallId}，工具实现<b>最多执行一次</b>。
 * 通过 {@link ClientStateStore} 的原子落库 + 进程内 in-flight 合流，抵御 INPUT_REQUIRED 的重复投递。
 */
final class ToolDispatcher {
    private final LocalToolRegistry registry;
    private final ClientStateStore store;
    private final Governance.PolicyGuard policyGuard;
    private final Governance.ApprovalProvider approvalProvider;
    private final Executor executor;

    private final ConcurrentMap<String, CompletableFuture<ToolExecutionRecord>> inFlight =
            new ConcurrentHashMap<>();

    ToolDispatcher(LocalToolRegistry registry,
                   ClientStateStore store,
                   Governance.PolicyGuard policyGuard,
                   Governance.ApprovalProvider approvalProvider,
                   Executor executor) {
        this.registry = registry;
        this.store = store;
        this.policyGuard = policyGuard;
        this.approvalProvider = approvalProvider;
        this.executor = executor;
    }

    /**
     * 执行 future。
     *
     * @param call InvocationEvent.ToolCall
     * @param ctx ToolExecutionContext
     * @return 执行 future
     */
    CompletableFuture<ToolExecutionRecord> dispatch(InvocationEvent.ToolCall call, ToolExecutionContext ctx) {
        String toolCallId = call.toolCallId();
        Optional<ToolExecutionRecord> already = store.findRecord(toolCallId);
        if (already.isPresent()) {
            return CompletableFuture.completedFuture(already.get());
        }
        // Defensive: if a previous run's pipeline completed exceptionally but wasn't cleaned up
        // (runPipeline.whenComplete should have removed it), remove the stale entry so the current
        // run gets a fresh pipeline instead of a dead future.
        CompletableFuture<ToolExecutionRecord> existing = inFlight.get(toolCallId);
        if (existing != null && existing.isCompletedExceptionally()) {
            inFlight.remove(toolCallId, existing);
        }
        return inFlight.computeIfAbsent(toolCallId, k -> runPipeline(call, ctx));
    }

    /**
     * Remove an in-flight entry (e.g., after a dispatch timeout/abandonment) so the next dispatch
     * for the same toolCallId gets a fresh pipeline instead of a stale incomplete future.
     *
     * @param toolCallId the tool call id to clear
     */
    void clearInFlight(String toolCallId) {
        inFlight.remove(toolCallId);
    }

    /**
     * 执行完整管道并以 StateStore 中先写入的最终记录为准。
     *
     * @param call 工具调用事件
     * @param ctx 工具执行上下文
     * @return 执行结果
     */
    private CompletableFuture<ToolExecutionRecord> runPipeline(InvocationEvent.ToolCall call,
                                                               ToolExecutionContext ctx) {
        String toolCallId = call.toolCallId();
        return computeRaw(call, ctx)
                .exceptionally(ex -> ToolExecutionRecord.error(
                        toolCallId, "tool_execution_error", rootMessage(ex)))
                .thenApply(rec -> store.saveRecordIfAbsent(toolCallId, rec))
                .whenCompleteAsync((r, e) -> inFlight.remove(toolCallId));
    }

    /**
     * 在调用 handler 前依次完成快照可见性、注册、参数、策略和审批校验。
     *
     * @param call 工具调用事件
     * @param ctx 工具执行上下文
     * @return 校验通过后的执行结果或拒绝记录
     */
    private CompletableFuture<ToolExecutionRecord> computeRaw(InvocationEvent.ToolCall call,
                                                              ToolExecutionContext ctx) {
        String toolCallId = call.toolCallId();
        // 007 §3.2 步骤 3：ToolView 可见性校验。服务端幻觉调用未在本次 exposure 声明的工具
        // → REJECTED(tool_not_declared)，不执行。这是"默认不暴露"与"治理骨架不可绕过"的安全语义。
        if (!ctx.visibleToolNames().isEmpty() && !ctx.visibleToolNames().contains(call.toolName())) {
            return CompletableFuture.completedFuture(ToolExecutionRecord.rejected(
                    toolCallId, "tool_not_declared",
                    "tool not in the ToolView declared for this invocation: " + call.toolName()));
        }
        // 007 §3.2 步骤 4：解析工具，缺失 → REJECTED(tool_not_found)。
        Optional<LocalTool.Registered> maybe = registry.find(call.toolName());
        if (maybe.isEmpty()) {
            return CompletableFuture.completedFuture(ToolExecutionRecord.rejected(
                    toolCallId, "tool_not_found",
                    "no local tool registered for name: " + call.toolName()));
        }
        LocalTool.Registered reg = maybe.get();
        LocalToolDescriptor descriptor = reg.descriptor();
        ToolInvocation invocation = new ToolInvocation(
                toolCallId, descriptor.toolId(), call.arguments(), call.deadline());

        // 007 §3.2 步骤 5：schema 校验，必填键缺失/参数非法 → REJECTED(invalid_tool_arguments)。
        for (String key : descriptor.requiredArgumentKeys()) {
            if (!invocation.arguments().containsKey(key)) {
                return CompletableFuture.completedFuture(ToolExecutionRecord.rejected(
                        toolCallId, "invalid_tool_arguments", "missing required argument: " + key));
            }
        }

        // 007 §3.2 步骤 6：PolicyGuard.check → DENY → REJECTED(permission_denied)。
        Governance.Decision decision = policyGuard.check(descriptor, invocation, ctx);
        if (!decision.allowed()) {
            String code = (decision.errorCode() != null) ? decision.errorCode() : "permission_denied";
            return CompletableFuture.completedFuture(
                    ToolExecutionRecord.rejected(toolCallId, code, decision.reason()));
        }

        // 007 §3.2 步骤 7：REQUIRE_APPROVAL → 未批 → REJECTED(rejected)。
        CompletionStage<Governance.ApprovalDecision> approval = descriptor.requiresApproval()
                ? approvalProvider.requestApproval(descriptor, invocation, ctx)
                : CompletableFuture.completedFuture(Governance.ApprovalDecision.approve());

        return approval.toCompletableFuture().thenCompose(ad -> {
            if (!ad.approved()) {
                return CompletableFuture.completedFuture(
                        ToolExecutionRecord.rejected(toolCallId, "rejected", ad.reason()));
            }
            return runTool(reg, invocation, ctx);
        });
    }

    /**
     * 执行 handler，并把协作式超时和异常归一化为唯一最终记录。
     *
     * @param reg 已注册的工具实例
     * @param invocation 工具调用参数
     * @param ctx 工具执行上下文
     * @return 执行结果
     */
    private CompletableFuture<ToolExecutionRecord> runTool(LocalTool.Registered reg,
                                                           ToolInvocation invocation,
                                                           ToolExecutionContext ctx) {
        String toolCallId = invocation.toolCallId();
        Duration timeout = reg.descriptor().timeout();
        CompletableFuture<ToolExecutionRecord> f = CompletableFuture
                .supplyAsync(() -> reg.tool().execute(invocation, ctx), executor)
                .thenCompose(CompletionStage::toCompletableFuture);
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            f = f.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        // 007 §3.2 步骤 8 + §5.3：超时 → TIMEOUT(timeout)；异常 → ERROR（渲染为 ERROR 文本，码名非闭集约束）。
        return f.handle((rec, ex) -> {
            if (ex != null) {
                Throwable cause = unwrap(ex);
                if (cause instanceof TimeoutException) {
                    return ToolExecutionRecord.timeout(toolCallId, "timeout",
                            "tool timed out after " + timeout);
                }
                return ToolExecutionRecord.error(toolCallId, "tool_execution_error", cause.getMessage());
            }
            return (rec != null) ? rec
                    : ToolExecutionRecord.error(toolCallId, "tool_execution_error", "tool returned null record");
        });
    }

    /**
     * unwrap。
     *
     * @param ex Throwable
     * @return unwrap
     */
    private static Throwable unwrap(Throwable ex) {
        Throwable c = ex;
        while ((c instanceof java.util.concurrent.CompletionException
                || c instanceof java.util.concurrent.ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }

    /**
     * rootMessage。
     *
     * @param ex Throwable
     * @return rootMessage
     */
    private static String rootMessage(Throwable ex) {
        Throwable c = unwrap(ex);
        return (c.getMessage() != null) ? c.getMessage() : c.getClass().getSimpleName();
    }
}
