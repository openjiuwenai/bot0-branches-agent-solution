package com.huawei.ascend.client.internal;

import com.huawei.ascend.client.api.AgentClient;
import com.huawei.ascend.client.api.ContinueInputRequest;
import com.huawei.ascend.client.api.Handle;
import com.huawei.ascend.client.api.InvocationCall;
import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationMode;
import com.huawei.ascend.client.api.InvocationRequest;
import com.huawei.ascend.client.api.InvocationSnapshot;
import com.huawei.ascend.client.api.TaskState;
import com.huawei.ascend.client.spi.Governance;
import com.huawei.ascend.client.state.spi.ClientStateStore;
import com.huawei.ascend.client.tool.spi.LocalToolDescriptor;
import com.huawei.ascend.client.tool.spi.LocalToolRegistry;
import com.huawei.ascend.client.tool.spi.ToolExecutionContext;
import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;
import com.huawei.ascend.client.tool.spi.ToolView;
import com.huawei.ascend.client.transport.spi.CredentialProvider;
import com.huawei.ascend.client.transport.spi.ToolWireSpec;
import com.huawei.ascend.client.transport.spi.TransportProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

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
 *   <li>维护 {@code invocationRef → taskRef} 的映射（业务只见 invocationRef，taskRef 内部使用）。</li>
 *   <li>计算生效的工具暴露策略并投影为 ToolView / wire clientTools。</li>
 *   <li>把 client_tool 类型的 INPUT_REQUIRED 自动就地执行并续传，业务侧只观测到连续事件流。</li>
 *   <li>对续传做"每 toolCallId 只提交一次"的防抖，与调度器的"最多执行一次"配合。</li>
 * </ul>
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
        // 交付面即能力面（FEAT-006 §2.5）：本迭代只交付 STREAMING，其余模式立即拒绝。
        if (request.mode() != InvocationMode.STREAMING) {
            throw new UnsupportedOperationException(
                    "UNSUPPORTED_MODE: only STREAMING is delivered in this iteration; mode=" + request.mode());
        }
        ToolExposurePolicy effective = effectivePolicy(request.conversationId(), request.exposure().orElse(null));
        List<ToolWireSpec> clientTools = toWireSpecs(registry.toolView(effective));
        String credential = resolveCredential(request.conversationId(), request.credentialToken().orElse(null));

        String invocationRef = request.invocationId();
        InvocationState state = new InvocationState(
                invocationRef, request.conversationId(), request.mode(),
                clientTools, credential);
        invocations.put(invocationRef, state);

        TransportProvider.CreateCommand cmd = new TransportProvider.CreateCommand(
                invocationRef, request.invocationId(), request.idempotencyKey(),
                request.conversationId(), request.agentId().orElse(null), request.mode(), request.input(),
                clientTools, credential, null);

        Flow.Publisher<InvocationEvent> upstream = transport.createAndStream(cmd);
        CallImpl call = new CallImpl(invocationRef, request.conversationId());
        calls.put(invocationRef, call);
        upstream.subscribe(call);
        return call;
    }

    @Override
    public InvocationCall continueInput(ContinueInputRequest request) {
        InvocationState related = invocations.get(request.relatedInvocationRef());
        CallImpl relatedCall = calls.get(request.relatedInvocationRef());
        if (related == null || relatedCall == null || related.taskRef == null) {
            throw new IllegalStateException(
                    "cannot continue input: unknown related invocation or task mapping not established yet: "
                            + request.relatedInvocationRef());
        }
        // feat-011 §6.9 AC-S4-4：SDK 本地预检 relatedInvocationRef 须处于 INPUT_REQUIRED 且无 client_tool；
        // 不可续接（已终态 / 正处 client_tool 待执行）→ 明确错误，不静默发续跑。
        if (relatedCall.lastState != TaskState.INPUT_REQUIRED) {
            throw new IllegalStateException(
                    "cannot continue input: related invocation is not in INPUT_REQUIRED (state="
                            + relatedCall.lastState + "): " + request.relatedInvocationRef());
        }
        if (relatedCall.hasPendingClientTool()) {
            throw new IllegalStateException(
                    "cannot continue input: related invocation is waiting for a client_tool result, "
                            + "use the SDK auto-resume path instead: " + request.relatedInvocationRef());
        }
        // 006 §3.4.1：建立业务可见的新 invocationRef，映射到同一 taskRef（一个 Task 可被多个 invocationRef 引用）。
        String newInvocationRef = request.invocationId();
        InvocationState newState = new InvocationState(
                newInvocationRef, request.conversationId(), related.mode,
                related.clientTools, related.credentialToken);
        newState.taskRef = related.taskRef;
        invocations.put(newInvocationRef, newState);

        TransportProvider.ResumeCommand cmd = new TransportProvider.ResumeCommand(
                newInvocationRef, related.taskRef, request.invocationId(), null,
                request.input(), related.mode, related.clientTools, related.credentialToken,
                related.conversationId);

        // 新 CallImpl：不订阅 transport 旧 Channel，而是由续跑同步响应直接驱动其事件流与 completion。
        CallImpl newCall = new CallImpl(newInvocationRef, request.conversationId());
        calls.put(newInvocationRef, newCall);

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

    /** 解析本次请求应附带的凭证：单次覆盖优先，否则回退到客户端级 CredentialProvider。 */
    private String resolveCredential(String conversationId, String perRequestToken) {
        if (perRequestToken != null && !perRequestToken.isEmpty()) {
            return perRequestToken;
        }
        return Optional.ofNullable(credentials)
                .map(c -> c.tokenFor(conversationId))
                .orElse(null);
    }

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

    private static List<ToolWireSpec> toWireSpecs(ToolView view) {
        List<ToolWireSpec> out = new ArrayList<>();
        for (LocalToolDescriptor d : view.tools()) {
            out.add(new ToolWireSpec(d.wireName(), d.description(), d.inputSchema()));
        }
        return List.copyOf(out);
    }

    private void driveClientTool(InvocationState state, InvocationEvent.ToolCall call) {
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
        TransportProvider.ResumeCommand cmd = new TransportProvider.ResumeCommand(
                state.invocationRef, state.taskRef, "msg-" + UUID.randomUUID(), toolCallId,
                observationText, state.mode, state.clientTools, state.credentialToken, state.conversationId);
        transport.resumeToolResult(cmd).whenComplete((snap, ex) -> {
            if (ex == null) {
                store.markSubmitted(toolCallId);
            } else {
                resumeGuard.remove(toolCallId); // 允许失败后重试
            }
        });
    }

    /** 每个 invocationRef 的内部状态（含 taskRef 映射与上报的 ToolView）。 */
    private static final class InvocationState {
        final String invocationRef;
        final String conversationId;
        final InvocationMode mode;
        final List<ToolWireSpec> clientTools;
        final String credentialToken;
        volatile String taskRef;

        InvocationState(String invocationRef, String conversationId, InvocationMode mode,
                        List<ToolWireSpec> clientTools, String credentialToken) {
            this.invocationRef = invocationRef;
            this.conversationId = conversationId;
            this.mode = mode;
            this.clientTools = clientTools;
            this.credentialToken = credentialToken;
        }
    }

    /**
     * 一次调用的句柄实现：订阅 transport 上游事件，处理 client_tool 自动驱动，
     * 把面向业务的事件转发到自有 SubmissionPublisher，并在终态完成 completion。
     */
    private final class CallImpl implements InvocationCall, Flow.Subscriber<InvocationEvent> {
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
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(InvocationEvent event) {
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
            }
        }

        @Override
        public void onError(Throwable throwable) {
            forward(new InvocationEvent.Failed(invocationRef, "transport_error", throwable.getMessage()));
            finishTerminal(TaskState.FAILED, "transport_error", throwable.getMessage());
        }

        @Override
        public void onComplete() {
            // 上游流结束但未到终态：以最后已知状态兜底完成（不制造额外失败）。
            if (!finished.get()) {
                finishTerminal(lastState, null, null);
            }
        }

        /** 是否正处 client_tool 待执行（SDK 自动续跑路径占用，禁止 continueInput）。 */
        boolean hasPendingClientTool() {
            return pendingClientTool.get();
        }

        /**
         * continueInput 续跑同步响应成功：把响应快照转为面向业务的事件投递到新 Call 的事件流，
         * 并据此完成 completion（006 §3.4.1：新 invocationRef 的 events/completion 复用同一 Task 后续投影）。
         */
        void completeFromResume(InvocationSnapshot snap) {
            if (finished.get()) {
                return;
            }
            lastState = (snap.state() != null) ? snap.state() : TaskState.UNKNOWN;
            if (snap.outputText() != null && !snap.outputText().isEmpty()) {
                forward(new InvocationEvent.ContentDelta(invocationRef, snap.outputText()));
            }
            TaskState st = snap.state();
            if (st == TaskState.INPUT_REQUIRED) {
                forward(new InvocationEvent.StatusChanged(invocationRef, st, false));
                InvocationEvent.ToolCall tc = snap.pendingToolCall();
                forward(new InvocationEvent.InputRequired(invocationRef, tc, null));
                // INPUT_REQUIRED 非终态：保持新 Call 开放，等待业务再次 continueInput 或 SDK 自动工具续跑。
                return;
            }
            if (st == TaskState.COMPLETED) {
                forward(new InvocationEvent.Completed(invocationRef, snap.outputText()));
            } else if (st == TaskState.FAILED) {
                String code = (snap.errorCode() != null) ? snap.errorCode() : "agent_error";
                forward(new InvocationEvent.Failed(invocationRef, code, snap.message()));
            } else if (st != null && st.isTerminal()) {
                forward(new InvocationEvent.StatusChanged(invocationRef, st, true));
            }
            finishTerminal(st, snap.errorCode(), snap.message());
        }

        /** continueInput 续跑失败：以 transport_error 终态完成新 Call。 */
        void failFromResume(Throwable ex) {
            forward(new InvocationEvent.Failed(invocationRef, "transport_error", ex.getMessage()));
            finishTerminal(TaskState.FAILED, "transport_error", ex.getMessage());
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
            // 兜底：若未收到 Accepted 就终态，以异常完成 accepted，避免业务永远阻塞。
            if (!accepted.isDone()) {
                accepted.completeExceptionally(new IllegalStateException(
                        "call terminated before accepted: state=" + state
                                + (errorCode != null ? ", errorCode=" + errorCode : "")));
            }
            InvocationState is = invocations.get(invocationRef);
            String taskRef = (is != null) ? is.taskRef : null;
            InvocationSnapshot snapshot = new InvocationSnapshot(
                    invocationRef, state, state.isTerminal(), taskRef, null,
                    output.length() > 0 ? output.toString() : null, errorCode, message);
            completion.complete(snapshot);
            downstream.close();
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }
}
