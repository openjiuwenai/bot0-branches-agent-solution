/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import com.openjiuwen.agents.reactrails.state.RailInvocationState;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Beta cognitive rail for ReActAgent — compress failed-attempt message history on
 * {@code __replan__} tool call.
 *
 * <p>Hooks {@code afterModelCall}: when the LLM calls {@code __replan__} (via
 * {@link ReplanTool}), compresses the message segment between the previous
 * compression boundary and the current replan response into a structured summary,
 * then replaces the context with the compact form:
 * <ol>
 *   <li>{@code SystemMessage}</li>
 *   <li>{@code UserMessage(initial query)}</li>
 *   <li>Prior summaries (if any) — each from a previous compression cycle</li>
 *   <li>{@code UserMessage} containing the structured summary</li>
 *   <li>Current {@code AssistantMessage(__replan__)} — preserved</li>
 * </ol>
 * Multiple replan cycles accumulate: each compression appends a new summary after prior ones,
 * keeping all prior summaries intact. No information is lost across cycles.
 *
 * <p><b>字节码实证修正</b>:
 * <ul>
 *   <li>{@code AgentCallbackContext.pushSteering(String)} — 参数为 {@code String}，非 {@code BaseMessage}</li>
 *   <li>{@code ModelContext.setMessages(List&lt;BaseMessage&gt;, boolean)} — 用于替换上下文</li>
 *   <li>{@code injectPendingSteering} 在 {@code afterModelCall} 所在循环的后续迭代中执行</li>
 * </ul>
 * 本 rail 使用 {@code setMessages} 而非 {@code pushSteering}，因为压缩摘要是永久性上下文替换，
 * 而非临时 steering。不涉及 pushSteering 调用。
 *
 * <p><b>IFF 契约</b>: strip {@code setMessages} call → 上下文保留全量消息 → token 数不减少 → RED.
 * Strip {@code hasReplan} detection → 永不压缩 → RED.
 *
 * <p><b>Honest boundary</b>: Phase1 规则提取工具调用签名，Phase2 LLM 摘要已 deferred
 * （需在 rail 内发起 LLM 调用，涉及注入时序复杂度）。
 *
 * @since 2026-07
 */
public class HistoryCompressorRail extends AgentRail {
    private static final String LINE_SEPARATOR = System.lineSeparator();

    /**
     * 可注入的压缩策略：Phase1 规则提取 → Phase2 LLM 摘要（deferred）。
     */
    private final Function<List<BaseMessage>, String> compressor;
    private final String stateKey = RailInvocationState.newKey(HistoryCompressorRail.class);

    /**
     * 默认构造，使用规则提取压缩器（Phase1 only）。
     */
    public HistoryCompressorRail() {
        this(HistoryCompressorRail::extractToolCallSummary);
    }

    /**
     * 可注入自定义压缩策略的构造。
     *
     * @param compressor 接收失败消息段，返回结构化摘要字符串
     */
    public HistoryCompressorRail(Function<List<BaseMessage>, String> compressor) {
        this.compressor = compressor;
    }

    /**
     * 测试观测点：当前压缩边界。
     *
     * @return last compressed message boundary
     */
    public int lastBoundary(AgentCallbackContext ctx) {
        return state(ctx).lastBoundary;
    }

    /**
     * 模型回调钩子：检测 {@code __replan__} 调用并压缩失败尝试段上下文。
     *
     * <p>时序（字节码 offset 实证）：
     * <ul>
     *   <li>{@code railedModelCall} 内 {@code LAMBDA → AFTER} 触发</li>
     *   <li>{@code callModel} 已返回，当前 response 已在 {@code ModelContext} 中</li>
     *   <li>{@code consumeForceFinish} (offset 700) 尚未执行</li>
     *   <li>下一轮循环：{@code injectPendingSteering}(offset 675) → {@code callModel}(offset 687)</li>
     * </ul>
     *
     * @param ctx callback context carrying model-call inputs
     */
    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        // 仅处理模型调用后的事件
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        Object response = inputs.getResponse();
        if (!(response instanceof AssistantMessage msg)) {
            return;
        }
        // null-check tool calls: 无工具调用的 final answer 不触发压缩
        if (msg.getToolCalls() == null) {
            return;
        }

        // 检测 __replan__ 工具调用
        boolean hasReplan = msg.getToolCalls().stream().anyMatch(tc -> ReplanTool.TOOL_NAME.equals(tc.getName()));
        if (!hasReplan) {
            return;
        }

        // 1. 读取当前 ModelContext 全部消息（含当前 response）
        List<BaseMessage> messages = ctx.getContext().getMessages();
        if (messages == null || messages.size() <= 2) {
            // 不足 [system, query] 时不能压缩
            return;
        }

        InvocationState state = state(ctx);

        // 2. 定位待压缩段：(lastBoundary, messages.size() - 1)
        //    保留 messages.get(messages.size() - 1) = 当前含 __replan__ 的 AssistantMessage
        //    segStart >= 2 保证 System(0) 和 UserMessage(initial query)(1) 不被压缩
        int segStart = Math.max(2, state.lastBoundary);
        int segEnd = messages.size() - 1; // 排除当前 __replan__ 消息
        if (segStart >= segEnd) {
            // 没有可压缩的中间段
            return;
        }

        List<BaseMessage> segment = messages.subList(segStart, segEnd);
        String summary = compressor.apply(segment);

        // 3. 重建上下文：
        //    [0] SystemMessage
        //    [1] UserMessage(initial query)
        //    [2..segStart) 已有摘要（累积保留）
        //    [segStart..segEnd) 被压缩为 [n] UserMessage("[尝试摘要]" + LINE_SEPARATOR + summary)
        //    [n+1] 当前 __replan__ 消息
        List<BaseMessage> compact = new ArrayList<>();
        // 永保留 system + initial query
        compact.add(messages.get(0)); // SystemMessage
        compact.add(messages.get(1)); // UserMessage(initial query)
        // 累积保留已有摘要（index 2 到 segStart 间的消息，如之前的压缩摘要）
        for (int i = 2; i < segStart; i++) {
            compact.add(messages.get(i));
        }
        compact.add(new UserMessage("[尝试摘要]" + LINE_SEPARATOR + summary)); // 本次压缩摘要
        compact.add(msg); // 当前 __replan__ 消息

        ctx.getContext().setMessages(compact, false);
        state.lastBoundary = compact.size() - 1;
    }

    private InvocationState state(AgentCallbackContext ctx) {
        return RailInvocationState.get(ctx, stateKey, InvocationState.class, InvocationState::new);
    }

    /**
     * Phase1 规则提取：收集 segment 中所有 {@code AssistantMessage} 的工具调用签名。
     *
     * <p>Phase2 LLM 摘要（deferred）：需要 rail 内发起 LLM 调用，涉及注入时序复杂度，
     * 当前 Phase1 已覆盖承重控制流，Phase2 不改变 IFF 契约。
     *
     * @param segment 待压缩的消息段
     * @return 结构化摘要字符串
     */
    private static String extractToolCallSummary(List<BaseMessage> segment) {
        List<String> calls = segment.stream().filter(m -> m instanceof AssistantMessage)
                .flatMap(m -> ((AssistantMessage) m).getToolCalls().stream())
                .map(tc -> "  - " + tc.getName() + "(" + tc.getArguments() + ")").collect(Collectors.toList());
        if (calls.isEmpty()) {
            return "未产生工具调用，对话直接触发 replan。";
        }
        return "尝试了以下工具调用：" + LINE_SEPARATOR + String.join(LINE_SEPARATOR, calls) + LINE_SEPARATOR + "以上尝试未能解决问题。";
    }

    private static final class InvocationState {
        private int lastBoundary;
    }
}
