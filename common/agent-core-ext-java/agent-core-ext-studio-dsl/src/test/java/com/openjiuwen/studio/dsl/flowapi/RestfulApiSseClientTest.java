/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * SSE line protocol + live HTTP SSE for FlowApi IR stream path.
 *
 * @since 2026-08-26
 */
class RestfulApiSseClientTest {
    @Test
    void parseSseDataLines_stripsPrefixLikePython() {
        String body = "event: msg\ndata:{\"a\":1}\n\ndata: chunk-2\n:comment\ndata:last\n";
        assertThat(RestfulApiSseClient.parseSseDataLines(body))
                .containsExactly("{\"a\":1}", " chunk-2", "last");
    }

    @Test
    void parseSseDataLines_ignoresNonData() {
        assertThat(RestfulApiSseClient.parseSseDataLines("hello\nid: 1\n")).isEmpty();
    }

    @Test
    void liveHttpSse_streamYieldsDataLinesThenFinish() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sse", exchange -> {
            byte[] bytes = "data:hello\ndata:world\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            int port = server.getAddress().getPort();
            String url = "http://127.0.0.1:" + port + "/sse";
            NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
            ComponentExecutable exec = registry.create(
                    AssembledNode.of(
                            "api_sse",
                            "jiuwen.plugin",
                            Map.of(
                                    "url",
                                    url,
                                    "method",
                                    "GET",
                                    "userFields",
                                    Map.of("outputs", List.of(Map.of("id", "out", "required", true))))),
                    NodeBuildContext.defaults("wf"));
            Iterator<Object> it =
                    exec.stream(Map.of("userFields", Map.of()), Mockito.mock(NodeSessionApi.class), null);
            List<Object> frames = new ArrayList<>();
            it.forEachRemaining(frames::add);
            // 2 SSE chunks + finish
            assertThat(frames.size()).isGreaterThanOrEqualTo(3);
            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) frames.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> meta0 = (Map<String, Object>) first.get("__stream_metadata__");
            assertThat(meta0).containsEntry("messages_type", "streaming");
            @SuppressWarnings("unchecked")
            Map<String, Object> last = (Map<String, Object>) frames.get(frames.size() - 1);
            @SuppressWarnings("unchecked")
            Map<String, Object> metaLast = (Map<String, Object>) last.get("__stream_metadata__");
            assertThat(metaLast).containsEntry("messages_type", "finish");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void liveHttpSse_non2xxThrows() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bad", exchange -> {
            exchange.sendResponseHeaders(500, 0);
            exchange.getResponseBody().close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            int port = server.getAddress().getPort();
            FlowApiEngine engine = new FlowApiEngine("n");
            engine.init(Map.of("url", "http://127.0.0.1:" + port + "/bad", "method", "GET"));
            assertThatThrownBy(() -> engine.stream(Map.of("userFields", Map.of()), null, null))
                    .hasMessageContaining("101745");
        } finally {
            server.stop(0);
        }
    }
}
