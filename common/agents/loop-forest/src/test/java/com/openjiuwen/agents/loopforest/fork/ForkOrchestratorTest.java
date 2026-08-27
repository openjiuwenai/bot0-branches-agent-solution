/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.fork;

import com.openjiuwen.agents.loopforest.observability.TraceForest;
import com.openjiuwen.agents.loopforest.verification.ConvergenceEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ForkOrchestrator 承重测试（R1-F3 处置：执行层原为零验证——唯一执行处是
 * env-gated e2e）。覆盖：根复用（R1-F6 修复面）、fork 委派与结果记账、
 * allResults 确定性排序。
 *
 * @since 2026-08
 */
class ForkOrchestratorTest {

    @Test
    void forkingFromRootReusesSingleRootAcrossCalls() {
        TraceForest forest = new TraceForest();
        ForkOrchestrator orch = new ForkOrchestrator(forest);
        assertThat(forest.isEmpty()).isTrue();

        String r1 = orch.forkingFromRoot((in, goal) -> "a:" + in).execute("t1", "g");
        String r2 = orch.forkingFromRoot((in, goal) -> "b:" + in).execute("t2", "g");

        assertThat(r1).isEqualTo("a:t1");
        assertThat(r2).isEqualTo("b:t2");
        // R1-F6 承重：两次 forkingFromRoot 复用一个根——恰 1 root + 2 fork = 3
        // （旧死守卫双臂 return null 每次再造新根 → size=4）
        assertThat(forest.size()).as("根复用：size=1根+2叉").isEqualTo(3);
    }

    @Test
    void forkingRegistersBranchAndResultAfterExecute() {
        TraceForest forest = new TraceForest();
        ForkOrchestrator orch = new ForkOrchestrator(forest);
        assertThat(forest.size()).as("执行前零登记（登记发生在 execute 后）").isZero();

        String out = orch.forkingFromRoot((in, goal) -> "x:" + in).execute("task-a", "g");
        assertThat(out).isEqualTo("x:task-a");
        assertThat(forest.size()).as("root+fork 各一").isEqualTo(2);
        assertThat(orch.allResults()).as("结果已记账").hasSize(1);
        assertThat(orch.allResults().get(0).result()).isEqualTo("x:task-a");
    }

    @Test
    void allResultsSortedDeterministically() {
        TraceForest forest = new TraceForest();
        ForkOrchestrator orch = new ForkOrchestrator(forest);
        var root = orch.forkingFromRoot((in, goal) -> "r");
        root.execute("t", "g");
        orch.forking(rootBranchOf(forest), 1, (in, goal) -> "b1").execute("t", "g");
        orch.forking(rootBranchOf(forest), 2, (in, goal) -> "b2").execute("t", "g");

        List<ConvergenceEvaluator.Candidate> all = orch.allResults();
        assertThat(all).as("三分支结果全在").hasSize(3);
        assertThat(all.get(0).branchId()).as("按 branchId 字典序——CHM 迭代序不外泄")
                .isLessThan(all.get(1).branchId());
        assertThat(all.get(1).branchId()).isLessThan(all.get(2).branchId());
    }

    /** 根分支 ID（TraceForest.roots()——R1-F6 处置顺带的公开 API）。 */
    private static String rootBranchOf(TraceForest forest) {
        return forest.roots().get(0).branchId();
    }
}
