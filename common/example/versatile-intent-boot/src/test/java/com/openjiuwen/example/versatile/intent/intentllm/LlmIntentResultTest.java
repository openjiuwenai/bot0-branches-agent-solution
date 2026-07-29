package com.openjiuwen.example.versatile.intent.intentllm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmIntentResultTest {
    @Test
    void parsesClassify() {
        String json = "{\"action\":\"classify\",\"intent_id\":\"intent_L2_hotel_domestic\","
                + "\"agent_id\":\"agent_card_biz_hotel_domestic\",\"response_content\":\"国内酒店\"}";
        LlmIntentResult r = LlmIntentResult.parse(json, "1");
        assertThat(r.action()).isEqualTo(LlmIntentResult.Action.CLASSIFY);
        assertThat(r.intentId()).isEqualTo("intent_L2_hotel_domestic");
        assertThat(r.agentId()).isEqualTo("agent_card_biz_hotel_domestic");
        assertThat(r.responseContent()).isEqualTo("国内酒店");
    }

    @Test
    void parsesAmbiguous() {
        String json = "{\"action\":\"ambiguous\",\"intent_id\":\"1\","
                + "\"response_content\":\"非本领域\"}";
        LlmIntentResult r = LlmIntentResult.parse(json, "1");
        assertThat(r.action()).isEqualTo(LlmIntentResult.Action.AMBIGUOUS);
        assertThat(r.intentId()).isEqualTo("1");
    }

    @Test
    void malformedJsonFallsBackToAmbiguous() {
        LlmIntentResult r = LlmIntentResult.parse("not json", "1");
        assertThat(r.action()).isEqualTo(LlmIntentResult.Action.AMBIGUOUS);
        assertThat(r.intentId()).isEqualTo("1");
    }

    @Test
    void outOfDomainActionFallsBackToAmbiguous() {
        String json = "{\"action\":\"ask_user\",\"prompt\":\"x\"}";
        LlmIntentResult r = LlmIntentResult.parse(json, "1");
        assertThat(r.action()).isEqualTo(LlmIntentResult.Action.AMBIGUOUS);
    }

    @Test
    void classifyMissingAgentIdFallsBackToAmbiguous() {
        String json = "{\"action\":\"classify\",\"intent_id\":\"x\"}";
        LlmIntentResult r = LlmIntentResult.parse(json, "1");
        assertThat(r.action()).isEqualTo(LlmIntentResult.Action.AMBIGUOUS);
    }
}