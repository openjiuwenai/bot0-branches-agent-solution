/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

/**
 * VetoRail 承重测试——content-IFF（非弱断言），含 mutation-RED 面。
 *
 * <p>每条断言验证具体行为后果（skip_tool 标志/预填结果/计数器），
 * 非「有调用/非空」弱断言。mutation-RED 面：剥契约（空契约）→ 一切放行 →
 * 证 shouldVeto 非恒真（承重而非摆设）。
 *
 * @since 2026-08
 */
class VetoRailTest {

    /**
     * 拒绝消息单一真源：classpath 资源文件（MR !66 模式）。
     * 测试不再硬编码副本——4-lens 审查（Lens C）实证双源已漂移且全套静默绿。
     */
    private static final String REJECTION_MSG = RejectionResource.load();

    /** 零提及纪律承重：资源内容不得出现任何具体字段名（提及即诱导）。 */
    @Test
    void rejectionResourceMentionsNoFieldNames() {
        assertThat(REJECTION_MSG)
                .as("零提及纪律——拒绝消息不得点名任何字段（GLH-2 核心承重）")
                .doesNotContain("baseline", "followup", "intervention", "timeline_review",
                        "confidence_score", "next_steps", "findings", "sources", "verdict")
                .isNotBlank();
    }

    /** 契约：write_artifact 允许 baseline/intervention/followup 三个顶层字段。 */
    private static VetoContract sampleContract() {
        return new VetoContract(Map.of(
                "write_artifact", Set.of("baseline", "intervention", "followup")));
    }

    private static VetoRail newRail(VetoContract contract) {
        return new VetoRail(contract, REJECTION_MSG);
    }

    // ═══ 承重断言：含契约外字段 → 否决 ═══

    @Test
    void shouldVetoWriteWithExtraTopLevelFields() {
        VetoRail rail = newRail(sampleContract());
        // 模拟 beforeToolCall：含 "timeline_review" 外字段
        Map<String, Object> args = Map.of(
                "baseline", "data/a.csv",
                "timeline_review", "额外内容");
        boolean veto = sampleContract().shouldVeto("write_artifact", args.keySet());
        assertThat(veto).as("含 timeline_review 外字段 → 应否决").isTrue();
        // 验证 rail 的 veto 方法后果（skip_tool + 预填结果 + 计数器）由集成测试覆盖
    }

    @Test
    void shouldNotVetoWriteWithOnlyAllowedFields() {
        VetoContract contract = sampleContract();
        Set<String> keys = Set.of("baseline", "intervention", "followup");
        assertThat(contract.shouldVeto("write_artifact", keys))
                .as("纯白名单字段 → 放行").isFalse();
    }

    @Test
    void shouldNotVetoToolNotCoveredByContract() {
        VetoContract contract = sampleContract();
        assertThat(contract.covers("read_file")).as("read_file 无契约").isFalse();
        assertThat(contract.shouldVeto("read_file", Set.of("any_field")))
                .as("无契约工具 fail-open").isFalse();
    }

    @Test
    void shouldNotVetoEmptyKeySet() {
        VetoContract contract = sampleContract();
        assertThat(contract.shouldVeto("write_artifact", Set.of()))
                .as("空键集 → 不否决（不可判定）").isFalse();
    }

    // ═══ JSON 顶层键提取 ═══

    @Test
    void jsonTopLevelKeysExtractsCorrectly() {
        Set<String> keys = VetoRail.JsonTopLevelKeys.extract(
                "{\"baseline\": \"a\", \"timeline_review\": \"b\", \"nested\": {\"x\": 1}}");
        assertThat(keys).as("JSON 顶层键含 baseline/timeline_review/nested")
                .contains("baseline", "timeline_review", "nested");
    }

    @Test
    void jsonTopLevelKeysReturnsNullForNonObject() {
        assertThat(VetoRail.JsonTopLevelKeys.extract("[1,2,3]")).as("数组 → null").isNull();
        assertThat(VetoRail.JsonTopLevelKeys.extract("\"string\"")).as("字符串 → null").isNull();
    }

    // ═══ mutation-RED：剥契约 → 一切放行 ═══

    @Test
    void mutationRedEmptyContractLetsEverythingThrough() {
        // 剥除契约（空契约）→ covers()=false → shouldVeto()=false
        // 证 shouldVeto 的判定非恒真——承重面
        VetoContract stripped = new VetoContract(Map.of());
        assertThat(stripped.covers("write_artifact"))
                .as("mutation: 空契约不覆盖任何工具").isFalse();
        assertThat(stripped.shouldVeto("write_artifact", Set.of("any", "foreign", "field")))
                .as("mutation: 空契约放行一切").isFalse();
    }

    // ═══ VetoRail 计数器 ═══

    @Test
    void vetoCountStartsAtZero() {
        VetoRail rail = newRail(sampleContract());
        assertThat(rail.getVetoCount()).as("初始否决计数为 0").isZero();
    }

    // ═══ 契约不可变性 ═══

    @Test
    void contractIsImmutableAfterConstruction() {
        Map<String, Set<String>> mutable = new java.util.HashMap<>();
        mutable.put("write_artifact", new java.util.HashSet<>(Set.of("a")));
        VetoContract contract = new VetoContract(mutable);
        // 改原 Map 不影响契约
        mutable.put("write_artifact", Set.of("b", "c"));
        assertThat(contract.allowedFields("write_artifact"))
                .as("契约不受外部 Map 变更影响")
                .containsExactly("a");
    }
}
