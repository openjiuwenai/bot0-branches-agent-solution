package com.huawei.ascend.client.verify;

import com.huawei.ascend.client.api.AgentClient;
import com.huawei.ascend.client.api.AgentClients;
import com.huawei.ascend.client.api.ContinueInputRequest;
import com.huawei.ascend.client.api.Handle;
import com.huawei.ascend.client.api.InvocationCall;
import com.huawei.ascend.client.api.InvocationEvent;
import com.huawei.ascend.client.api.InvocationMode;
import com.huawei.ascend.client.api.InvocationRequest;
import com.huawei.ascend.client.api.InvocationSnapshot;
import com.huawei.ascend.client.api.TaskState;
import com.huawei.ascend.client.spi.Governance;
import com.huawei.ascend.client.tool.spi.ToolExposurePolicy;
import com.huawei.ascend.client.transport.a2a.A2aHttpTransportProvider;
import com.huawei.ascend.client.transport.spi.CredentialProvider;
import com.huawei.ascend.mockgateway.MockGatewayServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可执行自校验用例（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>用法：
 * <ul>
 *   <li>{@code java ... CloudClientVerification} —— CLI，跑完退出，退出码 0/1。</li>
 *   <li>{@code java ... CloudClientVerification --ui} —— 打开薄可视化前端（浏览器）。</li>
 * </ul>
 */
public final class CloudClientVerification {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(CloudClientVerification.class.getName());

    private final List<String> failures = new ArrayList<>();
    private final AtomicInteger approvalCount = new AtomicInteger();
    private VerificationProgress progress = event -> {
        String nl = System.lineSeparator();
        switch (event.kind()) {
            case RUN_START -> System.out.println("[verify] gateway=" + event.message());
            case SCENARIO_START -> System.out.println(nl + "== " + event.message() + " ==");
            case CHECK -> System.out.println((Boolean.TRUE.equals(event.ok()) ? "  [ok]   " : "  [FAIL] ")
                    + event.message());
            case RUN_END -> System.out.println(nl + event.message());
            default -> {
                if (event.message() != null) {
                    System.out.println("  " + event.message());
                }
            }
        }
    };

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--ui".equals(arg) || "ui".equalsIgnoreCase(arg)) {
                ConversationApiServer.main(new String[0]);
                return;
            }
        }
        System.exit(new CloudClientVerification().run());
    }

    /** 供 Web UI 调用：注入进度回调后跑完全部场景。 */
    public int runWithProgress(VerificationProgress progress)
            throws InterruptedException, ExecutionException, TimeoutException, IOException {
        this.progress = progress;
        return run();
    }

    private int run() throws InterruptedException, ExecutionException, TimeoutException, IOException {
        failures.clear();
        approvalCount.set(0);

        String url = System.getenv("AGENT_GATEWAY_URL");
        MockGatewayServer embedded = null;
        if (url == null || url.isBlank()) {
            embedded = new MockGatewayServer(0);
            int port = embedded.start();
            url = "http://127.0.0.1:" + port;
            progress.onEvent(VerificationProgress.Event.runStart(url + " (embedded)"));
        } else {
            progress.onEvent(VerificationProgress.Event.runStart(url + " (external)"));
        }

        DemoTools tools = new DemoTools();
        AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(url))
                // 每次到网关的 HTTP 都附带 Bearer（Feat-Func-011 §4.9 强制鉴权）。
                .credentialProvider(CredentialProvider.staticToken("mock-token"))
                .policyGuard(Governance.PolicyGuard.allowAll())
                .approvalProvider((d, i, c) -> {
                    approvalCount.incrementAndGet();
                    progress.onEvent(VerificationProgress.Event.info(
                            null, "approval granted for ACTION tool: " + d.toolId()));
                    return CompletableFuture.completedFuture(Governance.ApprovalDecision.approve());
                })
                .build();
        tools.registerInto(client);

        try {
            scenarioStreamingClientTools(client, tools);
            scenarioUnsupportedModeRejected(client, tools);
            scenarioContinueInput(client);
            scenarioPlainMultiTurn(client, tools);
            scenarioDefaultNoExposure(client, tools);
            scenarioGovernanceErrorNotProjected(url);
        } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
            failures.add("unexpected exception: " + e);
            progress.onEvent(VerificationProgress.Event.info(null, "unexpected exception: " + e));
            LOG.log(java.util.logging.Level.WARNING, "verification failed", e);
        } finally {
            client.close();
            if (embedded != null) {
                embedded.stop();
            }
        }

        boolean ok = failures.isEmpty();
        String summary = ok ? "ALL CHECKS PASSED" : (failures.size() + " CHECK(S) FAILED");
        progress.onEvent(VerificationProgress.Event.runEnd(ok, summary));
        return ok ? 0 : 1;
    }

    private void scenarioStreamingClientTools(AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        String id = "s1";
        progress.onEvent(VerificationProgress.Event.scenarioStart(id,
                "Scenario 1: STREAMING + client tools (real HTTP + SSE)"));
        int beforeFails = failures.size();

        String conversationId = "conv-stream-1";
        client.exposeInConversation(conversationId,
                ToolExposurePolicy.allow(DemoTools.READ_PAGE, DemoTools.SUBMIT_ORDER));
        InvocationRequest request = InvocationRequest.builder()
                .agentId("agent-x")
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("please read the page then submit the order")
                .build();
        InvocationCall call = client.invoke(request);
        progress.onEvent(VerificationProgress.Event.info(id,
                "invoke STREAMING invocationRef=" + call.invocationRef()));
        InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

        check(id, snapshot.state() == TaskState.COMPLETED,
                "streaming invocation completed, state=" + snapshot.state());
        check(id, tools.readPageCount.get() == 1,
                "readPage executed exactly once despite duplicate INPUT_REQUIRED, actual="
                        + tools.readPageCount.get());
        check(id, tools.submitOrderCount.get() == 1,
                "submitOrder executed exactly once, actual=" + tools.submitOrderCount.get());
        check(id, approvalCount.get() == 1,
                "approval requested exactly once for the ACTION tool, actual=" + approvalCount.get());

        progress.onEvent(VerificationProgress.Event.scenarioEnd(id, failures.size() == beforeFails));
    }

    private void scenarioUnsupportedModeRejected(AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        String id = "s2";
        progress.onEvent(VerificationProgress.Event.scenarioStart(id,
                "Scenario 2: unsupported mode rejected + STREAMING ping (交付面即能力面)"));
        int beforeFails = failures.size();

        String conversationId = "conv-mode-1";
        client.exposeInConversation(conversationId, ToolExposurePolicy.allow(DemoTools.PING));

        // 本迭代仅交付 STREAMING：BLOCKING 应被 invoke() 立即以 UNSUPPORTED_MODE 拒绝。
        boolean rejected = false;
        String detail = "";
        try {
            client.invoke(InvocationRequest.builder()
                    .agentId("agent-x")
                    .conversationId(conversationId)
                    .mode(InvocationMode.BLOCKING)
                    .input("run ping")
                    .build());
        } catch (UnsupportedOperationException e) {
            rejected = true;
            detail = String.valueOf(e.getMessage());
        }
        check(id, rejected && detail.contains("UNSUPPORTED_MODE"),
                "BLOCKING rejected with UNSUPPORTED_MODE, detail=" + detail);

        // STREAMING 下同一个 ping 工具应能正常跑通一轮。
        InvocationRequest request = InvocationRequest.builder()
                .agentId("agent-x")
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("run ping")
                .build();
        InvocationCall call = client.invoke(request);
        progress.onEvent(VerificationProgress.Event.info(id,
                "invoke STREAMING invocationRef=" + call.invocationRef()));
        InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

        check(id, snapshot.state() == TaskState.COMPLETED,
                "streaming ping invocation completed, state=" + snapshot.state());
        check(id, tools.pingCount.get() == 1,
                "ping executed exactly once, actual=" + tools.pingCount.get());

        progress.onEvent(VerificationProgress.Event.scenarioEnd(id, failures.size() == beforeFails));
    }

    private void scenarioContinueInput(AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        String id = "s3";
        progress.onEvent(VerificationProgress.Event.scenarioStart(id,
                "Scenario 3: user-input continuation (continueInput over real HTTP)"));
        int beforeFails = failures.size();

        String conversationId = "conv-ui-1";
        // 不指定 agentId：验证 agentId 可选，由网关路由到默认 Agent（Feat-Func-011 §4.9 AC-4）。
        InvocationRequest request = InvocationRequest.builder()
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("NEEDS_USER_INPUT: what is your name?")
                .build();
        InvocationCall call = client.invoke(request);
        progress.onEvent(VerificationProgress.Event.info(id,
                "waiting for INPUT_REQUIRED (user input), then continueInput"));

        call.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent event) {
                if (event instanceof InvocationEvent.InputRequired ir && ir.toolCall() == null) {
                    progress.onEvent(VerificationProgress.Event.info(id,
                            "got user-input prompt, submitting continueInput=\"Alice\""));
                    client.continueInput(ContinueInputRequest.builder()
                            .conversationId(conversationId)
                            .relatedInvocationRef(call.invocationRef())
                            .mode(InvocationMode.STREAMING)
                            .input("Alice")
                            .build());
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
        check(id, snapshot.state() == TaskState.COMPLETED,
                "user-input continuation completed, state=" + snapshot.state());

        progress.onEvent(VerificationProgress.Event.scenarioEnd(id, failures.size() == beforeFails));
    }

    /** Scenario 4: 普通多轮对话（复用同一 conversationId 再 invoke 无 taskId 创建，得到新 Task）。 */
    private void scenarioPlainMultiTurn(AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        String id = "s4";
        progress.onEvent(VerificationProgress.Event.scenarioStart(id,
                "Scenario 4: plain multi-turn (reuse conversationId, new Task each turn)"));
        int beforeFails = failures.size();

        String conversationId = "conv-multi-1";
        // 记录本轮开始前的工具执行计数（前面场景已用过工具），断言本轮前后不变。
        int readBefore = tools.readPageCount.get();
        int submitBefore = tools.submitOrderCount.get();
        int pingBefore = tools.pingCount.get();
        // 第一轮：不声明 exposure、普通文本，应直接 COMPLETED（IMMEDIATE），无工具调用。
        InvocationRequest r1 = InvocationRequest.builder()
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("hello turn 1")
                .build();
        InvocationCall c1 = client.invoke(r1);
        Handle h1 = c1.accepted().toCompletableFuture().get(10, TimeUnit.SECONDS);
        InvocationSnapshot s1 = c1.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

        // 第二轮：复用同一 conversationId，新 invocation，无 taskId（普通多轮=新建 Task）。
        InvocationRequest r2 = InvocationRequest.builder()
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("hello turn 2")
                .build();
        InvocationCall c2 = client.invoke(r2);
        Handle h2 = c2.accepted().toCompletableFuture().get(10, TimeUnit.SECONDS);
        InvocationSnapshot s2 = c2.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

        check(id, s1.state() == TaskState.COMPLETED, "turn 1 completed, state=" + s1.state());
        check(id, s2.state() == TaskState.COMPLETED, "turn 2 completed, state=" + s2.state());
        check(id, !c1.invocationRef().equals(c2.invocationRef()),
                "two turns have distinct invocationRef");
        check(id, conversationId.equals(c1.conversationId())
                        && conversationId.equals(c2.conversationId()),
                "two turns share the same conversationId");
        // 普通多轮不应触发任何本地工具执行（计数与本轮开始前一致）。
        check(id, tools.readPageCount.get() == readBefore
                        && tools.submitOrderCount.get() == submitBefore
                        && tools.pingCount.get() == pingBefore,
                "no tools executed during plain multi-turn");
        // accepted() 回执携带诊断 taskRef，且两轮不同（各是新 Task）。
        check(id, h1.diagnosticTaskRef() != null && h2.diagnosticTaskRef() != null
                        && !h1.diagnosticTaskRef().equals(h2.diagnosticTaskRef()),
                "accepted() handles carry distinct diagnosticTaskRef across turns");

        c1.close();
        c2.close();
        progress.onEvent(VerificationProgress.Event.scenarioEnd(id, failures.size() == beforeFails));
    }

    /** Scenario 5: 默认不暴露（不声明 exposure → ToolView 为空 → 服务端不可见任何本地工具）。 */
    private void scenarioDefaultNoExposure(AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        String id = "s5";
        progress.onEvent(VerificationProgress.Event.scenarioStart(id,
                "Scenario 5: default-no-exposure (no exposure declared → no clientTools on wire)"));
        int beforeFails = failures.size();

        // 用一个全新的 conversationId，不 exposeInConversation、不在 request 里声明 exposure。
        String conversationId = "conv-noexp-1";
        int readBefore = tools.readPageCount.get();
        int submitBefore = tools.submitOrderCount.get();
        int pingBefore = tools.pingCount.get();

        InvocationRequest request = InvocationRequest.builder()
                .agentId("agent-x")
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("please read the page and submit the order")
                .build();
        InvocationCall call = client.invoke(request);
        InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

        // 默认不暴露：服务端看不到 clientTools → mock 走 IMMEDIATE 直接 COMPLETED，不请求任何工具。
        check(id, snapshot.state() == TaskState.COMPLETED,
                "default-no-exposure completed without tool calls, state=" + snapshot.state());
        check(id, tools.readPageCount.get() == readBefore
                        && tools.submitOrderCount.get() == submitBefore
                        && tools.pingCount.get() == pingBefore,
                "no tools executed when no exposure declared (default empty ToolView)");
        call.close();
        progress.onEvent(VerificationProgress.Event.scenarioEnd(id, failures.size() == beforeFails));
    }

    /** Scenario 6: 治理错误（401 AUTH_MISSING）不投影为成功 Task，而是以 Failed 终态暴露。 */
    private void scenarioGovernanceErrorNotProjected(String url)
            throws InterruptedException, ExecutionException, TimeoutException {
        String id = "s6";
        progress.onEvent(VerificationProgress.Event.scenarioStart(id,
                "Scenario 6: governance error (401) not projected as success"));
        int beforeFails = failures.size();

        // 构造一个不提供 credential 的 client：每次 HTTP 不带 Authorization → 网关 401 AUTH_MISSING。
        AgentClient noAuthClient = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(url))
                .policyGuard(Governance.PolicyGuard.allowAll())
                .approvalProvider(Governance.ApprovalProvider.autoApprove())
                .build();
        try {
            InvocationRequest request = InvocationRequest.builder()
                    .conversationId("conv-noauth-1")
                    .mode(InvocationMode.STREAMING)
                    .input("should be rejected by gateway")
                    .build();
            InvocationCall call = noAuthClient.invoke(request);
            InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);

            // 401 治理错误不应投影为 COMPLETED；应以 FAILED 终态暴露（feat-011 §4.9 AC-7 / 006 §5.3）。
            check(id, snapshot.state() == TaskState.FAILED,
                    "401 governance error surfaced as FAILED, not COMPLETED, state=" + snapshot.state());
            check(id, snapshot.errorCode() != null && !snapshot.errorCode().isEmpty(),
                    "failed snapshot carries an errorCode, errorCode=" + snapshot.errorCode());
            call.close();
        } finally {
            noAuthClient.close();
        }
        progress.onEvent(VerificationProgress.Event.scenarioEnd(id, failures.size() == beforeFails));
    }

    private void check(String scenarioId, boolean condition, String message) {
        progress.onEvent(VerificationProgress.Event.check(scenarioId, condition, message));
        if (!condition) {
            failures.add(message);
        }
    }
}
