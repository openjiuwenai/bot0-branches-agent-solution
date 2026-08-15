/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * EdpaKernel 全分支 content-IFF 单测（4-lens M2 治本：把"零测试死臂"变"测试锁定的
 * PevKernel 语义位"）。
 *
 * <p>生产事实（见 EdpaKernel javadoc honest boundary）：EDPA 唯一生产调用是
 * ProactiveConvergenceRail 恒传 {@code PlanOrAnswerError + 空 nodes}——只有
 * empty-nodes GlobalReplan 路径生产可达。其余分支（device/perception 降级、
 * 按节点数的 local/global 切分）是 PEV 移植语义，今天不可达但必须被锁住：
 * 一旦未来接线 DeviceFailure/PerceptionUnreliable 生产者，Kernel 行为已被
 * 本测试钉死，不会静默漂移。
 *
 * <p>断言形态：类型 + payload 字段（content-IFF），非弱断言——每个分支的
 * 返回类型与字段值都被精确断言，改动映射必红。
 *
 * @since 2026-08
 */
class EdpaKernelTest {

    /**
     * 生产真实路径：PlanOrAnswerError + 空 failedNodes → GlobalReplan（convergence
     * stall 消费的唯一形态）。
     */
    @Test
    void planOrAnswerErrorWithEmptyNodesYieldsGlobalReplan() {
        ReplanAction action = EdpaKernel.toReplanAction(
                new RootCause.PlanOrAnswerError(Set.of()), "覆盖停滞", Set.of());
        assertThat(action).as("empty nodes → GlobalReplan (production path)")
                .isInstanceOfSatisfying(ReplanAction.GlobalReplan.class,
                        gr -> assertThat(gr.feedback()).isEqualTo("覆盖停滞"));
    }

    /**
     * null failedNodes 与空集等价（防御分支）。
     */
    @Test
    void planOrAnswerErrorWithNullNodesYieldsGlobalReplan() {
        ReplanAction action = EdpaKernel.toReplanAction(
                new RootCause.PlanOrAnswerError(Set.of()), "fb", null);
        assertThat(action).as("null nodes treated as empty → GlobalReplan")
                .isInstanceOf(ReplanAction.GlobalReplan.class);
    }

    /**
     * PEV 语义位：nodes ≤ 2 → LocalReplan（携带 failedNodes + feedback）。
     */
    @Test
    void planOrAnswerErrorWithSmallNodeSetYieldsLocalReplan() {
        ReplanAction action = EdpaKernel.toReplanAction(
                new RootCause.PlanOrAnswerError(Set.of()), "修正反馈", Set.of("node-a", "node-b"));
        assertThat(action).as("≤2 nodes → LocalReplan")
                .isInstanceOfSatisfying(ReplanAction.LocalReplan.class, lr -> {
                    assertThat(lr.failedNodes()).containsExactlyInAnyOrder("node-a", "node-b");
                    assertThat(lr.feedback()).isEqualTo("修正反馈");
                });
    }

    /**
     * PEV 语义位：nodes ≥ 3 → GlobalReplan（失败面太大，局部重执行不划算）。
     */
    @Test
    void planOrAnswerErrorWithLargeNodeSetYieldsGlobalReplan() {
        ReplanAction action = EdpaKernel.toReplanAction(
                new RootCause.PlanOrAnswerError(Set.of()), "fb", Set.of("n1", "n2", "n3"));
        assertThat(action).as(">2 nodes → GlobalReplan").isInstanceOf(ReplanAction.GlobalReplan.class);
    }

    /**
     * PEV 语义位：DeviceFailure → AcceptPartial（重规划治不了坏设备，诚实降级），
     * reason 携带故障节点。
     */
    @Test
    void deviceFailureYieldsAcceptPartialWithNodes() {
        ReplanAction action = EdpaKernel.toReplanAction(
                new RootCause.DeviceFailure(Set.of("mcp-server")), "fb", Set.of());
        assertThat(action).as("device failure → AcceptPartial (honest degrade)")
                .isInstanceOfSatisfying(ReplanAction.AcceptPartial.class,
                        ap -> assertThat(ap.reason()).contains("mcp-server").contains("Device failure"));
    }

    /**
     * PEV 语义位：PerceptionUnreliable(thrown) → AcceptPartial，reason 标注 "threw"。
     */
    @Test
    void perceptionUnreliableThrownYieldsAcceptPartial() {
        ReplanAction action = EdpaKernel.toReplanAction(
                new RootCause.PerceptionUnreliable(true), "fb", Set.of());
        assertThat(action).as("verifier threw → AcceptPartial")
                .isInstanceOfSatisfying(ReplanAction.AcceptPartial.class,
                        ap -> assertThat(ap.reason()).contains("threw"));
    }

    /**
     * PEV 语义位：PerceptionUnreliable(not thrown) → AcceptPartial，reason 标注
     * "returned null"。
     */
    @Test
    void perceptionUnreliableNullYieldsAcceptPartial() {
        ReplanAction action = EdpaKernel.toReplanAction(
                new RootCause.PerceptionUnreliable(false), "fb", Set.of());
        assertThat(action).as("verifier returned null → AcceptPartial")
                .isInstanceOfSatisfying(ReplanAction.AcceptPartial.class,
                        ap -> assertThat(ap.reason()).contains("returned null"));
    }
}
