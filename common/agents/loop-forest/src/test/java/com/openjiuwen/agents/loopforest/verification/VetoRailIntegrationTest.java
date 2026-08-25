/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * VetoRail 集成测试——真实 AgentCallbackContext + ToolCallInputs 走完整 beforeToolCall 流程。
 *
 * <p>与 {@link VetoRailTest}（单元级契约测试）互补：本测试验证 rail hook 与
 * agent-core 的实际交互——skip_tool 标志设置、toolResult 预填、计数器递增。
 * 承重断言（content-IFF）：
 * <ul>
 *   <li>否决时：extra 含 _skip_tool=true + toolResult 含错误消息 + vetoCount ≥ 1</li>
 *   <li>放行时：extra 无 _skip_tool + toolResult 为 null + vetoCount 不变</li>
 * </ul>
 *
 * @since 2026-08
 */
class VetoRailIntegrationTest {

    /** 单一真源：与资源文件一致（RejectionResource.load——不再硬编码副本防漂移）。 */
    private static final String REJECTION = RejectionResource.load();

    private static VetoRail newRail() {
        return new VetoRail(
                new VetoContract(Map.of("write_artifact", Set.of("baseline", "followup"))),
                REJECTION);
    }

    private static AgentCallbackContext newCtx(String toolName, Object toolArgs) {
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolName(toolName)
                .toolArgs(toolArgs)
                .build();
        Map<String, Object> extra = new HashMap<>();
        return AgentCallbackContext.builder()
                .agent(new Object())
                .event(null)
                .inputs(inputs)
                .extra(extra)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ToolCallInputs inputsOf(AgentCallbackContext ctx) {
        return (ToolCallInputs) ctx.getInputs();
    }

    // ═══ 否决路径：含契约外字段 → skip_tool + 错误结果 + 计数递增 ═══

    @Test
    void vetoSetsSkipToolAndErrorResult() {
        VetoRail rail = newRail();
        Map<String, Object> argsWithExtra = Map.of(
                "baseline", "data/a.csv",
                "timeline_review", "多余字段");
        AgentCallbackContext ctx = newCtx("write_artifact", argsWithExtra);

        rail.beforeToolCall(ctx);

        // 1. skip_tool 标志
        assertThat(ctx.getExtra())
                .as("否决后 extra 含 _skip_tool=true")
                .containsEntry(VetoRail.SKIP_TOOL_EXTRA_KEY == null ? "" : "_skip_tool", Boolean.TRUE);

        // 2. 预填错误结果
        ToolCallInputs inputs = inputsOf(ctx);
        assertThat(inputs.getToolResult())
                .as("toolResult 应为含 error 的 Map")
                .isInstanceOf(Map.class);
        Map<String, Object> result = (Map<String, Object>) inputs.getToolResult();
        assertThat(result.get("error"))
                .as("error 消息与外置资源一致")
                .isEqualTo(REJECTION);

        // 3. 计数器
        assertThat(rail.getVetoCount())
                .as("否决后计数 ≥ 1")
                .isGreaterThanOrEqualTo(1);
    }

    // ═══ 否决路径：ToolMessage 协议正确性（真 LLM e2e 暴露的 API 400 根因）═══

    @Test
    void vetoPreFillsToolMessageWithRealCallIdAndContent() {
        VetoRail rail = newRail();
        Map<String, Object> argsWithExtra = Map.of(
                "baseline", "data/a.csv",
                "timeline_review", "多余字段");
        // 携带真实 ToolCall id——双参 ToolMessage(content, toolCallId) 曾被误当
        // (role, content) 用，导致 tool_call_id=拒绝消息文本 → API 400
        com.openjiuwen.core.foundation.llm.schema.ToolCall call =
                new com.openjiuwen.core.foundation.llm.schema.ToolCall();
        call.setId("call_00_TESTID");
        call.setName("write_artifact");
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(call)
                .toolName("write_artifact")
                .toolArgs(argsWithExtra)
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .agent(new Object())
                .event(null)
                .inputs(inputs)
                .extra(new HashMap<>())
                .build();

        rail.beforeToolCall(ctx);

        com.openjiuwen.core.foundation.llm.schema.ToolMessage msg = inputs.getToolMsg();
        assertThat(msg).as("否决后预填 ToolMessage").isNotNull();
        // content-IFF：content=拒绝消息（不是 "tool"）
        assertThat(msg.getContent())
                .as("content 必须是拒绝消息")
                .isEqualTo(REJECTION);
        // 协议承重：toolCallId 必须是真实调用 id（不是拒绝消息文本）
        assertThat(msg.getToolCallId())
                .as("toolCallId 必须回指被否决的调用")
                .isEqualTo("call_00_TESTID");
        assertThat(msg.getName()).isEqualTo("write_artifact");
    }

    // ═══ 放行路径：纯白名单字段 → 无 skip、无错误、计数不变 ═══

    @Test
    void allowedFieldsPassThroughWithoutVeto() {
        VetoRail rail = newRail();
        Map<String, Object> allowedArgs = Map.of(
                "baseline", "data/a.csv",
                "followup", "data/c.csv");
        AgentCallbackContext ctx = newCtx("write_artifact", allowedArgs);

        rail.beforeToolCall(ctx);

        assertThat(ctx.getExtra() == null || !ctx.getExtra().containsKey("_skip_tool"))
                .as("白名单字段放行：不设 _skip_tool")
                .isTrue();

        assertThat(inputsOf(ctx).getToolResult())
                .as("放行时 toolResult 不被预填")
                .isNull();

        assertThat(rail.getVetoCount())
                .as("放行时计数不变")
                .isZero();
    }

    // ═══ fail-open 路径：无契约工具 → 完全不干预 ═══

    @Test
    void uncoveredToolIsNotIntercepted() {
        VetoRail rail = newRail();
        AgentCallbackContext ctx = newCtx("read_file", Map.of("path", "test.csv"));

        rail.beforeToolCall(ctx);

        assertThat(ctx.getExtra()).as("无契约工具不设 skip").doesNotContainKey("_skip_tool");
        assertThat(rail.getVetoCount()).as("无契约工具不计数").isZero();
    }

    // ═══ JSON 字符串 args → 正确提取顶层键并否决 ═══

    @Test
    void jsonStringArgsWithExtraFieldAreVetoed() {
        VetoRail rail = newRail();
        String jsonArgs = "{\"baseline\": \"a\", \"extra_field\": \"b\"}";
        AgentCallbackContext ctx = newCtx("write_artifact", jsonArgs);

        rail.beforeToolCall(ctx);

        assertThat(ctx.getExtra())
                .as("JSON 字符串含 extra_field → 否决")
                .containsEntry("_skip_tool", Boolean.TRUE);
        assertThat(rail.getVetoCount()).as("JSON 路径也计数").isEqualTo(1);
    }

    // ═══ 零提及验证：错误消息不含具体字段名 ═══

    @Test
    @SuppressWarnings("unchecked")
    void rejectionMessageDoesNotMentionSpecificFieldNames() {
        VetoRail rail = newRail();
        AgentCallbackContext ctx = newCtx("write_artifact",
                Map.of("baseline", "a", "timeline_review", "b", "extra_thing", "c"));
        rail.beforeToolCall(ctx);

        Map<String, Object> result = (Map<String, Object>) inputsOf(ctx).getToolResult();
        String error = String.valueOf(result.get("error"));
        assertThat(error)
                .as("零提及纪律：拒绝消息不点名 timeline_review/extra_thing")
                .doesNotContain("timeline_review")
                .doesNotContain("extra_thing")
                .doesNotContain("baseline");
    }
}
