/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.internal;

import com.huawei.ascend.client.api.AgentClient;
import com.huawei.ascend.client.api.ClassifiedError;
import com.huawei.ascend.client.api.ContinueInputRequest;
import com.huawei.ascend.client.api.ErrorCodes;
import com.huawei.ascend.client.api.Handle;
import com.huawei.ascend.client.api.InvocationCall;
import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationMode;
import com.huawei.ascend.client.api.InvocationNotResumableException;
import com.huawei.ascend.client.api.InvocationRequest;
import com.huawei.ascend.client.api.InvocationSnapshot;
import com.huawei.ascend.client.api.TaskState;
import com.huawei.ascend.client.spi.Governance;
import com.huawei.ascend.client.state.spi.ClientStateStore;
import com.huawei.ascend.client.tool.spi.LocalToolDescriptor;
import com.huawei.ascend.client.tool.spi.LocalToolRegistry;
import com.huawei.ascend.client.tool.spi.ToolExecutionContext;
import com.huawei.ascend.client.tool.spi.ToolExecutionRecord;
import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;
import com.huawei.ascend.client.tool.spi.ToolView;
import com.huawei.ascend.client.transport.spi.CredentialProvider;
import com.huawei.ascend.client.transport.spi.ToolWireSpec;
import com.huawei.ascend.client.transport.spi.TransportProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;

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
    private final java.util.Set<String> resumeGuard = ConcurrentHashMap.newKeySet();

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
                effective.expiresAt().orElse(null));
        invocations.put(invocationRef, state);

        TransportProvider.CreateCommand cmd = new TransportProvider.CreateCommand(
                invocationRef, request.invocationId(), request.idempotencyKey(),
                request.conversationId(), request.agentId().orElse(null), request.mode(), request.input(),
                clientTools, credential, null, request.attributes());

        Flow.Publisher<InvocationEvent> upstream = transport.createAndStream(cmd);
        CallImpl call = new CallImpl(invocationRef, request.conversationId());
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
                .thenApply(snap -> withInvocationRef(invocationRef, snap));
    }

    /**
     * 传输层按 taskRef 解析出的 invocationRef 可能是回退值，这里统一改写为业务持有的句柄。
     *
     * @param invocationRef 业务持有的调用句柄
     * @param snap 传输层快照
     * @return 句柄已归正的快照
     */
    private static InvocationSnapshot withInvocationRef(String invocationRef, InvocationSnapshot snap) {
        if (snap == null) {
            return InvocationSnapshot.unknown(invocationRef);
        }
        if (invocationRef.equals(snap.invocationRef())) {
            return snap;
        }
        return new InvocationSnapshot(invocationRef, snap.state(), snap.terminal(),
                snap.diagnosticTaskRef(), snap.pendingToolCall(), snap.outputText(),
                snap.errorCode(), snap.message(), snap.recovery());
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
        // 续传轮继承原轮的暴露窗口：续接的是同一个服务端 Task，授权窗口不应因换了个 invocation 而重开。
        InvocationState newState = new InvocationState(
                newInvocationRef, request.conversationId(), related.mode,
                related.clientTools, related.credentialToken, request.idempotencyKey(), true,
                related.exposureExpiresAt);
        newState.taskRef = related.taskRef;
        invocations.put(newInvocationRef, newState);

        // SNAPSHOT_ONLY：传输层据此把响应帧留给本次返回快照，不汇入旧 invocation 的事件流。
        // messageId 取 idempotencyKey（缺省即 invocationId）：网关按 messageId 去重，
        // 用 invocationId 会让调用方显式设置的幂等键失效，续传重试就会产生重复副作用。
        TransportProvider.ResumeCommand cmd = new TransportProvider.ResumeCommand(
                newInvocationRef, related.taskRef, request.idempotencyKey(), null,
                request.input(), related.mode, related.clientTools, related.credentialToken,
                related.conversationId, TransportProvider.ResumeDelivery.SNAPSHOT_ONLY);

        // 新 CallImpl：不订阅 transport 旧 Channel，而是由续跑同步响应直接驱动其事件流与 completion。
        CallImpl newCall = new CallImpl(newInvocationRef, request.conversationId());
        calls.put(newInvocationRef, newCall);

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
                .thenAccept(record -> submitToolResult(state, call.toolCallId(), record));
    }

    private void submitToolResult(InvocationState state, String toolCallId,
                                  com.huawei.ascend.client.tool.spi.ToolExecutionRecord record) {
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
        volatile String taskRef;

        InvocationState(String invocationRef, String conversationId, InvocationMode mode,
                        List<ToolWireSpec> clientTools, String credentialToken, String idempotencyKey,
                        boolean snapshotDriven) {
            this(invocationRef, conversationId, mode, clientTools, credentialToken, idempotencyKey,
                    snapshotDriven, null);
        }

        InvocationState(String invocationRef, String conversationId, InvocationMode mode,
                        List<ToolWireSpec> clientTools, String credentialToken, String idempotencyKey,
                        boolean snapshotDriven, Instant exposureExpiresAt) {
            this.invocationRef = invocationRef;
            this.conversationId = conversationId;
            this.mode = mode;
            this.clientTools = clientTools;
            this.credentialToken = credentialToken;
            this.idempotencyKey = idempotencyKey;
            this.snapshotDriven = snapshotDriven;
            this.exposureExpiresAt = exposureExpiresAt;
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

        CallImpl(String invocationRef, String conversationId) {
            this.invocationRef = invocationRef;
            this.conversationId = conversationId;
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
            if (!downstream.isClosed()) {
                downstream.close();
            }
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
            } else if (event instanceof InvocationEvent.ProgressUncertain pu) {
                // FEAT-006 §5.1.4：流中断不等于失败。记录原因，由 finishTerminal 附带恢复线索结算。
                lastState = (pu.lastKnownState() != null) ? pu.lastKnownState() : TaskState.UNKNOWN;
                uncertainReason = pu.reason();
                forward(pu);
                finishTerminal(lastState, null, null);
            } else if (event instanceof InvocationEvent.Completed c) {
                if (c.outputText() != null && !c.outputText().isEmpty()) {
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
            String code = ClassifiedError.codeOf(throwable);
            forward(new InvocationEvent.Failed(invocationRef, code, throwable.getMessage(),
                    ClassifiedError.retryableOf(throwable)));
            finishTerminal(TaskState.FAILED, code, throwable.getMessage());
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

        /**
         * continueInput 续跑同步响应成功：把响应快照转为面向业务的事件投递到新 Call 的事件流，
         * 并据此完成 completion（006 §3.4.1：新 invocationRef 的 events/completion 复用同一 Task 后续投影）。
         *
         * @param snap 续跑响应快照
         */
        void completeFromResume(InvocationSnapshot snap) {
            if (finished.get()) {
                return;
            }
            TaskState st = (snap.state() != null) ? snap.state() : TaskState.UNKNOWN;
            lastState = st;
            String text = snap.outputText();
            // 必须累积到 output：finishTerminal 用它组装 completion() 快照的 outputText。
            // 只 forward 事件而不累积，会让快照驱动的 invocation（continueInput 及其内部工具续跑）
            // 拿到 outputText=null 的 completion，业务侧表现为"续轮结果为空"。
            if (text != null && !text.isEmpty()) {
                output.setLength(0);
                output.append(text);
            }
            if (st == TaskState.INPUT_REQUIRED) {
                // 等待点之前的正文单独作为增量投递；终态分支不这样做，避免与 Completed 携带的文本重复。
                if (text != null && !text.isEmpty()) {
                    forward(new InvocationEvent.ContentDelta(invocationRef, text));
                }
                forward(new InvocationEvent.StatusChanged(invocationRef, st, false));
                InvocationEvent.ToolCall tc = snap.pendingToolCall();
                InvocationState state = invocations.get(invocationRef);
                if (tc != null && state != null) {
                    // 续轮里服务端又要求端侧工具：仍由 SDK 自动执行并续传，业务不感知（FRZ-1）。
                    pendingClientTool.set(true);
                    driveClientTool(state, tc);
                } else {
                    forward(new InvocationEvent.InputRequired(invocationRef, null, null));
                }
                // INPUT_REQUIRED 非终态：保持新 Call 开放，等待业务再次 continueInput 或 SDK 自动工具续跑。
                return;
            }
            if (st == TaskState.COMPLETED) {
                forward(new InvocationEvent.Completed(invocationRef, text));
            } else if (st == TaskState.FAILED) {
                String code = (snap.errorCode() != null) ? snap.errorCode() : ErrorCodes.AGENT_ERROR;
                forward(new InvocationEvent.Failed(invocationRef, code, snap.message()));
            } else if (st.isTerminal()) {
                forward(new InvocationEvent.StatusChanged(invocationRef, st, true));
            } else if (text != null && !text.isEmpty()) {
                // 非终态且非等待点：正文作为增量投递，状态留待后续帧推进。
                forward(new InvocationEvent.ContentDelta(invocationRef, text));
            }
            finishTerminal(st, snap.errorCode(), snap.message());
        }

        /**
         * continueInput 续跑失败：以 transport_error 终态完成新 Call。
         *
         * @param ex 异常
         */
        void failFromResume(Throwable ex) {
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
            InvocationSnapshot snapshot = new InvocationSnapshot(
                    invocationRef, settled, settled.isTerminal(), taskRef, null,
                    output.length() > 0 ? output.toString() : null, errorCode, message,
                    recoveryHint(is, taskRef));
            completion.complete(snapshot);
            downstream.close();
            if (subscription != null) {
                subscription.cancel();
            }
        }

        /**
         * 进展不确定时给出结构化恢复线索：告诉业务"该查还是该以同键重发"，而不是让它自己猜。
         *
         * @param is 内部状态，可为 null
         * @param taskRef 诊断任务引用，可为 null
         * @return 恢复线索；进展确定时返回 null
         */
        private InvocationSnapshot.Recovery recoveryHint(InvocationState is, String taskRef) {
            String reason = uncertainReason;
            if (reason == null) {
                return null;
            }
            // 已有 taskRef → Task 确已创建，重发会造重复，只能查询；
            // 无 taskRef → 创建未被确认，须以同幂等键、同正文重发，由网关幂等回放取回原 Task。
            InvocationSnapshot.Recovery.Action action = (taskRef != null)
                    ? InvocationSnapshot.Recovery.Action.QUERY_INVOCATION
                    : InvocationSnapshot.Recovery.Action.RETRY_CREATE_SAME_KEY;
            return new InvocationSnapshot.Recovery(reason, conversationId,
                    (is != null) ? is.idempotencyKey : null, action);
        }
    }
}