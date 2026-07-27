package com.openjiuwen.example.versatile.intent.directchain;

import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayCardResolver;
import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayProperties;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DirectChainVersatileAgentHandlerTest {
    private HttpServer versatileMock;
    private HttpServer gatewayMock;

    @AfterEach
    void stop() {
        if (versatileMock != null) versatileMock.stop(0);
        if (gatewayMock != null) gatewayMock.stop(0);
    }

    @Test
    void interceptsA2aDelegateAndTunnelsBusinessSseByDefault() throws Exception {
        // 1) versatile mock: 返回三字段结果 agent_id=agent_card_biz_hotel_domestic
        versatileMock = HttpServer.create(new InetSocketAddress(0), 0);
        versatileMock.createContext("/v1/proj/agents/agent_L2/conversations/", exchange -> {
            String sse = "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                    + "{\"node_type\":\"QA\",\"response_content\":\"L2国内酒店\","
                    + "\"intent_id\":\"intent_L2_hotel_domestic\","
                    + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}\n\n"
                    + "data: {\"data\":{\"node_type\":\"End\"}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(sse.getBytes());
            exchange.getResponseBody().close();
        });
        versatileMock.start();

        // 2) gateway mock: 校验 X-Direct-Chain 头，返回业务 SSE data: 行
        AtomicInteger directChainHits = new AtomicInteger();
        gatewayMock = HttpServer.create(new InetSocketAddress(0), 0);
        gatewayMock.createContext("/a2a/agent_card_biz_hotel_domestic", exchange -> {
            if ("true".equals(exchange.getRequestHeaders().getFirst("X-Direct-Chain"))) {
                directChainHits.incrementAndGet();
            }
            String sse = "data: {\"custom_rsp_data\":{\"data\":{\"node_type\":\"QA\",\"text\":\"酒店预订成功\"}}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(sse.getBytes());
            exchange.getResponseBody().close();
        });
        gatewayMock.start();

        VersatileProperties vProps = new VersatileProperties();
        vProps.setUrlTemplate("http://127.0.0.1:" + versatileMock.getAddress().getPort()
                + "/v1/proj/agents/agent_L2/conversations/{conversation_id}");
        vProps.setResultNodeName("AnswerNode");
        com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction rc =
                new com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction();
        rc.setMatch("response_content"); rc.setGet("/custom_rsp_data/data/response_content");
        com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction ri =
                new com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction();
        ri.setMatch("intent_id"); ri.setGet("/custom_rsp_data/data/intent_id");
        com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction ra =
                new com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction();
        ra.setMatch("agent_id"); ra.setGet("/custom_rsp_data/data/agent_id");
        vProps.setResultExtractions(List.of(rc, ri, ra));

        // resolver 指向 gateway mock（复用现有 A2AGatewayCardResolver，不新增 URL 配置）
        A2AGatewayProperties gwProps = new A2AGatewayProperties();
        gwProps.setBaseUrl("http://127.0.0.1:" + gatewayMock.getAddress().getPort());
        gwProps.setJsonRpcPath("/a2a/{agentCard}");
        A2AGatewayCardResolver resolver = new A2AGatewayCardResolver(gwProps);

        DirectChainProperties dcProps = new DirectChainProperties();
        dcProps.setEnabled(true);
        // a2aForwardAgentCards 留空 => 默认全直链，agent_card_biz_hotel_domestic 走直链

        DirectChainVersatileAgentHandler handler =
                new DirectChainVersatileAgentHandler(vProps, dcProps, resolver);

        List<QueryChunk> chunks = new CopyOnWriteArrayList<>();
        Throwable[] error = new Throwable[1];
        boolean[] done = new boolean[1];

        ServeRequest req = new ServeRequest();
        req.setConversationId("c-dc");
        req.setStream(true);
        req.setMessages(List.of(Map.of("role", "user", "content", Map.of("query", "订酒店"))));

        handler.streamQuery(req, new com.openjiuwen.service.spec.spi.QueryStreamObserver() {
            public void onNext(QueryChunk c) { chunks.add(c); }
            public void onError(Throwable e) { error[0] = e; }
            public void onComplete() { done[0] = true; }
        });

        assertThat(error[0]).as("no error").isNull();
        assertThat(done[0]).isTrue();
        assertThat(directChainHits.get()).isEqualTo(1); // 经 gateway 直链
        assertThat(chunks).noneMatch(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType())); // 不外泄 a2a_delegate
        assertThat(chunks).anyMatch(c -> { // 透传业务事件 Map
            if (!QueryChunk.TYPE_CHUNK.equals(c.getType()) || !(c.getData() instanceof Map<?, ?> m)) return false;
            return m.containsKey("custom_rsp_data");
        });
    }

    @Test
    void forwardsA2aDelegateWhenAgentInA2aForwardSet() throws Exception {
        // 同上 versatile mock；agent_card_biz_hotel_domestic 被列入 a2aForwardAgentCards => 不直链，原样转发 a2a_delegate
        versatileMock = HttpServer.create(new InetSocketAddress(0), 0);
        versatileMock.createContext("/v1/proj/agents/agent_L2/conversations/", exchange -> {
            String sse = "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                    + "{\"node_type\":\"QA\",\"response_content\":\"L2\",\"intent_id\":\"intent_L2_hotel_domestic\","
                    + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}\n\n"
                    + "data: {\"data\":{\"node_type\":\"End\"}}\n\n";
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(sse.getBytes());
            exchange.getResponseBody().close();
        });
        versatileMock.start();

        VersatileProperties vProps = new VersatileProperties();
        vProps.setUrlTemplate("http://127.0.0.1:" + versatileMock.getAddress().getPort()
                + "/v1/proj/agents/agent_L2/conversations/{conversation_id}");
        vProps.setResultNodeName("AnswerNode");
        com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction ri =
                new com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction();
        ri.setMatch("intent_id"); ri.setGet("/custom_rsp_data/data/intent_id");
        com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction ra =
                new com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction();
        ra.setMatch("agent_id"); ra.setGet("/custom_rsp_data/data/agent_id");
        com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction rc =
                new com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.ResultExtraction();
        rc.setMatch("response_content"); rc.setGet("/custom_rsp_data/data/response_content");
        vProps.setResultExtractions(List.of(rc, ri, ra));

        A2AGatewayProperties gwProps = new A2AGatewayProperties();
        gwProps.setBaseUrl("http://127.0.0.1:1"); // 不会被调用
        gwProps.setJsonRpcPath("/a2a/{agentCard}");
        DirectChainProperties dcProps = new DirectChainProperties();
        dcProps.setEnabled(true);
        dcProps.setA2aForwardAgentCards(Set.of("agent_card_biz_hotel_domestic")); // 例外走 a2a

        DirectChainVersatileAgentHandler handler =
                new DirectChainVersatileAgentHandler(vProps, dcProps, new A2AGatewayCardResolver(gwProps));

        List<QueryChunk> chunks = new CopyOnWriteArrayList<>();
        boolean[] done = new boolean[1];
        ServeRequest req = new ServeRequest();
        req.setConversationId("c-a2a");
        req.setStream(true);
        req.setMessages(List.of(Map.of("role", "user", "content", Map.of("query", "订酒店"))));
        handler.streamQuery(req, new com.openjiuwen.service.spec.spi.QueryStreamObserver() {
            public void onNext(QueryChunk c) { chunks.add(c); }
            public void onError(Throwable e) {}
            public void onComplete() { done[0] = true; }
        });

        assertThat(done[0]).isTrue();
        assertThat(chunks).anyMatch(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType())); // a2a_delegate 原样转发
    }
}
