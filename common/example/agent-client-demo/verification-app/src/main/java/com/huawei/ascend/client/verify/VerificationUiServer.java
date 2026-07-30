/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.verify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 薄可视化前端（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>纯 JDK {@link HttpServer} + classpath 内 HTML/CSS/JS，无 Node、无前端构建。
 * 浏览器打开后可一键跑全部场景、也可勾选单跑，经 SSE 实时看进度与逐条断言。
 *
 * <p>看板与 CI 跑的是<b>同一份</b> {@link CloudClientVerification}：场景目录来自它的注册表，
 * 断言来自它的同一批 check，因此不存在"看板显示的"与"CI 判定的"两套结论。
 *
 * <pre>
 * java -cp ... com.huawei.ascend.client.verify.CloudClientVerification --ui
 * # 然后打开终端打印的 http://127.0.0.1:9090/
 * </pre>
 *
 * @since 2026-07-27
 */
public final class VerificationUiServer {
    private static final Logger LOG = Logger.getLogger(VerificationUiServer.class.getName());
    private static final String LF = String.valueOf((char) 10);
    private static final String CR = String.valueOf((char) 13);

    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();

    private final ExecutorService workers = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = java.util.concurrent.Executors.defaultThreadFactory().newThread(r);
                t.setName("verify-ui");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    // best-effort：UI 工作线程未捕获异常不中断服务，仅记录日志。
                    LOG.log(java.util.logging.Level.WARNING,
                            "uncaught exception in verify-ui worker " + thread.getName(), ex);
                });
                return t;
            });

    /**
     * 构造验证 UI 服务。
     *
     * @param port 监听端口
     */
    public VerificationUiServer(int port) {
        this.port = port;
    }

    /**
     * 启动验证 UI 服务主程序。
     *
     * @param args 命令行参数，第一个为端口号（可选）
     * @throws Exception 启动失败时抛出
     */
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("UI_PORT", "9090"));
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        VerificationUiServer ui = new VerificationUiServer(port);
        int bound = ui.start();
        String banner = System.lineSeparator()
                + "======================================================" + System.lineSeparator()
                + "  agent-client 验证控制台已启动" + System.lineSeparator()
                + "  请在浏览器打开: http://127.0.0.1:" + bound + "/" + System.lineSeparator()
                + "  按 Ctrl+C 结束" + System.lineSeparator()
                + "======================================================" + System.lineSeparator();
        LOG.info(banner);
        Thread.currentThread().join();
    }

    /**
     * 启动 UI 服务。
     *
     * @return 实际监听端口
     * @throws IOException 启动失败时抛出
     */
    public int start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/scenarios", this::handleScenarios);
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
        try (InputStream in = VerificationUiServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found: " + path);
                return;
            }
            byte[] body = in.readAllBytes();
            String ct = contentType(path);
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        String json = "{\"running\":" + running.get() + "}";
        send(ex, 200, "application/json; charset=utf-8", json);
    }

    /**
     * 场景目录：让看板在开跑前就能把全部场景列出来，并支持按 id 勾选。
     *
     * <p>目录直接来自 {@link CloudClientVerification} 的场景注册表，
     * 因此新增场景不需要动前端，也不会出现"看板列的"和"CI 跑的"两份清单。
     *
     * @param ex HTTP 交换对象
     * @throws IOException 写响应失败时抛出
     */
    private void handleScenarios(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"scenarios\":[");
        boolean first = true;
        for (CloudClientVerification.ScenarioSpec s : CloudClientVerification.catalog()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"id\":\"").append(esc(s.id()))
                    .append("\",\"title\":\"").append(esc(s.title()))
                    .append("\",\"category\":\"").append(esc(s.category()))
                    .append("\",\"summary\":\"").append(esc(s.summary()))
                    .append("\"}");
        }
        sb.append("]}");
        send(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    /**
     * 从 {@code ?ids=s1,s3} 解析要跑的场景；缺省或为空表示全部。
     *
     * @param rawQuery 原始查询串，可为 null
     * @return 场景 id 集合；空集合表示全部
     */
    private static Set<String> parseIds(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Set.of();
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !"ids".equals(pair.substring(0, eq))) {
                continue;
            }
            String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            Set<String> ids = new LinkedHashSet<>();
            for (String id : value.split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
            return ids;
        }
        return Set.of();
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
            // 心跳线程被中断即意味着要关闭 SSE 连接，直接退出循环（无需恢复中断标志）。
            LOG.info("SSE heartbeat interrupted, closing client connection");
        } finally {
            sseClients.remove(client);
            client.close();
            try {
                ex.close();
            } catch (IllegalStateException ignore) {
                // best-effort：exchange 已关闭。
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
        Set<String> ids = parseIds(ex.getRequestURI().getQuery());
        send(ex, 202, "application/json; charset=utf-8",
                "{\"accepted\":true,\"message\":\"started\"}");
        workers.execute(() -> {
            try {
                String what = ids.isEmpty() ? "全部场景" : ("场景 " + String.join(", ", ids));
                broadcast("info", jsonEvent("INFO", null, "开始运行：" + what, null));
                new CloudClientVerification().runWithProgress(event -> {
                    broadcast("progress", toJson(event));
                    // 同步记录到日志，方便对照；用 {N} 占位符避免禁用级别时拼接字符串（G.LOG.03）。
                    String scenario = (event.scenarioId() != null) ? event.scenarioId() + " " : "";
                    LOG.log(java.util.logging.Level.INFO, "[ui] {0} {1}{2}",
                            new Object[] {event.kind(), scenario, event.message()});
                }, ids);
            } catch (IOException | IllegalStateException e) {
                broadcast("progress", jsonEvent("RUN_END", null,
                        "unexpected failure: " + e.getMessage(), false));
                LOG.log(java.util.logging.Level.WARNING, "verification run failed", e);
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

    /**
     * JSON 文本。
     *
     * @param e VerificationProgress.Event
     * @return JSON 文本
     */
    private static String toJson(VerificationProgress.Event e) {
        return jsonEvent(e.kind().name(), e.scenarioId(), e.message(), e.ok());
    }

    /**
     * 构造一个 JSON 事件字符串。
     *
     * @param kind 事件类型
     * @param scenarioId 场景标识，可为 null
     * @param message 消息文本，可为 null
     * @param ok 是否通过，可为 null
     * @return JSON 文本
     */
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

    /**
     * JSON 字符串转义。
     *
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private static String esc(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace(LF, "\\n")
                .replace(CR, "");
    }

    /**
     * 发送 HTTP 响应。
     *
     * @param ex HTTP 交换对象
     * @param status HTTP 状态码
     * @param contentType Content-Type
     * @param body 响应体
     * @throws IOException 写响应失败时抛出
     */
    private static void send(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 根据路径推断 Content-Type。
     *
     * @param path 资源路径
     * @return Content-Type
     */
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

    private static final class SseClient implements AutoCloseable {
        final HttpExchange exchange;
        final OutputStream out;
        final AtomicBoolean closed = new AtomicBoolean(false);

        SseClient(HttpExchange exchange) throws IOException {
            this.exchange = exchange;
            this.out = exchange.getResponseBody();
        }

        synchronized void send(String event, String data) throws IOException {
            if (closed.get()) {
                throw new IOException("closed");
            }
            String payload = "event: " + event + LF + "data: " + data + LF + LF;
            out.write(payload.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    out.close();
                } catch (IOException ignore) {
                    // best-effort：连接已断开时关闭忽略。
                }
            }
        }
    }
}
