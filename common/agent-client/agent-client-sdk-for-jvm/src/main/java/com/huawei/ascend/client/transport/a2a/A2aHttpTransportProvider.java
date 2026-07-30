/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.a2a;

import com.huawei.ascend.client.api.ErrorCodes;
import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationMode;
import com.huawei.ascend.client.api.InvocationSnapshot;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认传输实现：A2A JSON-RPC 2.0 over HTTP + SSE（基于 JDK 内置 {@link HttpClient}，无额外网络框架）。
 *
 * <p><b>承载形态（v0730 冻结）</b>：创建走 {@code SendStreamingMessage}（SSE）；一切续跑
 * （端侧工具结果 / {@code continueInput}）走同步 {@code SendMessage}，响应为单次 JSON。
 * 状态查询走 {@code GetTask}。{@code CancelTask} / {@code SubscribeToTask} 延至后续版本，本类不实现。
 * 每一次出站 HTTP 都附带 {@code Authorization: Bearer <token>}。
 *
 * <p><b>续跑帧的归属</b>：由 {@link TransportProvider.ResumeDelivery} 决定。工具结果续跑属同一 invocation，
 * 响应帧汇入原调用事件流；{@code continueInput} 产生业务可见的新 invocation，响应帧<b>不</b>汇入原流，
 * 而是作为返回快照驱动新 invocation。
 *
 * <p><b>SSE 断连语义</b>（FEAT-006 §5.1.4）：中断<b>不等于</b> Task 失败。本实现按最后观测状态判别：
 * <ul>
 * <li>已到终态 —— 正常结束。</li>
 * <li>处于 {@code INPUT_REQUIRED} —— 服务端按约定关流，保持通道开放等待续跑，不做任何处置。</li>
 * <li>其余非终态 —— 视为非预期中断，先用 {@code GetTask} 主动查询确认真实状态；
 * 查询能给出确定状态就据此投影（多数断连由此完全恢复），否则投递
 * {@link InvocationEvent.ProgressUncertain} 并正常结算，<b>既不伪造终态也不悬挂</b>。</li>
 * <li>尚未取得 {@code taskRef}（创建未确认）—— 走 UNKNOWN 恢复：以同幂等键、同正文重发创建，
 * 由网关幂等回放取回原 Task，不产生重复 Task。</li>
 * </ul>
 *
 * @since 2026-07-27
 */
public final class A2aHttpTransportProvider implements TransportProvider {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(A2aHttpTransportProvider.class.getName());

    /** SSE 读空闲超时：超过该时长没有任何字节到达即判为连接已失效，避免调用方永久悬挂。 */
    private static final Duration DEFAULT_SSE_IDLE_TIMEOUT = Duration.ofSeconds(120);

    /** 同步请求超时。 */
    private static final Duration UNARY_TIMEOUT = Duration.ofSeconds(60);

    /** UNKNOWN 恢复的最大重发次数（含首次恢复尝试）。耗尽后投递进展不确定，不无限重试。 */
    private static final int MAX_CREATE_RECOVERY_ATTEMPTS = 3;

    /** BLOCKING 模式下轮询 GetTask 的最大次数。 */
    private static final int MAX_BLOCKING_POLLS = 60;

    /** BLOCKING 模式下轮询间隔。 */
    private static final Duration BLOCKING_POLL_INTERVAL = Duration.ofSeconds(1);

    private final URI endpoint;
    private final HttpClient http;
    private final A2aJsonCodec codec;
    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;
    private final Duration sseIdleTimeout;

    private final ConcurrentMap<String, Channel> byInvocationRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Channel> byTaskRef = new ConcurrentHashMap<>();

    /**
     * 构造传输实现（使用默认 ObjectMapper 与 SSE 读空闲超时）。
     *
     * @param baseUrl 网关基址；自动补齐 {@code /a2a} 后缀
     */
    public A2aHttpTransportProvider(String baseUrl) {
        this(baseUrl, new ObjectMapper(), DEFAULT_SSE_IDLE_TIMEOUT);
    }

    /**
     * 构造传输实现（使用默认 SSE 读空闲超时）。
     *
     * @param baseUrl 网关基址；自动补齐 {@code /a2a} 后缀
     * @param mapper JSON 编解码器
     */
    public A2aHttpTransportProvider(String baseUrl, ObjectMapper mapper) {
        this(baseUrl, mapper, DEFAULT_SSE_IDLE_TIMEOUT);
    }

    /**
     * 构造传输实现。
     *
     * @param baseUrl 网关基址；自动补齐 {@code /a2a} 后缀
     * @param mapper JSON 编解码器
     * @param sseIdleTimeout SSE 读空闲超时
     */
    public A2aHttpTransportProvider(String baseUrl, ObjectMapper mapper, Duration sseIdleTimeout) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(normalized.endsWith("/a2a") ? normalized : normalized + "/a2a");
        this.sseIdleTimeout = sseIdleTimeout;
        this.io = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonFactory("a2a-transport-io"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("a2a-transport-watchdog"));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(io)
                .build();
        this.codec = new A2aJsonCodec(mapper);
    }

    private static java.util.concurrent.ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName(name);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> {
                // best-effort：IO/看护线程未捕获异常不打断传输层主流程，仅记录日志。
                LOG.log(java.util.logging.Level.WARNING,
                        "uncaught exception in transport thread " + thread.getName(), ex);
            });
            return t;
        };
    }

    @Override
    public Flow.Publisher<InvocationEvent> createAndStream(CreateCommand cmd) {
        ObjectNode req = codec.buildCreate(cmd);
        // 原始创建正文保存下来：UNKNOWN 恢复必须逐字节复用同一正文，否则会命中网关幂等正文冲突。
        String body = codec.write(req);
        Channel ch = new Channel(cmd.invocationRef(), cmd.conversationId(),
                cmd.idempotencyKey() != null ? cmd.idempotencyKey() : cmd.invocationId(),
                cmd.mode(), body, cmd.credentialToken(), io);
        byInvocationRef.put(cmd.invocationRef(), ch);
        Runnable start = () -> {
            if (cmd.mode() == InvocationMode.STREAMING) {
                openSse(ch, body, cmd.credentialToken());
            } else {
                startUnaryCreate(ch, body, cmd.credentialToken());
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
            return failedFuture(new A2aTransportException(
                    "no active channel for taskRef " + cmd.taskRef(), null,
                    ErrorCodes.STREAM_INTERRUPTED, 0, false));
        }
        String credential = (cmd.credentialToken() != null) ? cmd.credentialToken() : ch.credential;
        String body = codec.write(codec.buildResume(cmd));
        // 帧归属（FRZ-1）：只有仍属原 invocation 的续跑才汇入原事件流；
        // continueInput 新建的 invocation 只取返回快照，否则会把事件投到错误的调用上。
        boolean intoStream = cmd.delivery() != ResumeDelivery.SNAPSHOT_ONLY;
        Channel sink = intoStream ? ch : null;
        String snapshotRef = intoStream ? ch.invocationRef : cmd.invocationRef();
        return sendUnary(ch, sink, body, credential, snapshotRef);
    }

    @Override
    public CompletionStage<InvocationSnapshot> getTask(String taskRef, String credentialToken) {
        Channel ch = byTaskRef.get(taskRef);
        String credential = (credentialToken != null) ? credentialToken : (ch != null ? ch.credential : null);
        String ref = (ch != null) ? ch.invocationRef : taskRef;
        return sendForSnapshot(codec.buildGet(taskRef), credential, ref);
    }

    @Override
    public void close() {
        for (Channel ch : byInvocationRef.values()) {
            ch.cancelWatchdog();
            if (!ch.publisher.isClosed()) {
                ch.publisher.close();
            }
        }
        byInvocationRef.clear();
        byTaskRef.clear();
        scheduler.shutdownNow();
        io.shutdownNow();
    }

    // ---------- HTTP ----------

    private HttpRequest.Builder base(String accept, String credential, boolean withTimeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", accept);
        if (withTimeout) {
            b.timeout(UNARY_TIMEOUT);
        }
        if (credential != null && !credential.isEmpty()) {
            b.header("Authorization", credential.startsWith("Bearer ") ? credential : "Bearer " + credential);
        }
        return b;
    }

    /**
     * 同步 {@code SendMessage}：解析响应为该 Task 的<b>完整</b>下一状态快照。
     *
     * @param ch 用于 taskRef 绑定与失败传播的通道
     * @param sink 事件汇入目标；为 null 表示本次响应帧不进入任何事件流
     * @param body 请求正文
     * @param credential 凭据
     * @param snapshotRef 返回快照使用的 invocationRef
     * @return 完整下一状态快照
     */
    private CompletionStage<InvocationSnapshot> sendUnary(Channel ch, Channel sink, String body,
                                                         String credential, String snapshotRef) {
        HttpRequest req = base("application/json", credential, true)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        CompletableFuture<InvocationSnapshot> ack = new CompletableFuture<>();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        A2aTransportException e = A2aTransportException.network(
                                "resume request failed: " + rootMessage(ex), ex);
                        ack.completeExceptionally(e);
                        failStream(sink, e);
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        A2aTransportException e = governanceError(resp.statusCode(), resp.body());
                        ack.completeExceptionally(e);
                        failStream(sink, e);
                        return;
                    }
                    try {
                        JsonNode result = extractResult(codec.readTree(resp.body()));
                        A2aJsonCodec.Frame f = codec.parseFrame(result).orElse(null);
                        bindTaskRef(ch, f);
                        if (sink != null) {
                            emit(sink, f);
                        } else if (f != null && f.state() != null && f.state().isTerminal()) {
                            // 快照驱动的续跑走到终态：通道再无用处，及时释放，避免 taskRef 映射堆积。
                            releaseChannel(ch);
                        } else {
                            // 快照驱动的续跑未到终态：保留通道，等待后续续跑推进。
                        }
                        ack.complete(snapshotFromFrame(snapshotRef, f));
                    } catch (A2aTransportException | IllegalArgumentException e) {
                        ack.completeExceptionally(e);
                        failStream(sink, e);
                    }
                });
        return ack;
    }

    /**
     * 非流式创建（{@code BLOCKING} / {@code ASYNC}）：单次 {@code SendMessage} 取回首个状态。
     *
     * <p>{@code BLOCKING} 语义要求"一次调用拿到最终结果"，若首个响应仍是非终态，则用
     * {@code GetTask} 有界轮询推进到终态或等待输入点。<b>禁止</b>改走 SSE 再聚合成一次性响应
     * （FEAT-006 §5.1.2）。{@code ASYNC} 不轮询：受理后即返回，由调用方用
     * {@code getInvocation} 观察。
     *
     * @param ch 通道
     * @param body 请求正文
     * @param credential 凭据
     */
    private void startUnaryCreate(Channel ch, String body, String credential) {
        sendUnary(ch, ch, body, credential, ch.invocationRef).whenComplete((snap, ex) -> {
            if (ex != null || snap == null) {
                return; // 失败已由 sendUnary 传播到事件流
            }
            if (ch.terminal.get() || snap.state() == TaskState.INPUT_REQUIRED) {
                return; // 已结算或已到等待输入点
            }
            if (ch.mode == InvocationMode.BLOCKING) {
                pollUntilSettled(ch, credential, 1);
            }

            // ASYNC：受理即止，不轮询。
        });
    }

    /**
     * BLOCKING 模式的有界轮询：推进到终态或等待输入点为止。
     *
     * @param ch 通道
     * @param credential 凭据
     * @param attempt 当前尝试序号，从 1 开始
     */
    private void pollUntilSettled(Channel ch, String credential, int attempt) {
        if (ch.terminal.get() || ch.publisher.isClosed()) {
            return;
        }
        if (attempt > MAX_BLOCKING_POLLS) {
            publishUncertain(ch, "blocking poll budget exhausted after " + MAX_BLOCKING_POLLS + " attempts");
            return;
        }
        scheduler.schedule(() -> sendForSnapshot(codec.buildGet(ch.taskRef), credential, ch.invocationRef)
                .whenComplete((snap, ex) -> {
                    if (ex != null) {
                        publishUncertain(ch, "blocking poll failed: " + rootMessage(ex));
                        return;
                    }
                    projectQueriedState(ch, snap);
                    if (!ch.terminal.get() && snap.state() != TaskState.INPUT_REQUIRED) {
                        pollUntilSettled(ch, credential, attempt + 1);
                    }
                }), BLOCKING_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 把网关的 HTTP 治理错误（401/403/400/409 等）解析为带稳定 code 与可重试判定的传输异常。
     *
     * @param status HTTP 状态码
     * @param body 响应体
     * @return 已分类的传输异常
     */
    private A2aTransportException governanceError(int status, String body) {
        String code = null;
        String message = body;
        try {
            JsonNode node = codec.readTree(body);
            if (node.hasNonNull("code")) {
                code = node.get("code").asText(null);
            }
            if (node.hasNonNull("message")) {
                message = node.get("message").asText(body);
            }
        } catch (A2aTransportException ignore) {
            // 非 JSON 响应体：保留原始文本，错误码按状态码兜底。
        }
        String resolved = (code != null && !code.isBlank()) ? code : ErrorCodes.fromHttpStatus(status);
        return A2aTransportException.governance(
                "gateway rejected request [" + status + "/" + resolved + "]: " + message, resolved, status);
    }

    // ---------- SSE ----------

    private void openSse(Channel ch, String body, String credential) {
        // SSE 不能设置整体请求超时（会截断长流），改用读空闲看护，见 armWatchdog。
        HttpRequest req = base("text/event-stream", credential, false)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        onCreateTransportFailure(ch, A2aTransportException.network(
                                "stream request failed: " + rootMessage(ex), ex));
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        onCreateHttpError(ch, governanceError(resp.statusCode(), readAll(resp.body())));
                        return;
                    }

                    // 声明了 STREAMING 却拿到非流式响应：明确报错，不静默降级（FEAT-006 §5.1.4）。
                    // 否则读循环找不到任何 data: 行，表现为一条诡异的空流，问题被掩盖。
                    A2aTransportException notStreaming = rejectIfNotStreaming(resp).orElse(null);
                    if (notStreaming != null) {
                        closeQuietly(resp.body());
                        failStream(ch, notStreaming);
                        return;
                    }
                    io.execute(() -> readSse(ch, resp.body()));
                });
    }

    /**
     * 判定流式请求是否收到了非流式响应。
     *
     * <p>判据取<b>宽松</b>侧：只有 {@code Content-Type} 明确是 JSON 才判为不支持流式。
     * 缺失或未知的 {@code Content-Type} 一律继续按 SSE 读取——网关实现对该头的设置并不统一，
     * 严格要求 {@code text/event-stream} 会把本可正常工作的链路误判为故障。
     *
     * @param resp HTTP 响应
     * @return 判为非流式时返回已分类异常；否则为空
     */
    private static Optional<A2aTransportException> rejectIfNotStreaming(HttpResponse<InputStream> resp) {
        String contentType = resp.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
            return Optional.empty();
        }
        return Optional.of(new A2aTransportException(
                "STREAMING was requested but the gateway answered with a non-streaming response"
                        + " (Content-Type: " + contentType + ")",
                null, ErrorCodes.STREAMING_UNAVAILABLE, resp.statusCode(), false));
    }

    private void readSse(Channel ch, InputStream in) {
        ch.touch();
        ch.idleTimedOut.set(false);
        ScheduledFuture<?> watchdog = armWatchdog(ch, in);
        ch.watchdog = watchdog;
        Throwable failure = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                ch.touch();
                if (line.isEmpty()) {
                    flushFrame(ch, data);
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else {
                    // event: / id: / 注释行（":"开头）当前不参与语义。
                    // id: 与 Last-Event-ID 的游标续传属后续版本（网关尚未下发 id 行）。
                }
            }
            flushFrame(ch, data);
        } catch (IOException | IllegalStateException | NullPointerException e) {
            failure = e;
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
        onSseStreamEnd(ch, failure);
    }

    /**
     * 读空闲看护：超过空闲阈值仍无字节到达就强制关闭底层流，让读循环退出并进入断连处置。
     *
     * <p>没有它，服务端"半开"连接会让读循环永久阻塞，调用方的 {@code completion()} 永不结算。
     *
     * @param ch 通道
     * @param in 底层输入流
     * @return 可取消的看护任务
     */
    private ScheduledFuture<?> armWatchdog(Channel ch, InputStream in) {
        long periodMs = Math.max(1000L, sseIdleTimeout.toMillis() / 4);
        return scheduler.scheduleAtFixedRate(() -> {
            if (ch.terminal.get()) {
                return;
            }
            long idleMs = (System.nanoTime() - ch.lastActivityNanos) / 1_000_000L;
            if (idleMs >= sseIdleTimeout.toMillis()) {
                ch.idleTimedOut.set(true);
                closeQuietly(in);
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * SSE 流结束（正常关闭、空闲超时或异常）后的统一处置。
     *
     * <p>按最后观测状态判别，而<b>不是</b>靠异常消息字符串匹配——后者既不可靠，
     * 也会把服务端按约定关流误判为故障。
     *
     * @param ch 通道
     * @param failure 读取异常；正常结束为 null
     */
    private void onSseStreamEnd(Channel ch, Throwable failure) {
        if (ch.terminal.get()) {
            return; // 已在流内结算终态。
        }
        if (ch.lastState == TaskState.INPUT_REQUIRED) {
            // 约定行为：服务端在投递等待输入后关闭本段流，等待 SDK/业务续跑。不是中断。
            return;
        }
        String reason;
        if (ch.idleTimedOut.get()) {
            reason = "sse idle timeout after " + sseIdleTimeout.toSeconds() + "s";
        } else if (failure != null) {
            reason = "sse read failed: " + rootMessage(failure);
        } else {
            reason = "sse stream closed by peer before reaching a terminal or input-required state";
        }
        beginDisconnectRecovery(ch, reason);
    }

    /**
     * 非预期中断后的恢复：优先把"不确定"变回"确定"，只有确实无法确定时才对上暴露不确定。
     *
     * @param ch 通道
     * @param reason 中断原因
     */
    private void beginDisconnectRecovery(Channel ch, String reason) {
        if (ch.terminal.get() || !ch.recovering.compareAndSet(false, true)) {
            return;
        }
        if (ch.taskRef == null) {
            // 创建未确认：不能简单重发（会产生重复 Task），走同键同正文的幂等重发。
            // 次数从 recoveryAttempt 累加而非固定从 1 起，否则反复中断会无限重连。
            recoverUnconfirmedCreate(ch, reason, ch.recoveryAttempt + 1);
        } else {
            confirmByQuery(ch, reason);
        }
    }

    /**
     * 已取得 taskRef 的中断：用 {@code GetTask} 主动查询确认真实状态（FRZ-4 b3）。
     *
     * @param ch 通道
     * @param reason 中断原因
     */
    private void confirmByQuery(Channel ch, String reason) {
        sendForSnapshot(codec.buildGet(ch.taskRef), ch.credential, ch.invocationRef)
                .whenComplete((snap, ex) -> {
                    if (ex != null) {
                        publishUncertain(ch, reason + "; state query failed: " + rootMessage(ex));
                        return;
                    }
                    boolean settled = projectQueriedState(ch, snap);
                    if (!settled) {
                        publishUncertain(ch, reason + "; server reports non-terminal state " + snap.state());
                    }
                });
    }

    /**
     * 把查询到的权威状态投影为事件。
     *
     * @param ch 通道
     * @param snap 查询快照
     * @return 已进入终态或等待输入点（即已"确定"）返回 true
     */
    private boolean projectQueriedState(Channel ch, InvocationSnapshot snap) {
        TaskState st = (snap != null) ? snap.state() : TaskState.UNKNOWN;
        if (st == null || st == TaskState.UNKNOWN) {
            return false;
        }
        ch.lastState = st;
        if (st == TaskState.COMPLETED) {
            submit(ch, new InvocationEvent.Completed(ch.invocationRef, snap.outputText()));
            terminate(ch);
            return true;
        }
        if (st == TaskState.FAILED) {
            String code = (snap.errorCode() != null) ? snap.errorCode() : ErrorCodes.AGENT_ERROR;
            submit(ch, new InvocationEvent.Failed(ch.invocationRef, code, snap.message()));
            terminate(ch);
            return true;
        }
        if (st.isTerminal()) {
            submit(ch, new InvocationEvent.StatusChanged(ch.invocationRef, st, true));
            terminate(ch);
            return true;
        }
        if (st == TaskState.INPUT_REQUIRED) {
            // 恢复到等待点：保持通道开放，允许 SDK 自动工具续跑或业务 continueInput。
            submit(ch, new InvocationEvent.StatusChanged(ch.invocationRef, TaskState.INPUT_REQUIRED, false));
            submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, snap.pendingToolCall(), null));
            ch.recovering.set(false);
            return true;
        }
        return false;
    }

    /**
     * 创建未确认时的 UNKNOWN 恢复：以同幂等键、逐字节相同的正文重发创建。
     *
     * <p>网关按 {@code params.message.messageId} 去重，命中回放即取回原 Task，不会产生重复 Task。
     * 若网关回 {@code IDEMPOTENCY_IN_FLIGHT}（同键同正文仍在途），退避后<b>用同键</b>再试。
     *
     * @param ch 通道
     * @param reason 中断原因
     * @param attempt 当前尝试序号，从 1 开始
     */
    private void recoverUnconfirmedCreate(Channel ch, String reason, int attempt) {
        if (attempt > MAX_CREATE_RECOVERY_ATTEMPTS) {
            publishUncertain(ch, reason + "; idempotent create retry exhausted after "
                    + MAX_CREATE_RECOVERY_ATTEMPTS + " attempts");
            return;
        }
        long backoffMs = 200L * (1L << (attempt - 1));
        scheduler.schedule(() -> {
            ch.recoveryAttempt = attempt;
            // 重发前释放在途守卫：本次重发若再次中断，仍能进入下一轮恢复。
            // 不释放会导致后续中断被静默丢弃，事件流永不关闭，调用方的 completion() 永久悬挂。
            // 无限重连由上面的 attempt 上限兜住。
            ch.recovering.set(false);
            if (ch.mode == InvocationMode.STREAMING) {
                openSse(ch, ch.createBody, ch.credential);
            } else {
                startUnaryCreate(ch, ch.createBody, ch.credential);
            }
        }, backoffMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 创建请求的网络失败：尚未确认是否已建 Task，走幂等重发；已确认过则按中断处置。
     *
     * @param ch 通道
     * @param e 已分类异常
     */
    private void onCreateTransportFailure(Channel ch, A2aTransportException e) {
        // 统一走恢复入口：内部按有无 taskRef 分流（幂等重发 / 查询确认），并统一做在途去重与次数兜底。
        beginDisconnectRecovery(ch, "create stream failed: " + e.getMessage());
    }

    /**
     * 创建请求被网关以 HTTP 治理错误拒绝。
     *
     * <p>{@code IDEMPOTENCY_IN_FLIGHT} 是<b>可重试</b>的：同键同正文的前一次创建仍在途，
     * 退避后用同键再试即可。其余治理错误是<b>确定</b>失败（鉴权/参数/正文冲突），直接暴露，
     * 不得投影为"进展不确定"。
     *
     * @param ch 通道
     * @param e 已分类异常
     */
    private void onCreateHttpError(Channel ch, A2aTransportException e) {
        if (e.retryable()) {
            beginDisconnectRecovery(ch, "gateway reports " + e.code());
            return;
        }
        failStream(ch, e);
    }

    /**
     * 投递"进展不确定"并正常结算：不伪造终态、不判失败、不悬挂（FRZ-4 b1）。
     *
     * @param ch 通道
     * @param reason 原因
     */
    private void publishUncertain(Channel ch, String reason) {
        if (ch.terminal.getAndSet(true)) {
            return;
        }
        TaskState last = (ch.lastState != null) ? ch.lastState : TaskState.UNKNOWN;
        submit(ch, new InvocationEvent.ProgressUncertain(ch.invocationRef, last, reason));
        unregister(ch);
        ch.cancelWatchdog();
        if (!ch.publisher.isClosed()) {
            ch.publisher.close();
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignore) {
            // 强制关闭是为了打断阻塞读，关闭本身失败无需处理。
        }
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cur.getClass().getSimpleName();
    }

    private void flushFrame(Channel ch, StringBuilder data) {
        if (data.length() == 0) {
            return;
        }
        String json = data.toString();
        data.setLength(0);
        JsonNode result = extractResult(codec.readTree(json));
        A2aJsonCodec.Frame f = codec.parseFrame(result).orElse(null);
        bindTaskRef(ch, f);
        emit(ch, f);
    }

    private CompletionStage<InvocationSnapshot> sendForSnapshot(ObjectNode req, String credential,
                                                               String invocationRef) {
        HttpRequest httpReq = base("application/json", credential, true)
                .POST(HttpRequest.BodyPublishers.ofString(codec.write(req), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw governanceError(resp.statusCode(), resp.body());
                    }
                    JsonNode result = extractResult(codec.readTree(resp.body()));
                    return snapshotFromFrame(invocationRef, codec.parseFrame(result).orElse(null));
                });
    }

    private static <T> CompletableFuture<T> failedFuture(Throwable t) {
        CompletableFuture<T> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    // ---------- 事件归一化 ----------

    /**
     * 首次看到 taskId 时建立 taskRef 绑定并投递受理事件。恢复重连时不重复投递。
     *
     * @param ch 通道
     * @param f 解析帧
     */
    private void bindTaskRef(Channel ch, A2aJsonCodec.Frame f) {
        if (f == null || f.taskId() == null || ch.taskRef != null) {
            return;
        }
        ch.taskRef = f.taskId();
        ch.contextId = f.contextId();
        byTaskRef.put(f.taskId(), ch);
        submit(ch, new InvocationEvent.Accepted(ch.invocationRef, f.taskId(), f.contextId()));
    }

    private void emit(Channel ch, A2aJsonCodec.Frame f) {
        if (f == null) {
            return;
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
                String code = (f.errorCode() != null) ? f.errorCode() : ErrorCodes.AGENT_ERROR;
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
                // 触发业务侧 continueInput。有 client_tool 意图时带上 ToolCall 由 SDK 自动执行。
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
        unregister(ch);
        ch.cancelWatchdog();
        if (!ch.publisher.isClosed()) {
            ch.publisher.close();
        }
    }

    /**
     * 释放通道但不向其事件流投递任何内容（用于快照驱动的续跑走到终态）。
     *
     * @param ch 通道
     */
    private void releaseChannel(Channel ch) {
        ch.terminal.set(true);
        unregister(ch);
        ch.cancelWatchdog();
        if (!ch.publisher.isClosed()) {
            ch.publisher.close();
        }
    }

    private void unregister(Channel ch) {
        if (ch.taskRef != null) {
            byTaskRef.remove(ch.taskRef);
        }
        byInvocationRef.remove(ch.invocationRef);
    }

    private void failStream(Channel ch, Throwable ex) {
        if (ch == null) {
            return;
        }
        ch.cancelWatchdog();
        if (!ch.terminal.getAndSet(true) && !ch.publisher.isClosed()) {
            unregister(ch);
            ch.publisher.closeExceptionally(ex);
        }
    }

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
     * 解出 JSON-RPC 的 {@code result}，遇 {@code error} 抛出已分类异常。
     *
     * @param root 响应根节点
     * @return result 节点
     */
    private static JsonNode extractResult(JsonNode root) {
        if (root.has("error") && !root.path("error").isNull()) {
            JsonNode err = root.get("error");
            int rpcCode = err.path("code").asInt();
            String message = err.path("message").asText();
            // JSON-RPC 层错误一律不可重试：方法不支持、Task 不存在、已到终态都不会因重试改变。
            String code = switch (rpcCode) {
                case -32601 -> ErrorCodes.METHOD_NOT_SUPPORTED;
                case -32001 -> ErrorCodes.TASK_NOT_FOUND;
                default -> "JSONRPC_" + rpcCode;
            };
            throw new A2aTransportException(
                    "JSON-RPC error: " + rpcCode + " " + message, null, code, 0, false);
        }
        return root.has("result") ? root.get("result") : root;
    }

    /**
     * 一次调用对应的通道：承载事件发布者、taskRef 映射与恢复所需上下文，可跨多段 SSE/单发续跑复用。
     */
    private static final class Channel {
        final String invocationRef;
        final String conversationId;
        final String idempotencyKey;
        final InvocationMode mode;

        /** 原始创建正文；UNKNOWN 恢复必须逐字节复用，否则触发网关幂等正文冲突。 */
        final String createBody;
        final String credential;
        final SubmissionPublisher<InvocationEvent> publisher;
        volatile String taskRef;
        volatile String contextId;
        volatile TaskState lastState;
        volatile long lastActivityNanos = System.nanoTime();
        volatile int recoveryAttempt;
        volatile ScheduledFuture<?> watchdog;
        final AtomicBoolean terminal = new AtomicBoolean(false);
        final AtomicBoolean recovering = new AtomicBoolean(false);
        final AtomicBoolean idleTimedOut = new AtomicBoolean(false);

        Channel(String invocationRef, String conversationId, String idempotencyKey, InvocationMode mode,
                String createBody, String credential, ExecutorService deliveryExecutor) {
            this.invocationRef = invocationRef;
            this.conversationId = conversationId;
            this.idempotencyKey = idempotencyKey;
            this.mode = mode;
            this.createBody = createBody;
            this.credential = credential;
            // 事件投递放到 IO 线程池，避免订阅者的处理逻辑跑在 SSE 读线程上把读取拖停。
            this.publisher = new SubmissionPublisher<>(deliveryExecutor, Flow.defaultBufferSize());
        }

        void touch() {
            lastActivityNanos = System.nanoTime();
        }

        void cancelWatchdog() {
            ScheduledFuture<?> w = watchdog;
            if (w != null) {
                w.cancel(false);
            }
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
