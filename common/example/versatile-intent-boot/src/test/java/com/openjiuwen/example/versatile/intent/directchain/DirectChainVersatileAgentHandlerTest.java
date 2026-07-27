/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.directchain;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayCardResolver;
import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayProperties;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link DirectChainVersatileAgentHandler} 直链截胡与 a2a 例外转发验证。
 *
 * @since 0.1.0
 */
class DirectChainVersatileAgentHandlerTest {
    private HttpServer versatileMock;
    private HttpServer gatewayMock;

    @AfterEach
    void stop() {
        if (versatileMock != null) {
            versatileMock.stop(0);
        }
        if (gatewayMock != null) {
            gatewayMock.stop(0);
        }
    }

    @Test
    void interceptsA2aDelegateAndTunnelsBusinessSseByDefault() throws Exception {
        // 1) versatile mock: 返回三字段结果 agent_id=agent_card_biz_hotel_domestic
        versatileMock = startVersatileMock("L2国内酒店");

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
            exchange.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });
        gatewayMock.start();

        // resolver 指向 gateway mock（复用现有 A2AGatewayCardResolver，不新增 URL 配置）
        A2AGatewayCardResolver resolver = new A2AGatewayCardResolver(
                buildGatewayProps(gatewayMock.getAddress().getPort()));
        DirectChainProperties dcProps = new DirectChainProperties();
        dcProps.setEnabled(true);
        // a2aForwardAgentCards 留空 => 默认全直链，agent_card_biz_hotel_domestic 走直链
        DirectChainVersatileAgentHandler handler = new DirectChainVersatileAgentHandler(
                buildVersatileProperties(versatileMock.getAddress().getPort()), dcProps, resolver);

        ServeRequest req = new ServeRequest();
        req.setConversationId("c-dc");
        req.setStream(true);
        req.setMessages(List.of(Map.of("role", "user", "content", Map.of("query", "订酒店"))));

        Throwable[] error = new Throwable[1];
        boolean[] done = new boolean[1];
        List<QueryChunk> chunks = captureStream(handler, req, error, done);

        assertThat(error[0]).as("no error").isNull();
        assertThat(done[0]).isTrue();
        assertThat(directChainHits.get()).isEqualTo(1); // 经 gateway 直链
        assertThat(chunks).noneMatch(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType())); // 不外泄 a2a_delegate
        assertThat(chunks).anyMatch(c -> { // 透传业务事件 Map
            if (!QueryChunk.TYPE_CHUNK.equals(c.getType()) || !(c.getData() instanceof Map<?, ?> m)) {
                return false;
            }
            return m.containsKey("custom_rsp_data");
        });
    }

    @Test
    void forwardsA2aDelegateWhenAgentInA2aForwardSet() throws Exception {
        // 同上 versatile mock；agent_card_biz_hotel_domestic 被列入 a2aForwardAgentCards => 不直链，原样转发 a2a_delegate
        versatileMock = startVersatileMock("L2");

        A2AGatewayCardResolver resolver = new A2AGatewayCardResolver(buildGatewayProps(1)); // 不会被调用
        DirectChainProperties dcProps = new DirectChainProperties();
        dcProps.setEnabled(true);
        dcProps.setA2aForwardAgentCards(Set.of("agent_card_biz_hotel_domestic")); // 例外走 a2a
        DirectChainVersatileAgentHandler handler = new DirectChainVersatileAgentHandler(
                buildVersatileProperties(versatileMock.getAddress().getPort()), dcProps, resolver);

        ServeRequest req = new ServeRequest();
        req.setConversationId("c-a2a");
        req.setStream(true);
        req.setMessages(List.of(Map.of("role", "user", "content", Map.of("query", "订酒店"))));

        boolean[] done = new boolean[1];
        List<QueryChunk> chunks = captureStream(handler, req, null, done);

        assertThat(done[0]).isTrue();
        assertThat(chunks).anyMatch(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType())); // a2a_delegate 原样转发
    }

    private HttpServer startVersatileMock(String responseContent) throws IOException {
        String sse = "data: {\"custom_rsp_data\":{\"node_name\":\"AnswerNode\",\"data\":"
                + "{\"node_type\":\"QA\",\"response_content\":\"" + responseContent + "\","
                + "\"intent_id\":\"intent_L2_hotel_domestic\","
                + "\"agent_id\":\"agent_card_biz_hotel_domestic\"}}}\n\n"
                + "data: {\"data\":{\"node_type\":\"End\"}}\n\n";
        HttpServer mock = HttpServer.create(new InetSocketAddress(0), 0);
        mock.createContext("/v1/proj/agents/agent_L2/conversations/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(sse.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().close();
        });
        mock.start();
        return mock;
    }

    private VersatileProperties buildVersatileProperties(int port) {
        VersatileProperties vProps = new VersatileProperties();
        vProps.setUrlTemplate("http://127.0.0.1:" + port
                + "/v1/proj/agents/agent_L2/conversations/{conversation_id}");
        vProps.setResultNodeName("AnswerNode");
        vProps.setResultExtractions(List.of(
                buildExtraction("response_content", "/custom_rsp_data/data/response_content"),
                buildExtraction("intent_id", "/custom_rsp_data/data/intent_id"),
                buildExtraction("agent_id", "/custom_rsp_data/data/agent_id")));
        return vProps;
    }

    private VersatileProperties.ResultExtraction buildExtraction(String match, String get) {
        VersatileProperties.ResultExtraction ex = new VersatileProperties.ResultExtraction();
        ex.setMatch(match);
        ex.setGet(get);
        return ex;
    }

    private A2AGatewayProperties buildGatewayProps(int port) {
        A2AGatewayProperties gwProps = new A2AGatewayProperties();
        gwProps.setBaseUrl("http://127.0.0.1:" + port);
        gwProps.setJsonRpcPath("/a2a/{agentCard}");
        return gwProps;
    }

    private List<QueryChunk> captureStream(DirectChainVersatileAgentHandler handler, ServeRequest req,
            Throwable[] error, boolean[] done) {
        List<QueryChunk> chunks = new CopyOnWriteArrayList<>();
        handler.streamQuery(req, new QueryStreamObserver() {
            /**
             * 接收流式 chunk，累积到列表。
             */
            @Override
            public void onNext(QueryChunk c) {
                chunks.add(c);
            }

            /**
             * 记录流式错误（当 error 槽非空时）。
             */
            @Override
            public void onError(Throwable e) {
                if (error != null) {
                    error[0] = e;
                }
            }

            /**
             * 标记流式完成（当 done 槽非空时）。
             */
            @Override
            public void onComplete() {
                if (done != null) {
                    done[0] = true;
                }
            }
        });
        return chunks;
    }
}
