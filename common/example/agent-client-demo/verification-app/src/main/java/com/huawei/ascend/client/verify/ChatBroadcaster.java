package com.huawei.ascend.client.verify;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把 {@link ChatMessage} 广播给所有已连接的 SSE 客户端（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>纯 JDK，JSON 手写序列化（沿用原 VerificationUiServer 的做法，不引入第三方依赖）。
 * payload 若是 Map/可 toString 对象，按 JSON 对象/字符串安全输出。
 */
final class ChatBroadcaster {

    private final List<SseClient> clients = new CopyOnWriteArrayList<>();

    void addClient(SseClient client) {
        clients.add(client);
    }

    void removeClient(SseClient client) {
        clients.remove(client);
    }

    void broadcast(ChatMessage msg) {
        String json = toJson(msg);
        List<SseClient> dead = new ArrayList<>();
        for (SseClient c : clients) {
            try {
                c.send("chat", json);
            } catch (IOException e) {
                c.closed.set(true);
                dead.add(c);
            }
        }
        clients.removeAll(dead);
    }

    /** 把 ChatMessage 序列化为前端可消费的 JSON。 */
    static String toJson(ChatMessage m) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        kv(sb, "type", m.type(), true);
        kv(sb, "sessionId", m.sessionId(), false);
        kv(sb, "invocationRef", m.invocationRef(), false);
        kv(sb, "toolCallId", m.toolCallId(), false);
        kv(sb, "text", m.text(), false);
        kv(sb, "state", m.state(), false);
        kv(sb, "detail", m.detail(), false);
        kv(sb, "outcome", m.outcome(), false);
        kv(sb, "errorCode", m.errorCode(), false);
        kv(sb, "message", m.message(), false);
        kv(sb, "scenarioId", m.scenarioId(), false);
        kv(sb, "label", m.label(), false);
        if (m.arguments() != null) {
            sb.append(",\"arguments\":");
            sb.append(toJsonObj(m.arguments()));
        }
        if (m.payload() != null) {
            sb.append(",\"payload\":");
            sb.append(toJsonValue(m.payload()));
        }
        if (m.ok() != null) {
            sb.append(",\"ok\":").append(m.ok());
        }
        sb.append('}');
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String key, String value, boolean first) {
        if (value == null) {
            return;
        }
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(key).append("\":\"").append(esc(value)).append('"');
    }

    @SuppressWarnings("unchecked")
    private static String toJsonObj(Object o) {
        if (o instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(esc(String.valueOf(e.getKey()))).append("\":");
                sb.append(toJsonValue(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(toJsonValue(item));
            }
            sb.append(']');
            return sb.toString();
        }
        return toJsonValue(o);
    }

    private static String toJsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        if (v instanceof Map || v instanceof List) {
            return toJsonObj(v);
        }
        return "\"" + esc(String.valueOf(v)) + "\"";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }

    /** 一个 SSE 客户端连接。OutputStream 在构造时获取一次，整个 SSE 会话期间复用，由 close() 关闭。 */
    static final class SseClient implements AutoCloseable {
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
            String payload = "event: " + event + "\ndata: " + data + "\n\n";
            out.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
