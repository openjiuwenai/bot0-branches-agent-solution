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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话式验证控制台的 HTTP 服务（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>纯 JDK {@link HttpServer}，无 Node、无前端构建。端点：
 * <ul>
 *   <li>{@code GET /} —— 静态首页（三栏对话布局）。</li>
 *   <li>{@code GET /api/queries} —— query 目录（前端渲染按钮）。</li>
 *   <li>{@code POST /api/chat/send} —— 单发一条 query，body {@code {queryId,sessionId}}。</li>
 *   <li>{@code POST /api/chat/send-serial} —— 串行发多条，body {@code {queryIds:[...],sessionId}}。</li>
 *   <li>{@code GET /api/chat/events} —— SSE，实时推送对话消息。</li>
 *   <li>{@code GET /api/chat/sessions} —— 当前会话列表。</li>
 *   <li>{@code POST /api/chat/new-session} —— 新建会话，body {@code {label}}。</li>
 *   <li>{@code GET /api/status} —— {@code {running}}。</li>
 * </ul>
 *
 * <p>并发约束：同一时刻只允许一个 query/串行组在跑（全局 running 标志），
 * 因为串行组复用 conversationId、s3 用 continueInput 续传，都不支持并发。
 */
final class ConversationApiServer {

    private final int port;
    private final ChatBroadcaster broadcaster = new ChatBroadcaster();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService workers = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> {
                Thread t = new Thread(r, "chat-ui");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thread, ex) -> {
                    // best-effort：API 工作线程未捕获异常不中断服务。
                });
                return t;
            });

    private ConversationDriver driver;
    private String gatewayUrl;

    ConversationApiServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("UI_PORT", "9090"));
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        ConversationApiServer ui = new ConversationApiServer(port);
        int bound = ui.start();
        System.out.println();
        System.out.println("======================================================");
        System.out.println("  agent-client 对话式验证控制台已启动");
        System.out.println("  请在浏览器打开: http://127.0.0.1:" + bound + "/");
        System.out.println("  按 Ctrl+C 结束");
        System.out.println("======================================================");
        System.out.println();
        Thread.currentThread().join();
    }

    int start() throws IOException {
        // 必须由环境变量 AGENT_GATEWAY_URL 指向一个已在外部独立运行的 gateway 进程。
        String url = System.getenv("AGENT_GATEWAY_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "未设置环境变量 AGENT_GATEWAY_URL。请先启动你的 gateway 进程，"
                            + "再用 set AGENT_GATEWAY_URL=http://127.0.0.1:<端口> 指向它，然后启动本程序。");
        }
        driver = new ConversationDriver(url, broadcaster);
        this.gatewayUrl = url;
        driver.announceGateway();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(workers);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/queries", this::handleQueries);
        server.createContext("/api/chat/send", this::handleSend);
        server.createContext("/api/chat/send-serial", this::handleSendSerial);
        server.createContext("/api/chat/events", this::handleEvents);
        server.createContext("/api/chat/sessions", this::handleSessions);
        server.createContext("/api/chat/new-session", this::handleNewSession);
        server.createContext("/api/status", this::handleStatus);
        server.start();
        return server.getAddress().getPort();
    }

    // ---------------------- handlers ----------------------

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path) || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..") || !path.startsWith("/")) {
            send(ex, 400, "text/plain", "bad path");
            return;
        }
        String resource = "web" + path;
        try (InputStream in = ConversationApiServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "text/plain", "not found: " + path);
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType(path));
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private void handleQueries(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder("{\"queries\":[");
        List<QueryCatalog.Query> all = QueryCatalog.all();
        for (int i = 0; i < all.size(); i++) {
            QueryCatalog.Query q = all.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"id\":\"").append(q.id()).append("\",");
            sb.append("\"displayName\":\"").append(esc(q.displayName())).append("\",");
            sb.append("\"input\":\"").append(esc(q.input())).append("\",");
            sb.append("\"group\":\"").append(q.group().name()).append("\",");
            sb.append("\"groupLabel\":\"").append(esc(q.group().label)).append("\",");
            sb.append("\"description\":\"").append(esc(q.description())).append("\",");
            sb.append("\"expectedFailed\":").append(q.expectedFailed());
            sb.append('}');
        }
        sb.append("]}");
        send(ex, 200, "application/json; charset=utf-8", sb.toString());
    }

    private void handleSend(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String queryId = extractString(body, "queryId");
        String sessionId = extractString(body, "sessionId");
        if (queryId == null) {
            send(ex, 400, "application/json; charset=utf-8",
                    "{\"accepted\":false,\"message\":\"queryId required\"}");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            send(ex, 409, "application/json; charset=utf-8",
                    "{\"accepted\":false,\"message\":\"another query is running\"}");
            return;
        }
        send(ex, 202, "application/json; charset=utf-8",
                "{\"accepted\":true,\"message\":\"started\"}");
        workers.execute(() -> {
            String sid = (sessionId == null || sessionId.isBlank())
                    ? driver.createSession(queryId) : sessionId;
            try {
                ConversationDriver.QueryResult result = driver.runQuery(queryId, sid);
                broadcaster.broadcast(ChatMessage.info(sid,
                        "query " + queryId + " 完成: " + (result.ok() ? "通过" : "存在失败")));
            } catch (RuntimeException e) {
                broadcaster.broadcast(ChatMessage.error(sid, null, "unexpected", String.valueOf(e)));
            } finally {
                running.set(false);
            }
        });
    }

    private void handleSendSerial(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        List<String> queryIds = extractStringList(body, "queryIds");
        String sessionId = extractString(body, "sessionId");
        if (queryIds.isEmpty()) {
            send(ex, 400, "application/json; charset=utf-8",
                    "{\"accepted\":false,\"message\":\"queryIds required\"}");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            send(ex, 409, "application/json; charset=utf-8",
                    "{\"accepted\":false,\"message\":\"another query is running\"}");
            return;
        }
        send(ex, 202, "application/json; charset=utf-8",
                "{\"accepted\":true,\"message\":\"started\"}");
        workers.execute(() -> {
            String sid = (sessionId == null || sessionId.isBlank())
                    ? driver.createSession("serial") : sessionId;
            try {
                broadcaster.broadcast(ChatMessage.info(sid,
                        "开始串行发送 " + queryIds.size() + " 条 query"));
                List<ConversationDriver.QueryResult> results = driver.runSerial(queryIds, sid);
                long passed = results.stream().filter(ConversationDriver.QueryResult::ok).count();
                broadcaster.broadcast(ChatMessage.info(sid,
                        "串行发送完成: " + passed + "/" + results.size() + " 通过"));
            } catch (RuntimeException e) {
                broadcaster.broadcast(ChatMessage.error(sid, null, "unexpected", String.valueOf(e)));
            } finally {
                running.set(false);
            }
        });
    }

    private void handleNewSession(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method not allowed");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String label = extractString(body, "label");
        if (label == null) {
            label = "新会话";
        }
        String id = driver.createSession(label);
        send(ex, 200, "application/json; charset=utf-8",
                "{\"sessionId\":\"" + id + "\",\"label\":\"" + esc(label) + "\"}");
    }

    private void handleSessions(HttpExchange ex) throws IOException {
        List<ConversationDriver.SessionInfo> list = driver.sessions();
        StringBuilder sb = new StringBuilder("{\"sessions\":[");
        for (int i = 0; i < list.size(); i++) {
            ConversationDriver.SessionInfo s = list.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"id\":\"").append(s.id()).append("\",");
            sb.append("\"label\":\"").append(esc(s.label())).append("\",");
            sb.append("\"conversationId\":\"").append(s.conversationId()).append("\",");
            sb.append("\"messageCount\":").append(s.messageCount());
            sb.append('}');
        }
        sb.append("]}");
        send(ex, 200, "application/json; charset=utf-8", sb.toString());
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
        ChatBroadcaster.SseClient client = new ChatBroadcaster.SseClient(ex);
        broadcaster.addClient(client);
        client.send("connected", "{\"ok\":true}");
        try {
            while (!client.closed.get()) {
                Thread.sleep(15000);
                client.send("ping", "{}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            broadcaster.removeClient(client);
            client.close();
            try {
                ex.close();
            } catch (IllegalStateException ignore) {
                // best-effort：exchange 已关闭。
            }
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        send(ex, 200, "application/json; charset=utf-8",
                "{\"running\":" + running.get()
                        + ",\"gatewayUrl\":\"" + esc(gatewayUrl) + "\"}");
    }

    // ---------------------- helpers ----------------------

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

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    /** 从 JSON body 提取一个字符串字段（简单正则，足够本场景的简单请求体）。 */
    private static String extractString(String body, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** 从 JSON body 提取字符串数组字段。 */
    private static List<String> extractStringList(String body, String key) {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher m = p.matcher(body);
        if (!m.find()) {
            return out;
        }
        String arr = m.group(1);
        Pattern item = Pattern.compile("\"([^\"]*)\"");
        Matcher im = item.matcher(arr);
        while (im.find()) {
            out.add(im.group(1));
        }
        return out;
    }
}
