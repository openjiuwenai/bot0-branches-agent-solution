package com.openjiuwen.example.versatile.intent.directchain;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class RawVersatilePassthroughHandlerTest {
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void streamsBusinessEventsAsMapChunks() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/proj/agents/agent_biz/conversations/", exchange -> {
            String sse = "data: {\"custom_rsp_data\":{\"data\":{\"node_type\":\"QA\",\"text\":\"酒店预订成功\"}}}\n\n"
                    + "data: {\"data\":{\"node_type\":\"End\"}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(sse.getBytes());
            exchange.getResponseBody().close();
        });
        server.start();

        VersatileProperties props = new VersatileProperties();
        props.setUrlTemplate("http://127.0.0.1:" + server.getAddress().getPort()
                + "/v1/proj/agents/agent_biz/conversations/{conversation_id}");

        RawVersatilePassthroughHandler handler = new RawVersatilePassthroughHandler(props);
        List<QueryChunk> chunks = new CopyOnWriteArrayList<>();
        Throwable[] error = new Throwable[1];
        boolean[] done = new boolean[1];

        ServeRequest req = new ServeRequest();
        req.setConversationId("c-raw");
        req.setMessages(List.of(Map.of("role", "user", "content", Map.of("query", "订酒店"))));

        handler.streamQuery(req, new com.openjiuwen.service.spec.spi.QueryStreamObserver() {
            public void onNext(QueryChunk c) { chunks.add(c); }
            public void onError(Throwable e) { error[0] = e; }
            public void onComplete() { done[0] = true; }
        });

        assertThat(error[0]).as("no error").isNull();
        assertThat(done[0]).isTrue();
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getType()).isEqualTo(QueryChunk.TYPE_CHUNK);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) chunks.get(0).getData();
        assertThat(first).containsKey("custom_rsp_data");
    }
}