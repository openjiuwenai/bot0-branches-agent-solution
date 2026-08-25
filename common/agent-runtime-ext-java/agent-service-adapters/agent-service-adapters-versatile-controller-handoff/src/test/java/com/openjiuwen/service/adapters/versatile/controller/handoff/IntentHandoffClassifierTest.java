/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * IntentHandoffClassifier 验收：结构化识别条件命中、事件类型门控、
 * resolution-priority 可用来源判定与 signal 类型旁路（spec 2.2/3.4/3.5）。
 *
 * @since 2026-08-19
 */
class IntentHandoffClassifierTest {
    private ControllerHandoffProperties properties() {
        ControllerHandoffProperties p = new ControllerHandoffProperties();
        ControllerHandoffProperties.Classify c = new ControllerHandoffProperties.Classify();
        c.setFieldPath("/data/code");
        c.setFieldValue(List.of("14000", "14001"));
        p.setClassify(c);
        ControllerHandoffProperties.Fields f = p.getFields();
        f.setHandoffType("/data/handoff_type");
        f.setIntentId("/data/intent_id");
        f.setBusinessDomain("/data/domain");
        f.setTargetAgentId("/data/target_agent/id");
        f.setDedupKey("/data/dedup_key");
        return p;
    }

    @Test
    void classifiesHandoffWhenFieldValueHits() {
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"intent_hotel\","
                        + "\"domain\":\"hotel\",\"target_agent\":{\"id\":\"agent_card_layer2_hotel\"},"
                        + "\"dedup_key\":\"k1\"}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        IntentHandoff h = result.handoff();
        assertThat(h.handoffType()).isEqualTo("L1_TO_L2");
        assertThat(h.intentId()).isEqualTo("intent_hotel");
        assertThat(h.businessDomain()).isEqualTo("hotel");
        assertThat(h.targetAgentId()).isEqualTo("agent_card_layer2_hotel");
        assertThat(h.dedupKey()).isEqualTo("k1");
    }

    @Test
    void numericFieldPathMatchesTextualForm() {
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14001,\"handoff_type\":\"T\",\"intent_id\":\"i\","
                        + "\"domain\":\"d\",\"target_agent\":{\"id\":\"a\"}}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(result.handoff().handoffType()).isEqualTo("T");
    }

    @Test
    void notHandoffWhenFieldValueDiffers() {
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":-1,\"reason\":\"workflow exception\"}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.NOT_HANDOFF);
        assertThat(result.handoff()).isNull();
    }

    @Test
    void notHandoffWhenPathMissing() {
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"event\":\"exception\",\"message\":\"controller error\"}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.NOT_HANDOFF);
    }

    @Test
    void exceptionTextNeverClassifiedAsHandoff() {
        // 识别必须基于结构化字段命中，不得依赖文本关键字（spec 3.5）
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "data: {\"event\":\"exception\",\"data\":{\"text\":\"请转调到酒店智能体处理\"}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.NOT_HANDOFF);
    }

    @Test
    void eventTypeMismatchBlocksClassification() {
        ControllerHandoffProperties p = properties();
        p.getClassify().setEventType("controller_message");
        HandoffClassification result = new IntentHandoffClassifier(p).classify(
                "{\"event\":\"other_event\",\"data\":{\"code\":14000}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.NOT_HANDOFF);
    }

    @Test
    void eventTypeJsonFieldSatisfiesMatch() {
        ControllerHandoffProperties p = properties();
        p.getClassify().setEventType("controller_message");
        HandoffClassification result = new IntentHandoffClassifier(p).classify(
                "{\"event\":\"controller_message\",\"data\":{\"code\":14000,\"handoff_type\":\"T\","
                        + "\"intent_id\":\"i\",\"domain\":\"d\",\"target_agent\":{\"id\":\"a\"}}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
    }

    @Test
    void missingRequiredFieldsIgnoredAsNotHandoff() {
        // 生产 SSE 混入信号字段不全的帧（如意图回显：text 带值、summary 键缺失）：
        // 识别命中但提取路径缺失 → IGNORED 整行抑制（WARN 可观测），
        // 不处理、不透传基线、不产出 MESSAGE_CONTRACT（spec 2.2，2026-08-19 确认）
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.IGNORED);
        assertThat(result.handoff()).isNull();
    }

    @Test
    void blankRequiredFieldStillPresent() {
        // 空串视为字段在场：非本次解析来源的字段合法为空（如 direct 目标为空走 intent 映射）
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"i\","
                        + "\"domain\":\"\",\"target_agent\":{\"id\":\"\"}}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
    }

    @Test
    void handoffWhenSingleResolutionSourcePresent() {
        // 生产报文只携带本次解析用到的字段（如仅 summary 意图值，无 domain/target 键）：
        // 三个解析来源（intent/domain/direct）任一非空即 HANDOFF（2026-08-20 确认，
        // 对齐 HandoffTargetResolver 的 resolution-priority 语义）
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"intent_hotel\"}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(result.handoff().businessDomain()).isNull();
        assertThat(result.handoff().targetAgentId()).isNull();
    }

    @Test
    void sourcesOutsideResolutionPriorityDoNotQualifyEchoFrame() {
        // resolution-priority=[intent] 时，intent 为空但 domain/target 有值（部署把
        // 这两个提取路径配在与 classify 同路径的 /data/node_name，回显帧即有值）
        // 不算可用来源：回显帧 IGNORED，避免其提前命中抑制掉后续带 summary 的完整帧
        // （2026-08-20 生产 TARGET_MISSING：intent-null domain=意图返回 directTarget=意图返回）
        ControllerHandoffProperties p = properties();
        p.getTarget().setResolutionPriority(List.of("intent"));
        IntentHandoffClassifier classifier = new IntentHandoffClassifier(p);
        HandoffClassification echo = classifier.classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"\","
                        + "\"domain\":\"意图返回\",\"target_agent\":{\"id\":\"意图返回\"}}}");
        assertThat(echo.outcome()).isEqualTo(HandoffClassification.Outcome.IGNORED);
        HandoffClassification complete = classifier.classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"3\","
                        + "\"domain\":\"意图返回\",\"target_agent\":{\"id\":\"意图返回\"}}}");
        assertThat(complete.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(complete.handoff().intentId()).isEqualTo("3");
    }

    @Test
    void missingHandoffTypeNoLongerBlocksHandoff() {
        // handoff-type 改为可选：路径缺失置 null，signal.handoff-types 匹配自然不命中
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000,\"intent_id\":\"i\",\"domain\":\"hotel\"}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(result.handoff().handoffType()).isNull();
    }

    @Test
    void allResolutionSourcesBlankOrMissingIgnored() {
        // 识别命中但三个解析来源全空（键缺失或空串）：无可解析目标，整行 IGNORED
        // （WARN 可观测）——含默认回复等 QA 帧被宽松识别条件误命中的场景
        HandoffClassification allBlank = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"T\",\"intent_id\":\"\","
                        + "\"domain\":\"\",\"target_agent\":{\"id\":\"\"}}}");
        assertThat(allBlank.outcome()).isEqualTo(HandoffClassification.Outcome.IGNORED);

        HandoffClassification blankAndMissing = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000,\"intent_id\":\"\"}}");
        assertThat(blankAndMissing.outcome()).isEqualTo(HandoffClassification.Outcome.IGNORED);
    }

    @Test
    void signalHandoffTypeBypassesResolutionSourceGate() {
        // signal.handoff-types 命中的类型（如 L2 退回一级）不出站、无需解析目标：
        // 三个来源全空仍判 HANDOFF（upstream-signal 语义，spec 3.4）
        ControllerHandoffProperties p = properties();
        p.getSignal().setHandoffTypes(List.of("L2_TO_L1"));
        HandoffClassification result = new IntentHandoffClassifier(p).classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L2_TO_L1\",\"intent_id\":\"\","
                        + "\"domain\":\"\",\"target_agent\":{\"id\":\"\"}}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(result.handoff().handoffType()).isEqualTo("L2_TO_L1");
    }

    @Test
    void missingDedupKeyIsNotContractViolation() {
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"i\","
                        + "\"domain\":\"hotel\",\"target_agent\":{\"id\":\"a1\"}}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(result.handoff().dedupKey()).isNull();
    }

    @Test
    void sseDataPrefixAndBlankLinesHandled() {
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "data: {\"data\":{\"code\":14000,\"handoff_type\":\"T\",\"intent_id\":\"i\","
                        + "\"domain\":\"d\",\"target_agent\":{\"id\":\"a\"}}}");
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(new IntentHandoffClassifier(properties()).classify(null).outcome())
                .isEqualTo(HandoffClassification.Outcome.NOT_HANDOFF);
        assertThat(new IntentHandoffClassifier(properties()).classify("").outcome())
                .isEqualTo(HandoffClassification.Outcome.NOT_HANDOFF);
    }
}
