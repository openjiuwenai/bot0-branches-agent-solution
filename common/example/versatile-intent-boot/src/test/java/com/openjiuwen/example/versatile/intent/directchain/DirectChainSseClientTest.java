package com.openjiuwen.example.versatile.intent.directchain;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DirectChainSseClientTest {
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void postsBodyAndStreamsDataLines() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        List<String> capturedBody = new CopyOnWriteArrayList<>();
        List<String> lines = new CopyOnWriteArrayList<>();
        server.createContext("/run", exchange -> {
            capturedBody.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write("data: {\"a\":1}\n\n".getBytes());
                os.write("\n".getBytes()); // blank line skipped
                os.write("data: {\"b\":2}\n\n".getBytes());
            }
        });
        server.start();

        DirectChainSseClient client = new DirectChainSseClient();
        client.postStream("http://127.0.0.1:" + server.getAddress().getPort() + "/run",
                Map.of("q", "订酒店"), Map.of("X-Direct-Chain", "true"), Duration.ofSeconds(5),
                lines::add);

        assertThat(capturedBody).hasSize(1);
        assertThat(capturedBody.get(0)).contains("\"q\":\"订酒店\"");
        assertThat(lines).containsExactly("data: {\"a\":1}", "data: {\"b\":2}");
    }
}