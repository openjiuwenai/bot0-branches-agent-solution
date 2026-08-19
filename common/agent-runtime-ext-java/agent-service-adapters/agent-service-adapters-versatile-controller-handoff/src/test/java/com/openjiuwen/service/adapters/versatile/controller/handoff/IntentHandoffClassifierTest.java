/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
    void contractViolationWhenConfiguredFieldMissing() {
        HandoffClassification result = new IntentHandoffClassifier(properties()).classify(
                "{\"data\":{\"code\":14000}}");
        // handoff-type/intent-id/business-domain/target-agent-id 路径已配置但报文缺失
        assertThat(result.outcome()).isEqualTo(HandoffClassification.Outcome.CONTRACT_VIOLATION);
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
