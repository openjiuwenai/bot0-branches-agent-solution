package com.huawei.ascend.client.verify;

import com.huawei.ascend.client.api.AgentClient;
import com.huawei.ascend.client.api.AgentClients;
import com.huawei.ascend.client.api.ContinueInputRequest;
import com.huawei.ascend.client.api.InvocationCall;
import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationMode;
import com.huawei.ascend.client.api.InvocationRequest;
import com.huawei.ascend.client.api.InvocationSnapshot;
import com.huawei.ascend.client.api.TaskState;
import com.huawei.ascend.client.spi.Governance;
import com.huawei.ascend.client.tool.spi.ToolExecutionRecord;
import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;
import com.huawei.ascend.client.tool.spi.ToolInvocation;
import com.huawei.ascend.client.transport.a2a.A2aHttpTransportProvider;
import com.huawei.ascend.client.transport.spi.CredentialProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话式驱动器（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>编排"发起一次 query → 订阅 SDK 事件流 → 翻译成对话消息 → 跑断言"的全过程，
 * 把消息经 {@link ChatBroadcaster} 推给前端 SSE。
 *
 * <p>会话模型：
 * <ul>
 *   <li>串行组 query 复用同一 {@link Session}（同一 {@code AgentClient} + 同一 {@code conversationId}）。</li>
 *   <li>单独组 / demo 组每次新建 {@link Session}。</li>
 * </ul>
 */
final class ConversationDriver {

    private final String gatewayUrl;
    private final ChatBroadcaster broadcaster;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();

    ConversationDriver(String gatewayUrl, ChatBroadcaster broadcaster) {
        this.gatewayUrl = gatewayUrl;
        this.broadcaster = broadcaster;
    }

    /** 启动时广播网关信息。 */
    void announceGateway() {
        broadcaster.broadcast(ChatMessage.info(null, "网关: " + gatewayUrl + " (external)"));
    }

    /** 创建一个新会话，返回 sessionId。 */
    String createSession(String label) {
        String id = "session-" + sessionSeq.incrementAndGet();
        Session s = new Session(id, label);
        sessions.put(id, s);
        broadcaster.broadcast(ChatMessage.sessionCreated(id, label));
        return id;
    }

    List<SessionInfo> sessions() {
        List<SessionInfo> out = new ArrayList<>();
        for (Session s : sessions.values()) {
            out.add(new SessionInfo(s.id, s.label, s.conversationId, s.messageCount));
        }
        return out;
    }

    /**
     * 在指定会话上跑一条 query（同步：阻塞到该 query 终态 + 断言完成）。
     *
     * @return 该 query 的断言结果汇总（ok=true 表示全部断言通过）。
     */
    QueryResult runQuery(String queryId, String sessionId) {
        QueryCatalog.Query q = QueryCatalog.find(queryId);
        Session session = sessions.get(sessionId);
        if (session == null) {
            String newId = createSession(q.id());
            session = sessions.get(newId);
            sessionId = newId;
        }
        broadcaster.broadcast(ChatMessage.scenarioStart(sessionId, queryId, q.displayName()));

        // 串行组首次进入时，把 conversationId 固化为串行共享值；后续串行 query 复用。
        if (q.conversationStrategy() == QueryCatalog.ConversationStrategy.REUSE_SERIAL
                && session.conversationId == null) {
            session.conversationId = QueryCatalog.SERIAL_CONVERSATION_ID;
        }

        List<Assertion> assertions = new ArrayList<>();
        boolean overall;

        switch (queryId) {
            case "s1" -> overall = runStreamingClientTools(session, q, assertions);
            case "s2" -> overall = runUnsupportedModeThenPing(session, q, assertions);
            case "s3" -> overall = runContinueInput(session, q, assertions);
            case "s4" -> overall = runPlainMultiTurn(session, q, assertions);
            case "s5" -> overall = runDefaultNoExposure(session, q, assertions);
            case "s6" -> overall = runGovernanceError(session, q, assertions);
            default -> overall = runPlainDemo(session, q, assertions);
        }

        broadcaster.broadcast(ChatMessage.scenarioEnd(sessionId, queryId, overall));
        session.messageCount++;
        return new QueryResult(queryId, sessionId, overall, assertions);
    }

    /** 在同一会话上按序串行跑多条 query。 */
    List<QueryResult> runSerial(List<String> queryIds, String sessionId) {
        List<QueryResult> results = new ArrayList<>();
        for (String qid : queryIds) {
            results.add(runQuery(qid, sessionId));
        }
        return results;
    }

    // ---------------------- scenarios ----------------------

    private boolean runStreamingClientTools(Session s, QueryCatalog.Query q, List<Assertion> out) {
        int readBefore = s.tools.readPageCount.get();
        int submitBefore = s.tools.submitOrderCount.get();
        int approvalBefore = s.approvalCount.get();

        if (q.exposure().isPresent()) {
            s.client.exposeInConversation(s.conversationId, q.exposure().get());
        }
        InvocationSnapshot snap = invokeAndWait(s, q, out);
        boolean ok = true;
        ok &= check(out, "s1", snap.state() == TaskState.COMPLETED,
                "streaming invocation completed, state=" + snap.state());
        ok &= check(out, "s1", s.tools.readPageCount.get() == readBefore + 1,
                "readPage executed exactly once despite duplicate INPUT_REQUIRED, actual="
                        + (s.tools.readPageCount.get() - readBefore));
        ok &= check(out, "s1", s.tools.submitOrderCount.get() == submitBefore + 1,
                "submitOrder executed exactly once, actual="
                        + (s.tools.submitOrderCount.get() - submitBefore));
        ok &= check(out, "s1", s.approvalCount.get() == approvalBefore + 1,
                "approval requested exactly once for the ACTION tool, actual="
                        + (s.approvalCount.get() - approvalBefore));
        return ok;
    }

    private boolean runUnsupportedModeThenPing(Session s, QueryCatalog.Query q, List<Assertion> out) {
        // 先验证 BLOCKING 被立即拒绝（不产生对话流，仅作为断言）。
        boolean rejected = false;
        String detail = "";
        try {
            s.client.invoke(InvocationRequest.builder()
                    .agentId(q.agentId().orElse(null))
                    .conversationId(s.conversationId)
                    .mode(InvocationMode.BLOCKING)
                    .input(q.input())
                    .build());
        } catch (UnsupportedOperationException e) {
            rejected = true;
            detail = String.valueOf(e.getMessage());
        }
        boolean ok = true;
        ok &= check(out, "s2", rejected && detail.contains("UNSUPPORTED_MODE"),
                "BLOCKING rejected with UNSUPPORTED_MODE, detail=" + detail);
        if (rejected) {
            broadcaster.broadcast(ChatMessage.info(s.id,
                    "BLOCKING 已被 SDK 拒绝: " + detail));
        }

        // 再跑 STREAMING ping，产生对话流。
        if (q.exposure().isPresent()) {
            s.client.exposeInConversation(s.conversationId, q.exposure().get());
        }
        int pingBefore = s.tools.pingCount.get();
        InvocationSnapshot snap = invokeAndWait(s, q, out);
        ok &= check(out, "s2", snap.state() == TaskState.COMPLETED,
                "streaming ping invocation completed, state=" + snap.state());
        ok &= check(out, "s2", s.tools.pingCount.get() == pingBefore + 1,
                "ping executed exactly once, actual=" + (s.tools.pingCount.get() - pingBefore));
        return ok;
    }

    private boolean runContinueInput(Session s, QueryCatalog.Query q, List<Assertion> out) {
        InvocationRequest request = InvocationRequest.builder()
                .conversationId(s.conversationId)
                .mode(InvocationMode.STREAMING)
                .input(q.input())
                .build();
        InvocationCall call = s.client.invoke(request);
        broadcaster.broadcast(ChatMessage.user(s.id, call.invocationRef(), q.input()));
        broadcaster.broadcast(ChatMessage.status(s.id, call.invocationRef(), "INPUT_REQUIRED", "等待用户输入…"));

        CountDownLatch userPrompt = new CountDownLatch(1);
        call.events().subscribe(new Flow.Subscriber<>() {
            Flow.Subscription sub;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.sub = subscription;
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent event) {
                if (event instanceof InvocationEvent.InputRequired ir && ir.toolCall() == null) {
                    broadcaster.broadcast(ChatMessage.info(s.id,
                            "收到用户输入提示，续传 continueInput=\"Alice\""));
                    s.client.continueInput(ContinueInputRequest.builder()
                            .conversationId(s.conversationId)
                            .relatedInvocationRef(call.invocationRef())
                            .mode(InvocationMode.STREAMING)
                            .input("Alice")
                            .build());
                    userPrompt.countDown();
                } else if (event instanceof InvocationEvent.Completed c) {
                    broadcaster.broadcast(ChatMessage.assistantFinal(s.id,
                            call.invocationRef(), c.outputText()));
                } else if (event instanceof InvocationEvent.Failed f) {
                    broadcaster.broadcast(ChatMessage.error(s.id,
                            call.invocationRef(), f.errorCode(), f.message()));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                broadcaster.broadcast(ChatMessage.error(s.id,
                        call.invocationRef(), "subscription_error", throwable.getMessage()));
            }

            @Override
            public void onComplete() {
            }
        });

        try {
            InvocationSnapshot snap = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            boolean ok = check(out, "s3", snap.state() == TaskState.COMPLETED,
                    "user-input continuation completed, state=" + snap.state());
            call.close();
            return ok;
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
            broadcaster.broadcast(ChatMessage.error(s.id,
                    call.invocationRef(), "unexpected", String.valueOf(e)));
            out.add(new Assertion("s3", false, "unexpected exception: " + e));
            call.close();
            return false;
        }
    }

    private boolean runPlainMultiTurn(Session s, QueryCatalog.Query q, List<Assertion> out) {
        int readBefore = s.tools.readPageCount.get();
        int submitBefore = s.tools.submitOrderCount.get();
        int pingBefore = s.tools.pingCount.get();

        InvocationRequest r1 = InvocationRequest.builder()
                .conversationId(s.conversationId)
                .mode(InvocationMode.STREAMING)
                .input(q.input() + " 1")
                .exposure(q.exposure().orElse(ToolExposurePolicy.none()))
                .build();
        InvocationCall c1 = s.client.invoke(r1);
        broadcaster.broadcast(ChatMessage.user(s.id, c1.invocationRef(), r1.input()));
        InvocationSnapshot s1;
        try {
            c1.accepted().toCompletableFuture().get(10, TimeUnit.SECONDS);
            s1 = c1.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            broadcaster.broadcast(ChatMessage.assistantFinal(s.id, c1.invocationRef(), s1.outputText()));
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
            out.add(new Assertion("s4", false, "turn 1 failed: " + e));
            c1.close();
            return false;
        }

        InvocationRequest r2 = InvocationRequest.builder()
                .conversationId(s.conversationId)
                .mode(InvocationMode.STREAMING)
                .input(q.input() + " 2")
                .exposure(q.exposure().orElse(ToolExposurePolicy.none()))
                .build();
        InvocationCall c2 = s.client.invoke(r2);
        broadcaster.broadcast(ChatMessage.user(s.id, c2.invocationRef(), r2.input()));
        InvocationSnapshot s2;
        try {
            c2.accepted().toCompletableFuture().get(10, TimeUnit.SECONDS);
            s2 = c2.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            broadcaster.broadcast(ChatMessage.assistantFinal(s.id, c2.invocationRef(), s2.outputText()));
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
            out.add(new Assertion("s4", false, "turn 2 failed: " + e));
            c2.close();
            c1.close();
            return false;
        }

        boolean ok = true;
        ok &= check(out, "s4", s1.state() == TaskState.COMPLETED, "turn 1 completed, state=" + s1.state());
        ok &= check(out, "s4", s2.state() == TaskState.COMPLETED, "turn 2 completed, state=" + s2.state());
        ok &= check(out, "s4", !c1.invocationRef().equals(c2.invocationRef()),
                "two turns have distinct invocationRef");
        ok &= check(out, "s4", s.conversationId.equals(c1.conversationId())
                        && s.conversationId.equals(c2.conversationId()),
                "two turns share the same conversationId");
        ok &= check(out, "s4", s.tools.readPageCount.get() == readBefore
                        && s.tools.submitOrderCount.get() == submitBefore
                        && s.tools.pingCount.get() == pingBefore,
                "no tools executed during plain multi-turn");
        c1.close();
        c2.close();
        return ok;
    }

    private boolean runDefaultNoExposure(Session s, QueryCatalog.Query q, List<Assertion> out) {
        int readBefore = s.tools.readPageCount.get();
        int submitBefore = s.tools.submitOrderCount.get();
        int pingBefore = s.tools.pingCount.get();
        InvocationSnapshot snap = invokeAndWait(s, q, out);
        boolean ok = true;
        ok &= check(out, "s5", snap.state() == TaskState.COMPLETED,
                "default-no-exposure completed without tool calls, state=" + snap.state());
        ok &= check(out, "s5", s.tools.readPageCount.get() == readBefore
                        && s.tools.submitOrderCount.get() == submitBefore
                        && s.tools.pingCount.get() == pingBefore,
                "no tools executed when no exposure declared (default empty ToolView)");
        return ok;
    }

    private boolean runGovernanceError(Session s, QueryCatalog.Query q, List<Assertion> out) {
        // 构造一个不提供 credential 的 client：每次 HTTP 不带 Authorization → 网关 401 AUTH_MISSING。
        AgentClient noAuthClient = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(gatewayUrl))
                .policyGuard(Governance.PolicyGuard.allowAll())
                .approvalProvider(Governance.ApprovalProvider.autoApprove())
                .build();
        try {
            InvocationRequest request = InvocationRequest.builder()
                    .conversationId(s.conversationId)
                    .mode(InvocationMode.STREAMING)
                    .input(q.input())
                    .build();
            InvocationCall call = noAuthClient.invoke(request);
            broadcaster.broadcast(ChatMessage.user(s.id, call.invocationRef(), q.input()));
            subscribeEvents(s, call, out, "s6");
            InvocationSnapshot snap = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            boolean ok = true;
            ok &= check(out, "s6", snap.state() == TaskState.FAILED,
                    "401 governance error surfaced as FAILED, not COMPLETED, state=" + snap.state());
            ok &= check(out, "s6", snap.errorCode() != null && !snap.errorCode().isEmpty(),
                    "failed snapshot carries an errorCode, errorCode=" + snap.errorCode());
            call.close();
            return ok;
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
            broadcaster.broadcast(ChatMessage.error(s.id, null, "unexpected", String.valueOf(e)));
            out.add(new Assertion("s6", false, "unexpected exception: " + e));
            return false;
        } finally {
            noAuthClient.close();
        }
    }

    private boolean runPlainDemo(Session s, QueryCatalog.Query q, List<Assertion> out) {
        if (q.exposure().isPresent()) {
            s.client.exposeInConversation(s.conversationId, q.exposure().get());
        }
        int readBefore = s.tools.readPageCount.get();
        int submitBefore = s.tools.submitOrderCount.get();
        int pingBefore = s.tools.pingCount.get();
        InvocationSnapshot snap = invokeAndWait(s, q, out);
        boolean ok = check(out, q.id(), snap.state() == TaskState.COMPLETED,
                "demo invocation completed, state=" + snap.state());
        // demo 不强断言工具执行次数，仅作信息提示。
        int toolsRun = (s.tools.readPageCount.get() - readBefore)
                + (s.tools.submitOrderCount.get() - submitBefore)
                + (s.tools.pingCount.get() - pingBefore);
        if (toolsRun > 0) {
            broadcaster.broadcast(ChatMessage.info(s.id,
                    "demo 触发了 " + toolsRun + " 次本地工具执行"));
        }
        return ok;
    }

    // ---------------------- helpers ----------------------

    /** 发起 STREAMING 调用、广播 user 消息、订阅事件流、等待终态。 */
    private InvocationSnapshot invokeAndWait(Session s, QueryCatalog.Query q, List<Assertion> out) {
        InvocationRequest.Builder b = InvocationRequest.builder()
                .conversationId(s.conversationId)
                .mode(InvocationMode.STREAMING)
                .input(q.input());
        q.agentId().ifPresent(b::agentId);
        q.exposure().ifPresent(b::exposure);
        InvocationRequest request = b.build();

        InvocationCall call = s.client.invoke(request);
        broadcaster.broadcast(ChatMessage.user(s.id, call.invocationRef(), q.input()));
        subscribeEvents(s, call, out, q.id());
        try {
            InvocationSnapshot snap = call.completion().toCompletableFuture().get(30, TimeUnit.SECONDS);
            call.close();
            return snap;
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
            broadcaster.broadcast(ChatMessage.error(s.id, call.invocationRef(), "unexpected", String.valueOf(e)));
            out.add(new Assertion(q.id(), false, "unexpected exception: " + e));
            call.close();
            return InvocationSnapshot.unknown(call.invocationRef());
        }
    }

    /** 订阅事件流，把 SDK 事件翻译成对话消息。 */
    private void subscribeEvents(Session s, InvocationCall call, List<Assertion> out, String scenarioId) {
        call.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent event) {
                if (event instanceof InvocationEvent.Accepted a) {
                    broadcaster.broadcast(ChatMessage.status(s.id, a.invocationRef(),
                            "ACCEPTED", "已受理 taskRef=" + a.diagnosticTaskRef()));
                } else if (event instanceof InvocationEvent.StatusChanged sc) {
                    broadcaster.broadcast(ChatMessage.status(s.id, sc.invocationRef(),
                            sc.state().name(), "状态变更: " + sc.state()
                                    + (sc.terminal() ? " (终态)" : "")));
                } else if (event instanceof InvocationEvent.ContentDelta cd) {
                    broadcaster.broadcast(ChatMessage.assistantDelta(s.id, cd.invocationRef(), cd.text()));
                } else if (event instanceof InvocationEvent.InputRequired ir) {
                    if (ir.toolCall() == null) {
                        broadcaster.broadcast(ChatMessage.status(s.id, ir.invocationRef(),
                                "INPUT_REQUIRED", "需要用户补充输入"));
                    }
                    // client_tool 类型的 InputRequired 由 SDK 自动消费，不会到这里。
                } else if (event instanceof InvocationEvent.Completed c) {
                    if (c.outputText() != null && !c.outputText().isEmpty()) {
                        broadcaster.broadcast(ChatMessage.assistantFinal(s.id,
                                c.invocationRef(), c.outputText()));
                    }
                } else if (event instanceof InvocationEvent.Failed f) {
                    broadcaster.broadcast(ChatMessage.error(s.id,
                            f.invocationRef(), f.errorCode(), f.message()));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                broadcaster.broadcast(ChatMessage.error(s.id, call.invocationRef(),
                        "subscription_error", throwable.getMessage()));
            }

            @Override
            public void onComplete() {
            }
        });
    }

    private boolean check(List<Assertion> out, String scenarioId, boolean condition, String message) {
        out.add(new Assertion(scenarioId, condition, message));
        broadcaster.broadcast(ChatMessage.assertion(null, scenarioId, condition, message));
        return condition;
    }

    // ---------------------- 内部类型 ----------------------

    /** 一个对话会话：拥有独立的 client / conversationId / 工具计数快照。 */
    final class Session {
        final String id;
        final String label;
        final AgentClient client;
        final DemoTools tools = new DemoTools();
        final AtomicInteger approvalCount = new AtomicInteger();
        volatile String conversationId;
        int messageCount = 0;

        Session(String id, String label) {
            this.id = id;
            this.label = label;
            this.conversationId = "conv-" + id;
            this.client = AgentClients.builder()
                    .transport(new A2aHttpTransportProvider(gatewayUrl))
                    .credentialProvider(CredentialProvider.staticToken("mock-token"))
                    .policyGuard(Governance.PolicyGuard.allowAll())
                    .approvalProvider((d, i, c) -> {
                        approvalCount.incrementAndGet();
                        broadcaster.broadcast(ChatMessage.info(this.id,
                                "审批通过 ACTION 工具: " + d.toolId()));
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                Governance.ApprovalDecision.approve());
                    })
                    .build();
            // 工具执行观察者：把 toolName/arguments/payload 推给前端。
            tools.registerInto(client, this::onToolExecuted);
        }

        private void onToolExecuted(ToolInvocation invocation, ToolExecutionRecord record) {
            ToolExecutionObserver.Snapshot snap = ToolExecutionObserver.Snapshot.of(invocation, record);
            broadcaster.broadcast(ChatMessage.toolCall(id, invocation.toolCallId(),
                    snap.toolName(), snap.arguments()));
            broadcaster.broadcast(ChatMessage.toolResult(id, invocation.toolCallId(),
                    snap.outcome().name(), snap.payload(), snap.errorCode(), snap.message()));
        }
    }

    record SessionInfo(String id, String label, String conversationId, int messageCount) {
    }

    record Assertion(String scenarioId, boolean ok, String message) {
    }

    record QueryResult(String queryId, String sessionId, boolean ok, List<Assertion> assertions) {
    }
}
