package com.huawei.ascend.client.verify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 薄可视化前端（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>纯 JDK {@link HttpServer} + classpath 内 HTML/CSS/JS，无 Node、无前端构建。
 * 浏览器打开后点「开始验证」，经 SSE 实时看 4 个场景的进度与断言结果。
 *
 * <pre>
 * java -cp ... com.huawei.ascend.client.verify.CloudClientVerification --ui
 * # 然后打开终端打印的 http://127.0.0.1:9090/
 * </pre>
 */
public final class VerificationUiServer {

    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "verify-ui");
        t.setDaemon(true);
        return t;
    });

    public VerificationUiServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("UI_PORT", "9090"));
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        VerificationUiServer ui = new VerificationUiServer(port);
        int bound = ui.start();
        System.out.println();
        System.out.println("======================================================");
        System.out.println("  agent-client 验证控制台已启动");
        System.out.println("  请在浏览器打开: http://127.0.0.1:" + bound + "/");
        System.out.println("  按 Ctrl+C 结束");
        System.out.println("======================================================");
        System.out.println();
        Thread.currentThread().join();
    }

    public int start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/run", this::handleRun);
        server.createContext("/api/events", this::handleEvents);
        server.createContext("/api/status", this::handleStatus);
        server.start();
        return server.getAddress().getPort();
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }
        // 只允许 web/ 下的相对路径，防止路径穿越
        if (path.contains("..") || !path.startsWith("/")) {
            send(ex, 400, "text/plain", "bad path");
            return;
        }
        String resource = "web" + path;
        InputStream in = VerificationUiServer.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            send(ex, 404, "text/plain", "not found: " + path);
            return;
        }
        byte[] body = in.readAllBytes();
        in.close();
        String ct = contentType(path);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        String json = "{\"running\":" + running.get() + "}";
        send(ex, 200, "application/json; charset=utf-8", json);
    }

    private void handleEvents(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(ex);
        sseClients.add(client);
        client.send("connected", "{\"ok\":true}");
        // 阻塞住连接，直到客户端断开或服务关闭；心跳避免代理空闲断连
        try {
            while (!client.closed.get()) {
                Thread.sleep(15000);
                client.send("ping", "{}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sseClients.remove(client);
            try {
                ex.close();
            } catch (RuntimeException ignore) {
            }
        }
    }

    private void handleRun(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            send(ex, 409, "application/json; charset=utf-8",
                    "{\"accepted\":false,\"message\":\"verification already running\"}");
            return;
        }
        send(ex, 202, "application/json; charset=utf-8",
                "{\"accepted\":true,\"message\":\"started\"}");
        workers.execute(() -> {
            try {
                broadcast("info", jsonEvent("INFO", null, "verification started from UI", null));
                new CloudClientVerification().runWithProgress(event -> {
                    broadcast("progress", toJson(event));
                    // 同步打印到终端，方便对照
                    System.out.println("[ui] " + event.kind() + " "
                            + (event.scenarioId() != null ? event.scenarioId() + " " : "")
                            + event.message());
                });
            } catch (InterruptedException | ExecutionException | TimeoutException | IOException | RuntimeException e) {
                broadcast("progress", jsonEvent("RUN_END", null,
                        "unexpected failure: " + e.getMessage(), false));
                e.printStackTrace();
            } finally {
                running.set(false);
            }
        });
    }

    private void broadcast(String eventName, String dataJson) {
        List<SseClient> dead = new ArrayList<>();
        for (SseClient c : sseClients) {
            try {
                c.send(eventName, dataJson);
            } catch (IOException e) {
                c.closed.set(true);
                dead.add(c);
            }
        }
        sseClients.removeAll(dead);
    }

    private static String toJson(VerificationProgress.Event e) {
        return jsonEvent(e.kind().name(), e.scenarioId(), e.message(), e.ok());
    }

    private static String jsonEvent(String kind, String scenarioId, String message, Boolean ok) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"kind\":\"").append(esc(kind)).append("\"");
        if (scenarioId != null) {
            sb.append(",\"scenarioId\":\"").append(esc(scenarioId)).append("\"");
        }
        if (message != null) {
            sb.append(",\"message\":\"").append(esc(message)).append("\"");
        }
        if (ok != null) {
            sb.append(",\"ok\":").append(ok);
        }
        sb.append('}');
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    private static void send(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static final class SseClient {
        final HttpExchange exchange;
        final AtomicBoolean closed = new AtomicBoolean(false);

        SseClient(HttpExchange exchange) {
            this.exchange = exchange;
        }

        synchronized void send(String event, String data) throws IOException {
            if (closed.get()) {
                throw new IOException("closed");
            }
            String payload = "event: " + event + "\ndata: " + data + "\n\n";
            OutputStream os = exchange.getResponseBody();
            os.write(payload.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
    }
}
