/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bug #4 evidence — CriteriaReplanBridgeRail hardcodes {@code __replan__} tool
 * reference in its steering hint without verifying the target agent has that
 * tool registered.
 *
 * <p>Two independent assertions combine to prove the dangling reference:
 * <ol>
 *   <li>{@link #site1_gradientHintHardcodesReplanToolReference()} — rail emits a
 *       hint containing literal string {@code "__replan__"} (source location:
 *       {@code CriteriaReplanBridgeRail.java:188}).</li>
 *   <li>{@link #bareReActAgentHasNoReplanToolAfterRailRegistration()} — attaching
 *       the rail to an agent does NOT auto-register {@link ReplanTool}, so the
 *       agent's {@code AbilityManager} still lacks {@code __replan__}.</li>
 * </ol>
 *
 * <p>Combined, (1) + (2) demonstrate that a consumer following the natural
 * pattern — attach the rail without also calling
 * {@code ReplanTool.registerOnto(agent)} — ends up with rail-generated LLM hints
 * referencing a tool the agent cannot dispatch. This is verify-agent's actual
 * configuration (see {@link VerifyAgentFactory} — no {@code ReplanTool.registerOnto}).
 *
 * <p><b>Current status</b>: both tests PASS today because the bug is present.
 * When Bug #4 is fixed (fallback / peek AbilityManager), assertion (1) must be
 * flipped to {@code doesNotContain("__replan__")}. The fixer of Bug #4 must
 * update this test accordingly — that's the intended handshake.
 *
 * <p>This test is standalone: it does NOT hit any live LLM, does NOT need the
 * verify-agent runtime to be running, and does NOT require Bug #3 to be
 * resolved. It reads the rail's hint directly from an in-process capture queue.
 *
 * @see <a href="../../../../../../../../ISSUE_BUG4_hardcoded_replan_tool_reference.md">ISSUE_BUG4</a>
 */
class Bug4ReplanReferenceEvidenceTest {
    @Test
    void site1_gradientHintHardcodesReplanToolReference() {
        // Given: a CriteriaVerifier that emits gradient-metadata Violations
        // (this is what a future GradientVerifier would produce; RuleBasedCriteriaVerifier
        // currently does not, which is why Site 1 is dead code in the shipped PR — but the
        // hardcoding is there, waiting to activate)
        CriteriaVerifier gradient = (criteria, output, history) -> List.of(new Violation("对比矩阵", "缺失",
                Map.of("isPartial", Boolean.TRUE, "covered", List.<String>of(), "missing", List.of("对比矩阵"))));

        // When: rail fires under-limit fail path → pushSteering(gradientHint)
        CaptureSteeringQueue steering = new CaptureSteeringQueue();
        ReplanRail replan = new ReplanRail(3);
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(gradient, List.of("对比矩阵"), replan);
        rail.afterModelCall(ctxWithFinalAnswer("some bad answer", steering));

        // Then: hint hardcodes "__replan__" reference — this is the Site 1 bug
        assertThat(steering.captured).as("rail must have pushed a steering hint on verify-fail under-limit").hasSize(1);
        assertThat(steering.captured.get(0))
                .as("Site 1 (CriteriaReplanBridgeRail.java:188) hardcodes __replan__ into the hint "
                        + "regardless of whether the target agent has the tool registered")
                .contains("__replan__");
    }

    @Test
    void bareReActAgentHasNoReplanToolAfterRailRegistration() {
        // Given: a bare ReActAgent with ONLY CriteriaReplanBridgeRail attached —
        // this mirrors verify-agent's actual factory (no ReplanTool.registerOnto call)
        ReActAgent bare = new ReActAgent(AgentCard.builder().id("bare-no-tool").name("bare-no-tool").build());
        bare.registerRail(
                new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(), List.of("dummy"), new ReplanRail(3)));

        // Then: AbilityManager does NOT contain __replan__ — attaching the rail does not
        // auto-provision the tool the rail's hint references. Consumer must remember to
        // call ReplanTool.registerOnto(agent) separately (see react-rails/README.md:32),
        // and if they forget, no warning fires anywhere.
        List<ToolInfo> visible = bare.getAbilityManager().listToolInfo();
        assertThat(visible).as("attaching CriteriaReplanBridgeRail must not silently add __replan__ visibility "
                + "— but also does not warn that its hint references a missing tool")
                .noneMatch(t -> ReplanTool.TOOL_NAME.equals(t.getName()));
    }

    // ---------- helpers (copied from CriteriaReplanBridgeRailTest for standalone use) ----------

    private static AgentCallbackContext ctxWithFinalAnswer(String answer, SteeringQueue steeringQ) {
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);
        return AgentCallbackContext.builder().agent(new Object()).inputs(inputs).steeringQueue(steeringQ).build();
    }

    /** Capture steering queue spy — records pushSteering calls. */
    private static final class CaptureSteeringQueue implements SteeringQueue {
        private final List<String> captured = new ArrayList<>();

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
}
