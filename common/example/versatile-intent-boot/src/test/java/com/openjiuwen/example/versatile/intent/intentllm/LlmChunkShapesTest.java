package com.openjiuwen.example.versatile.intent.intentllm;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.QueryChunk;
import org.junit.jupiter.api.Test;

import java.util.Map;

class LlmChunkShapesTest {
    @SuppressWarnings("unchecked")
    @Test
    void delegateInterruptShape() {
        QueryChunk c = LlmChunkShapes.delegateInterrupt(
                "agent_card_L2_hotel", "国内酒店", "订酒店", true);
        assertThat(c.getType()).isEqualTo(QueryChunk.TYPE_INTERRUPT);
        Map<String, Object> p = (Map<String, Object>) c.getData();
        assertThat(p.get("type")).isEqualTo("__interaction__");
        assertThat(p.get("agentName")).isEqualTo("agent_card_L2_hotel");
        assertThat(p.get("responseContent")).isEqualTo("国内酒店");
        assertThat(p.get("resume")).isEqualTo(false);
        assertThat(p.get("message")).isEqualTo("订酒店");
        assertThat(p.get("_stream_mode")).isEqualTo("sse");
        Map<String, Object> ctx = (Map<String, Object>) p.get("context");
        assertThat(ctx.get("_interrupt_kind")).isEqualTo("a2a_delegate");
        assertThat(ctx.get("agentName")).isEqualTo("agent_card_L2_hotel");
        assertThat(p.get("toolCallId")).asString().startsWith("llm-delegate-");
    }

    @Test
    void delegateResultCarriesInterrupt() {
        Map<String, Object> r = LlmChunkShapes.delegateResult(
                "agent_card_L2_hotel", "国内酒店", "订酒店", false);
        assertThat(r.get("role")).isEqualTo("assistant");
        assertThat(r.get("content")).isEqualTo("国内酒店");
        assertThat(r.get("_interrupt")).isNotNull();
        assertThat(((Map<?, ?>) r.get("_interrupt")).get("agentName"))
                .isEqualTo("agent_card_L2_hotel");
    }

    @Test
    void ambiguousChunkShape() {
        QueryChunk c = LlmChunkShapes.ambiguousChunk("非本领域", "1");
        assertThat(c.getType()).isEqualTo(QueryChunk.TYPE_CHUNK);
        Map<String, Object> p = (Map<String, Object>) c.getData();
        assertThat(p.get("type")).isEqualTo("answer");
        assertThat(p.get("response_content")).isEqualTo("非本领域");
        assertThat(p.get("intent_id")).isEqualTo("1");
    }

    @Test
    void ambiguousResultShape() {
        Map<String, Object> r = LlmChunkShapes.ambiguousResult("非本领域", "1");
        assertThat(r.get("role")).isEqualTo("assistant");
        assertThat(r.get("content")).isEqualTo("非本领域");
        assertThat(r.get("intent_id")).isEqualTo("1");
        assertThat(r.get("_interrupt")).isNull();
    }
}