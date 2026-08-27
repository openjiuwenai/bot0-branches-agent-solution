/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.observability;

import com.openjiuwen.agents.pev.observability.PevTrace;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轨迹森林——graph-loop 树结构轨迹的存储与导航。
 *
 * <p>管理一组 {@link BranchedTrace}，提供：
 * <ul>
 *   <li>{@link #addRoot} / {@link #addFork}——添加根分支或子分支</li>
 *   <li>{@link #childrenOf}——获取某分支的全部直接子分支</li>
 *   <li>{@link #pathToRoot}——从某分支回溯到根的路径（回滚用）</li>
 *   <li>{@link #depth}——分支深度（根=0）</li>
 *   <li>{@link #size}——森林中的分支总数</li>
 * </ul>
 *
 * <p><b>线程安全</b>：ConcurrentHashMap 存储；分支一旦添加不可修改（record 不可变）。
 *
 * <p><b>Honest boundary</b>：
 * <ul>
 *   <li>本类不做回滚执行（只提供寻址）——回滚的「重放」属于执行层</li>
 *   <li>不做收敛评估（GroundTruthVerifier 属于 ConvergenceRail）</li>
 *   <li>不管理分支生命周期（active/converged/abandoned）——第一版最小化</li>
 * </ul>
 *
 * @since 2026-08
 */
public final class TraceForest {

    /** 分支存储：branchId → BranchedTrace。 */
    private final Map<String, BranchedTrace> branches = new ConcurrentHashMap<>();

    /** 子分支索引：parentBranchId → 直接子分支 ID 列表（导航加速）。 */
    private final Map<String, List<String>> childrenIndex = new ConcurrentHashMap<>();

    /** 分支 ID 序列生成器（简单递增，保证唯一性）。 */
    private final AtomicLong idSeq = new AtomicLong(0);

    /**
     * 生成下一个分支 ID。
     *
     * @return 格式为 "b-{seq}" 的唯一分支 ID
     */
    public String nextBranchId() {
        return "b-" + idSeq.incrementAndGet();
    }

    /**
     * 添加根分支（无父）。
     *
     * @param trace 根分支的执行轨迹
     * @return 添加的 BranchedTrace（含分配的 branchId）
     */
    public BranchedTrace addRoot(PevTrace trace) {
        String id = nextBranchId();
        BranchedTrace bt = new BranchedTrace(BranchAddress.root(id), trace);
        branches.put(id, bt);
        return bt;
    }

    /**
     * 添加子分支（指定父分支与分叉点）。
     *
     * @param parentBranchId 父分支 ID（必须已存在）
     * @param forkPhaseIndex 父 trace 中的分叉 Phase 索引
     * @param trace 子分支的执行轨迹
     * @return 添加的 BranchedTrace
     * @throws IllegalArgumentException 如果父分支不存在
     */
    public BranchedTrace addFork(String parentBranchId, int forkPhaseIndex, PevTrace trace) {
        if (!branches.containsKey(parentBranchId)) {
            throw new IllegalArgumentException(
                    "parent branch not found: " + parentBranchId);
        }
        String id = nextBranchId();
        BranchedTrace bt = new BranchedTrace(
                BranchAddress.fork(id, parentBranchId, forkPhaseIndex), trace);
        branches.put(id, bt);
        childrenIndex.computeIfAbsent(parentBranchId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(id);
        return bt;
    }

    /**
     * 获取指定分支。
     *
     * @param branchId 分支 ID
     * @return 分支轨迹；不存在返回 empty
     */
    public Optional<BranchedTrace> get(String branchId) {
        return Optional.ofNullable(branches.get(branchId));
    }

    /**
     * 获取某分支的全部直接子分支。
     *
     * @param branchId 父分支 ID
     * @return 直接子分支列表（可能为空）
     */
    public List<BranchedTrace> childrenOf(String branchId) {
        List<String> childIds = childrenIndex.getOrDefault(branchId, List.of());
        List<BranchedTrace> result = new ArrayList<>(childIds.size());
        for (String id : childIds) {
            BranchedTrace bt = branches.get(id);
            if (bt != null) {
                result.add(bt);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 从某分支回溯到根的路径（含自身，不含根——回滚寻址用）。
     *
     * <p>路径顺序：从当前分支到根方向的依次父链。
     * 例：root → A → B → C，则 pathToRoot("C") = [C, B, A]。
     *
     * @param branchId 起始分支 ID
     * @return 从起始分支到根方向的父链（不含根）；根自身返回空列表
     */
    public List<BranchedTrace> pathToRoot(String branchId) {
        List<BranchedTrace> path = new ArrayList<>();
        String current = branchId;
        while (current != null) {
            BranchedTrace bt = branches.get(current);
            if (bt == null) {
                break;
            }
            if (!bt.address().isRoot()) {
                path.add(bt);
            }
            current = bt.address().parentBranchId();
        }
        return Collections.unmodifiableList(path);
    }

    /**
     * 获取某分支的深度（根=0，子=父+1）。
     *
     * @param branchId 分支 ID
     * @return 深度；不存在返回 -1
     */
    public int depth(String branchId) {
        int d = 0;
        String current = branchId;
        while (current != null) {
            BranchedTrace bt = branches.get(current);
            if (bt == null) {
                return -1;
            }
            if (bt.address().isRoot()) {
                return d;
            }
            d++;
            current = bt.address().parentBranchId();
        }
        return d;
    }

    /**
     * 全部根分支（pathToRoot 为空者——多根场景逐个返回；ForkOrchestrator 单根）。
     *
     * @return 根分支列表（无根返回空）
     */
    public List<BranchedTrace> roots() {
        return branches.values().stream()
                .filter(bt -> bt.address().isRoot())
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 森林中的分支总数。
     *
     * @return 分支数
     */
    public int size() {
        return branches.size();
    }

    /**
     * 森林是否为空。
     *
     * @return true 如果无任何分支
     */
    public boolean isEmpty() {
        return branches.isEmpty();
    }
}
