package com.openjiuwen.example.versatile.intent.intentllm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class LlmIntentAgentHandlerTest {
    private LlmIntentClient client;
    private LlmIntentPromptBuilder promptBuilder;
    private LlmIntentProperties props;
    private VersatileProperties versatile;

    @BeforeEach
    void setUp() {
        client = mock(LlmIntentClient.class);
        promptBuilder = mock(LlmIntentPromptBuilder.class);
        when(promptBuilder.buildConversation(anyList()))
                .thenReturn(List.of(Map.of("role", "user", "content", "x")));
        props = new LlmIntentProperties();
        versatile = new VersatileProperties();
        versatile.setAmbiguousIntentId("1");
    }

    private LlmIntentAgentHandler handler() {
        return new LlmIntentAgentHandler(props, versatile, client, promptBuilder);
    }

    @Test
    void queryClassifyProducesDelegateInterrupt() {
        when(client.complete(any())).thenReturn(
                "{\"action\":\"classify\",\"intent_id\":\"intent_L2_hotel_domestic\","
                        + "\"agent_id\":\"agent_card_biz_hotel_domestic\","
                        + "\"response_content\":\"国内酒店\"}");
        QueryResponse r = handler().query(serveRequest("订酒店"));
        Map<?, ?> result = (Map<?, ?>) r.getResult();
        assertThat(result.get("_interrupt")).isNotNull();
        assertThat(((Map<?, ?>) result.get("_interrupt")).get("agentName"))
                .isEqualTo("agent_card_biz_hotel_domestic");
    }

    @Test
    void queryAmbiguousProducesIntentId() {
        when(client.complete(any())).thenReturn(
                "{\"action\":\"ambiguous\",\"intent_id\":\"1\",\"response_content\":\"非酒店\"}");
        QueryResponse r = handler().query(serveRequest("买机票"));
        Map<?, ?> result = (Map<?, ?>) r.getResult();
        assertThat(result.get("intent_id")).isEqualTo("1");
        assertThat(result.get("_interrupt")).isNull();
    }

    @Test
    void streamQueryEmitsDelegateChunk() {
        when(client.complete(any())).thenReturn(
                "{\"action\":\"classify\",\"intent_id\":\"x\","
                        + "\"agent_id\":\"agent_card_biz_hotel_domestic\","
                        + "\"response_content\":\"国内酒店\"}");
        List<QueryChunk> emitted = new ArrayList<>();
        QueryStreamObserver obs = new QueryStreamObserver() {
            @Override public void onNext(QueryChunk chunk) { emitted.add(chunk); }
            @Override public void onError(Throwable t) { }
            @Override public void onComplete() { }
            @Override public boolean isCancelled() { return false; }
        };
        handler().streamQuery(serveRequest("订酒店"), obs);
        assertThat(emitted).anyMatch(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType()));
    }

    private static ServeRequest serveRequest(String text) {
        ServeRequest r = new ServeRequest();
        r.setConversationId("c-test");
        r.setStream(false);
        r.setMessages(List.of(Map.of("role", "user", "content", text)));
        return r;
    }

    @Test
    void maintainsConversationHistoryAcrossTurns() {
        // Use a real prompt builder so the messages passed to the client reflect
        // accumulated history (system + N user turns) as freshly built lists.
        LlmIntentPromptBuilder realBuilder = new LlmIntentPromptBuilder(versatile, props);
        LlmIntentAgentHandler h = new LlmIntentAgentHandler(props, versatile, client, realBuilder);
        when(client.complete(any())).thenReturn(
                "{\"action\":\"classify\",\"intent_id\":\"x\","
                        + "\"agent_id\":\"agent_card_biz_hotel_domestic\","
                        + "\"response_content\":\"国内酒店\"}");
        h.query(serveRequest("订酒店"));
        h.query(serveRequest("500元"));

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).complete(captor.capture());
        List<List<Map<String, Object>>> calls = captor.getAllValues();
        // system + 1 turn, then system + 2 turns (history accumulated).
        assertThat(calls.get(0)).hasSize(2);
        assertThat(calls.get(1)).hasSize(3);
        assertThat(String.valueOf(calls.get(1).get(2).get("content"))).contains("500元");
    }

    @Test
    void clearSessionDropsConversationHistory() {
        LlmIntentPromptBuilder realBuilder = new LlmIntentPromptBuilder(versatile, props);
        LlmIntentAgentHandler h = new LlmIntentAgentHandler(props, versatile, client, realBuilder);
        when(client.complete(any())).thenReturn(
                "{\"action\":\"classify\",\"intent_id\":\"x\","
                        + "\"agent_id\":\"agent_card_biz_hotel_domestic\","
                        + "\"response_content\":\"国内酒店\"}");
        h.query(serveRequest("订酒店"));
        h.clearSession("c-test");
        h.query(serveRequest("500元"));

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, times(2)).complete(captor.capture());
        // After clearSession the history is reset: the second turn is classified alone.
        assertThat(captor.getAllValues().get(1)).hasSize(2);
    }
}
