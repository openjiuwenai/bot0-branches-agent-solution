/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.handoff;

import com.openjiuwen.service.adapters.versatile.agentfw.IntentAgentResolver;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileResponseExtractor;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffClassification;
import com.openjiuwen.service.adapters.versatile.controller.handoff.IntentHandoffClassifier;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sanity check: the mock controller's SSE lines must be recognized by the
 * FEAT-002 baseline extractor (answer + terminal End marker) and by the
 * intent-handoff classifier (production {@code message} wire format).
 *
 * @since 0.1.0
 */
class MockWireFormatExtractionTest {

    @Test
    void answerAndEndLinesProduceLegacyAnswer() {
        VersatileProperties properties = new VersatileProperties();
        properties.setResultNodeName("AnswerNode");
        VersatileResponseExtractor extractor =
                new VersatileResponseExtractor(properties, new IntentAgentResolver(properties));
        extractor.consumeLine("data: {\"event\":\"node_finished\",\"data\":{"
                + "\"node_name\":\"AnswerNode\",\"node_type\":\"QA\","
                + "\"outputs\":{\"response\":\"一级本地业务答案\"},\"text\":\"一级本地业务答案\"}}");
        extractor.consumeLine("data: {\"event\":\"workflow_finished\",\"data\":{"
                + "\"node_id\":\"node_end\",\"node_type\":\"End\",\"node_status\":\"node_finished\"}}");
        List<QueryChunk> finish = extractor.finish();
        assertThat(finish).hasSize(1);
        assertThat(String.valueOf(finish.get(0).getData())).contains("一级本地业务答案");
    }

    @Test
    void exceptionLineFails() {
        VersatileProperties properties = new VersatileProperties();
        VersatileResponseExtractor extractor =
                new VersatileResponseExtractor(properties, new IntentAgentResolver(properties));
        extractor.consumeLine("data: {\"event\":\"exception\",\"data\":{\"code\":500}}");
        List<QueryChunk> finish = extractor.finish();
        assertThat(finish).hasSize(1);
        assertThat(finish.get(0).getType()).isEqualTo(QueryChunk.TYPE_ERROR);
    }

    /** 生产 message 报文：转调分类命中，intent 取 data.summary，dedup-key 取顶层 createdTime。 */
    @Test
    void messageFormatHandoffLinesAreClassified() {
        ControllerHandoffProperties handoff = new ControllerHandoffProperties();
        ControllerHandoffProperties.Classify classify = new ControllerHandoffProperties.Classify();
        classify.setEventType("message");
        classify.setFieldPath("/data/node_name");
        classify.setFieldValue(List.of("意图返回", "不在范围"));
        handoff.setClassify(classify);
        handoff.getFields().setHandoffType("/data/node_name");
        handoff.getFields().setIntentId("/data/summary");
        handoff.getFields().setDedupKey("/createdTime");
        IntentHandoffClassifier classifer = new IntentHandoffClassifier(handoff);

        HandoffClassification intent =
                classifer.classify("data: {\"event\":\"message\",\"data\":{\"text\":\"\",\"summary\":\"3\","
                        + "\"node_id\":\"node_1787129452975\",\"node_type\":\"QA\",\"node_name\":\"意图返回\","
                        + "\"is_finished\":true,\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9317\","
                        + "\"workflow_name\":\"eqijimorengongzuoliu_fenbushiyanzheng\"},"
                        + "\"createdTime\":1787140547059}");
        assertThat(intent.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(intent.handoff().intentId()).isEqualTo("3");
        assertThat(intent.handoff().handoffType()).isEqualTo("意图返回");
        assertThat(intent.handoff().dedupKey()).isEqualTo("1787140547059");

        HandoffClassification notInScope =
                classifer.classify("data: {\"event\":\"message\",\"data\":{\"text\":\"\",\"summary\":\"不在范围\","
                        + "\"node_id\":\"node_1787129452975\",\"node_type\":\"QA\",\"node_name\":\"不在范围\","
                        + "\"is_finished\":true,\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9317\","
                        + "\"workflow_name\":\"eqijimorengongzuoliu_fenbushiyanzheng\"},"
                        + "\"createdTime\":1787140547060}");
        assertThat(notInScope.outcome()).isEqualTo(HandoffClassification.Outcome.HANDOFF);
        assertThat(notInScope.handoff().intentId()).isEqualTo("不在范围");

        // 生产意图回显帧（text 带值、summary 键缺失）：识别命中但三个解析来源全无
        // 非空值 → IGNORED 整行抑制（不处理、不透传、不报错；2026-08-20 三选一契约）
        // 注意勿将 business-domain/target-agent-id 配置为 node_name 等恒有值的路径——
        // 新契约下非空即算可用来源，会让回显帧误判 HANDOFF
        HandoffClassification echo =
                classifer.classify("data: {\"event\":\"message\",\"data\":{\"text\":\"3\","
                        + "\"node_id\":\"node_1787129452975\",\"node_type\":\"QA\",\"node_name\":\"意图返回\","
                        + "\"is_finished\":true,\"workflow_id\":\"81476c36-28e6-4ec1-84c5-247be51a9317\","
                        + "\"workflow_name\":\"eqijimorengongzuoliu_fenbushiyanzheng\"},"
                        + "\"createdTime\":1787140547058}");
        assertThat(echo.outcome()).isEqualTo(HandoffClassification.Outcome.IGNORED);
        assertThat(echo.handoff()).isNull();

        // 业务答案（node_finished）不满足 event-type=message，不误判为转调
        HandoffClassification answer = classifer.classify(
                "data: {\"event\":\"node_finished\",\"data\":{\"node_name\":\"AnswerNode\",\"node_type\":\"QA\","
                        + "\"outputs\":{\"response\":\"一级本地业务答案\"},\"text\":\"一级本地业务答案\"}}");
        assertThat(answer.outcome()).isEqualTo(HandoffClassification.Outcome.NOT_HANDOFF);
    }
}
