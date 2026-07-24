/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Versatile streaming response extraction rules.
 *
 * @since 2026-06-30
 */
class VersatileResponseExtractorTest {
    private static VersatileProperties props(String resultNodeName) {
        VersatileProperties p = new VersatileProperties();
        p.setResultNodeName(resultNodeName);
        return p;
    }

    private static IntentAgentResolver resolver(VersatileProperties props) {
        return new IntentAgentResolver(props);
    }

    @Test
    void emitsErrorWhenStreamEndsBeforeEndSignal() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props("AnswerNode"), resolver(props("AnswerNode")));

        List<QueryChunk> chunks = new ArrayList<>(extractor.consumeLine("data: {\"event\":\"message\"}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_ERROR);
        assertThat(chunks.get(0).getData()).isEqualTo("{\"event\":\"message\"}");
        assertThat(String.valueOf(chunks.get(1).getData()))
                .contains("\"code\":\"VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL\"")
                .contains("\"reason\":\"no End/exception event\"");
    }

    @Tag("smoke")
    @Test
    void extractsResultNodeAndEmitsAnswerOnEnd() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\","
                + "\"node_name\":\"AnswerNode\",\"text\":\"final\"}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_CHUNK);
        assertAnswerEnvelope(chunks.get(1), "final");
    }

    @Test
    void emitsErrorWhenResultNodeArrivesWithoutEndSignal() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props("AnswerNode"), resolver(props("AnswerNode")));

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\","
                + "\"node_name\":\"AnswerNode\",\"text\":\"final\"}}"))
                .isEmpty();
        List<QueryChunk> chunks = extractor.finish();

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(0).getData()))
                .contains("\"code\":\"VERSATILE_STREAM_CLOSED_WITHOUT_TERMINAL\"")
                .contains("\"reason\":\"no End/exception event\"");
    }

    @Test
    void extractsResultFromCustomResponseData() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"text\":\"custom final\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_CHUNK);
        assertAnswerEnvelope(chunks.get(1), "custom final");
    }

    @Test
    void marksExceptionAsFailed() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"event\":\"exception\",\"data\":{\"message\":\"boom\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK, QueryChunk.TYPE_ERROR);
        assertThat(chunks.get(1).getData()).asString().contains("exception");
    }

    @Test
    void emitsNoFinalChunkWhenCompletedWithoutResult() {
        VersatileProperties props = props("AnswerNode");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\","
                + "\"node_name\":\"AnswerNode\",\"text\":\"\",\"summary\":\"final\"}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_CHUNK);
        assertThat(chunks.get(0).getData()).asString().contains("\"node_type\":\"End\"");
    }

    @Test
    void extractsThreeFieldResultWhenConfigured() {
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(props, "agent_id", "/custom_rsp_data/data/agent_id");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"response_content\":\"酒店预订\","
                + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_card_L2_hotel\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_INTERRUPT);
        QueryChunk delegate = chunks.stream()
                .filter(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType()))
                .findFirst().orElseThrow();
        Map<?, ?> payload = (Map<?, ?>) delegate.getData();
        assertThat(payload.get("agentName")).isEqualTo("agent_card_L2_hotel");
        assertThat(payload.get("responseContent")).isEqualTo("酒店预订");
        Map<?, ?> context = (Map<?, ?>) payload.get("context");
        assertThat(context.get("_interrupt_kind")).isEqualTo("a2a_delegate");
    }

    @Test
    void emitsA2aDelegateWithEmptyResponseContentWhenWorkflowOmitsResponseContent() {
        // Reference scenario from production: intent node returns only intent_id + agent_id.
        // response_content is now optional — the a2a_delegate interrupt carries an empty
        // responseContent so the Caller skips appending an assistant message.
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(props, "agent_id", "/custom_rsp_data/data/agent_id");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\","
                + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":\"agent_card_L2_hotel\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_INTERRUPT);
        QueryChunk delegate = chunks.stream()
                .filter(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType()))
                .findFirst().orElseThrow();
        Map<?, ?> payload = (Map<?, ?>) delegate.getData();
        assertThat(payload.get("agentName")).isEqualTo("agent_card_L2_hotel");
        assertThat(payload.get("responseContent")).isEqualTo("");
    }

    @Test
    void emitsAgentIdUnmappedErrorWhenResponseContentMissingAndNoMapping() {
        // response_content missing AND agent_id missing AND no intent-agent-mapping
        // → resolver still throws UNMAPPED (response_content being optional doesn't
        // rescue an unresolvable agent_id).
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"intent_id\":\"intent_L1_hotel\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(chunks.size() - 1).getData()))
                .contains("VERSATILE_INTENT_AGENT_ID_UNMAPPED");
    }

    @Test
    void emitsInterruptWhenNativeSignalHasCompleteInfo() {
        VersatileProperties props = props("AnswerNode");
        props.getInterrupt().setSignalMatch("need_user_input");
        props.getInterrupt().setPromptGet("/data/question");
        props.getInterrupt().setInputRequirementGet("/data/input_schema");
        props.getInterrupt().setResumeTokenGet("/data/resume_token");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        List<QueryChunk> chunks = new ArrayList<>(extractor.consumeLine(
                "data: {\"event\":\"need_user_input\",\"data\":{"
                        + "\"question\":\"请问入住日期？\","
                        + "\"input_schema\":\"date\","
                        + "\"resume_token\":\"tok-1\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_INTERRUPT);
        Map<String, Object> payload = (Map<String, Object>) chunks.get(0).getData();
        assertThat(payload).containsEntry("message", "请问入住日期？")
                .containsEntry("input_requirement", "date")
                .containsEntry("resume_token", "tok-1");
    }

    @Test
    void emitsErrorWhenNativeSignalMissingFields() {
        VersatileProperties props = props("AnswerNode");
        props.getInterrupt().setSignalMatch("need_user_input");
        props.getInterrupt().setPromptGet("/data/question");
        props.getInterrupt().setInputRequirementGet("/data/input_schema");
        props.getInterrupt().setResumeTokenGet("/data/resume_token");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        List<QueryChunk> chunks = new ArrayList<>(extractor.consumeLine(
                "data: {\"event\":\"need_user_input\",\"data\":{\"question\":\"q\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(chunks.size() - 1).getData()))
                .contains("VERSATILE_INTENT_INTERRUPT_INCOMPLETE");
    }

    @Test
    void emitsErrorWhenNativeSignalSeenButAllFieldsMissing() {
        VersatileProperties props = props("AnswerNode");
        props.getInterrupt().setSignalMatch("need_user_input");
        props.getInterrupt().setPromptGet("/data/question");
        props.getInterrupt().setInputRequirementGet("/data/input_schema");
        props.getInterrupt().setResumeTokenGet("/data/resume_token");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        List<QueryChunk> chunks = new ArrayList<>(extractor.consumeLine(
                "data: {\"event\":\"need_user_input\",\"data\":{}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(chunks.size() - 1).getData()))
                .contains("VERSATILE_INTENT_INTERRUPT_INCOMPLETE");
    }

    @Test
    void lastInterruptLineWinsWhenMultipleSeen() {
        VersatileProperties props = props("AnswerNode");
        props.getInterrupt().setSignalMatch("need_user_input");
        props.getInterrupt().setPromptGet("/data/question");
        props.getInterrupt().setInputRequirementGet("/data/input_schema");
        props.getInterrupt().setResumeTokenGet("/data/resume_token");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        List<QueryChunk> chunks = new ArrayList<>(extractor.consumeLine(
                "data: {\"event\":\"need_user_input\",\"data\":{"
                        + "\"question\":\"第一问\",\"input_schema\":\"text\",\"resume_token\":\"tok-1\"}}"));
        chunks.addAll(extractor.consumeLine(
                "data: {\"event\":\"need_user_input\",\"data\":{"
                        + "\"question\":\"第二问\",\"input_schema\":\"date\",\"resume_token\":\"tok-2\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .containsExactly(QueryChunk.TYPE_INTERRUPT);
        Map<String, Object> payload = (Map<String, Object>) chunks.get(0).getData();
        assertThat(payload).containsEntry("message", "第二问")
                .containsEntry("input_requirement", "date")
                .containsEntry("resume_token", "tok-2");
    }

    @Test
    void emitsAgentIdUnmappedErrorWhenIntentNotInMapping() {
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        // No agent_id extraction, no intent-agent-mapping → resolver throws UNMAPPED
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"response_content\":\"酒店预订\","
                + "\"intent_id\":\"intent_unmapped\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = new ArrayList<>(
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}"));
        chunks.addAll(extractor.finish());

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(chunks.size() - 1).getData()))
                .contains("VERSATILE_INTENT_AGENT_ID_UNMAPPED")
                .contains("intent_unmapped");
    }

    private static void addExtraction(VersatileProperties props, String match, String get) {
        VersatileProperties.ResultExtraction extraction = new VersatileProperties.ResultExtraction();
        extraction.setMatch(match);
        extraction.setGet(get);
        props.getResultExtractions().add(extraction);
    }

    @Test
    void emitsAgentIdNotUniqueErrorWhenAgentIdIsArray() {
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(props, "agent_id", "/custom_rsp_data/data/agent_id");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"response_content\":\"酒店预订\","
                + "\"intent_id\":\"intent_L1_hotel\",\"agent_id\":[\"agent_a\",\"agent_b\"]}}}"))
                .isEmpty();
        List<QueryChunk> chunks = extractor.finish();

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(chunks.size() - 1).getData()))
                .contains("VERSATILE_INTENT_AGENT_ID_NOT_UNIQUE");
    }

    @Test
    void emitsResultTypeErrorWhenIntentIdIsNonString() {
        VersatileProperties props = props("AnswerNode");
        addExtraction(props, "response_content", "/custom_rsp_data/data/response_content");
        addExtraction(props, "intent_id", "/custom_rsp_data/data/intent_id");
        addExtraction(props, "agent_id", "/custom_rsp_data/data/agent_id");
        VersatileResponseExtractor extractor = new VersatileResponseExtractor(props, resolver(props));

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\","
                + "\"data\":{\"node_type\":\"QA\",\"response_content\":\"酒店预订\","
                + "\"intent_id\":42,\"agent_id\":\"agent_card_L2_hotel\"}}}"))
                .isEmpty();
        List<QueryChunk> chunks = extractor.finish();

        assertThat(chunks).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_ERROR);
        assertThat(String.valueOf(chunks.get(chunks.size() - 1).getData()))
                .contains("VERSATILE_INTENT_RESULT_TYPE")
                .contains("intent_id");
    }

    private static void assertAnswerEnvelope(QueryChunk chunk, String expectedOutput) {
        assertThat(chunk.getData()).isInstanceOf(Map.class);
        Map<?, ?> envelope = (Map<?, ?>) chunk.getData();
        assertThat(envelope.get("type")).isEqualTo("answer");
        assertThat(envelope.get("output")).isEqualTo(expectedOutput);
        assertThat(envelope.containsKey("payload")).isFalse();
    }
}
