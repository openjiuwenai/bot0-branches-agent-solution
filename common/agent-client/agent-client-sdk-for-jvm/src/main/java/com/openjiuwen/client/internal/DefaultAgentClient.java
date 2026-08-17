/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.internal;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.ClassifiedError;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.ErrorCodes;
import com.openjiuwen.client.api.Handle;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationNotResumableException;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.spi.Governance;
import com.openjiuwen.client.state.spi.ClientStateStore;
import com.openjiuwen.client.tool.spi.LocalToolDescriptor;
import com.openjiuwen.client.tool.spi.LocalToolRegistry;
import com.openjiuwen.client.tool.spi.ToolExecutionContext;
import com.openjiuwen.client.tool.spi.ToolExecutionRecord;
import com.openjiuwen.client.tool.spi.ToolExposurePolicy;
import com.openjiuwen.client.tool.spi.ToolView;
import com.openjiuwen.client.transport.spi.CredentialProvider;
import com.openjiuwen.client.transport.spi.ToolWireSpec;
import com.openjiuwen.client.transport.spi.CallTreeTransportProvider;
import com.openjiuwen.client.transport.spi.InvocationOutputTransportProvider;
import com.openjiuwen.client.transport.spi.TransportProvider;
import com.openjiuwen.client.api.calltree.CallTreeSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * {@link AgentClient} 的默认实现（FEAT-006/007 内核编排）。
 *
 * <p>关键职责：
 * <ul>
 * <li>维护 {@code invocationRef → taskRef} 的映射（业务只见 invocationRef，taskRef 内部使用）。</li>
 * <li>计算生效的工具暴露策略并投影为 ToolView / wire clientTools。</li>
 * <li>把 client_tool 类型的 INPUT_REQUIRED 自动就地执行并续传，业务侧只观测到连续事件流。</li>
 * <li>对续传做"每 toolCallId 只提交一次"的防抖，与调度器的"最多执行一次"配合。</li>
 * </ul>
 *
 * @since 2026-07-27
 */
public final class DefaultAgentClient implements AgentClient {
    private static final int MAX_RETAINED_TERMINAL_INVOCATIONS = 256;

    private final TransportProvider transport;
    private final LocalToolRegistry registry;
    private final ClientStateStore store;
    private final ToolDispatcher dispatcher;
    private final ObservationTextRenderer renderer;
    private final ExecutorService toolExecutor;
    private final CredentialProvider credentials;

    private final ConcurrentMap<String, ToolExposurePolicy> conversationExposure = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InvocationState> invocations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CallImpl> calls = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> terminalInvocations =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Set<String> resumeGuard = ConcurrentHashMap.newKeySet();

    /**
     * 构造默认客户端实例。
     *
     * @param transport 传输提供者
     * @param registry 本地工具注册表
     * @param store 客户端状态存储
     * @param policyGuard 策略门禁
     * @param approvalProvider 审批提供者
     * @param toolExecutor 工具执行线程池
     * @param mapper JSON 编解码器
     * @param credentials 凭证提供者
     */
    public DefaultAgentClient(TransportProvider transport,
                              LocalToolRegistry registry,
                              ClientStateStore store,
                              Governance.PolicyGuard policyGuard,
                              Governance.ApprovalProvider approvalProvider,
                              ExecutorService toolExecutor,
                              ObjectMapper mapper,
                              CredentialProvider credentials) {
        this.transport = transport;
        this.registry = registry;
        this.store = store;
        this.toolExecutor = toolExecutor;
        this.credentials = credentials;
        this.renderer = new ObservationTextRenderer(mapper);
        this.dispatcher = new ToolDispatcher(registry, store, policyGuard, approvalProvider, toolExecutor);
    }

    @Override
    public void exposeInConversation(String conversationId, ToolExposurePolicy policy) {
        conversationExposure.put(conversationId, policy);
    }

    @Override
    public LocalToolRegistry tools() {
        return registry;
    }

    @Override
    public InvocationCall invoke(InvocationRequest request) {
        ToolExposurePolicy effective = effectivePolicy(request.conversationId(), request.exposure().orElse(null));
        // 暴露窗口在创建时就已关闭 → ToolView 为空：不宣告自己不会执行的能力，
        // 免得服务端白跑一轮工具请求再被拒。窗口在创建之后才关闭的情形由 driveClientTool 兜底拒绝。
        List<ToolWireSpec> clientTools = effective.isExpired()
                ? List.of()
                : toWireSpecs(registry.toolView(effective));
        String credential = resolveCredential(request.conversationId(), request.credentialToken().orElse(null));

        String invocationRef = request.invocationId();
        InvocationState state = new InvocationState(
                invocationRef, request.conversationId(), request.mode(),
                clientTools, credential, request.idempotencyKey(), false,
                effective.expiresAt().orElse(null), invocationRef);
        invocations.put(invocationRef, state);

        TransportProvider.CreateCommand cmd = new TransportProvider.CreateCommand(
                invocationRef, request.invocationId(), request.idempotencyKey(),
                request.conversationId(), request.agentId().orElse(null), request.mode(), request.input(),
                clientTools, credential, null, request.attributes());

        Flow.Publisher<InvocationEvent> upstream = transport.createAndStream(cmd);
        CallImpl call = new CallImpl(invocationRef, request.conversationId(), callTreePublisher(invocationRef));
        calls.put(invocationRef, call);
        upstream.subscribe(call);
        return call;
    }

    @Override
    public CompletionStage<InvocationSnapshot> getInvocation(String invocationRef) {
        InvocationState state = invocations.get(invocationRef);
        if (state == null || state.taskRef == null) {
            // 还没建立 taskRef 映射（创建未确认）或已回收：返回 UNKNOWN，不抛异常。
            return CompletableFuture.completedFuture(InvocationSnapshot.unknown(invocationRef));
        }
        return transport.getTask(state.taskRef, state.credentialToken)
                .thenApply(snap -> withInvocationRef(invocationRef, snap,
                        currentCallTree(invocationRef).orElse(snap != null ? snap.callTree() : null)))
                .thenApply(snap -> {
                    CallImpl call = calls.get(invocationRef);
                    if (call != null && snap != null && (snap.terminal()
                            || snap.state() == TaskState.INPUT_REQUIRED)) {
                        call.completeFromQuery(snap);
                    }
                    return snap;
                });
    }

    /**
     * 传输层按 taskRef 解析出的 invocationRef 可能是回退值，这里统一改写为业务持有的句柄。
     *
     * @param invocationRef 业务持有的调用句柄
     * @param snap 传输层快照
     * @param callTree 调用树
     * @return 句柄已归正的快照
     */
    private static InvocationSnapshot withInvocationRef(String invocationRef, InvocationSnapshot snap,
            CallTreeSnapshot callTree) {
        if (snap == null) {
            return InvocationSnapshot.unknown(invocationRef);
        }
        if (invocationRef.equals(snap.invocationRef()) && callTree == snap.callTree()) {
            return snap;
        }
        return new InvocationSnapshot(invocationRef, snap.state(), snap.terminal(),
                snap.diagnosticTaskRef(), snap.pendingToolCall(), snap.outputText(),
                snap.errorCode(), snap.message(), snap.recovery(), callTree);
    }

    private Flow.Publisher<CallTreeSnapshot> callTreePublisher(String invocationRef) {
        if (transport instanceof CallTreeTransportProvider trees) {
            return trees.callTree(invocationRef);
        }
        return InvocationCall.superCallTreePublisher();
    }

    private Optional<CallTreeSnapshot> currentCallTree(String invocationRef) {
        if (transport instanceof CallTreeTransportProvider trees) {
            return trees.currentCallTree(invocationRef);
        }
        return Optional.empty();
    }

    private Optional<String> currentOutputText(String invocationRef) {
        if (transport instanceof InvocationOutputTransportProvider outputs) {
            return outputs.currentOutputText(invocationRef);
        }
        return Optional.empty();
    }

    @Override
    public InvocationCall continueInput(ContinueInputRequest request) {
        InvocationState related = invocations.get(request.relatedInvocationRef());
        CallImpl relatedCall = calls.get(request.relatedInvocationRef());
        if (related == null || relatedCall == null || related.taskRef == null) {
            throw new InvocationNotResumableException(request.relatedInvocationRef(),
                    "cannot continue input: unknown related invocation or task mapping not established yet: "
                            + request.relatedInvocationRef());
        }
        // feat-011 §6.9 AC-S4-4：SDK 本地预检 relatedInvocationRef 须处于 INPUT_REQUIRED 且无 client_tool；
        // 不可续接（已终态 / 正处 client_tool 待执行）→ 明确错误，不静默发续跑。
        if (relatedCall.lastState != TaskState.INPUT_REQUIRED) {
            throw new InvocationNotResumableException(request.relatedInvocationRef(),
                    "cannot continue input: related invocation is not in INPUT_REQUIRED (state="
                            + relatedCall.lastState + "): " + request.relatedInvocationRef());
        }
        if (relatedCall.hasPendingClientTool()) {
            throw new InvocationNotResumableException(request.relatedInvocationRef(),
                    "cannot continue input: related invocation is waiting for a client_tool result, "
                            + "use the SDK auto-resume path instead: " + request.relatedInvocationRef());
        }
        // 006 §3.4.1：建立业务可见的新 invocationRef，映射到同一 taskRef（一个 Task 可被多个 invocationRef 引用）。
        String newInvocationRef = request.invocationId();
        // 续轮 mode 强制继承首轮 invocation 的 mode（FEAT-006 §47）：同一 conversation 内不得流式/非流式横跳，
        // 业务在 continueInput 中声明的 mode 被忽略。续传轮继承原轮的暴露窗口：续接的是同一个服务端 Task，
        // 授权窗口不应因换了个 invocation 而重开。
        InvocationMode inheritedMode = related.mode;
        InvocationState newState = new InvocationState(
                newInvocationRef, request.conversationId(), inheritedMode,
                related.clientTools, related.credentialToken, request.idempotencyKey(), true,
                related.exposureExpiresAt, related.treeInvocationRef);
        newState.taskRef = related.taskRef;
        invocations.put(newInvocationRef, newState);

        // SNAPSHOT_ONLY：传输层据此把响应帧留给本次返回快照，不汇入旧 invocation 的事件流。
        // messageId 取 idempotencyKey（缺省即 invocationId）：网关按 messageId 去重，
        // 用 invocationId 会让调用方显式设置的幂等键失效，续传重试就会产生重复副作用。
        TransportProvider.ResumeCommand cmd = new TransportProvider.ResumeCommand(
                newInvocationRef, related.taskRef, request.idempotencyKey(), null,
                request.input(), inheritedMode, related.clientTools, related.credentialToken,
                related.conversationId, TransportProvider.ResumeDelivery.SNAPSHOT_ONLY);

        // 新 CallImpl：不订阅 transport 旧 Channel，而是由续跑 unary 响应直接驱动其事件流与 completion。
        CallImpl newCall = new CallImpl(newInvocationRef, request.conversationId(),
                callTreePublisher(related.treeInvocationRef), related.treeInvocationRef);
        calls.put(newInvocationRef, newCall);
        // 续轮关联的 Task 已存在，新句柄的 accepted 无需等待最终 GetTask 观察。
        newCall.acceptExistingTask(related.taskRef);

        // 交接：原 invocation 的可观测生命到"等待输入"为止，后续由新 invocationRef 承载（006 §3.4.1）。
        // 不做交接，原句柄的 completion() 会永远悬挂——它的事件流已不会再收到任何帧。
        relatedCall.settleAtInputPoint();

        transport.resumeToolResult(cmd).whenComplete((snap, ex) -> {
            if (ex != null) {
                newCall.failFromResume(ex);
            } else {
                newCall.completeFromResume(snap);
            }
        });
        return newCall;
    }

    @Override
    public void close() {
        transport.close();
        toolExecutor.shutdownNow();
    }

    /**
     * 解析本次请求应附带的凭证：单次覆盖优先，否则回退到客户端级 CredentialProvider。
     *
     * @param conversationId 会话标识
     * @param perRequestToken 单次请求级凭证覆盖
     * @return 对应结果
     */
    private String resolveCredential(String conversationId, String perRequestToken) {
        if (perRequestToken != null && !perRequestToken.isEmpty()) {
            return perRequestToken;
        }
        return Optional.ofNullable(credentials)
                .map(c -> c.tokenFor(conversationId))
                .orElse(null);
    }

    /**
     * effectivePolicy。
     *
     * @param conversationId String
     * @param invocationPolicy ToolExposurePolicy
     * @return effectivePolicy
     */

    private ToolExposurePolicy effectivePolicy(String conversationId, ToolExposurePolicy invocationPolicy) {
        ToolExposurePolicy conv = conversationExposure.get(conversationId);
        if (conv == null && invocationPolicy == null) {
            return ToolExposurePolicy.none();
        }
        if (conv == null) {
            return invocationPolicy;
        }
        if (invocationPolicy == null) {
            return conv;
        }
        return conv.and(invocationPolicy);
    }

    /**
     * toWireSpecs。
     *
     * @param view ToolView
     * @return toWireSpecs
     */

    private static List<ToolWireSpec> toWireSpecs(ToolView view) {
        List<ToolWireSpec> out = new ArrayList<>();
        for (LocalToolDescriptor d : view.tools()) {
            out.add(new ToolWireSpec(d.wireName(), d.description(), d.inputSchema()));
        }
        return List.copyOf(out);
    }

    private void driveClientTool(InvocationState state, InvocationEvent.ToolCall call) {
        // 007 §2「只能执行当前**可用**的工具」+「上下文过期须形成结构化记录」：
        // 暴露窗口已关闭 → 结构化拒绝并回传，绝不执行。回传而非静默丢弃，否则服务端 Task 会一直挂在等待点。
        if (state.exposureExpired()) {
            submitToolResult(state, call.toolCallId(), ToolExecutionRecord.rejected(
                    call.toolCallId(), "context_expired",
                    "tool exposure window closed at " + state.exposureExpiresAt));
            return;
        }
        // 把本次 invocation 上报的 ToolView 工具名集合传入 ctx，供 ToolDispatcher 做可见性校验（007 §3.2 步骤 3）。
        java.util.Set<String> visibleNames = new java.util.HashSet<>();
        for (ToolWireSpec spec : state.clientTools) {
            visibleNames.add(spec.name());
        }
        ToolExecutionContext ctx = new ToolExecutionContext(
                state.conversationId, state.invocationRef, state.invocationRef,
                call.deadline(), Map.of(), visibleNames);
        dispatcher.dispatch(call, ctx)
                .orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(record -> submitToolResult(state, call.toolCallId(), record))
                .exceptionally(ex -> {
                    // dispatch timed out or failed (e.g., stale inFlight entry from a previous run,
                    // toolExecutor saturated, tool execution hung) — clear the stale entry so the next
                    // dispatch for the same toolCallId gets a fresh pipeline.
                    dispatcher.clearInFlight(call.toolCallId());
                    if (state.resumeToolAtInputPoint) {
                        // 方案 A：续轮 INPUT_REQUIRED 等待点上的端侧工具超时/失败不应拉死整个调用。
                        // 把工具失败回退为 error record 走 submitToolResult，让服务端 Task 继续挂在
                        // INPUT_REQUIRED、Call 保持开放，业务仍可再次 continueInput。
                        String errMsg = (ex.getMessage() != null) ? ex.getMessage() : ex.getClass().getSimpleName();
                        submitToolResult(state, call.toolCallId(),
                                com.openjiuwen.client.tool.spi.ToolExecutionRecord.error(
                                        call.toolCallId(), "tool_execution_error", errMsg));
                    } else {
                        // 首次创建调用场景：工具失败直接失败整个调用，避免业务悬挂。
                        failCall(state.invocationRef, ex);
                    }
                    return null;
                });
    }

    private void submitToolResult(InvocationState state, String toolCallId,
                                  com.openjiuwen.client.tool.spi.ToolExecutionRecord record) {
        // 工具续传已了结（无论下面哪条 return 路径），清掉续轮等待点标记，避免污染下一次工具请求。
        state.resumeToolAtInputPoint = false;
        if (store.isSubmitted(toolCallId)) {
            return;
        }
        if (!resumeGuard.add(toolCallId)) {
            return; // 已有一次续传在途，避免重复提交
        }
        String observationText = renderer.render(record);
        // 由 continueInput 新建的 invocation 没有传输通道，其工具续跑只能靠返回快照驱动；
        // 由 invoke 创建的 invocation 则把帧汇回自己的事件流，业务侧仍是一条连续流。
        TransportProvider.ResumeDelivery delivery = state.snapshotDriven
                ? TransportProvider.ResumeDelivery.SNAPSHOT_ONLY
                : TransportProvider.ResumeDelivery.EXISTING_STREAM;
        TransportProvider.ResumeCommand cmd = new TransportProvider.ResumeCommand(
                state.invocationRef, state.taskRef, "msg-" + UUID.randomUUID(), toolCallId,
                observationText, state.mode, state.clientTools, state.credentialToken, state.conversationId,
                delivery);
        transport.resumeToolResult(cmd).whenComplete((snap, ex) -> {
            if (ex != null) {
                resumeGuard.remove(toolCallId); // 允许失败后重试
                if (state.snapshotDriven) {
                    failCall(state.invocationRef, ex);
                }
                return;
            }
            store.markSubmitted(toolCallId);
            if (state.snapshotDriven) {
                driveFromSnapshot(state.invocationRef, snap);
            }
        });
    }

    /**
     * 把续跑响应快照驱动到指定 invocation 的事件流（仅快照驱动型 invocation 使用）。
     *
     * @param invocationRef 调用句柄
     * @param snap 续跑响应快照
     */
    private void driveFromSnapshot(String invocationRef, InvocationSnapshot snap) {
        CallImpl call = calls.get(invocationRef);
        if (call != null) {
            call.completeFromResume(snap);
        }
    }

    /**
     * 把续跑失败结算到指定 invocation。
     *
     * @param invocationRef 调用句柄
     * @param ex 异常
     */
    private void failCall(String invocationRef, Throwable ex) {
        CallImpl call = calls.get(invocationRef);
        if (call != null) {
            call.failFromResume(ex);
        }
    }

    /**
     * 每个 invocationRef 的内部状态（含 taskRef 映射与上报的 ToolView）。
     */
    private static final class InvocationState {
        final String invocationRef;
        final String conversationId;
        final InvocationMode mode;
        final List<ToolWireSpec> clientTools;
        final String credentialToken;

        /** 幂等键：进展不确定时作为恢复线索交给业务（UNKNOWN 恢复须复用同键）。 */
        final String idempotencyKey;

        /**
         * true 表示该 invocation 由 {@code continueInput} 新建，没有传输事件流通道，
         * 其后续事件只能由续跑响应快照驱动。
         */
        final boolean snapshotDriven;

        /**
         * 本次调用生效的暴露窗口截止时刻；null 表示不过期。
         *
         * <p>在创建时定格：服务端事后请求端侧工具，须按<b>当时</b>的授权判定，
         * 而不是按此后可能已被业务改动的会话级策略。
         */
        final Instant exposureExpiresAt;
        final String treeInvocationRef;
        volatile String taskRef;

        /**
         * 标记当前是否处于"续轮 INPUT_REQUIRED 等待点 + SDK 正在自动驱动端侧工具"语义。
         *
         * <p>置位后，{@code driveClientTool} 的超时/异常分支不再 {@code failCall} 拉死整个调用，
         * 而是把工具失败回退为 {@link ToolExecutionRecord#error} 走 {@code submitToolResult}，
         * 让服务端 Task 继续挂在 INPUT_REQUIRED、Call 保持开放，业务仍可再次 {@code continueInput}。
         * 仅在 {@link #completeFromResume} 的续轮 INPUT_REQUIRED + pendingToolCall 分支置位，工具续传完成后清零。
         */
        volatile boolean resumeToolAtInputPoint;

        InvocationState(String invocationRef, String conversationId, InvocationMode mode,
                        List<ToolWireSpec> clientTools, String credentialToken, String idempotencyKey,
                        boolean snapshotDriven) {
            this(invocationRef, conversationId, mode, clientTools, credentialToken, idempotencyKey,
                    snapshotDriven, null, invocationRef);
        }

        InvocationState(String invocationRef, String conversationId, InvocationMode mode,
                        List<ToolWireSpec> clientTools, String credentialToken, String idempotencyKey,
                        boolean snapshotDriven, Instant exposureExpiresAt, String treeInvocationRef) {
            this.invocationRef = invocationRef;
            this.conversationId = conversationId;
            this.mode = mode;
            this.clientTools = clientTools;
            this.credentialToken = credentialToken;
            this.idempotencyKey = idempotencyKey;
            this.snapshotDriven = snapshotDriven;
            this.exposureExpiresAt = exposureExpiresAt;
            this.treeInvocationRef = treeInvocationRef;
        }

        boolean exposureExpired() {
            return exposureExpiresAt != null && !Instant.now().isBefore(exposureExpiresAt);
        }
    }

    /**
     * 一次调用的句柄实现：订阅 transport 上游事件，处理 client_tool 自动驱动，
     * 把面向业务的事件转发到自有 SubmissionPublisher，并在终态完成 completion。
     */
    private final class CallImpl implements InvocationCall, Flow.Subscriber<InvocationEvent> {
        /** 每批向上游申请的事件数；消费到批次的一半时补充，保持有界在途量。 */
        private static final int DEMAND_BATCH = 32;

        private final String invocationRef;
        private final String conversationId;
        private final Flow.Publisher<CallTreeSnapshot> callTreePublisher;
        private final String treeInvocationRef;
        private final java.util.concurrent.SubmissionPublisher<InvocationEvent> downstream =
                new java.util.concurrent.SubmissionPublisher<>();
        private final CompletableFuture<InvocationSnapshot> completion = new CompletableFuture<>();
        private final CompletableFuture<Handle> accepted = new CompletableFuture<>();
        private final StringBuilder output = new StringBuilder();
        private final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicBoolean pendingClientTool =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private volatile Flow.Subscription subscription;
        private volatile TaskState lastState = TaskState.SUBMITTED;

        /** 进展不确定的原因；非空表示本次调用以"不确定"结算而非终态。 */
        private volatile String uncertainReason;
        private int consumedSinceRequest;

        CallImpl(String invocationRef, String conversationId, Flow.Publisher<CallTreeSnapshot> callTreePublisher) {
            this(invocationRef, conversationId, callTreePublisher, invocationRef);
        }

        CallImpl(String invocationRef, String conversationId, Flow.Publisher<CallTreeSnapshot> callTreePublisher,
                String treeInvocationRef) {
            this.invocationRef = invocationRef;
            this.conversationId = conversationId;
            this.callTreePublisher = callTreePublisher;
            this.treeInvocationRef = treeInvocationRef;
        }

        @Override
        public String invocationRef() {
            return invocationRef;
        }

        @Override
        public String conversationId() {
            return conversationId;
        }

        @Override
        public CompletionStage<Handle> accepted() {
            return accepted;
        }

        @Override
        public Flow.Publisher<InvocationEvent> events() {
            return downstream;
        }

        @Override
        public Flow.Publisher<CallTreeSnapshot> callTree() {
            return callTreePublisher;
        }

        @Override
        public CompletionStage<InvocationSnapshot> completion() {
            return completion;
        }

        @Override
        public void close() {
            // 幂等关闭：取消上游订阅、关闭下游 publisher。不影响服务端 Task 状态，不抛异常。
            try {
                if (subscription != null) {
                    subscription.cancel();
                }
            } catch (IllegalStateException | NullPointerException ignore) {
                // AutoCloseable 契约：close 不抛异常。
            }
            transport.closeObservation(treeInvocationRef);
            if (!finished.get()) {
                finishExceptionally(new CancellationException(
                        "local observation closed by caller; server Task was not cancelled"));
            }
            calls.remove(invocationRef, this);
            invocations.remove(invocationRef);
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            // 有界 demand：不再 request(Long.MAX_VALUE)，让上游可感知本地消费速度。
            subscription.request(DEMAND_BATCH);
        }

        @Override
        public void onNext(InvocationEvent event) {
            replenishDemand();
            InvocationState state = invocations.get(invocationRef);
            // JDK 17 基线：不使用 switch 的类型模式匹配（Java 21 才转正），改用 instanceof 模式。
            if (event instanceof InvocationEvent.Accepted a) {
                if (state != null) {
                    state.taskRef = a.diagnosticTaskRef();
                }
                // 结算 accepted() future（Handle 携带诊断用 taskRef）。
                if (!accepted.isDone()) {
                    accepted.complete(new Handle(invocationRef, conversationId, a.diagnosticTaskRef()));
                }
                forward(a);
            } else if (event instanceof InvocationEvent.StatusChanged s) {
                lastState = s.state();
                forward(s);
                if (s.terminal()) {
                    finishTerminal(s.state(), null, null);
                }
            } else if (event instanceof InvocationEvent.ContentDelta d) {
                output.append(d.text());
                forward(d);
            } else if (event instanceof InvocationEvent.InputRequired ir) {
                InvocationEvent.ToolCall tc = ir.toolCall();
                if (tc != null && state != null) {
                    // client_tool：由 SDK 自动就地执行并续传，不打扰业务。
                    pendingClientTool.set(true);
                    driveClientTool(state, tc);
                } else {
                    // 需要用户补充输入：转发给业务，由其调用 continueInput。
                    lastState = TaskState.INPUT_REQUIRED;
                    forward(ir);
                }
            } else if (event instanceof InvocationEvent.ProtocolDiagnostic diagnostic) {
                forward(diagnostic);
            } else if (event instanceof InvocationEvent.ProgressUncertain pu) {
                // 记录恢复线索。Gateway 正常关流时由 onComplete 按既有契约结算；
                // Runtime 会紧随一个 onError，使 completion 异常完成，不伪造 Task 终态。
                lastState = (pu.lastKnownState() != null) ? pu.lastKnownState() : TaskState.UNKNOWN;
                uncertainReason = pu.reason();
                forward(pu);
            } else if (event instanceof InvocationEvent.Completed c) {
                if (c.outputText() != null) {
                    output.setLength(0);
                    output.append(c.outputText());
                }
                forward(c);
                finishTerminal(TaskState.COMPLETED, null, null);
            } else if (event instanceof InvocationEvent.Failed f) {
                forward(f);
                finishTerminal(TaskState.FAILED, f.errorCode(), f.message());
            } else {
                // 理论不可达：所有 InvocationEvent 子类型均已覆盖。
                throw new IllegalStateException("unexpected event: " + event);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (accepted.isDone() && !accepted.isCompletedExceptionally()) {
                // Task 已受理后这里只能说明 Client 失去观察能力，不能伪造服务端 FAILED 终态。
                finishExceptionally(throwable);
            } else {
                // taskId 尚未取得时创建本身失败，保持既有“创建失败快照”契约。
                String code = ClassifiedError.codeOf(throwable);
                forward(new InvocationEvent.Failed(invocationRef, code, throwable.getMessage(),
                        ClassifiedError.retryableOf(throwable)));
                finishTerminal(TaskState.FAILED, code, throwable.getMessage());
            }
        }

        @Override
        public void onComplete() {
            // 上游流结束但未到终态：以最后已知状态兜底完成（不制造额外失败，也不悬挂）。
            if (!finished.get()) {
                finishTerminal(lastState, null, null);
            }
        }

        private void replenishDemand() {
            Flow.Subscription s = subscription;
            if (s == null) {
                return;
            }
            if (++consumedSinceRequest >= DEMAND_BATCH / 2) {
                consumedSinceRequest = 0;
                s.request(DEMAND_BATCH / 2);
            }
        }

        /**
         * 有待处理 client_tool 返回 true。
         *
         * @return 有待处理 client_tool 返回 true
         */
        boolean hasPendingClientTool() {
            return pendingClientTool.get();
        }

        /**
         * 在"等待输入"点结算本调用：业务已通过 {@code continueInput} 把后续交给新的 invocationRef，
         * 本句柄不会再收到任何事件。以非终态 INPUT_REQUIRED 结算，避免调用方悬挂。
         */
        void settleAtInputPoint() {
            finishTerminal(TaskState.INPUT_REQUIRED, null, null);
        }

        void acceptExistingTask(String taskRef) {
            if (!accepted.isDone()) {
                accepted.complete(new Handle(invocationRef, conversationId, taskRef));
                forward(new InvocationEvent.Accepted(invocationRef, taskRef, conversationId));
            }
        }

        /**
         * continueInput 续跑 unary 响应成功：把响应快照转为面向业务的事件投递到新 Call 的事件流，
         * 并据此完成 completion（006 §3.4.1：新 invocationRef 的 events/completion 复用同一 Task 后续投影）。
         *
         * @param snap 续跑响应快照
         */
        void completeFromResume(InvocationSnapshot snap) {
            if (finished.get()) {
                return;
            }
            InvocationState state = invocations.get(invocationRef);
            String taskRef = (state != null && state.taskRef != null)
                    ? state.taskRef : snap.diagnosticTaskRef();
            if (!accepted.isDone()) {
                accepted.complete(new Handle(invocationRef, conversationId, taskRef));
                forward(new InvocationEvent.Accepted(invocationRef, taskRef, conversationId));
            }
            TaskState st = (snap.state() != null) ? snap.state() : TaskState.UNKNOWN;
            lastState = st;
            String text = snap.outputText();
            // 必须累积到 output：finishTerminal 用它组装 completion() 快照的 outputText。
            // 只 forward 事件而不累积，会让快照驱动的 invocation（continueInput 及其内部工具续跑）
            // 拿到 outputText=null 的 completion，业务侧表现为"续轮结果为空"。
            if (text != null) {
                output.setLength(0);
                output.append(text);
            }
            if (st == TaskState.INPUT_REQUIRED) {
                handleResumeInputRequired(state, snap, text);
                return;
            }
            if (forwardResumeStateEvent(st, snap, text)) {
                // ASYNC 非终态：保持 Call 未完成，等待业务后续 getInvocation，不结算。
                return;
            }
            finishTerminal(st, snap.errorCode(), snap.message());
        }

        void completeFromQuery(InvocationSnapshot snap) {
            if (finished.get()) {
                return;
            }
            completeFromResume(snap);
        }

        /**
         * 续跑响应到达 INPUT_REQUIRED：投递等待点事件，并按是否有 client_tool 分流。
         *
         * @param state 内部状态，可为 null
         * @param snap 续跑响应快照
         * @param text 累积后的输出文本
         */
        private void handleResumeInputRequired(InvocationState state, InvocationSnapshot snap, String text) {
            // 等待点之前的正文单独作为增量投递；终态分支不这样做，避免与 Completed 携带的文本重复。
            if (text != null && !text.isEmpty()) {
                forward(new InvocationEvent.ContentDelta(invocationRef, text));
            }
            forward(new InvocationEvent.StatusChanged(invocationRef, TaskState.INPUT_REQUIRED, false));
            InvocationEvent.ToolCall tc = snap.pendingToolCall();
            if (tc != null && state != null) {
                // 续轮里服务端又要求端侧工具：仍由 SDK 自动执行并续传，业务不感知（FRZ-1）。
                // 置位 resumeToolAtInputPoint：此处工具若超时/失败，按方案 A 回退工具结果而非拉死 Call，
                // 让服务端 Task 继续挂在 INPUT_REQUIRED，业务仍可再次 continueInput。
                pendingClientTool.set(true);
                state.resumeToolAtInputPoint = true;
                driveClientTool(state, tc);
            } else {
                forward(new InvocationEvent.InputRequired(invocationRef, null, null));
                finishTerminal(TaskState.INPUT_REQUIRED, null, null);
            }
        }

        /**
         * 续跑响应到达非 INPUT_REQUIRED 状态：按状态类型投递对应事件。
         *
         * @param st 任务状态
         * @param snap 续跑响应快照
         * @param text 累积后的输出文本
         * @return ASYNC 非终态返回 true（保持 Call 未完成，跳过 finishTerminal）；其余返回 false
         */
        private boolean forwardResumeStateEvent(TaskState st, InvocationSnapshot snap, String text) {
            if (st == TaskState.COMPLETED) {
                forward(new InvocationEvent.Completed(invocationRef, text));
                return false;
            }
            if (st == TaskState.FAILED) {
                String code = (snap.errorCode() != null) ? snap.errorCode() : ErrorCodes.AGENT_ERROR;
                forward(new InvocationEvent.Failed(invocationRef, code, snap.message()));
                return false;
            }
            if (st.isTerminal()) {
                forward(new InvocationEvent.StatusChanged(invocationRef, st, true));
                return false;
            }
            forward(new InvocationEvent.StatusChanged(invocationRef, st, false));
            if (text != null && !text.isEmpty()) {
                forward(new InvocationEvent.ContentDelta(invocationRef, text));
            }
            InvocationState state = invocations.get(invocationRef);
            if (state != null && state.mode == InvocationMode.ASYNC) {
                return true;
            }
            uncertainReason = "strict unary SendMessage returned non-terminal state " + st;
            forward(new InvocationEvent.ProgressUncertain(invocationRef, st, uncertainReason));
            return false;
        }

        /**
         * continueInput 续跑失败：以 transport_error 终态完成新 Call。
         *
         * @param ex 异常
         */
        void failFromResume(Throwable ex) {
            if (accepted.isDone() && !accepted.isCompletedExceptionally()) {
                // 续轮关联的 Task 已受理；续传/观察失败不等于服务端 Task FAILED。
                finishExceptionally(ex);
                return;
            }
            String code = ClassifiedError.codeOf(ex);
            forward(new InvocationEvent.Failed(invocationRef, code, ex.getMessage(),
                    ClassifiedError.retryableOf(ex)));
            finishTerminal(TaskState.FAILED, code, ex.getMessage());
        }

        private void forward(InvocationEvent event) {
            if (!downstream.isClosed()) {
                downstream.submit(event);
            }
        }

        private void finishTerminal(TaskState state, String errorCode, String message) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            TaskState settled = (state != null) ? state : TaskState.UNKNOWN;
            // 兜底：若未收到 Accepted 就终态，以异常完成 accepted，避免业务永远阻塞。
            if (!accepted.isDone()) {
                accepted.completeExceptionally(new IllegalStateException(
                        "call terminated before accepted: state=" + settled
                                + (errorCode != null ? ", errorCode=" + errorCode : "")));
            }
            InvocationState is = invocations.get(invocationRef);
            String taskRef = (is != null) ? is.taskRef : null;
            String materializedOutput = currentOutputText(treeInvocationRef)
                    .orElse(output.length() > 0 ? output.toString() : null);
            InvocationSnapshot snapshot = new InvocationSnapshot(
                    invocationRef, settled, settled.isTerminal(), taskRef, null,
                    materializedOutput, errorCode, message,
                    recoveryHint(is, taskRef).orElse(null), currentCallTree(treeInvocationRef).orElse(null));
            completion.complete(snapshot);
            downstream.close();
            if (subscription != null) {
                subscription.cancel();
            }
            if (settled.isTerminal()) {
                calls.remove(invocationRef, this);
                retainTerminalInvocation(invocationRef);
            }
        }

        private void finishExceptionally(Throwable failure) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            if (!accepted.isDone()) {
                accepted.completeExceptionally(failure);
            }
            completion.completeExceptionally(failure);
            downstream.closeExceptionally(failure);
            if (subscription != null) {
                subscription.cancel();
            }
            calls.remove(invocationRef, this);
            retainTerminalInvocation(invocationRef);
        }

        /**
         * 进展不确定时给出结构化恢复线索：告诉业务"该查还是该以同键重发"，而不是让它自己猜。
         *
         * @param is 内部状态，可为 null
         * @param taskRef 诊断任务引用，可为 null
         * @return 恢复线索；进展确定时为空
         */
        private Optional<InvocationSnapshot.Recovery> recoveryHint(InvocationState is, String taskRef) {
            String reason = uncertainReason;
            if (reason == null) {
                return Optional.empty();
            }
            // 已有 taskRef → Task 确已创建，重发会造重复，只能查询；
            // 无 taskRef → 创建未被确认，须以同幂等键、同正文重发，由网关幂等回放取回原 Task。
            InvocationSnapshot.Recovery.Action action;
            if (taskRef != null) {
                action = InvocationSnapshot.Recovery.Action.QUERY_INVOCATION;
            } else if (reason.contains("runtime create outcome unknown")) {
                action = InvocationSnapshot.Recovery.Action.MANUAL_RECONCILIATION;
            } else {
                action = InvocationSnapshot.Recovery.Action.RETRY_CREATE_SAME_KEY;
            }
            return Optional.of(new InvocationSnapshot.Recovery(reason, conversationId,
                    (is != null) ? is.idempotencyKey : null, action));
        }
    }

    private void retainTerminalInvocation(String invocationRef) {
        terminalInvocations.add(invocationRef);
        while (terminalInvocations.size() > MAX_RETAINED_TERMINAL_INVOCATIONS) {
            String expired = terminalInvocations.poll();
            if (expired != null) {
                invocations.remove(expired);
            }
        }
    }
}
