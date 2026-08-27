/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.studio.dsl.flowsubworkflow.FlowSubWorkflowConfig;
import com.openjiuwen.studio.dsl.flowsubworkflow.FlowSubWorkflowEngine;
import com.openjiuwen.studio.dsl.flowsubworkflow.SubWorkflowException;
import com.openjiuwen.studio.dsl.flowsubworkflow.SubWorkflowExecutionStatus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link FlowSubWorkflowEngine} Python parity helpers.
 *
 * @since 2026-08-26
 */

class FlowSubWorkflowEngineTest {
    @Test
    void packageSuccess_matchesPythonShape() {
        FlowSubWorkflowEngine engine =
                new FlowSubWorkflowEngine(FlowSubWorkflowConfig.fromNodeConfigs("n1", Map.of()));
        Map<String, Object> out = engine.packageSuccess("hi", Map.of("a", 1), Map.of("g", 2));
        assertThat(out)
                .containsEntry("responseContent", "hi")
                .containsEntry("userFields", Map.of("a", 1))
                .containsEntry("memory", Map.of("g", 2));
        assertThat(engine.nodeState().status()).isEqualTo(SubWorkflowExecutionStatus.END);
        assertThat(engine.lastChildCompleted()).isTrue();
    }

    @Test
    void parseNormalChildInvokeResult_readsStreamTail() {
        FlowSubWorkflowEngine engine =
                new FlowSubWorkflowEngine(FlowSubWorkflowConfig.fromNodeConfigs("n1", Map.of()));
        Map<String, Object> result =
                Map.of(
                        "answer",
                        "",
                        "stream",
                        List.of(Map.of("answer", "from-stream", "userFields", Map.of("x", 9))));
        FlowSubWorkflowEngine.ParsedChildResult parsed = engine.parseNormalChildInvokeResult(result);
        assertThat(parsed.responseContent()).isEqualTo("from-stream");
        assertThat(parsed.userFields()).containsEntry("x", 9);
    }

    @Test
    void findInterruptInStateTree_scansNestedCompState() {
        FlowSubWorkflowEngine engine =
                new FlowSubWorkflowEngine(FlowSubWorkflowConfig.fromNodeConfigs("nest", Map.of()));
        Map<String, Object> tree =
                Map.of(
                        "scope",
                        Map.of(
                                "child_qa",
                                Map.of(
                                        "questioner_state",
                                        Map.of("status", "user_interact", "question", "城市?"))));
        FlowSubWorkflowEngine.InterruptHit hit = engine.findInterruptInStateTree(tree);
        assertThat(hit).isNotNull();
        assertThat(hit.nodeId()).isEqualTo("child_qa");
        assertThat(hit.prompt()).isEqualTo("城市?");
    }

    @Test
    void statusCodes_matchPython() {
        assertThat(SubWorkflowException.CONFIG_VALIDATION_ERROR).isEqualTo(101160);
        assertThat(SubWorkflowException.WORKFLOW_INSTANCE_NOT_FOUND).isEqualTo(101161);
        assertThat(SubWorkflowException.EXECUTION_ERROR).isEqualTo(101162);
        assertThat(SubWorkflowException.STREAM_ERROR).isEqualTo(101163);
        assertThat(SubWorkflowException.EXECUTION_TIMEOUT).isEqualTo(101164);
    }

    @Test
    void packageStudioSuccess_keepsChainMarkersInsideUserFields() {
        FlowSubWorkflowEngine engine =
                new FlowSubWorkflowEngine(FlowSubWorkflowConfig.fromNodeConfigs("n1", Map.of()));
        Map<String, Object> python = engine.packageStudioSuccess(Map.of("result", "ok"), 2, null);
        assertThat(python.get("responseContent")).isEqualTo("ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) python.get("userFields");
        assertThat(uf)
                .containsEntry("result", "ok")
                .containsEntry("__nestingDepth__", 2)
                .containsEntry("nestedWorkflowState", "end")
                .containsEntry("should_interrupt", false);
    }
}
