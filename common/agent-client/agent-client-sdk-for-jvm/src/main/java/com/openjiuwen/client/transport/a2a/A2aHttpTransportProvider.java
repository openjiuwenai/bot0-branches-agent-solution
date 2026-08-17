/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.ErrorCodes;
import com.openjiuwen.client.api.ClassifiedError;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.ObservationTimeoutException;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.api.calltree.CallTreeSnapshot;
import com.openjiuwen.client.transport.spi.CallTreeTransportProvider;
import com.openjiuwen.client.transport.spi.InvocationOutputTransportProvider;
import com.openjiuwen.client.transport.spi.TransportProvider;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
import java.util.concurrent.atomic.AtomicReference;

/**
 * 默认传输实现：A2A JSON-RPC 2.0 over HTTP + SSE（基于 JDK 内置 {@link HttpClient}，无额外网络框架）。
 *
 * <p><b>承载形态（v0730 冻结）</b>：创建走 {@code SendStreamingMessage}（SSE）；续跑 wire method
 * 沿用首轮 mode（FEAT-006 §47）：首轮 STREAMING 的续跑走 {@code SendStreamingMessage}（SSE），
 * 首轮 BLOCKING/ASYNC 的续跑走 unary {@code SendMessage}（单次 JSON），
 * 后者由 {@code params.configuration.returnImmediately} 决定受理即返或等待本轮结果。
 * 状态查询走 {@code GetTask}。Runtime STREAMING 在已知 taskId 后可用
 * {@code SubscribeToTask} 恢复当前状态并继续接收新事件；{@code CancelTask} 本版本不实现。
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
public class A2aHttpTransportProvider
        implements TransportProvider, CallTreeTransportProvider, InvocationOutputTransportProvider {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(A2aHttpTransportProvider.class.getName());

    /**
     * SSE 读空闲超时：超过该时长没有任何字节到达即判为连接已失效，避免调用方永久悬挂。
     */
    private static final Duration DEFAULT_SSE_IDLE_TIMEOUT = Duration.ofSeconds(120);

    /**
     * 同步请求超时。
     */
    private static final Duration UNARY_TIMEOUT = Duration.ofSeconds(60);

    /**
     * BLOCKING 从首次获得 taskId 起的最长自动观察时间。
     */
    private static final Duration DEFAULT_BLOCKING_OBSERVATION_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 有效非终态快照之间的查询间隔；不限制有效 WORKING 返回次数。
     */
    private static final Duration DEFAULT_BLOCKING_POLL_INTERVAL = Duration.ofMillis(500);

    /**
     * UNKNOWN 恢复的最大重发次数（含首次恢复尝试）。耗尽后投递进展不确定，不无限重试。
     */
    private static final int MAX_CREATE_RECOVERY_ATTEMPTS = 3;
    private static final int MAX_OBSERVATION_RECOVERY_FAILURES = 3;
    private static final int MAX_COMPLETED_TREES = 256;

    private final URI endpoint;
    private final HttpClient http;
    private final A2aJsonCodec codec;
    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;
    private final Duration sseIdleTimeout;
    private final Duration blockingObservationTimeout;
    private final Duration blockingPollInterval;
    private final EndpointPolicy endpointPolicy;

    private final ConcurrentMap<String, Channel> byInvocationRef = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Channel> byTaskRef = new ConcurrentHashMap<>();
    private final Map<String, CallTreeSnapshot> completedTrees = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CallTreeSnapshot> eldest) {
                    return size() > MAX_COMPLETED_TREES;
                }
            });
    private final Map<String, String> completedOutputs = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_COMPLETED_TREES;
                }
            });

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
        this(baseUrl, mapper, sseIdleTimeout, GatewayEndpointPolicy.INSTANCE);
    }

    A2aHttpTransportProvider(String baseUrl, ObjectMapper mapper, Duration sseIdleTimeout,
            EndpointPolicy endpointPolicy) {
        this(baseUrl, mapper, sseIdleTimeout, endpointPolicy,
                DEFAULT_BLOCKING_OBSERVATION_TIMEOUT, DEFAULT_BLOCKING_POLL_INTERVAL);
    }

    A2aHttpTransportProvider(String baseUrl, ObjectMapper mapper, Duration sseIdleTimeout,
            EndpointPolicy endpointPolicy, Duration blockingObservationTimeout, Duration blockingPollInterval) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(normalized.endsWith("/a2a") ? normalized : normalized + "/a2a");
        this.sseIdleTimeout = sseIdleTimeout;
        this.blockingObservationTimeout = blockingObservationTimeout;
        this.blockingPollInterval = blockingPollInterval;
        this.endpointPolicy = endpointPolicy;
        this.io = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), daemonFactory("a2a-transport-io"));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("a2a-transport-watchdog"));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(io)
                .build();
        this.codec = new A2aJsonCodec(mapper);
    }

    static Duration defaultIdleTimeout() {
        return DEFAULT_SSE_IDLE_TIMEOUT;
    }

    /**
     * 包级测试探针：活动 invocation Channel 数。
     */
    int activeInvocationCount() {
        return byInvocationRef.size();
    }

    /**
     * 包级测试探针：活动 taskId Channel 数。
     */
    int activeTaskCount() {
        return byTaskRef.size();
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
        CreateCommand effective = endpointPolicy.createCommand(cmd);
        ObjectNode req = codec.buildCreate(effective);
        // 原始创建正文保存下来：UNKNOWN 恢复必须逐字节复用同一正文，否则会命中网关幂等正文冲突。
        String body = codec.write(req);
        Channel ch = new Channel(effective.invocationRef(), effective.conversationId(),
                effective.idempotencyKey() != null ? effective.idempotencyKey() : effective.invocationId(),
                effective.mode(), body, effective.credentialToken(), io);
        byInvocationRef.put(effective.invocationRef(), ch);
        Runnable start = () -> {
            if (effective.mode() == InvocationMode.STREAMING) {
                openSse(ch, body, effective.credentialToken());
            } else {
                startUnaryCreate(ch, body, effective.credentialToken());
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
        Channel resolvedChannel = ch;
        String credential = (cmd.credentialToken() != null) ? cmd.credentialToken() : ch.credential;
        String body = codec.write(codec.buildResume(cmd));
        // 帧归属（FRZ-1）：只有仍属原 invocation 的续跑才汇入原事件流；
        // continueInput 新建的 invocation 只取返回快照，否则会把事件投到错误的调用上。
        boolean intoStream = cmd.delivery() != ResumeDelivery.SNAPSHOT_ONLY;
        Channel sink = intoStream ? ch : null;
        String snapshotRef = intoStream ? ch.invocationRef : cmd.invocationRef();
        // 续轮 mode 继承首轮：STREAMING 续跑走 SSE（SendStreamingMessage），其他走 unary SendMessage。
        if (cmd.mode() == InvocationMode.STREAMING) {
            return sendResumeStreaming(ch, sink, body, credential, snapshotRef);
        }
        return sendUnary(resolvedChannel, sink, body, credential, snapshotRef).thenCompose(snapshot -> {
            if (cmd.mode() != InvocationMode.BLOCKING
                    || snapshot == null || snapshot.state() == TaskState.INPUT_REQUIRED
                    || snapshot.terminal() || resolvedChannel.taskRef == null) {
                return CompletableFuture.completedFuture(snapshot);
            }
            // BLOCKING 续轮与首次创建保持同一有界观察契约；ASYNC 只返回当前快照，
            // 后续由业务显式 getInvocation 驱动。
            resolvedChannel.lastState = snapshot.state();
            resolvedChannel.blockingObservationStartedNanos = 0L;
            CompletableFuture<InvocationSnapshot> terminal = new CompletableFuture<>();
            beginBlockingObservation(resolvedChannel, snapshotRef, terminal);
            return terminal;
        });
    }

    @Override
    public CompletionStage<InvocationSnapshot> getTask(String taskRef, String credentialToken) {
        Channel ch = byTaskRef.get(taskRef);
        String credential = (credentialToken != null) ? credentialToken : (ch != null ? ch.credential : null);
        String ref = (ch != null) ? ch.invocationRef : taskRef;
        Channel channel = ch;
        return sendForSnapshot(codec.buildGet(taskRef), credential, ref, channel).thenApply(snapshot -> {
            if (channel != null && snapshot != null && (snapshot.terminal()
                    || snapshot.state() == TaskState.INPUT_REQUIRED)) {
                projectQueriedState(channel, snapshot);
            }
            return snapshot;
        });
    }

    @Override
    public void closeObservation(String invocationRef) {
        Channel ch = byInvocationRef.get(invocationRef);
        if (ch != null) {
            cancelLocalObservation(ch);
        }
    }

    @Override
    public Flow.Publisher<CallTreeSnapshot> callTree(String invocationRef) {
        Channel channel = byInvocationRef.get(invocationRef);
        if (channel == null || channel.callTree == null) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    subscriber.onComplete();
                }

                @Override
                public void cancel() {
                }
            });
        }
        return channel.callTree.publisher();
    }

    @Override
    public Optional<CallTreeSnapshot> currentCallTree(String invocationRef) {
        Channel channel = byInvocationRef.get(invocationRef);
        return channel == null ? Optional.ofNullable(completedTrees.get(invocationRef))
                : channel.callTree == null ? Optional.empty() : channel.callTree.current();
    }

    @Override
    public Optional<String> currentOutputText(String invocationRef) {
        Channel channel = byInvocationRef.get(invocationRef);
        return channel == null
                ? Optional.ofNullable(completedOutputs.get(invocationRef))
                : channel.rootOutput.currentText();
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
        completedTrees.clear();
        completedOutputs.clear();
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
        String effectiveCredential = endpointPolicy.credential(credential).orElse(null);
        if (effectiveCredential != null && !effectiveCredential.isEmpty()) {
            b.header("Authorization", effectiveCredential.startsWith("Bearer ")
                    ? effectiveCredential : "Bearer " + effectiveCredential);
        }
        return b;
    }

    /**
     * 流式续跑（首轮 STREAMING 的工具结果/用户输入续跑）：发 {@code SendStreamingMessage} + SSE，
     * 把响应帧折叠成单个下一状态快照结算返回 future。
     *
     * <p>帧归属同 {@link #sendUnary}：{@code sink} 非空时帧同时汇入原调用事件流（工具结果续跑场景，
     * 业务侧看到连续流）；{@code sink} 为 null 时帧只驱动返回快照（continueInput 场景）。
     * 终态/INPUT_REQUIRED 即结算 future 并按需释放通道；中途断连走 {@code GetTask} 查询兜底。
     *
     * @param ch 用于 taskRef 绑定与失败传播的通道
     * @param sink 事件汇入目标；为 null 表示本次响应帧不进入任何事件流
     * @param body 请求正文
     * @param credential 凭据
     * @param snapshotRef 返回快照使用的 invocationRef
     * @return 完整下一状态快照
     */
    private CompletionStage<InvocationSnapshot> sendResumeStreaming(Channel ch, Channel sink, String body,
                                                                    String credential, String snapshotRef) {
        CompletableFuture<InvocationSnapshot> ack = new CompletableFuture<>();
        HttpRequest req = base("text/event-stream", credential, false)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((resp, ex) -> {
                    if (ex != null) {
                        A2aTransportException e = A2aTransportException.network(
                                "streaming resume request failed: " + rootMessage(ex), ex);
                        ack.completeExceptionally(e);
                        failStream(sink, e);
                        return;
                    }
                    if (resp.statusCode() / 100 != 2) {
                        A2aTransportException e = governanceError(resp.statusCode(), readAll(resp.body()));
                        ack.completeExceptionally(e);
                        failStream(sink, e);
                        return;
                    }
                    A2aTransportException notStreaming = rejectIfNotStreaming(resp).orElse(null);
                    if (notStreaming != null) {
                        closeQuietly(resp.body());
                        ack.completeExceptionally(notStreaming);
                        failStream(sink, notStreaming);
                        return;
                    }
                    io.execute(() -> readResumeSse(ch, sink, resp.body(), ack, snapshotRef));
                });
        return ack;
    }

    /**
     * 读流式续跑的 SSE 流，把帧折叠成单个快照结算 future。
     *
     * <p>与创建场景的 {@link #readSse} 不同：续跑已确认 taskRef，不需要 UNKNOWN 幂等重发恢复；
     * 断连时直接走 {@code GetTask} 查询确认状态。读到终态或 INPUT_REQUIRED 即结算 future。
     *
     * @param ch 通道
     * @param sink 事件汇入目标；为 null 表示帧不进入任何事件流
     * @param in SSE 输入流
     * @param ack 待结算的 future
     * @param snapshotRef 返回快照使用的 invocationRef
     */
    private void readResumeSse(Channel ch, Channel sink, InputStream in,
                               CompletableFuture<InvocationSnapshot> ack, String snapshotRef) {
        ch.touch();
        ch.idleTimedOut.set(false);
        ScheduledFuture<?> watchdog = armWatchdog(ch, in);
        ResumeTail tail = readResumeLines(ch, sink, in, ack, snapshotRef);
        if (watchdog != null) {
            watchdog.cancel(false);
        }
        if (ack.isDone()) {
            return;
        }
        // 中途断连或未达终态：用 GetTask 查询确认真实状态。
        confirmResumeByQuery(ch, sink, ack, snapshotRef, tail);
    }

    /**
     * 逐行读取续跑 SSE 流并尝试结算，返回流尾观测结果供兜底查询使用。
     *
     * @param ch 通道
     * @param sink 事件汇入目标；为 null 表示帧不进入任何事件流
     * @param in SSE 输入流
     * @param ack 待结算的 future
     * @param snapshotRef 返回快照使用的 invocationRef
     * @return 流尾观测结果（最后帧与读取异常）
     */
    private ResumeTail readResumeLines(Channel ch, Channel sink, InputStream in,
                                       CompletableFuture<InvocationSnapshot> ack, String snapshotRef) {
        A2aJsonCodec.Frame[] lastFrame = {null};
        Throwable failure = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null && !ack.isDone()) {
                ch.touch();
                Optional<A2aJsonCodec.Frame> parsed = onResumeLine(ch, sink, data, line);
                if (parsed.isPresent()) {
                    lastFrame[0] = parsed.get();
                    if (maybeSettleResume(ch, ack, snapshotRef, parsed.get())) {
                        break;
                    }
                }
            }
            // 流正常关闭但未到终态/INPUT_REQUIRED：尝试最后一帧结算，否则走查询兜底。
            // 已结算（终态/等待点触发了 break）则不再 flush，避免向已关闭的 publisher 投递。
            if (!ack.isDone()) {
                flushResumeFrame(ch, sink, data).ifPresent(f -> lastFrame[0] = f);
            }
        } catch (IOException | RuntimeException e) {
            failure = e;
        }
        return new ResumeTail(lastFrame[0], failure);
    }

    /**
     * 处理单行续跑 SSE：空行触发帧解析与投递，data: 行累积，其余行忽略。
     *
     * @param ch 通道
     * @param sink 事件汇入目标
     * @param data 累积缓冲
     * @param line 当前 SSE 行
     * @return 空行触发并解析出的帧；其他情况为空
     */
    private Optional<A2aJsonCodec.Frame> onResumeLine(Channel ch, Channel sink, StringBuilder data, String line) {
        if (line.isEmpty()) {
            return flushResumeFrame(ch, sink, data);
        }
        if (line.startsWith("data:")) {
            data.append(line.substring(5).trim());
        }
        // event: / id: / 注释行当前不参与语义，忽略。
        return Optional.empty();
    }

    /**
     * 终态或 INPUT_REQUIRED 帧的结算：释放通道并完成 future。
     *
     * @param ch 通道
     * @param ack 待结算的 future
     * @param snapshotRef 返回快照使用的 invocationRef
     * @param f 当前帧
     * @return 已结算返回 true，表示本段流已尽其用应停止读取
     */
    private boolean maybeSettleResume(Channel ch, CompletableFuture<InvocationSnapshot> ack,
                                      String snapshotRef, A2aJsonCodec.Frame f) {
        TaskState st = f.state();
        if (st == null || (!st.isTerminal() && st != TaskState.INPUT_REQUIRED)) {
            return false;
        }
        // 终态帧需释放通道；INPUT_REQUIRED 保留通道供后续续跑。
        if (st.isTerminal()) {
            releaseChannel(ch);
        }
        ack.complete(snapshotFromFrame(snapshotRef, f));
        return true;
    }

    /**
     * 续跑 SSE 流尾观测结果：最后帧与读取异常，供兜底查询使用。
     *
     * @param lastFrame 流内最后观测到的帧；可能为 null
     * @param failure 读取异常；正常关闭为 null
     */
    private record ResumeTail(A2aJsonCodec.Frame lastFrame, Throwable failure) {
        // 仅规范构造器，无额外成员。
    }

    /**
     * 解析并投递单个续跑 SSE 帧。
     *
     * @param ch 通道
     * @param sink 事件汇入目标；为 null 只解析不投递
     * @param data 累积的 data 行内容（会被清空）
     * @return 解析出的帧；无内容时为空
     */
    private Optional<A2aJsonCodec.Frame> flushResumeFrame(Channel ch, Channel sink, StringBuilder data) {
        if (data.length() == 0) {
            return Optional.empty();
        }
        String json = data.toString();
        data.setLength(0);
        JsonNode result = extractResult(codec.readTree(json));
        A2aJsonCodec.Frame f = codec.parseFrame(result).orElse(null);
        bindTaskRef(ch, f);
        if (sink != null) {
            emit(sink, f);
        } else {
            applyRootOutput(ch, f);
        }
        return Optional.ofNullable(f);
    }

    /**
     * 流式续跑断连/未达终态时的查询兜底：用 {@code GetTask} 确认真实状态并结算 future。
     *
     * @param ch 通道
     * @param sink 事件汇入目标；为 null 不投递
     * @param ack 待结算的 future
     * @param snapshotRef 返回快照使用的 invocationRef
     * @param tail 流尾观测结果（最后帧与读取异常）
     */
    private void confirmResumeByQuery(Channel ch, Channel sink, CompletableFuture<InvocationSnapshot> ack,
                                      String snapshotRef, ResumeTail tail) {
        A2aJsonCodec.Frame lastFrame = tail.lastFrame();
        Throwable failure = tail.failure();
        if (ch.taskRef == null) {
            // 没有 taskRef 无法查询：用最后观测帧兜底结算，否则报错。
            if (lastFrame != null) {
                ack.complete(snapshotFromFrame(snapshotRef, lastFrame));
            } else {
                String reason = (failure != null) ? rootMessage(failure) : "stream closed without any frame";
                ack.completeExceptionally(A2aTransportException.network(
                        "streaming resume failed: " + reason, failure));
            }
            return;
        }
        sendForSnapshot(codec.buildGet(ch.taskRef), ch.credential, snapshotRef, ch)
                .whenComplete((snap, ex) -> onQueryComplete(sink, ack, failure, snap, ex));
    }

    /**
     * 查询完成后的结算：失败时合成原因并传播，成功时按需投递事件并完成 future。
     *
     * @param sink 事件汇入目标；为 null 不投递
     * @param ack 待结算的 future
     * @param failure SSE 读取异常；正常关闭为 null
     * @param snap 查询返回的快照
     * @param ex 查询异常；成功为 null
     */
    private void onQueryComplete(Channel sink, CompletableFuture<InvocationSnapshot> ack,
                                 Throwable failure, InvocationSnapshot snap, Throwable ex) {
        if (ex != null) {
            String reason = (failure != null)
                    ? "sse read failed: " + rootMessage(failure)
                            + "; state query failed: " + rootMessage(ex)
                    : "sse stream closed before terminal; state query failed: " + rootMessage(ex);
            A2aTransportException e = A2aTransportException.network(reason, ex);
            ack.completeExceptionally(e);
            failStream(sink, e);
            return;
        }
        if (sink != null && snap.state() != null) {
            // 把查询确认的状态作为事件投递到原流（与创建场景 confirmByQuery 行为对齐）。
            projectQueriedState(sink, snap);
        }
        ack.complete(snap);
    }

    /**
     * Unary {@code SendMessage}：解析响应为该 Task 的<b>完整</b>下一状态快照。
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
                        if (ch.taskRef != null) {
                            recoverUnaryByQuery(ch, sink, ack, snapshotRef, e);
                        } else {
                            ack.completeExceptionally(e);
                            failStream(sink, e);
                        }
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
                        } else {
                            applyRootOutput(ch, f);
                        }
                        // 快照驱动的续跑（无 sink）走到终态：通道再无用处，及时释放，避免 taskRef 映射堆积；
                        // 未到终态则保留通道，等待后续续跑推进。
                        if (sink == null && f != null && f.state() != null && f.state().isTerminal()) {
                            releaseChannel(ch);
                        }
                        ack.complete(snapshotFromFrame(snapshotRef, f));
                    } catch (A2aTransportException | IllegalArgumentException e) {
                        ack.completeExceptionally(e);
                        failStream(sink, e);
                    }
                });
        return ack;
    }

    private void recoverUnaryByQuery(Channel ch, Channel sink, CompletableFuture<InvocationSnapshot> ack,
            String snapshotRef, Throwable originalFailure) {
        sendForSnapshot(codec.buildGet(ch.taskRef), ch.credential, snapshotRef, ch)
                .whenComplete((snapshot, queryFailure) -> {
                    if (queryFailure != null) {
                        A2aTransportException failure = A2aTransportException.network(
                                "unary request failed: " + rootMessage(originalFailure)
                                        + "; GetTask reconciliation failed: " + rootMessage(queryFailure),
                                queryFailure);
                        ack.completeExceptionally(failure);
                        failStream(sink, failure);
                        return;
                    }
                    if (ch.callTree != null) {
                        ch.callTree.markRecovered(false);
                    }
                    if (sink != null && snapshot != null) {
                        projectQueriedState(sink, snapshot);
                    }
                    ack.complete(snapshot);
                });
    }

    /**
     * 非流式创建（{@code BLOCKING} / {@code ASYNC}）：单次 {@code SendMessage} 取回当前状态。
     *
     * <p>BLOCKING 在任一 Endpoint 取得 taskId 且结果仍为 {@code SUBMITTED}/{@code WORKING} 时，
     * SDK 自动用 {@code GetTask} 观察到终态或观察超时。ASYNC 在受理后停止自动网络活动，
     * 由业务通过 {@code getInvocation} 按需查询。
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
            if (ch.terminal.get()) {
                return; // 已由响应帧结算终态
            }
            if (snap.state() == TaskState.INPUT_REQUIRED) {
                // client_tool 需要保持原通道开放，让 SDK 自动续跑并把后续帧汇回同一调用。
                // 用户输入等待点没有自动动作：关闭本段观察窗口，使 completion() 以
                // INPUT_REQUIRED 非终态快照结算；保留 taskRef 通道供 continueInput 使用。
                if (snap.pendingToolCall() == null && !ch.publisher.isClosed()) {
                    ch.publisher.close();
                }
                return;
            }
            if (ch.mode == InvocationMode.BLOCKING && ch.taskRef != null) {
                beginBlockingObservation(ch, ch.invocationRef, null);
            }
            // ASYNC 在 accepted 后不保留后台观察任务，由调用方按需 getInvocation。
        });
    }

    private void beginBlockingObservation(Channel ch, String snapshotRef,
            CompletableFuture<InvocationSnapshot> observationResult) {
        if (ch.blockingObservationStartedNanos == 0L) {
            ch.blockingObservationStartedNanos = System.nanoTime();
        }
        scheduleBlockingPoll(ch, snapshotRef, observationResult, 0L);
    }

    private void scheduleBlockingPoll(Channel ch, String snapshotRef,
            CompletableFuture<InvocationSnapshot> observationResult, long delayMillis) {
        ch.recoveryFuture = scheduler.schedule(() -> {
            if (ch.terminal.get()) {
                if (observationResult != null && !observationResult.isDone()) {
                    observationResult.completeExceptionally(new IllegalStateException(
                            "observation channel closed before a final snapshot"));
                }
                return;
            }
            if (blockingObservationExpired(ch)) {
                ObservationTimeoutException timeout = new ObservationTimeoutException(snapshotRef, ch.taskRef,
                        ch.lastState != null ? ch.lastState : TaskState.UNKNOWN, blockingObservationTimeout);
                if (observationResult != null) {
                    observationResult.completeExceptionally(timeout);
                }
                failStream(ch, timeout);
                return;
            }
            pollBlockingTask(ch, snapshotRef, observationResult);
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private boolean blockingObservationExpired(Channel ch) {
        return ch.blockingObservationStartedNanos != 0L
                && System.nanoTime() - ch.blockingObservationStartedNanos
                >= blockingObservationTimeout.toNanos();
    }

    private void pollBlockingTask(Channel ch, String snapshotRef,
            CompletableFuture<InvocationSnapshot> observationResult) {
        sendForSnapshot(codec.buildGet(ch.taskRef), ch.credential, snapshotRef, ch)
                .whenComplete((snap, failure) -> {
                    if (ch.terminal.get()) {
                        return;
                    }
                    if (failure != null) {
                        retryBlockingObservation(ch, snapshotRef, observationResult, failure);
                        return;
                    }
                    if (!recordTaskObservationSuccess(ch, snap)) {
                        retryBlockingObservation(ch, snapshotRef, observationResult,
                                new IllegalStateException("GetTask returned an invalid Task snapshot"));
                        return;
                    }
                    if (projectQueriedState(ch, snap)) {
                        if (observationResult != null) {
                            observationResult.complete(snap);
                        }
                        if (snap != null && snap.state() == TaskState.INPUT_REQUIRED
                                && snap.pendingToolCall() == null && !ch.publisher.isClosed()) {
                            ch.publisher.close();
                        }
                        if (snap != null && snap.state() == TaskState.INPUT_REQUIRED) {
                            ch.blockingObservationStartedNanos = 0L;
                        }
                        return;
                    }
                    scheduleBlockingPoll(ch, snapshotRef, observationResult, blockingPollInterval.toMillis());
                });
    }

    private void retryBlockingObservation(Channel ch, String snapshotRef,
            CompletableFuture<InvocationSnapshot> observationResult, Throwable failure) {
        ClassifiedError classified = ClassifiedError.unwrap(failure).orElse(null);
        if (classified != null && !classified.retryable()) {
            if (observationResult != null) {
                observationResult.completeExceptionally(failure);
            }
            failStream(ch, failure);
            return;
        }
        int failures = ++ch.observationFailures;
        if (failures >= MAX_OBSERVATION_RECOVERY_FAILURES) {
            Throwable exhausted = classified != null ? failure : A2aTransportException.network(
                    "automatic GetTask observation failed " + failures + " times: "
                            + rootMessage(failure), failure);
            if (observationResult != null) {
                observationResult.completeExceptionally(exhausted);
            }
            failStream(ch, exhausted);
            return;
        }
        long delay = blockingPollInterval.toMillis() * (1L << (failures - 1));
        scheduleBlockingPoll(ch, snapshotRef, observationResult, delay);
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
                    io.execute(() -> readSse(ch, resp.body(), false));
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

    private void readSse(Channel ch, InputStream in, boolean subscription) {
        ch.activeInput.set(in);
        ch.touch();
        ch.idleTimedOut.set(false);
        ScheduledFuture<?> watchdog = armWatchdog(ch, in);
        ch.watchdog = watchdog;
        Throwable failure = null;
        int acceptedFrames = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String eventId = null;
            String line;
            while ((line = r.readLine()) != null) {
                ch.touch();
                if (line.isEmpty()) {
                    if (flushFrame(ch, data, eventId, subscription)) {
                        acceptedFrames++;
                    }
                    eventId = null;
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.startsWith("id:")) {
                    eventId = line.substring(3).trim();
                } else {
                    // event: / 注释行（":"开头）不参与业务语义。
                    continue;
                }
            }
            if (flushFrame(ch, data, eventId, subscription)) {
                acceptedFrames++;
            }
            if (subscription && acceptedFrames > 0) {
                ch.observationFailures = 0;
                markRecoveredTree(ch);
            }
        } catch (IOException | RuntimeException e) {
            failure = e;
        } finally {
            ch.activeInput.compareAndSet(in, null);
            if (watchdog != null) {
                watchdog.cancel(false);
            }
        }
        if (subscription && acceptedFrames == 0 && !ch.terminal.get()
                && ch.lastState != TaskState.INPUT_REQUIRED) {
            onSubscriptionFailure(ch, "SubscribeToTask closed before a valid event",
                    failure != null ? failure : new IOException("empty subscription stream"));
            return;
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
            if (!endpointPolicy.retryUnconfirmedCreate()) {
                failStream(ch, new A2aTransportException(
                        reason + "; Runtime create failed before a taskId was observed",
                        null, ErrorCodes.CREATE_FAILED_NO_TASK_ID, 0, false));
                return;
            }
            // 创建未确认：不能简单重发（会产生重复 Task），走同键同正文的幂等重发。
            // 次数从 recoveryAttempt 累加而非固定从 1 起，否则反复中断会无限重连。
            recoverUnconfirmedCreate(ch, reason, ch.recoveryAttempt + 1);
        } else {
            recoverKnownTask(ch, reason);
        }
    }

    private void recoverKnownTask(Channel ch, String reason) {
        if (endpointPolicy.useSubscriptionForRecovery(ch.mode)) {
            openSubscription(ch, reason);
        } else {
            pollTask(ch, reason);
        }
    }

    private void openSubscription(Channel ch, String reason) {
        HttpRequest.Builder request = base("text/event-stream", ch.credential, false);
        if (endpointPolicy.cursorReplaySupported()
                && ch.replayCursor != null && !ch.replayCursor.isBlank()) {
            request.header("Last-Event-ID", ch.replayCursor);
        }
        HttpRequest httpRequest = request.POST(HttpRequest.BodyPublishers.ofString(
                codec.write(codec.buildSubscribe(ch.taskRef)), StandardCharsets.UTF_8)).build();
        http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).whenComplete((resp, failure) -> {
            if (failure != null) {
                onSubscriptionFailure(ch, reason, failure);
                return;
            }
            if (resp.statusCode() / 100 != 2) {
                onSubscriptionFailure(ch, reason, governanceError(resp.statusCode(), readAll(resp.body())));
                return;
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("");
            if (contentType.toLowerCase(java.util.Locale.ROOT).contains("application/json")) {
                String body = readAll(resp.body());
                try {
                    extractResult(codec.readTree(body));
                    onObservationFailure(ch, reason, new IllegalStateException("SubscribeToTask returned JSON"));
                } catch (A2aTransportException error) {
                    if (ErrorCodes.REPLAY_CURSOR_EXPIRED.equals(error.code())
                            || ErrorCodes.SUBSCRIPTION_UNAVAILABLE.equals(error.code())) {
                        ch.recovering.set(false);
                        pollTask(ch, reason + "; subscription requires current Task reconciliation");
                    } else if (ErrorCodes.METHOD_NOT_SUPPORTED.equals(error.code())
                            && endpointPolicy.type() == com.openjiuwen.client.api.EndpointType.GATEWAY) {
                        ch.recovering.set(false);
                        pollTask(ch, reason + "; gateway subscribe unsupported");
                    } else {
                        onSubscriptionFailure(ch, reason, error);
                    }
                }
                return;
            }
            ch.recovering.set(false);
            io.execute(() -> readSse(ch, resp.body(), true));
        });
    }

    private void onSubscriptionFailure(Channel ch, String reason, Throwable failure) {
        if (endpointPolicy.type() == com.openjiuwen.client.api.EndpointType.RUNTIME && ch.taskRef != null) {
            ch.recovering.set(false);
            if (++ch.observationFailures >= MAX_OBSERVATION_RECOVERY_FAILURES) {
                publishUncertain(ch, reason + "; recovery failed " + ch.observationFailures
                        + " times: " + rootMessage(failure), ErrorCodes.RECOVERY_RETRY_EXHAUSTED);
                return;
            }
            pollTask(ch, reason + "; SubscribeToTask failed: " + rootMessage(failure));
            return;
        }
        onObservationFailure(ch, reason, failure);
    }

    private void onObservationFailure(Channel ch, String reason, Throwable failure) {
        ch.recovering.set(false);
        if (failure instanceof A2aTransportException classified && !classified.retryable()) {
            publishUncertain(ch, reason + "; deterministic recovery error: " + rootMessage(failure));
            return;
        }
        int attempts = ++ch.observationFailures;
        if (attempts >= MAX_OBSERVATION_RECOVERY_FAILURES) {
            publishUncertain(ch, reason + "; recovery failed " + attempts + " times: " + rootMessage(failure),
                    ErrorCodes.RECOVERY_RETRY_EXHAUSTED);
            return;
        }
        long delay = 200L * (1L << (attempts - 1));
        ch.recoveryFuture = scheduler.schedule(() -> {
            if (!ch.terminal.get()) {
                beginDisconnectRecovery(ch, reason);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void pollTask(Channel ch, String reason) {
        sendForSnapshot(codec.buildGet(ch.taskRef), ch.credential, ch.invocationRef, ch)
                .whenComplete((snap, failure) -> {
            ch.recovering.set(false);
            if (failure != null) {
                onObservationFailure(ch, reason, failure);
                return;
            }
            if (!recordTaskObservationSuccess(ch, snap)) {
                onObservationFailure(ch, reason,
                        new IllegalStateException("GetTask returned an invalid Task snapshot"));
                return;
            }
            markRecoveredTree(ch);
            if (!projectQueriedState(ch, snap)) {
                ch.recoveryFuture = scheduler.schedule(
                        () -> beginDisconnectRecovery(ch, reason), 200L, TimeUnit.MILLISECONDS);
            }
        });
    }

    private boolean recordTaskObservationSuccess(Channel ch, InvocationSnapshot snapshot) {
        if (snapshot == null || snapshot.state() == null || snapshot.state() == TaskState.UNKNOWN
                || snapshot.diagnosticTaskRef() == null || snapshot.diagnosticTaskRef().isBlank()
                || !snapshot.diagnosticTaskRef().equals(ch.taskRef)) {
            return false;
        }
        ch.observationFailures = 0;
        return true;
    }

    /**
     * 已取得 taskRef 的中断：用 {@code GetTask} 主动查询确认真实状态（FRZ-4 b3）。
     *
     * @param ch 通道
     * @param reason 中断原因
     */
    private void confirmByQuery(Channel ch, String reason) {
        sendForSnapshot(codec.buildGet(ch.taskRef), ch.credential, ch.invocationRef, ch)
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
        publishUncertain(ch, reason, ErrorCodes.STREAM_INTERRUPTED);
    }

    private void publishUncertain(Channel ch, String reason, String runtimeErrorCode) {
        if (endpointPolicy.type() == com.openjiuwen.client.api.EndpointType.RUNTIME) {
            if (ch.terminal.get()) {
                return;
            }
            TaskState last = (ch.lastState != null) ? ch.lastState : TaskState.UNKNOWN;
            submit(ch, new InvocationEvent.ProgressUncertain(ch.invocationRef, last, reason));
            failStream(ch, new A2aTransportException(reason, null,
                    runtimeErrorCode, 0, false));
            return;
        }
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

    private boolean flushFrame(Channel ch, StringBuilder data, String eventId, boolean replayed) {
        if (data.length() == 0) {
            return false;
        }
        String json = data.toString();
        data.setLength(0);
        if (endpointPolicy.cursorReplaySupported() && eventId != null && !eventId.isBlank()
                && ch.seenEventIds.contains(eventId)) {
            return false;
        }
        JsonNode result = extractResult(codec.readTree(json));
        A2aJsonCodec.Frame f = codec.parseFrame(result).orElse(null);
        bindTaskRef(ch, f);
        boolean validObservation = isValidObservationFrame(ch, f);
        if (replayed && validObservation) {
            ch.observationFailures = 0;
            markRecoveredTree(ch);
        }
        emit(ch, f);
        if (endpointPolicy.cursorReplaySupported() && eventId != null && !eventId.isBlank()) {
            ch.recordEventId(eventId);
        }
        return validObservation;
    }

    private static boolean isValidObservationFrame(Channel ch, A2aJsonCodec.Frame frame) {
        if (frame == null || frame.taskId() == null || frame.taskId().isBlank()
                || ch.taskRef == null || !ch.taskRef.equals(frame.taskId())) {
            return false;
        }
        return frame.state() != null || frame.artifact() != null || frame.taskSnapshot();
    }

    private void markRecoveredTree(Channel ch) {
        if (ch.callTree == null) {
            return;
        }
        if (endpointPolicy.type() == com.openjiuwen.client.api.EndpointType.RUNTIME) {
            ch.callTree.markPartialRecovery();
        } else {
            ch.callTree.markRecovered(endpointPolicy.cursorReplaySupported());
        }
    }

    private CompletionStage<InvocationSnapshot> sendForSnapshot(ObjectNode req, String credential,
                                                                String invocationRef) {
        return sendForSnapshot(req, credential, invocationRef, null);
    }

    private CompletionStage<InvocationSnapshot> sendForSnapshot(ObjectNode req, String credential,
                                                                 String invocationRef, Channel channel) {
        HttpRequest httpReq = base("application/json", credential, true)
                .POST(HttpRequest.BodyPublishers.ofString(codec.write(req), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(httpReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() / 100 != 2) {
                        throw governanceError(resp.statusCode(), resp.body());
                    }
                    JsonNode result = extractResult(codec.readTree(resp.body()));
                    A2aJsonCodec.Frame frame = codec.parseFrame(result).orElse(null);
                    if (channel != null && frame != null) {
                        bindTaskRef(channel, frame);
                        applyRootOutput(channel, frame);
                        if (channel.callTree != null) {
                            for (ProtocolArtifact artifact : frame.taskArtifacts()) {
                                channel.callTree.accept(artifact);
                            }
                            channel.callTree.updateRootState(frame.state());
                        }
                    }
                    return snapshotFromFrame(invocationRef, frame);
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
        if (ch.callTree != null) {
            ch.callTree.bindRoot(f.taskId());
        }
        byTaskRef.put(f.taskId(), ch);
        submit(ch, new InvocationEvent.Accepted(ch.invocationRef, f.taskId(), f.contextId()));
    }

    private void applyRootOutput(Channel ch, A2aJsonCodec.Frame frame) {
        if (ch == null || frame == null) {
            return;
        }
        if (frame.taskSnapshot()) {
            ch.rootOutput.replaceWithTaskSnapshot(frame.taskArtifacts());
        } else if (frame.artifact() != null) {
            ch.rootOutput.accept(frame.artifact());
        } else {
            // Text-only frame with no artifact/task-snapshot semantic; nothing to apply.
        }
    }

    private void emit(Channel ch, A2aJsonCodec.Frame f) {
        if (f == null) {
            return;
        }
        applyRootOutput(ch, f);
        emitCallTreeUpdate(ch, f);
        if (f.state() == null) {
            if (f.text() != null) {
                submit(ch, new InvocationEvent.ContentDelta(ch.invocationRef, f.text()));
            }
            return;
        }
        ch.lastState = f.state();
        switch (f.state()) {
            case COMPLETED -> {
                submit(ch, new InvocationEvent.Completed(ch.invocationRef,
                        ch.rootOutput.currentText().orElse(f.text())));
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
            case INPUT_REQUIRED -> emitInputRequired(ch, f);
            default -> submit(ch, new InvocationEvent.StatusChanged(ch.invocationRef, f.state(), false));
        }
    }

    private void emitCallTreeUpdate(Channel ch, A2aJsonCodec.Frame f) {
        if (ch.callTree == null) {
            return;
        }
        if (f.artifact() != null) {
            ch.callTree.accept(f.artifact());
        }
        for (ProtocolArtifact artifact : f.taskArtifacts()) {
            ch.callTree.accept(artifact);
        }
        ch.callTree.updateRootState(f.state());
    }

    private void emitInputRequired(Channel ch, A2aJsonCodec.Frame f) {
        submit(ch, new InvocationEvent.StatusChanged(ch.invocationRef, TaskState.INPUT_REQUIRED, false));
        A2aJsonCodec.Interrupt it = f.interrupt();
        if (it == null) {
            submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, null, null));
        } else if (it.userInput()) {
            submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, null, it.prompt()));
        } else if (!it.validResumeTarget()) {
            submit(ch, new InvocationEvent.ProtocolDiagnostic(ch.invocationRef,
                    ErrorCodes.INPUT_RESUME_TARGET_MISSING,
                    "client_tool interrupt requires non-blank toolCallId and toolName"));
            submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, null, it.prompt()));
        } else {
            Duration dl = (it.deadlineMs() != null) ? Duration.ofMillis(it.deadlineMs()) : null;
            InvocationEvent.ToolCall call = new InvocationEvent.ToolCall(
                    it.toolCallId(), it.toolName(), it.arguments(), dl);
            submit(ch, new InvocationEvent.InputRequired(ch.invocationRef, call, null));
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
        ch.cancelRecovery();
        ch.closeActiveInput();
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
        ch.cancelRecovery();
        ch.closeActiveInput();
        if (!ch.publisher.isClosed()) {
            ch.publisher.close();
        }
    }

    private void unregister(Channel ch) {
        if (ch.callTree != null) {
            ch.callTree.close();
            ch.callTree.current().ifPresent(tree -> completedTrees.put(ch.invocationRef, tree));
        }
        ch.rootOutput.currentText().ifPresent(text -> completedOutputs.put(ch.invocationRef, text));
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
        ch.cancelRecovery();
        ch.closeActiveInput();
        if (!ch.terminal.getAndSet(true) && !ch.publisher.isClosed()) {
            unregister(ch);
            ch.publisher.closeExceptionally(ex);
        }
    }

    private InvocationSnapshot snapshotFromFrame(String invocationRef, A2aJsonCodec.Frame f) {
        String ref = (invocationRef != null) ? invocationRef : (f != null ? f.taskId() : null);
        TaskState st = (f != null && f.state() != null) ? f.state() : TaskState.UNKNOWN;
        InvocationEvent.ToolCall pending = null;
        if (f != null && f.interrupt() != null && !f.interrupt().userInput()
                && f.interrupt().validResumeTarget()) {
            A2aJsonCodec.Interrupt it = f.interrupt();
            Duration dl = (it.deadlineMs() != null) ? Duration.ofMillis(it.deadlineMs()) : null;
            pending = new InvocationEvent.ToolCall(it.toolCallId(), it.toolName(), it.arguments(), dl);
        }
        String outputText = null;
        if (f != null) {
            outputText = f.taskSnapshot()
                    ? RootOutputReducer.materializeTaskSnapshot(f.taskArtifacts()).orElse(f.text())
                    : f.text();
        }
        return new InvocationSnapshot(ref, st, st.isTerminal(),
                (f != null) ? f.taskId() : null, pending,
                outputText,
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
            String declaredCode = err.path("data").path("code").asText(null);
            String code = "CURSOR_EXPIRED".equals(declaredCode)
                    || ErrorCodes.REPLAY_CURSOR_EXPIRED.equals(declaredCode)
                    ? ErrorCodes.REPLAY_CURSOR_EXPIRED
                    : switch (rpcCode) {
                        case -32601 -> ErrorCodes.METHOD_NOT_SUPPORTED;
                        case -32001 -> ErrorCodes.TASK_NOT_FOUND;
                        case -32004 -> ErrorCodes.SUBSCRIPTION_UNAVAILABLE;
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

        /**
         * 原始创建正文；UNKNOWN 恢复必须逐字节复用，否则触发网关幂等正文冲突。
         */
        final String createBody;
        final String credential;
        final SubmissionPublisher<InvocationEvent> publisher;
        final CallTreeReducer callTree;
        final RootOutputReducer rootOutput;
        volatile String taskRef;
        volatile String contextId;
        volatile TaskState lastState;
        volatile long lastActivityNanos = System.nanoTime();
        volatile int recoveryAttempt;
        volatile int observationFailures;
        volatile long blockingObservationStartedNanos;
        volatile String replayCursor;
        final java.util.LinkedHashSet<String> seenEventIds = new java.util.LinkedHashSet<>();
        volatile ScheduledFuture<?> watchdog;
        volatile ScheduledFuture<?> recoveryFuture;
        final AtomicReference<InputStream> activeInput = new AtomicReference<>();
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
            this.callTree = mode == InvocationMode.STREAMING ? new CallTreeReducer(mode) : null;
            this.rootOutput = new RootOutputReducer();
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

        void cancelRecovery() {
            ScheduledFuture<?> future = recoveryFuture;
            if (future != null) {
                future.cancel(false);
            }
        }

        void closeActiveInput() {
            InputStream in = activeInput.getAndSet(null);
            if (in != null) {
                closeQuietly(in);
            }
        }

        synchronized void recordEventId(String eventId) {
            seenEventIds.add(eventId);
            replayCursor = eventId;
            while (seenEventIds.size() > 2048) {
                java.util.Iterator<String> iterator = seenEventIds.iterator();
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void cancelLocalObservation(Channel ch) {
        if (ch.terminal.getAndSet(true)) {
            return;
        }
        ch.cancelWatchdog();
        ch.cancelRecovery();
        ch.closeActiveInput();
        unregister(ch);
        if (!ch.publisher.isClosed()) {
            ch.publisher.close();
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
