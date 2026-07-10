/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PEV decision core 承重测试 — mock 证 diagnose/dispatch 控制流（硬断言）。
 * 每个测试标注 mutation-RED：剥什么会红。
 */
class PevKernelTest {
    // ==================== diagnoseRootCause 优先级契约 ====================

    @Test
    void parseFailureDominatesAllOtherSignalsYieldsPerceptionUnreliable() {
        // 即使有 DeviceFailure 节点 + verify failed，hasParseFailure 仍最高优先
        PevKernel.VerifyResult vr = new PevKernel.VerifyResult(false, Set.of("A"), "fb", true);

        RootCause c = PevKernel.diagnoseRootCause(vr, Set.of("A"),
                Map.of("A", new NodeResult.DeviceFailure("A", "e", true)));

        assertThat(c).isInstanceOf(RootCause.PerceptionUnreliable.class);
        // mutation-RED: 剥 hasParseFailure 优先判定（拿掉 if）→ 该用例拿到 DeviceFailure → RED
    }

    @Test
    void deviceFailureNodeIntersectVerifyFailedYieldsDeviceFailure() {
        PevKernel.VerifyResult vr = new PevKernel.VerifyResult(false, Set.of("A", "B"), "fb", false);

        RootCause c = PevKernel.diagnoseRootCause(vr, Set.of("A"),
                Map.of("A", new NodeResult.DeviceFailure("A", "e", true)));

        assertThat(c).isInstanceOfSatisfying(RootCause.DeviceFailure.class,
                failure -> assertThat(failure.nodes()).containsExactly("A"));
        // mutation-RED: 剥 nodeResults DeviceFailure 收集 → 不识别 A → 退化为 PlanOrAnswerError → RED
    }

    @Test
    void noDeviceSignalYieldsPlanOrAnswerError() {
        PevKernel.VerifyResult vr = new PevKernel.VerifyResult(false, Set.of("A", "B"), "fb", false);

        RootCause c = PevKernel.diagnoseRootCause(vr, Set.of(), Map.of());

        assertThat(c).isInstanceOf(RootCause.PlanOrAnswerError.class);
        // mutation-RED: 改 fallback 返 DeviceFailure → 该用例 RED
    }

    // ==================== toReplanAction IFF 契约 ====================

    @Test
    void deviceFailureYieldsAcceptPartialNeverRetry() {
        ReplanAction a = PevKernel.toReplanAction(new RootCause.DeviceFailure(Set.of("A")), "fb", Set.of("A"));

        assertThat(a).isInstanceOf(ReplanAction.AcceptPartial.class);
        // mutation-RED: 剥 toReplanAction 的 DeviceFailure case arm → sealed switch 非穷举 → 编译红（编译期证，比运行时 RED 更早）
    }

    @Test
    void perceptionUnreliableYieldsAcceptPartialNeverTrustFailed() {
        ReplanAction a = PevKernel.toReplanAction(new RootCause.PerceptionUnreliable(true), "fb", Set.of("A"));

        assertThat(a).isInstanceOf(ReplanAction.AcceptPartial.class);
    }

    @Test
    void planErrorFewFailedYieldsLocalReplan() {
        ReplanAction a = PevKernel.toReplanAction(new RootCause.PlanOrAnswerError(Set.of()), "fb", Set.of("A", "B"));

        assertThat(a).isInstanceOfSatisfying(ReplanAction.LocalReplan.class,
                replan -> assertThat(replan.failedNodes()).containsExactlyInAnyOrder("A", "B"));
        // mutation-RED: 改 threshold <=2 → <2 → AB 用例退化为 GlobalReplan → RED
    }

    @Test
    void planErrorManyFailedYieldsGlobalReplan() {
        ReplanAction a = PevKernel.toReplanAction(new RootCause.PlanOrAnswerError(Set.of()), "fb",
                Set.of("A", "B", "C"));

        assertThat(a).isInstanceOf(ReplanAction.GlobalReplan.class);
        // mutation-RED: 改 threshold <=2 → <=3 → ABC 退化为 LocalReplan → RED
    }

    @Test
    void planErrorEmptyFailedYieldsGlobalReplan() {
        ReplanAction a = PevKernel.toReplanAction(new RootCause.PlanOrAnswerError(Set.of()), "fb", Set.of());

        assertThat(a).isInstanceOf(ReplanAction.GlobalReplan.class);
        // mutation-RED: 剥 isEmpty 分支 → 空 failedNodes 走 size<=2 → LocalReplan → RED
    }
}
