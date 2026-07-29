package com.openjiuwen.example.versatile.intent.intentllm;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class LlmIntentPromptBuilderTest {
    @Test
    void promptContainsAvailableAgentCardsAndDomainConstraint() {
        VersatileProperties vp = new VersatileProperties();
        vp.setIntentAgentMapping(Map.of(
                "intent_L2_hotel_domestic", List.of(candidate("agent_card_biz_hotel_domestic"))));
        vp.setAmbiguousIntentId("1");
        LlmIntentProperties lp = new LlmIntentProperties();
        lp.setDomain("hotel");

        LlmIntentPromptBuilder b = new LlmIntentPromptBuilder(vp, lp);
        List<Map<String, Object>> msgs = b.build(serveRequest("订酒店"));

        assertThat(msgs).isNotEmpty();
        Map<String, Object> system = msgs.get(0);
        assertThat(system.get("role")).isEqualTo("system");
        String sys = String.valueOf(system.get("content"));
        assertThat(sys).contains("agent_card_biz_hotel_domestic");
        assertThat(sys).contains("hotel");
        assertThat(sys).contains("\"action\":\"classify\"");
        assertThat(sys).contains("\"intent_id\":\"1\"");
    }

    @Test
    void promptIncludesUserHistory() {
        VersatileProperties vp = new VersatileProperties();
        vp.setAmbiguousIntentId("1");
        LlmIntentProperties lp = new LlmIntentProperties();
        LlmIntentPromptBuilder b = new LlmIntentPromptBuilder(vp, lp);

        List<Map<String, Object>> msgs = b.build(serveRequest("500元"));

        // system + at least one user message carrying the query
        assertThat(msgs.size()).isGreaterThanOrEqualTo(2);
        Map<String, Object> last = msgs.get(msgs.size() - 1);
        assertThat(last.get("role")).isEqualTo("user");
        assertThat(String.valueOf(last.get("content"))).contains("500元");
    }

    private static VersatileProperties.MappingCandidate candidate(String card) {
        VersatileProperties.MappingCandidate c = new VersatileProperties.MappingCandidate();
        c.setAgentCard(card);
        return c;
    }

    private static ServeRequest serveRequest(String text) {
        ServeRequest r = new ServeRequest();
        r.setConversationId("c-test");
        r.setStream(false);
        r.setMessages(List.of(Map.of("role", "user", "content", text)));
        return r;
    }
}
