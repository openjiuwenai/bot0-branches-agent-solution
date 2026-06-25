package com.openjiuwen.service.adapters.versatile.agentfw;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersatileResponseExtractorTest {

    @Test
    void emitsInputRequiredWhenStreamEndsBeforeEndSignal() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

        List<VersatileResponseExtractor.Event> events = extractor.consumeLine("data: {\"event\":\"message\"}");
        events.addAll(extractor.finish());

        assertThat(events).extracting(VersatileResponseExtractor.Event::type)
                .containsExactly(
                        VersatileResponseExtractor.EventType.PASSTHROUGH,
                        VersatileResponseExtractor.EventType.INPUT_REQUIRED);
        assertThat(events.get(0).data()).isEqualTo("{\"event\":\"message\"}");
    }

    @Test
    void extractsResultNodeAndEmitsCompletedOnEnd() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\",\"node_name\":\"AnswerNode\",\"text\":\"final\"}}"))
                .isEmpty();
        List<VersatileResponseExtractor.Event> events =
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}");
        events.addAll(extractor.finish());

        assertThat(events).extracting(VersatileResponseExtractor.Event::type)
                .containsExactly(
                        VersatileResponseExtractor.EventType.PASSTHROUGH,
                        VersatileResponseExtractor.EventType.COMPLETED);
        String result = extractor.result();
        assertThat(result).isEqualTo("final");
    }

    @Test
    void emitsInputRequiredWhenResultNodeArrivesWithoutEndSignal() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

        assertThat(extractor.consumeLine("data: {\"data\":{\"node_type\":\"QA\",\"node_name\":\"AnswerNode\",\"text\":\"final\"}}"))
                .isEmpty();
        List<VersatileResponseExtractor.Event> events = extractor.finish();

        assertThat(events).extracting(VersatileResponseExtractor.Event::type)
                .containsExactly(VersatileResponseExtractor.EventType.INPUT_REQUIRED);
        assertThat(extractor.result()).isEqualTo("final");
    }

    @Test
    void extractsResultFromCustomResponseData() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

        assertThat(extractor.consumeLine("data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":{\"node_type\":\"QA\",\"text\":\"custom final\"}}}"))
                .isEmpty();
        List<VersatileResponseExtractor.Event> events =
                extractor.consumeLine("data: {\"data\":{\"node_type\":\"End\"}}");
        events.addAll(extractor.finish());

        assertThat(events).extracting(VersatileResponseExtractor.Event::type)
                .containsExactly(
                        VersatileResponseExtractor.EventType.PASSTHROUGH,
                        VersatileResponseExtractor.EventType.COMPLETED);
        assertThat(extractor.result()).isEqualTo("custom final");
    }

    @Test
    void marksExceptionAsFailed() {
        VersatileResponseExtractor extractor = new VersatileResponseExtractor("AnswerNode");

        List<VersatileResponseExtractor.Event> events =
                extractor.consumeLine("data: {\"event\":\"exception\",\"data\":{\"message\":\"boom\"}}");
        events.addAll(extractor.finish());

        assertThat(events).extracting(VersatileResponseExtractor.Event::type)
                .containsExactly(
                        VersatileResponseExtractor.EventType.PASSTHROUGH,
                        VersatileResponseExtractor.EventType.FAILED);
        assertThat(extractor.error()).contains("exception");
    }
}
