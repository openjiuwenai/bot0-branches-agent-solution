/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.rail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.edpa.explore.ExplorationResult;
import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.core.context.ContextStats;
import com.openjiuwen.core.context.ContextWindow;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.context.token.TokenCounter;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ExploreRail 承重测试 — mock context 证 Explore 阶段 findings 注入 steering 队列.
 *
 * <p>八出口验证：
 * <ol>
 *   <li>工具调用轮 + 窗口内 → explore 调用 + pushSteering(findings)</li>
 *   <li>最终答案轮（无 toolCalls）→ 不 explore、不 push</li>
 *   <li>窗口耗尽（exploreRound >= maxRounds）→ 不 explore、不 push</li>
 *   <li>explorer 返回 null/空 findings → 不 push</li>
 *   <li>explorer 抛异常 → 不 crash、不 push（静默降级）</li>
 *   <li>afterModelCall push 的 steering 含 findings + candidateApproaches</li>
 *   <li>beforeModelCall 写 extra(edpa_explore_round) 且不碰静态通道</li>
 *   <li>非 ModelCallInputs / 非 AssistantMessage → 无动作</li>
 * </ol>
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>剥 ctx.pushSteering(formatted) → steering 空 → RED</li>
 *   <li>剥 explorer.explore(...) 调用 → result null → 不 push → RED</li>
 *   <li>剥 toolCalls 非空判定 → 最终答案轮也 explore → RED</li>
 *   <li>剥 exploreRound >= maxRounds 判定 → 窗口耗尽仍 explore → RED</li>
 *   <li>剥 exploreRound++ → 永不耗尽窗口 → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class ExploreRailTest {
    private static final String INITIAL_QUERY = "分析2026年A股市场走势";

    private static final String FINDINGS = "发现：市场情绪偏谨慎，资金面收紧";

    private static final List<String> APPROACHES = List.of("技术面分析", "资金面分析", "政策面分析");

    // ============================================================
    // Construction
    // ============================================================

    @Test
    void constructorRejectsNullExplorer() {
        assertThatThrownBy(() -> new ExploreRail(null, ExploreBudget.DEFAULT)).as("null explorer must be rejected")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explorer");
    }

    @Test
    void constructorRejectsNullBudget() {
        Explorer explorer = (q, b) -> new ExplorationResult(FINDINGS, APPROACHES);
        assertThatThrownBy(() -> new ExploreRail(explorer, null)).as("null budget must be rejected")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("budget");
    }

    @Test
    void constructorSetsPriorityNinety() {
        ExploreRail rail = newRailWithRecordingExplorer();

        assertThat(rail.getPriority()).as("priority must be 90 (fires before Checklist=80)").isEqualTo(90);
    }

    // ============================================================
    // Main bearing: tool-call round within window → explore + pushSteering
    // ============================================================

    @Test
    void toolCallRoundWithinWindowExploresAndPushesSteering() {
        RecordingExplorer explorer = new RecordingExplorer();
        ExploreRail rail = new ExploreRail(explorer, new ExploreBudget(2, 3, 60_000));
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        AssistantMessage toolMsg = assistantWithToolCall("market_search", "{\"q\":\"A股\"}");
        AgentCallbackContext ctx = ctxWithToolResult(toolMsg, sq);

        rail.afterModelCall(ctx);

        assertThat(explorer.callCount.get()).as("explorer.explore must be invoked exactly once").isEqualTo(1);
        assertThat(explorer.lastUserInput).as("explorer must receive the initial user query from messages[1]")
                .isEqualTo(INITIAL_QUERY);
        assertThat(explorer.lastBudget).as("explorer must receive the configured budget").isNotNull();

        assertThat(sq.captured).as("findings must be pushed into steering queue").hasSize(1);
        String steering = sq.captured.get(0);
        assertThat(steering).as("steering must carry the explore-phase findings").contains(FINDINGS);
        assertThat(steering).as("steering must list candidate approaches").contains("技术面分析", "政策面分析");
        // mutation-RED: strip ctx.pushSteering → captured empty → RED
        // mutation-RED: strip explorer.explore call → explorer.callCount=0 → RED
    }

    @Test
    void exploreRoundIncrementedPerPush() {
        ExploreRail rail = newRailWithRecordingExplorer();
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        assertThat(rail.getExploreRound()).isZero();

        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));
        assertThat(rail.getExploreRound()).as("first tool-call round must increment exploreRound to 1").isEqualTo(1);

        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t2", "{}"), sq));
        assertThat(rail.getExploreRound()).as("second tool-call round must increment exploreRound to 2").isEqualTo(2);
        // mutation-RED: strip exploreRound++ → stays 0 → window never exhausts → RED
    }

    // ============================================================
    // Guard: final-answer round (no tool calls) → skip
    // ============================================================

    @Test
    void finalAnswerRoundDoesNotExplore() {
        RecordingExplorer explorer = new RecordingExplorer();
        ExploreRail rail = new ExploreRail(explorer, ExploreBudget.DEFAULT);
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        AssistantMessage finalAnswer = new AssistantMessage("最终答案：A股将震荡整理");
        AgentCallbackContext ctx = ctxWithContext(finalAnswer, sq);

        rail.afterModelCall(ctx);

        assertThat(explorer.callCount.get()).as("final-answer round must NOT invoke explorer").isEqualTo(0);
        assertThat(sq.captured).as("final-answer round must NOT push steering").isEmpty();
        assertThat(rail.getExploreRound()).as("exploreRound must stay 0 on final-answer round").isZero();
        // mutation-RED: strip toolCalls-empty check → explore runs on final answer → RED
    }

    // ============================================================
    // Guard: window exhausted → skip
    // ============================================================

    @Test
    void windowExhaustedStopsExploring() {
        RecordingExplorer explorer = new RecordingExplorer();
        ExploreBudget budget = new ExploreBudget(2, 3, 60_000);
        ExploreRail rail = new ExploreRail(explorer, budget);
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        // Two explores exhaust the window (maxRounds=2)
        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));
        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t2", "{}"), sq));
        assertThat(rail.getExploreRound()).as("precondition: window should be exhausted after 2 explores").isEqualTo(2);
        assertThat(explorer.callCount.get()).isEqualTo(2);

        // Third tool-call round → window exhausted, no further explore
        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t3", "{}"), sq));

        assertThat(explorer.callCount.get()).as("window exhausted must NOT invoke explorer again").isEqualTo(2);
        assertThat(sq.captured).as("window exhausted must NOT push steering").hasSize(2);
        assertThat(rail.getExploreRound()).as("exploreRound must not exceed maxRounds").isEqualTo(2);
        // mutation-RED: strip exploreRound >= maxRounds check → 3rd explore runs → RED
    }

    // ============================================================
    // Guard: explorer returns null / empty findings → no push
    // ============================================================

    @Test
    void nullExplorationResultDoesNotPush() {
        Explorer explorer = (q, b) -> null;
        ExploreRail rail = new ExploreRail(explorer, ExploreBudget.DEFAULT);
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));

        assertThat(sq.captured).as("null ExplorationResult must not push steering").isEmpty();
        assertThat(rail.getExploreRound()).as("null result must not increment exploreRound").isZero();
    }

    @Test
    void emptyFindingsDoesNotPush() {
        Explorer explorer = (q, b) -> new ExplorationResult("", APPROACHES);
        ExploreRail rail = new ExploreRail(explorer, ExploreBudget.DEFAULT);
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));

        assertThat(sq.captured).as("empty findings must not push steering").isEmpty();
        assertThat(rail.getExploreRound()).as("empty findings must not increment exploreRound").isZero();
    }

    @Test
    void nullFindingsStringDoesNotPush() {
        Explorer explorer = (q, b) -> new ExplorationResult(null, APPROACHES);
        ExploreRail rail = new ExploreRail(explorer, ExploreBudget.DEFAULT);
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));

        assertThat(sq.captured).as("null findings string must not push steering").isEmpty();
    }

    // ============================================================
    // Guard: explorer throws → no crash, no push (honest degradation)
    // ============================================================

    @Test
    void explorerExceptionDoesNotCrashLoop() {
        Explorer explorer = (q, b) -> {
            throw new IllegalStateException("explorer LLM timeout");
        };
        ExploreRail rail = new ExploreRail(explorer, ExploreBudget.DEFAULT);
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        AgentCallbackContext ctx = ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq);

        // Must not throw — explorer failure must degrade silently
        rail.afterModelCall(ctx);

        assertThat(sq.captured).as("explorer exception must not push steering").isEmpty();
        assertThat(rail.getExploreRound()).as("explorer exception must not increment exploreRound").isZero();
    }

    // ============================================================
    // beforeModelCall: writes extra, does NOT touch static channel
    // ============================================================

    @Test
    void beforeModelCallPublishesExploreRoundToExtra() {
        ExploreRail rail = newRailWithRecordingExplorer();
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        AgentCallbackContext ctx = ctxWithContext(new AssistantMessage("ans"), sq);

        // Simulate one explore round
        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));
        assertThat(rail.getExploreRound()).isEqualTo(1);

        rail.beforeModelCall(ctx);

        assertThat(ctx.getExtra().get(ExploreRail.EXTRA_EXPLORE_ROUND))
                .as("beforeModelCall must publish current exploreRound to extra").isEqualTo(1);
    }

    @Test
    void beforeModelCallZeroRoundWhenNoExploreYet() {
        ExploreRail rail = newRailWithRecordingExplorer();
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        AgentCallbackContext ctx = ctxWithContext(new AssistantMessage("ans"), sq);

        rail.beforeModelCall(ctx);

        assertThat(ctx.getExtra().get(ExploreRail.EXTRA_EXPLORE_ROUND))
                .as("beforeModelCall must publish 0 before any explore").isEqualTo(0);
    }

    // ============================================================
    // Non-bearing inputs → no action
    // ============================================================

    @Test
    void nonModelCallInputsNoAction() {
        ExploreRail rail = newRailWithRecordingExplorer();
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        // inputs is null → not instanceof ModelCallInputs
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).context(stubContext()).build();
        ctx.setExtra(new LinkedHashMap<>());

        rail.afterModelCall(ctx);

        assertThat(rail.getExploreRound()).isZero();
    }

    @Test
    void nonAssistantResponseNoAction() {
        ExploreRail rail = newRailWithRecordingExplorer();
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse("plain string, not AssistantMessage");
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs)
                .context(stubContext()).steeringQueue(sq).build();
        ctx.setExtra(new LinkedHashMap<>());

        rail.afterModelCall(ctx);

        assertThat(rail.getExploreRound()).as("non-AssistantMessage response must not explore").isZero();
    }

    // ============================================================
    // Steering content format
    // ============================================================

    @Test
    void steeringContainsExplorePhaseHeaderAndApproaches() {
        ExploreRail rail = newRailWithRecordingExplorer();
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));

        assertThat(sq.captured).hasSize(1);
        String steering = sq.captured.get(0);
        assertThat(steering).as("steering must carry the Explore-phase header").contains("【探索发现");
        assertThat(steering).as("steering must carry the 候选方向 label").contains("候选方向");
        assertThat(steering).as("each approach must be prefixed with a dash").contains("- 技术面分析");
    }

    @Test
    void steeringWithoutCandidateApproachesStillPushed() {
        Explorer explorer = (q, b) -> new ExplorationResult("only findings, no approaches");
        ExploreRail rail = new ExploreRail(explorer, ExploreBudget.DEFAULT);
        CaptureSteeringQueue sq = new CaptureSteeringQueue();

        rail.afterModelCall(ctxWithToolResult(assistantWithToolCall("t1", "{}"), sq));

        assertThat(sq.captured).as("findings present must push even without approaches").hasSize(1);
        assertThat(sq.captured.get(0)).contains("only findings, no approaches");
        assertThat(sq.captured.get(0)).as("no 候选方向 label when approaches empty").doesNotContain("候选方向");
    }

    // ============================================================
    // Test helpers
    // ============================================================

    private static ExploreRail newRailWithRecordingExplorer() {
        return new ExploreRail(new RecordingExplorer(), ExploreBudget.DEFAULT);
    }

    private static AssistantMessage assistantWithToolCall(String toolName, String args) {
        ToolCall tc = new ToolCall();
        tc.setId("call-" + System.nanoTime());
        tc.setName(toolName);
        tc.setArguments(args);
        AssistantMessage msg = new AssistantMessage("calling " + toolName);
        msg.setToolCalls(List.of(tc));
        return msg;
    }

    private static StubModelContext stubContext() {
        List<BaseMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage("You are a market analyst."));
        messages.add(new UserMessage(INITIAL_QUERY));
        return new StubModelContext(messages);
    }

    private static AgentCallbackContext ctxWithContext(AssistantMessage response, SteeringQueue sq) {
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(response);
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs)
                .context(stubContext()).steeringQueue(sq).build();
        ctx.setExtra(new LinkedHashMap<>());
        return ctx;
    }

    private static AgentCallbackContext ctxWithToolResult(AssistantMessage toolMsg, SteeringQueue sq) {
        return ctxWithContext(toolMsg, sq);
    }

    /**
     * Recording Explorer — captures call arguments and returns a fixed result.
     */
    static final class RecordingExplorer implements Explorer {
        final AtomicInteger callCount = new AtomicInteger(0);
        volatile String lastUserInput;
        volatile ExploreBudget lastBudget;

        @Override
        public ExplorationResult explore(String userInput, ExploreBudget budget) {
            callCount.incrementAndGet();
            lastUserInput = userInput;
            lastBudget = budget;
            return new ExplorationResult(FINDINGS, APPROACHES);
        }
    }

    /**
     * CaptureSteeringQueue — records every pushed steering string.
     * Pattern reused from StagnationDetectionRailTest.
     */
    static class CaptureSteeringQueue implements SteeringQueue {
        final List<String> captured = new ArrayList<>();

        @Override
        public synchronized void pushSteering(String hint) {
            captured.add(hint);
        }

        @Override
        public synchronized List<String> drainSteering() {
            List<String> result = List.copyOf(captured);
            captured.clear();
            return result;
        }
    }

    /**
     * Minimal stub ModelContext storing messages in a simple list.
     * Pattern reused from HistoryCompressorRailTest. Only implements
     * getMessages() — all other abstract methods throw.
     */
    static class StubModelContext extends ModelContext {
        final List<BaseMessage> messages;

        StubModelContext(List<BaseMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }

        @Override
        public int size() {
            return messages.size();
        }

        @Override
        public List<BaseMessage> getMessages(Integer windowSize, boolean isSortDesc) {
            return messages;
        }

        @Override
        public void setMessages(List<BaseMessage> newMessages, boolean isSortDesc) {
            messages.clear();
            messages.addAll(newMessages);
        }

        @Override
        public List<BaseMessage> popMessages(int n, boolean isSortDesc) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void clearMessages(boolean isSortDesc) {
            messages.clear();
        }

        @Override
        public List<BaseMessage> addMessages(List<BaseMessage> msgs) {
            messages.addAll(msgs);
            return msgs;
        }

        @Override
        public ContextWindow getContextWindow(List<BaseMessage> msgs, List<ToolInfo> tools, Integer windowSize,
                Integer overlap, Map<String, Object> kwargs) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public ContextStats statistic() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String sessionId() {
            return "test-session";
        }

        @Override
        public String contextId() {
            return "test-context";
        }

        @Override
        public TokenCounter tokenCounter() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public Tool reloaderTool() {
            throw new UnsupportedOperationException("stub");
        }
    }
}
