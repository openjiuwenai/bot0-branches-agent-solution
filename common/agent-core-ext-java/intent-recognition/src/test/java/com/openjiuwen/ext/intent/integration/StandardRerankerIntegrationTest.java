/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.retrieval.common.RerankerConfig;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.core.retrieval.reranker.StandardReranker;
import com.openjiuwen.ext.intent.api.IntentCandidate;
import com.openjiuwen.ext.intent.api.IntentRecognitionResult;
import com.openjiuwen.ext.intent.api.IntentRecognizer;
import com.openjiuwen.ext.intent.api.IntentRecognizers;
import com.openjiuwen.ext.intent.api.IntentTargetAdapter;
import com.openjiuwen.ext.intent.reranker.IntentRecognizerConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class StandardRerankerIntegrationTest {
    @Test
    void usesInjectedStandardRerankerWithoutOwnedHttpClient() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"results\":[{\"index\":0,\"relevance_score\":0.91}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            RerankerConfig rerankerConfig = new RerankerConfig();
            rerankerConfig.setApiBase("http://127.0.0.1:" + server.getAddress().getPort());
            rerankerConfig.setModelName("test-reranker");
            rerankerConfig.setTimeout(3.0);
            Reranker reranker = new StandardReranker(rerankerConfig);
            IntentRecognizer<String> recognizer = IntentRecognizers.<String>builder().targets(List.of("order-agent"))
                    .targetAdapter(adapter()).reranker(reranker).config(config()).build();

            IntentRecognitionResult<String> result = recognizer.recognize("track my order");

            assertThat(result).isEqualTo(IntentRecognitionResult.matched("order-agent"));
            assertThat(requestBody.get()).contains("test-reranker", "track my order", "order capability");
        } finally {
            server.stop(0);
        }
    }

    private static IntentTargetAdapter<String> adapter() {
        return new IntentTargetAdapter<>() {
            @Override
            public String snapshot(String target) {
                return target;
            }

            @Override
            public String targetKey(String target) {
                return target;
            }

            @Override
            public List<IntentCandidate> candidates(int targetIndex, String target) {
                return List.of(new IntentCandidate(targetIndex, "order-agent:track", "order capability"));
            }
        };
    }

    private static IntentRecognizerConfig config() {
        return IntentRecognizerConfig.builder().scoreThreshold(0.5).marginThreshold(0.1)
                .candidateFormatVersion("test-v1").modelVersion("test-reranker").build();
    }
}
