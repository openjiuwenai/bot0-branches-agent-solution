/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.a2a;

import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationSnapshot;
import com.huawei.ascend.client.api.InvocationMode;
import com.huawei.ascend.client.api.TaskState;
import com.huawei.ascend.client.transport.spi.TransportProvider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认传输实现：A2A JSON-RPC 2.0 over HTTP + SSE（基于 JDK 内置 {@link HttpClient}，无额外网络框架）。
 *
 * <p>创建调用使用 {@code SendStreamingMessage}（SSE）；本地工具结果 / 用户输入续跑使用同步
 * {@code SendMessage}（单条 JSON，Feat-Func-011 §5.9.3）。续跑通过对既有 {@code taskId} 再次发消息实现，
 * 单条响应解析后的帧汇入同一个调用流。每一次 HTTP 都附带 {@code Authorization: Bearer <token>}。
 *
 * <p>SSE 语义遵循 Feat-Func-009：服务端在投递 INPUT_REQUIRED 后会关闭当前 SSE 队列；
 * 本实现据此保持调用流开放，待上层完成本地工具执行后由 {@code resumeToolResult} 开启下一段 SSE 续传。
 *
 * @since 2026-07-27
 */
public final class A2aHttpTransportProvider implements TransportProvider {
    private final URI endpoint;
    private final HttpClient http;
    private final A2aJsonCodec codec;
    private final ExecutorService io;

    private final ConcurrentMap<String, Channel> byInvocationRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Channel> byTaskRef = new ConcurrentHashMap<>();

    public A2aHttpTransportProvider(String baseUrl) {
        this(baseUrl, new ObjectMapper());
    }

    public A2aHttpTransportProvider(String baseUrl, ObjectMapper mapper) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(normalized.endsWith("/a2a") ? normalized : normalized + "/a2a");
        this.io = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), r -> {
                    Thread t = java.util.concurrent.Executors.defaultThreadFactory().newThread(r);
                    t.setName("a2a-transport-io");
                    t.setDaemon(true);
                    t.setUncaughtExceptionHandler((thread, ex) -> {
                        // best-effort：IO 线程未捕获异常不打断传输层主流程。
                    });
                    return t;
                });
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(io)
                .build();
        this.codec = new A2aJsonCodec(mapper);
    }

    @Override
    public Flow.Publisher<InvocationEvent> createAndStream(CreateCommand cmd) {
        Channel ch = new Channel(cmd.invocationRef());
        byInvocationRef.put(cmd.invocationRef(), ch);
        Runnable start = () -> {
            ObjectNode req = codec.buildCreate(cmd);
            if (cmd.mode() == InvocationMode.STREAMING) {
                openSse(ch, codec.write(req), cmd.credentialToken(), null);
            } else {
                sendUnary(ch, codec.write(req), cmd.credentialToken(), null);
            }
        };
        return new LazyStartPublisher(ch.publisher, start);
    }

    @Override
    public CompletionStage<InvocationSnapshot> resumeToolResult(ResumeCommand cmd) {
        Channel ch = byTaskRef.get(cmd.taskRef());
        if (ch == null) {
            ch = byInvocationRef.get(cmd.invocationRef());
        }
        if (ch == null) {
            CompletableFuture<InvocationSnapshot> failed = new CompletableFuture<>();
            failed.completeExceptionally(
                    new A2aTransportException("no active channel for taskRef " + cmd.taskRef()));
            return failed;
        }
        CompletableFuture<InvocationSnapshot> ack = new CompletableFuture<>();
        ObjectNode req = codec.buildResume(cmd);
        // 续跑一律同步 SendMessage（Feat-Func-011 §5.9.3）：单条 JSON 响应汇入既有调用流。
        sendUnary(ch, codec.write(req), cmd.credentialToken(), ack);
        return ack;
    }

    @Override
    public CompletionStage<InvocationSnapshot> getTask(String taskRef) {
        return sendForSnapshot(codec.buildGet(taskRef), null, resolveInvocationRef(taskRef));
    }

    @Override
    public CompletionStage<InvocationSnapshot> cancel(String taskRef, String reason) {
        return sendForSnapshot(codec.buildCancel(taskRef, reason), null, resolveInvocationRef(taskRef));
    }

    @Override
    public void close() {
        for (Channel ch : byInvocationRef.values()) {
            if (!ch.publisher.isClosed()) {
                ch.publisher.close();
            }
        }
        byInvocationRef.clear();
        byTaskRef.clear();
        io.shutdownNow();
    }

    // ---------- HTTP ----------

    private HttpRequest.Builder base(String accept, String credential, boolean withTimeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", accept);
        if (withTimeout) {
            b.timeout(Duration.ofSeconds(60));
        }
        if (credential != null && !credential.isEmpty()) {
            b.header("Authorization", credential.startsWith("Bearer ") ? credential : "Bearer " + credential);
        }
        return b;
    }

    private void sendUnary(Channel ch, String body, String credential,
                           CompletableFuture<InvocationSnapshot> ack) {
        HttpRequest req = base("application/json", credential, true)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        failChannel(ch, ack, ex);
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        // 网关治理错误以 HTTP 4xx/5xx + {code,message} 返回（Feat-Func-011 §4.9）。
                        failChannel(ch, ack, governanceError(resp.statusCode(), resp.body()));
                        return;
                    }
                    try {
                        JsonNode result = extractResult(codec.readTree(resp.body()));
                        codec.parseFrame(result).ifPresent(f -> emit(ch, f));
                        if (ack != null) {
                            ack.complete(workingSnapshot(ch));
                        }
                    } catch (A2aTransportException | IllegalArgumentException e) {
                        failChannel(ch, ack, e);
                    }
                });
    }

    /**
     * 把网关的 HTTP 治理错误（401/403/400/409 等）解析为带稳定 code 的传输异常。
     */
    private A2aTransportException governanceError(int status, String body) {
        String code = "HTTP_" + status;
        String message = body;
        try {
            JsonNode node = codec.readTree(body);
            if (node.hasNonNull("code")) {
                code = node.get("code").asText(code);
            }
            if (node.hasNonNull("message")) {
                message = node.get("message").asText(body);
            }
        } catch (A2aTransportException ignore) {
            // 非 JSON 响应体：保留原始文本。
        }
        return new A2aTransportException("gateway rejected request [" + status + "/" + code + "]: " + message);
    }
    private void openSse(Channel ch, String body, String credential,
                         CompletableFuture<InvocationSnapshot> ack) {
        HttpRequest req = base("text/event-stream", credential, false)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        failChannel(ch, ack, ex);
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        // 创建流式请求被网关治理拒绝：读出错误体并带稳定 code 失败。
                        failChannel(ch, ack, governanceError(resp.statusCode(), readAll(resp.body())));
                        return;
                    }
                    if (ack != null) {
                        ack.complete(workingSnapshot(ch));
                    }
                    io.execute(() -> readSse(ch, resp.body()));
                });
    }
    private void readSse(Channel ch, InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    flushFrame(ch, data);
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else {
                    // 忽略 event:/id:/注释行
                }
            }
            flushFrame(ch, data);
        } catch (IOException e) {
            handleSseReadFailure(ch, e);
        } catch (IllegalStateException | NullPointerException e) {
            handleSseReadFailure(ch, e);
        }
    }
    private void handleSseReadFailure(Channel ch, Throwable e) {
        if (!ch.terminal.get()) {
            // SSE 连接自然断开且非终态：不制造失败，等待后续 resume 续传。
            if (isHardFailure(e)) {
                ch.publisher.closeExceptionally(e);
            }
        }
    }

    /**
     * readAll。
     *
     * @param in InputStream
     * @return readAll
     */

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private void flushFrame(Channel ch, StringBuilder data) {
        if (data.length() == 0) {
            return;
        }
        String json = data.toString();
        data.setLength(0);
        JsonNode result = extractResult(codec.readTree(json));
        codec.parseFrame(result).ifPresent(f -> emit(ch, f));
    }

    /**
     * sendForSnapshot。
     *
     * @param req ObjectNode
     * @param credential String
     * @param invocationRef String
     * @return sendForSnapshot
     */

    private CompletionStage<InvocationSnapshot> sendForSnapshot(ObjectNode req, String credential,
                                                               String invocationRef) {
        HttpRequest httpReq = base("application/json", credential, true)
                .POST(HttpRequest.BodyPublishers.ofString(codec.write(req), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    JsonNode result = extractResult(codec.readTree(resp.body()));
                    return snapshotFromFrame(invocationRef, codec.parseFrame(result).orElse(null));
                });
    }

    // ---------- 事件归一化 ----------

    private void emit(Channel ch, A2aJsonCodec.Frame f) {
        if (f == null) {
            return;
        }
        if (f.taskId() != null && ch.taskRef == null) {
            ch.taskRef = f.taskId();
            ch.contextId = f.contextId();
            byTaskRef.put(f.taskId(), ch);
            submit(ch, new InvocationEvent.Accepted(ch.invocationRef, f.taskId(), f.contextId()));
        }
        if (f.state() == null) {
            if (f.text() != null) {
                submit(ch, new InvocationEvent.ContentDelta(ch.invocationRef, f.text()));
            }
            return;
        }
        ch.lastState = f.state();
        switch (f.state()) {
            case COMPLETED -> {
                submit(ch, new InvocationEvent.Completed(ch.invocationRef, f.text()));
                terminate(ch);
            }
            case FAILED -> {
                String code = (f.errorCode() != null) ? f.errorCode() : "agent_error";
                submit(ch, new InvocationEvent.Failed(ch.invocationRef, code, f.errorMessage()));
                terminate(ch);
            }
            case CANCELED, REJECTED -> {
                submit(ch, new InvocationEvent.StatusChanged(ch.invocationRef, f.state(), true));
                terminate(ch);
            }
            case INPUT_REQUIRED -> {
                submit(ch, new InvocationEvent.StatusChanged(ch.invocationRef, TaskState.INPUT_REQUIRED, false));
                A2aJsonCodec.Interrupt it = f.interrupt();
                // 006 §3.3：INPUT_REQUIRED 无 _interrupt / 非 client_tool → InputRequired(null)，
                // 触发业务侧 continueInput。有 _interrupt 时按其类型分发（user_input 同样走 InputRequired(null)）。
                if (it == null) {
                    submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, null, null));
                } else if (it.userInput()) {
                    submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, null, it.prompt()));
                } else {
                    Duration dl = (it.deadlineMs() != null) ? Duration.ofMillis(it.deadlineMs()) : null;
                    InvocationEvent.ToolCall call = new InvocationEvent.ToolCall(
                            it.toolCallId(), it.toolName(), it.arguments(), dl);
                    submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, call, null));
                }
            }
            default -> submit(ch, new InvocationEvent.StatusChanged(ch.invocationRef, f.state(), false));
        }
    }

    private void submit(Channel ch, InvocationEvent event) {
        if (!ch.publisher.isClosed()) {
            ch.publisher.submit(event);
        }
    }

    private void terminate(Channel ch) {
        ch.terminal.set(true);
        if (ch.taskRef != null) {
            byTaskRef.remove(ch.taskRef);
        }
        byInvocationRef.remove(ch.invocationRef);
        if (!ch.publisher.isClosed()) {
            ch.publisher.close();
        }
    }

    private void failChannel(Channel ch, CompletableFuture<InvocationSnapshot> ack, Throwable ex) {
        if (ack != null) {
            ack.completeExceptionally(ex);
        }
        if (!ch.terminal.get() && !ch.publisher.isClosed()) {
            ch.publisher.closeExceptionally(ex);
        }
    }

    /**
     * workingSnapshot。
     *
     * @param ch Channel
     * @return workingSnapshot
     */

    private InvocationSnapshot workingSnapshot(Channel ch) {
        TaskState st = (ch.lastState != null) ? ch.lastState : TaskState.WORKING;
        return new InvocationSnapshot(ch.invocationRef, st, st.isTerminal(), ch.taskRef, null, null, null, null);
    }

    /**
     * snapshotFromFrame。
     *
     * @param invocationRef String
     * @param f A2aJsonCodec.Frame
     * @return snapshotFromFrame
     */

    private InvocationSnapshot snapshotFromFrame(String invocationRef, A2aJsonCodec.Frame f) {
        String ref = (invocationRef != null) ? invocationRef : (f != null ? f.taskId() : null);
        TaskState st = (f != null && f.state() != null) ? f.state() : TaskState.UNKNOWN;
        InvocationEvent.ToolCall pending = null;
        if (f != null && f.interrupt() != null && !f.interrupt().userInput()) {
            A2aJsonCodec.Interrupt it = f.interrupt();
            Duration dl = (it.deadlineMs() != null) ? Duration.ofMillis(it.deadlineMs()) : null;
            pending = new InvocationEvent.ToolCall(it.toolCallId(), it.toolName(), it.arguments(), dl);
        }
        return new InvocationSnapshot(ref, st, st.isTerminal(),
                (f != null) ? f.taskId() : null, pending,
                (f != null) ? f.text() : null,
                (f != null) ? f.errorCode() : null,
                (f != null) ? f.errorMessage() : null);
    }

    /**
     * resolveInvocationRef。
     *
     * @param taskRef String
     * @return resolveInvocationRef
     */

    private String resolveInvocationRef(String taskRef) {
        Channel ch = byTaskRef.get(taskRef);
        return (ch != null) ? ch.invocationRef : taskRef;
    }

    /**
     * extractResult。
     *
     * @param root JsonNode
     * @return extractResult
     */

    private static JsonNode extractResult(JsonNode root) {
        if (root.has("error") && !root.path("error").isNull()) {
            JsonNode err = root.get("error");
            throw new A2aTransportException("JSON-RPC error: "
                    + err.path("code").asInt() + " " + err.path("message").asText());
        }
        return root.has("result") ? root.get("result") : root;
    }

    /**
     * 布尔结果。
     *
     * @param e Throwable
     * @return 布尔结果
     */

    private static boolean isHardFailure(Throwable e) {
        // 读到流末尾/连接优雅关闭视为正常（等待续传）；其余按硬失败处理。
        String msg = String.valueOf(e.getMessage());
        return !(e instanceof java.io.EOFException) && !msg.contains("closed") && !msg.contains("EOF");
    }

    /**
     * 一次调用对应的通道：承载事件发布者与 taskRef 映射，可跨多段 SSE/单发续传复用。
     */
    private static final class Channel {
        final String invocationRef;
        final SubmissionPublisher<InvocationEvent> publisher =
                new SubmissionPublisher<>(Runnable::run, Flow.defaultBufferSize());
        volatile String taskRef;
        volatile String contextId;
        volatile TaskState lastState;
        final AtomicBoolean terminal = new AtomicBoolean(false);

        Channel(String invocationRef) {
            this.invocationRef = invocationRef;
        }
    }

    /**
     * 首个订阅者到达后才真正发起 HTTP，避免事件早于订阅而丢失。
     */
    private static final class LazyStartPublisher implements Flow.Publisher<InvocationEvent> {
        private final SubmissionPublisher<InvocationEvent> delegate;
        private final Runnable start;
        private final AtomicBoolean started = new AtomicBoolean(false);

        LazyStartPublisher(SubmissionPublisher<InvocationEvent> delegate, Runnable start) {
            this.delegate = delegate;
            this.start = start;
        }
        @Override
        public void subscribe(Flow.Subscriber<? super InvocationEvent> subscriber) {
            delegate.subscribe(subscriber);
            if (started.compareAndSet(false, true)) {
                start.run();
            }
        }
    }
}