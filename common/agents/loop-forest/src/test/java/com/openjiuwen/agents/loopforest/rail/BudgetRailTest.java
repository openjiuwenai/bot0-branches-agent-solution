/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.rail;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BudgetRail 承重测试——4-lens 审查（Lens B 字节码取证）三连缺陷的钉子。
 *
 * <ul>
 *   <li>M2 计数下溢：拒绝路径从未占用槽位，afterToolCall 不得归还
 *       （旧实现无条件减 → activeBranches 负数 → 束宽闸被自己击穿）</li>
 *   <li>M3 异常泄漏：fork 抛异常时 AFTER 不触发，onToolException 必须归还</li>
 *   <li>M4 协议：拒绝必须预填 ToolMessage(content, toolCallId, name)——
 *       缺失则被跳过的 tool_call 无响应，下一个请求 API 400</li>
 * </ul>
 *
 * @since 2026-08
 */
class BudgetRailTest {

    private static AgentCallbackContext newForkCtx(Map<String, Object> extra) {
        ToolCall call = new ToolCall();
        call.setId("call_00_BUDGET");
        call.setName("fork_subtask");
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(call)
                .toolName("fork_subtask")
                .toolArgs(Map.of("sub_goal", "explore"))
                .build();
        return AgentCallbackContext.builder()
                .agent(new Object())
                .event(null)
                .inputs(inputs)
                .extra(extra)
                .build();
    }

    // ═══ M2：拒绝路径不计数下溢 ═══

    @Test
    void rejectedForkDoesNotUnderflowActiveBranches() {
        BudgetRail rail = new BudgetRail(1, 5, 0); // 束宽 1
        // 框架语义：每次工具调用一个独立 ctx/extra（before/after/exception 同 ctx）
        Map<String, Object> extraFirst = new HashMap<>();
        Map<String, Object> extraActive = new HashMap<>();
        Map<String, Object> extraRejected1 = new HashMap<>();
        Map<String, Object> extraRejected2 = new HashMap<>();

        // 第一次 fork 放行并完成
        AgentCallbackContext first = newForkCtx(extraFirst);
        rail.beforeToolCall(first);
        assertThat(rail.getActiveBranches()).isEqualTo(1);
        rail.afterToolCall(first);
        assertThat(rail.getActiveBranches()).isZero();

        // 打满束宽：放行 1 个，拒绝 2 个
        AgentCallbackContext active = newForkCtx(extraActive);
        rail.beforeToolCall(active);
        assertThat(rail.getActiveBranches()).isEqualTo(1);
        AgentCallbackContext rejected1 = newForkCtx(extraRejected1);
        rail.beforeToolCall(rejected1);
        AgentCallbackContext rejected2 = newForkCtx(extraRejected2);
        rail.beforeToolCall(rejected2);
        assertThat(rail.getRejections()).isEqualTo(2);

        // 关键断言：被拒调用的 afterToolCall 不得归还槽位（旧实现 → -1）
        rail.afterToolCall(rejected1);
        rail.afterToolCall(rejected2);
        assertThat(rail.getActiveBranches())
                .as("拒绝路径未占用槽位——归还后不得下溢为负")
                .isEqualTo(1);

        // 活跃调用完成后正常归零
        rail.afterToolCall(active);
        assertThat(rail.getActiveBranches()).isZero();
    }

    // ═══ M3：异常路径归还槽位 ═══

    @Test
    void forkExceptionReleasesSlotViaOnToolException() {
        BudgetRail rail = new BudgetRail(2, 5, 0);
        Map<String, Object> extra = new HashMap<>();

        AgentCallbackContext allowed = newForkCtx(extra);
        rail.beforeToolCall(allowed);
        assertThat(rail.getActiveBranches()).isEqualTo(1);

        // 子 agent 抛异常：AFTER 不触发，onToolException 兜底归还
        rail.onToolException(allowed);
        assertThat(rail.getActiveBranches())
                .as("异常路径必须归还槽位——泄漏满 W 次后分叉永久瘫痪")
                .isZero();

        // 幂等：重复异常回调不得再次归还（下溢防护）
        rail.onToolException(allowed);
        assertThat(rail.getActiveBranches()).isZero();
    }

    // ═══ M4 + D4：拒绝预填 ToolMessage 协议 + 三因拆真话 ═══

    @Test
    void rejectedForkPreFillsToolMessageWithCallId() {
        BudgetRail rail = new BudgetRail(1, 5, 0);
        Map<String, Object> extra = new HashMap<>();

        AgentCallbackContext active = newForkCtx(extra);
        rail.beforeToolCall(active); // 占满束宽
        Map<String, Object> extraRejected = new HashMap<>();
        AgentCallbackContext rejected = newForkCtx(extraRejected);
        rail.beforeToolCall(rejected); // 被拒

        ToolCallInputs inputs = (ToolCallInputs) rejected.getInputs();
        assertThat(inputs.getToolMsg())
                .as("拒绝必须预填 ToolMessage——否则被跳过的 tool_call 无响应 → API 400")
                .isNotNull();
        assertThat(inputs.getToolMsg().getToolCallId())
                .as("toolCallId 必须回指被拒调用")
                .isEqualTo("call_00_BUDGET");
        String msg = String.valueOf(inputs.getToolMsg().getContent());
        // D4 beamwidth 变体：数字承重事实 + 指导性降级（web 搜索仍可用）
        assertThat(msg)
                .as("束宽拒绝=暂时性真话：槽位事实 + 导流继续当前分支")
                .contains("Fork not started: 1 of 1 branch slots are active")
                .contains("a slot frees when a branch finishes")
                .contains("Continue the current branch with what has already been collected")
                .contains("web searches remain available");
        // 负向断言：beamwidth 永不出现终局措辞（暂时性真话的承重面）
        assertThat(msg)
                .as("beamwidth 是暂时性——永不出现终局措辞")
                .doesNotContain("no further forks")
                .doesNotContain("final");
        assertThat(extraRejected.get("_skip_tool")).isEqualTo(Boolean.TRUE);
    }

    @Test
    void exhaustedForkCarriesFinalityOnlyOnSecondRejection() {
        BudgetRail rail = new BudgetRail(2, 1, 0); // 总 fork 上限 1
        Map<String, Object> extraA = new HashMap<>();
        AgentCallbackContext first = newForkCtx(extraA);
        rail.beforeToolCall(first); // 放行（用掉唯一配额）
        rail.afterToolCall(first);  // 归还槽位

        // 第一次 exhausted 拒绝：无终局标记
        Map<String, Object> extraR1 = new HashMap<>();
        AgentCallbackContext r1 = newForkCtx(extraR1);
        rail.beforeToolCall(r1);
        String msg1 = String.valueOf(((ToolCallInputs) r1.getInputs()).getToolMsg().getContent());
        assertThat(msg1)
                .as("exhausted 拒绝带配额数字；首次拒绝无终局标记")
                .contains("fork limit (1 of 1) is reached")
                .contains("no further forks will be granted")
                .doesNotContain("This decision is final");

        // 第二次拒绝：终局标记出现
        Map<String, Object> extraR2 = new HashMap<>();
        AgentCallbackContext r2 = newForkCtx(extraR2);
        rail.beforeToolCall(r2);
        String msg2 = String.valueOf(((ToolCallInputs) r2.getInputs()).getToolMsg().getContent());
        assertThat(msg2)
                .as("二次拒绝起附终局标记（dsh 3/5/8 升级思想的降维）")
                .contains("This decision is final for this run.");
    }

    @Test
    void tokenBudgetRejectionIsHonestWithoutNumbers() {
        BudgetRail rail = new BudgetRail(2, 5, 100);
        rail.recordTokens(200); // 超池
        Map<String, Object> extra = new HashMap<>();
        AgentCallbackContext rejected = newForkCtx(extra);
        rail.beforeToolCall(rejected);
        String msg = String.valueOf(
                ((ToolCallInputs) rejected.getInputs()).getToolMsg().getContent());
        assertThat(msg)
                .as("token 池是估计值——拒绝文案不带数字（诚实）但带终局语义")
                .contains("token budget cannot cover another fork")
                .doesNotContain("(")
                .contains("Continue the current branch");
    }

    // ═══ 放行路径不设 skip、不预填消息 ═══

    @Test
    void allowedForkPassesThroughCleanly() {
        BudgetRail rail = new BudgetRail(2, 5, 0);
        Map<String, Object> extra = new HashMap<>();

        AgentCallbackContext allowed = newForkCtx(extra);
        rail.beforeToolCall(allowed);

        assertThat(extra).doesNotContainKey("_skip_tool");
        assertThat(((ToolCallInputs) allowed.getInputs()).getToolMsg()).isNull();
        assertThat(rail.getTotalForks()).isEqualTo(1);
    }
}
