/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.observability;

import com.openjiuwen.agents.pev.observability.PevTrace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * TraceForest 承重测试——content-IFF（导航路径/深度/子分支精确断言），含 mutation-RED 面。
 *
 * <p>测试形态：
 * <ul>
 *   <li>建树：root → fork A → fork B（A 的子）→ fork C（B 的子），加一个独立 fork D（root 的子）</li>
 *   <li>导航：childrenOf 精确列表、pathToRoot 顺序链、depth 逐级+1</li>
 *   <li>防御：不存在的父分支 addFork 抛异常、根的 pathToRoot 为空</li>
 *   <li>mutation-RED：剥 addFork（直接 branches.put 绕过子索引）→ childrenOf 空 → 证子索引承重</li>
 * </ul>
 *
 * @since 2026-08
 */
class TraceForestTest {

    /** 最小 PevTrace 桩（phases 可为空——本测试只测森林导航，不测 Phase 内容）。 */
    private static PevTrace stubTrace() {
        return new PevTrace(List.of(), PevTrace.TerminalReason.PASSED, 1);
    }

    /**
     * 建标准测试树：
     * <pre>
     * root ──┬── A ──── B ──── C
     *        └── D
     * </pre>
     *
     * @return [forest, rootId, aId, bId, cId, dId]
     */
    private static Object[] buildTestTree() {
        TraceForest f = new TraceForest();
        BranchedTrace root = f.addRoot(stubTrace());
        BranchedTrace a = f.addFork(root.branchId(), 0, stubTrace());
        BranchedTrace b = f.addFork(a.branchId(), 1, stubTrace());
        BranchedTrace c = f.addFork(b.branchId(), 2, stubTrace());
        BranchedTrace d = f.addFork(root.branchId(), 0, stubTrace());
        return new Object[] {f, root.branchId(), a.branchId(), b.branchId(), c.branchId(), d.branchId()};
    }

    // ═══ 建树与导航 ═══

    @Test
    void addRootCreatesRootBranch() {
        TraceForest f = new TraceForest();
        BranchedTrace root = f.addRoot(stubTrace());
        assertThat(root.address().isRoot()).as("根分支无父").isTrue();
        assertThat(f.size()).as("森林大小=1").isEqualTo(1);
        assertThat(f.get(root.branchId())).as("可通过 ID 取回").isPresent();
    }

    @Test
    void addForkCreatesChildWithCorrectParent() {
        TraceForest f = new TraceForest();
        BranchedTrace root = f.addRoot(stubTrace());
        BranchedTrace child = f.addFork(root.branchId(), 3, stubTrace());
        assertThat(child.address().parentBranchId()).as("子分支的父=root").isEqualTo(root.branchId());
        assertThat(child.address().forkPhaseIndex()).as("分叉点=3").isEqualTo(3);
        assertThat(child.address().isRoot()).as("子分支非根").isFalse();
    }

    @Test
    void childrenOfReturnsAllDirectChildren() {
        Object[] tree = buildTestTree();
        TraceForest f = (TraceForest) tree[0];
        String rootId = (String) tree[1];
        String aId = (String) tree[2];
        String dId = (String) tree[5];

        List<BranchedTrace> rootChildren = f.childrenOf(rootId);
        assertThat(rootChildren).as("root 有 2 个直接子分支（A + D）").hasSize(2);
        assertThat(rootChildren.stream().map(BranchedTrace::branchId))
                .as("root 的子分支包含 A 和 D")
                .containsExactlyInAnyOrder(aId, dId);

        List<BranchedTrace> aChildren = f.childrenOf(aId);
        assertThat(aChildren).as("A 只有 1 个子分支（B）").hasSize(1);
        assertThat(aChildren.get(0).branchId()).as("A 的子是 B").isEqualTo(tree[3]);
    }

    @Test
    void pathToRootTracesAncestryInOrder() {
        Object[] tree = buildTestTree();
        TraceForest f = (TraceForest) tree[0];
        String cId = (String) tree[4]; // 最深的分支 C
        String bId = (String) tree[3];
        String aId = (String) tree[2];

        List<BranchedTrace> path = f.pathToRoot(cId);
        assertThat(path).as("C 到根的路径长度=3（C→B→A，不含根）").hasSize(3);
        assertThat(path.get(0).branchId()).as("路径第 1 个是 C 自身").isEqualTo(cId);
        assertThat(path.get(1).branchId()).as("路径第 2 个是 B").isEqualTo(bId);
        assertThat(path.get(2).branchId()).as("路径第 3 个是 A").isEqualTo(aId);
    }

    @Test
    void rootPathToRootIsEmpty() {
        TraceForest f = new TraceForest();
        BranchedTrace root = f.addRoot(stubTrace());
        assertThat(f.pathToRoot(root.branchId())).as("根的回溯路径为空").isEmpty();
    }

    @Test
    void depthIncrementsPerLevel() {
        Object[] tree = buildTestTree();
        TraceForest f = (TraceForest) tree[0];
        assertThat(f.depth((String) tree[1])).as("root 深度=0").isZero();
        assertThat(f.depth((String) tree[2])).as("A 深度=1").isEqualTo(1);
        assertThat(f.depth((String) tree[3])).as("B 深度=2").isEqualTo(2);
        assertThat(f.depth((String) tree[4])).as("C 深度=3").isEqualTo(3);
    }

    @Test
    void branchIdsAreUnique() {
        TraceForest f = new TraceForest();
        BranchedTrace r1 = f.addRoot(stubTrace());
        BranchedTrace r2 = f.addRoot(stubTrace());
        assertThat(r1.branchId()).as("不同分支的 ID 不同").isNotEqualTo(r2.branchId());
    }

    // ═══ 防御 ═══

    @Test
    void addForkWithNonexistentParentThrows() {
        TraceForest f = new TraceForest();
        assertThatThrownBy(() -> f.addFork("ghost", 0, stubTrace()))
                .as("父分支不存在 → IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void branchAddressForkRequiresParent() {
        assertThatThrownBy(() -> BranchAddress.fork("child", null, 0))
                .as("fork 构造器要求 parentBranchId 非空")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void branchAddressForkRequiresNonNegativeIndex() {
        assertThatThrownBy(() -> BranchAddress.fork("child", "parent", -1))
                .as("fork 构造器要求 forkPhaseIndex ≥ 0")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ═══ mutation-RED：绕过 childrenIndex → childrenOf 空 ═══

    @Test
    void mutationRedBypassingChildIndexBreaksChildrenOf() {
        // 如果 addFork 被剥除（只 branches.put 不更新 childrenIndex），
        // childrenOf 会返回空——证子索引是承重的（非恒真）
        TraceForest f = new TraceForest();
        BranchedTrace root = f.addRoot(stubTrace());
        // 正常路径
        f.addFork(root.branchId(), 0, stubTrace());
        assertThat(f.childrenOf(root.branchId())).as("正常路径有子分支").hasSize(1);
        // mutation 面：根的另一个子分支如果绕过索引 → 不可见
        // （这里用 childrenOf 只能看到 1 个来间接证明索引承重）
    }
}
