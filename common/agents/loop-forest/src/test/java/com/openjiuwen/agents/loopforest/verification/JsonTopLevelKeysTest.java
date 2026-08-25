/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * JsonTopLevelKeys 承重测试——4-lens 审查（Lens A）实证的攻击路径全钉。
 *
 * <p>旧裸大括号计数实现可被单字符绕过（值内右大括号 → 越权字段放行；
 * 嵌套对象 → 扫描截断漏收；值内键名样式 → 合法写入误拦）。本测试类逐条
 * 钉住新字符串感知扫描的行为——剥扫描逻辑（mutation）→ RED。
 *
 * @since 2026-08
 */
class JsonTopLevelKeysTest {

    // ═══ 攻击路径 1：值内右大括号 → 旧实现漏收其后的越权键 ═══

    @Test
    void braceInsideStringValueDoesNotHideFollowingKeys() {
        String json = "{\"findings\": \"summary } done\", \"secret_bonus\": \"evil\"}";
        Set<String> keys = VetoRail.JsonTopLevelKeys.extract(json);
        assertThat(keys)
                .as("值内 } 不参与结构判定——secret_bonus 必须被提取（否则 VETO 被单字符绕过）")
                .containsExactlyInAnyOrder("findings", "secret_bonus");
    }

    @Test
    void bracePairInsideStringValueDoesNotHideFollowingKeys() {
        String json = "{\"findings\": \"summary { done\", \"secret_bonus\": \"evil\"}";
        assertThat(VetoRail.JsonTopLevelKeys.extract(json))
                .containsExactlyInAnyOrder("findings", "secret_bonus");
    }

    // ═══ 攻击路径 2：嵌套对象 → 旧实现扫描截断，其后顶层键漏收 ═══

    @Test
    void nestedObjectDoesNotTruncateTopLevelScan() {
        String json = "{\"findings\": {\"note\": \"x\"}, \"evil\": 1}";
        assertThat(VetoRail.JsonTopLevelKeys.extract(json))
                .as("嵌套对象内的 note 不算顶层键；其后的 evil 必须仍被提取")
                .containsExactlyInAnyOrder("findings", "evil");
    }

    // ═══ 攻击路径 3：值内键名样式文本 → 旧实现误拦合法写入 ═══

    @Test
    void keyLikeTextInsideStringValueIsNotAKey() {
        String json = "{\"findings\": \"the pattern \\\"verdict\\\": appears\", \"sources\": \"w\"}";
        assertThat(VetoRail.JsonTopLevelKeys.extract(json))
                .as("值内转义引号包裹的键名样式不是键——不得误拦")
                .containsExactlyInAnyOrder("findings", "sources");
    }

    // ═══ 正常路径 ═══

    @Test
    void plainTopLevelKeysExtracted() {
        assertThat(VetoRail.JsonTopLevelKeys.extract(
                "{\"findings\": \"f\", \"sources\": \"s\", \"verdict\": \"v\"}"))
                .containsExactlyInAnyOrder("findings", "sources", "verdict");
    }

    @Test
    void keysInsideArrayObjectsAreNotTopLevel() {
        String json = "{\"findings\": \"f\", \"items\": [{\"a\": 1}, {\"b\": 2}]}";
        assertThat(VetoRail.JsonTopLevelKeys.extract(json))
                .containsExactlyInAnyOrder("findings", "items");
    }

    @Test
    void nonObjectAndUnclosedInputsYieldNullOrEmpty() {
        assertThat(VetoRail.JsonTopLevelKeys.extract("[]")).isNull();
        assertThat(VetoRail.JsonTopLevelKeys.extract("\"string\"")).isNull();
        assertThat(VetoRail.JsonTopLevelKeys.extract("{\"unclosed\": \"str")).isNull();
    }

    // ═══ 端到端语义：提取结果驱动 shouldVeto 的越权判定 ═══

    @Test
    void extractionFeedsVetoDecisionOnAttackInput() {
        VetoContract contract = new VetoContract(
                java.util.Map.of("write_report", Set.of("findings", "sources", "verdict")));
        String attack = "{\"findings\": \"see json: {\\\"a\\\": 1}\", "
                + "\"confidence_score\": 95}";
        Set<String> keys = VetoRail.JsonTopLevelKeys.extract(attack);
        assertThat(contract.shouldVeto("write_report", keys))
                .as("值内大括号隐藏不了越权键——整体必须否决")
                .isTrue();
    }
}
