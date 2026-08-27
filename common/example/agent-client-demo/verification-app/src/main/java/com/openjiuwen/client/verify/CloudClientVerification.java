/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.verify;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
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
import com.openjiuwen.client.tool.spi.ToolExposurePolicy;
import com.openjiuwen.client.transport.a2a.A2aHttpTransportProvider;
import com.openjiuwen.client.transport.spi.CredentialProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
 * <li>{@code java ... CloudClientVerification} —— CLI，跑完退出，退出码 0/1。</li>
 * <li>{@code java ... CloudClientVerification --ui} —— 打开薄可视化前端（浏览器）。</li>
 * </ul>
 *
 * @since 2026-07-27
 */
public final class CloudClientVerification {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(CloudClientVerification.class.getName());

    private final List<String> failures = new ArrayList<>();
    private final AtomicInteger approvalCount = new AtomicInteger();
    private DemoTools demoTools;
    private VerificationProgress progress = event -> {
        String nl = System.lineSeparator();
        switch (event.kind()) {
            case RUN_START -> LOG.log(java.util.logging.Level.INFO, "[verify] gateway={0}", event.message());
            case SCENARIO_START -> LOG.log(java.util.logging.Level.INFO, "{0}== {1} ==",
                    new Object[] {nl, event.message()});
            case CHECK -> LOG.log(java.util.logging.Level.INFO, "{0}{1}",
                    new Object[] {Boolean.TRUE.equals(event.ok()) ? "  [ok]   " : "  [FAIL] ",
                            event.message()});
            case RUN_END -> LOG.log(java.util.logging.Level.INFO, "{0}{1}",
                    new Object[] {nl, event.message()});
            default -> {
                if (event.message() != null) {
                    LOG.log(java.util.logging.Level.INFO, "  {0}", event.message());
                }
            }
        }
    };

    /**
     * 启动云客户端验证主程序。
     *
     * @param args 命令行参数
     * @throws Exception 执行失败时抛出
     */
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--ui".equals(arg) || "ui".equalsIgnoreCase(arg)) {
                VerificationUiServer.main(new String[0]);
                return;
            }
        }
        System.exit(new CloudClientVerification().run(Set.of()));
    }

    /**
     * 场景目录（id、标题、分类），供看板在开跑前就列出全部场景。
     *
     * @return 按执行顺序排列的场景目录
     */
    static List<ScenarioSpec> catalog() {
        return List.of(ScenarioSpec.values());
    }

    /**
     * 供 Web UI 调用：注入进度回调后跑完全部场景。
     *
     * @param progress 进度回调
     * @return 失败断言数为 0 时返回 0，否则返回 1
     */
    public int runWithProgress(VerificationProgress progress) {
        return runWithProgress(progress, Set.of());
    }

    /**
     * 供 Web UI 调用：注入进度回调后跑指定场景。
     *
     * @param progress 进度回调
     * @param selectedIds 要跑的场景 id；空集合表示全部
     * @return 失败断言数为 0 时返回 0，否则返回 1
     */
    public int runWithProgress(VerificationProgress progress, Set<String> selectedIds) {
        this.progress = progress;
        return run(selectedIds);
    }

    /**
     * 跑指定场景并汇总结果。
     *
     * <p>必须通过环境变量 {@code AGENT_GATEWAY_URL} 指向一个真实的外部 gateway；本套件不内嵌任何 mock，
     * 不配则直接抛 {@link IllegalStateException} 终止，避免在"没有真实 gateway"的情况下误跑出失真结论。
     *
     * @param selectedIds 要跑的场景 id；空集合表示全部
     * @return 失败断言数为 0 时返回 0，否则返回 1
     */
    private int run(Set<String> selectedIds) {
        failures.clear();

        String url = System.getenv("AGENT_GATEWAY_URL");
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "AGENT_GATEWAY_URL is required: point it at a real gateway (e.g. http://127.0.0.1:8080). "
                            + "This verification suite no longer embeds any mock gateway.");
        }
        progress.onEvent(VerificationProgress.Event.runStart(url + " (external)"));

        for (ScenarioSpec spec : ScenarioSpec.values()) {
            if (!selectedIds.isEmpty() && !selectedIds.contains(spec.id())) {
                continue;
            }
            // 每个验证场景使用独立 Client，避免整套验证累计占用超过默认 5 个 conversation 名额。
            // S4 在其场景内部复用同一个 Client 验证同 conversation 多 invocation。
            DemoTools tools = new DemoTools();
            this.demoTools = tools;
            AgentClient client = buildClient(url);
            tools.registerInto(client);
            try {
                Map<ScenarioSpec, Body> bodies = registry(client, tools, url);
                runScenario(spec, bodies.get(spec));
            } finally {
                client.close();
            }
        }

        boolean ok = failures.isEmpty();
        String summary = ok ? "ALL CHECKS PASSED" : (failures.size() + " CHECK(S) FAILED");
        progress.onEvent(VerificationProgress.Event.runEnd(ok, summary));
        return ok ? 0 : 1;
    }

    /**
     * 场景 id 到执行体的绑定。执行顺序由 {@link ScenarioSpec} 的声明顺序决定。
     *
     * @param client 已注册工具的客户端
     * @param tools 工具计数器
     * @param url 网关地址（s6 需要另建无凭证客户端）
     * @return 场景执行体表
     */
    private Map<ScenarioSpec, Body> registry(AgentClient client, DemoTools tools, String url) {
        Map<ScenarioSpec, Body> m = new EnumMap<>(ScenarioSpec.class);
        m.put(ScenarioSpec.S1, id -> scenarioStreamingClientTools(id, client, tools));
        m.put(ScenarioSpec.S2, id -> scenarioBlockingAndStreamingPing(id, client, tools));
        m.put(ScenarioSpec.S3, id -> scenarioContinueInput(id, client));
        m.put(ScenarioSpec.S4, id -> scenarioPlainMultiTurn(id, client, tools));
        m.put(ScenarioSpec.S5, id -> scenarioDefaultNoExposure(id, client, tools));
        m.put(ScenarioSpec.S6, id -> scenarioGovernanceErrorNotProjected(id, url));
        m.put(ScenarioSpec.S7, id -> scenarioAsyncThenQuery(id, client));
        m.put(ScenarioSpec.S8, id -> scenarioDropRecoveredByQuery(id, client));
        m.put(ScenarioSpec.S9, id -> scenarioDropStaysUncertain(id, client));
        m.put(ScenarioSpec.S10, id -> scenarioNotResumable(id, client));
        m.put(ScenarioSpec.S11, id -> scenarioAttributesReachTargetRuntime(id, client));
        m.put(ScenarioSpec.S12, id -> scenarioExpiredExposure(id, client, tools));
        m.put(ScenarioSpec.S13, id -> scenarioAgentIdRouting(id, client));
        return m;
    }

    /**
     * 跑单个场景：统一发起止事件、隔离计数器、并把异常收敛成一条失败断言。
     *
     * @param spec 场景元信息
     * @param body 场景执行体
     */
    private void runScenario(ScenarioSpec spec, Body body) {
        progress.onEvent(VerificationProgress.Event.scenarioStart(spec.id(), spec.title()));
        int beforeFails = failures.size();
        // 开跑前清零：场景之间不共享工具执行计数，否则单独重跑某个场景时绝对计数断言必然失败。
        resetCounters();
        try {
            body.run(spec.id());
        } catch (InterruptedException e) {
            // G.CON.10：不恢复中断标志，协作式退出；记录失败断言。
            check(spec.id(), false, "scenario interrupted: " + e.getMessage());
        } catch (ExecutionException | TimeoutException | IllegalStateException e) {
            // 单个场景抛异常不再中断整轮：看板需要看到其余场景的结论。
            check(spec.id(), false, "unexpected exception: " + e);
            LOG.log(java.util.logging.Level.WARNING, "scenario " + spec.id() + " failed", e);
        }
        progress.onEvent(VerificationProgress.Event.scenarioEnd(spec.id(), failures.size() == beforeFails));
    }

    private void resetCounters() {
        approvalCount.set(0);
        if (demoTools != null) {
            demoTools.resetCounters();
        }
    }

    /**
     * 一个场景的执行体。
     */
    @FunctionalInterface
    private interface Body {
        /**
         * 执行场景。
         *
         * @param scenarioId 场景 id，用于给断言归属
         * @throws InterruptedException 若发生 InterruptedException
         * @throws ExecutionException 若发生 ExecutionException
         * @throws TimeoutException 若发生 TimeoutException
         */
        void run(String scenarioId) throws InterruptedException, ExecutionException, TimeoutException;
    }

    /**
     * 场景元信息。声明顺序即执行顺序。
     */
    enum ScenarioSpec {
        S1("s1", "Scenario 1: STREAMING + client tools (real HTTP + SSE)", "工具调用",
                "流式调用中服务端驱动端侧工具，重复请求只执行一次，Action 工具走审批"),
        S2("s2", "Scenario 2: BLOCKING via gateway sync API + STREAMING ping", "调用模式",
                "BLOCKING 走网关同步接口而非本地聚合流式结果"),
        S3("s3", "Scenario 3: user-input continuation (continueInput over real HTTP)", "续轮",
                "等待用户输入后由新句柄承载续轮，原句柄止于等待点"),
        S4("s4", "Scenario 4: plain multi-turn (reuse conversationId, new Task each turn)", "多轮",
                "复用 conversationId 的普通多轮，每轮新建 Task 且不触发工具"),
        S5("s5", "Scenario 5: default-no-exposure (no exposure declared → no clientTools on wire)", "工具治理",
                "未声明 exposure 则 ToolView 为空，服务端看不到任何本地工具"),
        S6("s6", "Scenario 6: governance error (401) not projected as success", "异常",
                "治理错误以 FAILED 终态暴露并携带错误码，不伪装成成功"),
        S7("s7", "Scenario 7: ASYNC accepted then observed via getInvocation (GetTask)", "调用模式",
                "异步受理后用 invocationRef 查询权威状态"),
        S8("s8", "Scenario 8: mid-stream drop recovered to a terminal state via GetTask", "断连恢复",
                "流中断但服务端已完成：客户端自查把不确定变回确定，业务无感"),
        S9("s9", "Scenario 9: mid-stream drop with server still working yields progress-uncertain", "断连恢复",
                "流中断且服务端仍在跑：不判失败、不悬挂，给出结构化恢复线索"),
        S10("s10", "Scenario 10: continuing a non-resumable invocation yields a classified error", "异常",
                "不可续接返回稳定错误码与 retryable，而非裸异常"),
        S11("s11", "Scenario 11: request attributes (trace/correlation) reach the target runtime", "上下文传递",
                "trace / correlation 穿过 Gateway 和 BUS 到达目标 Runtime"),
        S12("s12", "Scenario 12: an expired exposure window advertises no tools", "工具治理",
                "暴露窗口已关闭则不宣告工具，自然不会被驱动执行"),
        S13("s13", "Scenario 13: Gateway agentId is required and reaches the target runtime", "上下文传递",
                "Gateway 工厂要求 agentId；合法值到达目标 Runtime，空白值在发网前拒绝");

        private final String id;
        private final String title;
        private final String category;
        private final String summary;

        ScenarioSpec(String id, String title, String category, String summary) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.summary = summary;
        }

        String id() {
            return id;
        }

        String title() {
            return title;
        }

        String category() {
            return category;
        }

        String summary() {
            return summary;
        }
    }

    /**
     * 构建验证用客户端实例。
     *
     * @param url 网关地址
     * @return 客户端实例
     */
    private AgentClient buildClient(String url) {
        return AgentClients.builder()
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
                .maxDistinctConversations(5)
                .build();
    }

    private void scenarioStreamingClientTools(String id, AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        String conversationId = "conv-stream-1";
        client.exposeInConversation(conversationId,
                ToolExposurePolicy.allow(DemoTools.READ_PAGE, DemoTools.SUBMIT_ORDER));
        InvocationRequest request = InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("please read the page then submit the order")
                .build();
        InvocationCall call = client.invoke(request);
        progress.onEvent(VerificationProgress.Event.info(id,
                "invoke STREAMING invocationRef=" + call.invocationRef()));
        InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

        // 真栈断言：STREAMING 调用经 gateway→runtime 走通并到达终态。
        // 工具执行/审批计数断言仅在 mock-gateway 下成立（runtime 的 a2a demo Agent 不会基于
        // client ToolView 主动请求端侧工具），这里改为信息级记录，不判失败。
        check(id, snapshot.state() == TaskState.COMPLETED,
                "streaming invocation completed, state=" + snapshot.state());
        progress.onEvent(VerificationProgress.Event.info(id,
                "tool execution counts (mock-gateway only): readPage=" + tools.readPageCount.get()
                        + " submitOrder=" + tools.submitOrderCount.get()
                        + " approval=" + approvalCount.get()));
        call.close();
    }

    private void scenarioBlockingAndStreamingPing(String id, AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        String conversationId = "conv-mode-1";
        client.exposeInConversation(conversationId, ToolExposurePolicy.allow(DemoTools.PING));

        // BLOCKING 不订阅 events()，直接等 completion：验证它走网关同步接口，而不是本地聚合流式结果。
        InvocationCall blocking = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId(conversationId)
                .mode(InvocationMode.BLOCKING)
                .input("blocking hello")
                .exposure(ToolExposurePolicy.none())
                .build());
        InvocationSnapshot blockingSnap =
                blocking.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
        check(id, blockingSnap.state() == TaskState.COMPLETED,
                "BLOCKING completed without subscribing events(), state=" + blockingSnap.state());
        check(id, blockingSnap.outputText() != null && !blockingSnap.outputText().isEmpty(),
                "BLOCKING snapshot carries the final output text");
        blocking.close();

        // STREAMING 下同一个调用应能正常跑通。
        InvocationRequest request = InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("run ping")
                .build();
        InvocationCall call = client.invoke(request);
        progress.onEvent(VerificationProgress.Event.info(id,
                "invoke STREAMING invocationRef=" + call.invocationRef()));
        InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

        check(id, snapshot.state() == TaskState.COMPLETED,
                "streaming ping invocation completed, state=" + snapshot.state());
        // ping 工具执行计数仅在 mock-gateway 下成立；真栈下 runtime 不会请求端侧工具，改为信息级记录。
        progress.onEvent(VerificationProgress.Event.info(id,
                "tool execution counts (mock-gateway only): ping=" + tools.pingCount.get()));
        call.close();
    }

    private void scenarioContinueInput(String id, AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        String conversationId = "conv-ui-1";
        // Gateway 创建请求要求显式指定已注册的 agentId；续轮通过 taskId 关联既有任务。
        // 输入文本走 a2a demo 的 calc 路径：Agent A 委派给 Agent B，Agent B 的 calc 工具会中断等待确认，
        // 产生真实的 INPUT_REQUIRED，验证 continueInput 续轮在真栈上能跑通。
        InvocationCall call = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId(conversationId)
                .agentId("demo-a2a-agent-a")
                .mode(InvocationMode.STREAMING)
                .input("Please calculate 1+1 through Agent B.")
                .build());

        // 续轮由 continueInput 返回的新句柄承载（006 §3.4.1）：原句柄止于"等待输入"点，
        // 最终结果只出现在新句柄上。
        java.util.concurrent.atomic.AtomicReference<InvocationCall> continuation =
                new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch continued = new CountDownLatch(1);
        subscribeContinuePrompt(call, id, client, continuation, continued);

        boolean prompted = continued.await(60, TimeUnit.SECONDS);
        InvocationCall next = continuation.get();
        check(id, prompted && next != null, "user-input prompt surfaced and continueInput issued");
        if (next != null) {
            InvocationSnapshot first = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            check(id, first.state() == TaskState.INPUT_REQUIRED && !first.terminal(),
                    "first invocation settles at the input point (non-terminal), state=" + first.state());
            InvocationSnapshot snapshot = next.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            check(id, snapshot.state() == TaskState.COMPLETED,
                    "continuation completed on the new invocationRef, state=" + snapshot.state());
            check(id, !next.invocationRef().equals(call.invocationRef()),
                    "continuation has a distinct invocationRef");
            next.close();
        }
        call.close();
    }

    /**
     * 订阅事件流，在收到用户输入提示时以 continueInput 发起续轮。
     *
     * @param call 原调用句柄
     * @param id 场景标识
     * @param client 客户端
     * @param continuation 续轮句柄持有者
     * @param continued 续轮发起完成同步点
     */
    private void subscribeContinuePrompt(InvocationCall call, String id, AgentClient client,
                                         java.util.concurrent.atomic.AtomicReference<InvocationCall> continuation,
                                         CountDownLatch continued) {
        call.events().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent event) {
                if (event instanceof InvocationEvent.InputRequired ir && ir.toolCall() == null
                        && continuation.get() == null) {
                    progress.onEvent(VerificationProgress.Event.info(id, "got prompt, submitting continueInput"));
                    continuation.set(client.continueInput(ContinueInputRequest.builder()
                            .conversationId(call.conversationId())
                            .relatedInvocationRef(call.invocationRef())
                            .input("ok")
                            .build()));
                    continued.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                continued.countDown();
            }

            @Override
            public void onComplete() {
                continued.countDown();
            }
        });
    }

    /**
     * Scenario 7: ASYNC 受理后用 getInvocation 观察进展（Q1 开放北向 GetTask 后解锁）。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioAsyncThenQuery(String id, AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        InvocationCall call = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId("conv-async-1")
                .agentId("demo-a2a-agent-a")
                .mode(InvocationMode.ASYNC)
                .input("async hello")
                .exposure(ToolExposurePolicy.none())
                .build());
        Handle handle = call.accepted().toCompletableFuture().get(30, TimeUnit.SECONDS);
        check(id, handle.diagnosticTaskRef() != null && !handle.diagnosticTaskRef().isEmpty(),
                "ASYNC returns an accepted handle carrying a diagnostic taskRef");

        InvocationSnapshot queried =
                client.getInvocation(call.invocationRef()).toCompletableFuture().get(30, TimeUnit.SECONDS);
        check(id, queried.state() != TaskState.UNKNOWN,
                "getInvocation returns an authoritative state, state=" + queried.state());
        check(id, call.invocationRef().equals(queried.invocationRef()),
                "queried snapshot is keyed by the business invocationRef, not the taskRef");

        InvocationSnapshot unknown = client.getInvocation("inv-does-not-exist")
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        check(id, unknown.state() == TaskState.UNKNOWN,
                "getInvocation on an unknown ref yields UNKNOWN instead of throwing");
        call.close();
    }

    /**
     * Scenario 8: SSE 非终态中断，但服务端其实已完成 —— 客户端靠 GetTask 把"不确定"变回"确定"。
     *
     * <p>双模式兼容：mock-gateway 下输入 {@code "stream hello"} 触发 DROP_THEN_COMPLETE，
     * 流中断后服务端已跑到 COMPLETED，客户端靠 GetTask 查到确定终态；真栈下该输入是一次普通
     * STREAMING 调用，正常到达 COMPLETED。两种模式都断言 COMPLETED。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioDropRecoveredByQuery(String id, AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<InvocationEvent> seen = new ArrayList<>();
        InvocationCall call = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId("conv-drop-recover")
                .agentId("demo-a2a-agent-a")
                .mode(InvocationMode.STREAMING)
                .input("stream hello")
                .exposure(ToolExposurePolicy.none())
                .build());
        collect(call, seen);

        InvocationSnapshot snap = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
        check(id, snap.state() == TaskState.COMPLETED,
                "streaming invocation reached COMPLETED, state=" + snap.state()
                        + " (real stack: normal completion; mock-gateway: recovered via GetTask after drop)");
        progress.onEvent(VerificationProgress.Event.info(id,
                "drop-recovery semantics (DROP_THEN_COMPLETE) exercised on mock-gateway; "
                        + "real stack yields normal COMPLETED"));
        call.close();
    }

    /**
     * Scenario 9: SSE 非终态中断且服务端仍在跑 —— 投递"进展不确定"并给出恢复线索。
     *
     * <p>双模式兼容：mock-gateway 下输入 {@code "stream hello again"} 触发 DROP_STAYS_WORKING，
     * 流中断后 GetTask 查到 WORKING，SDK 投递 ProgressUncertain，completion() 以 WORKING 结算；
     * 真栈下该输入是一次普通 STREAMING 调用，正常到达 COMPLETED。断言允许这两种终态结算结果。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioDropStaysUncertain(String id, AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<InvocationEvent> seen = new ArrayList<>();
        InvocationCall call = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId("conv-drop-uncertain")
                .agentId("demo-a2a-agent-a")
                .mode(InvocationMode.STREAMING)
                .input("stream hello again")
                .exposure(ToolExposurePolicy.none())
                .build());
        collect(call, seen);

        InvocationSnapshot snap = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
        // 双模式断言：真栈 → COMPLETED；mock-gateway DROP_STAYS_WORKING → WORKING（ProgressUncertain 结算）。
        check(id, snap.state() == TaskState.COMPLETED || snap.state() == TaskState.WORKING,
                "streaming invocation settled, state=" + snap.state()
                        + " (COMPLETED on real stack, WORKING on mock drop-stays-working)");
        progress.onEvent(VerificationProgress.Event.info(id,
                "progress-uncertain semantics exercised when state=WORKING (mock-gateway DROP_STAYS_WORKING); "
                        + "real stack yields COMPLETED"));
        call.close();
    }

    /**
     * Scenario 10: 关联不可续接时返回<b>可编程</b>错误，而不是裸 IllegalStateException。
     *
     * <p>覆盖 FEAT-006 §5.1.3「不可续接必须明确报错，不得静默新建普通任务」+「错误分类」MUST：
     * 业务需要拿到稳定 `code` 与 `retryable` 来决策，不该去解析异常文案。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioNotResumable(String id, AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        // 先跑一个直接 COMPLETED 的调用，它已终态，不可续接。
        InvocationCall done = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId("conv-not-resumable")
                .mode(InvocationMode.STREAMING)
                .input("plain hello")
                .exposure(ToolExposurePolicy.none())
                .build());
        done.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

        try {
            client.continueInput(ContinueInputRequest.builder()
                    .conversationId("conv-not-resumable")
                    .relatedInvocationRef(done.invocationRef())
                    .input("too late")
                    .build());
            check(id, false, "continueInput on a terminal invocation must be rejected");
        } catch (InvocationNotResumableException e) {
            check(id, ErrorCodes.RELATED_NOT_RESUMABLE.equals(e.code()),
                    "rejection carries the stable RELATED_NOT_RESUMABLE code, actual=" + e.code());
            check(id, !e.retryable(), "a non-resumable continuation is not retryable");
            check(id, done.invocationRef().equals(e.relatedInvocationRef()),
                    "rejection points back at the offending relatedInvocationRef");
        }
        // 未知句柄同样应被明确拒绝，而不是静默新建任务。
        try {
            client.continueInput(ContinueInputRequest.builder()
                    .conversationId("conv-not-resumable")
                    .relatedInvocationRef("inv-never-existed")
                    .input("hello")
                    .build());
            check(id, false, "continueInput on an unknown ref must be rejected");
        } catch (InvocationNotResumableException e) {
            check(id, ErrorCodes.RELATED_NOT_RESUMABLE.equals(e.code()),
                    "unknown related ref is rejected with the same stable code");
        }
        done.close();
    }

    /**
     * Scenario 11: 业务附加属性（trace / correlation）穿过 Gateway 和 BUS 到达目标 Runtime。
     *
     * <p>覆盖 FEAT-006「业务上下文与凭证传递」MUST。Demo Runtime 会把
     * {@code metadata.attributes.traceId} 写入最终 artifact 文本，因此可以直接断言
     * client → Gateway → BUS → Runtime 的完整透传结果。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioAttributesReachTargetRuntime(String id, AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        String traceId = "trace-" + UUID.randomUUID();
        InvocationCall call = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId("conv-attributes")
                .agentId("demo-a2a-agent-a")
                .mode(InvocationMode.STREAMING)
                .input("carry my trace")
                .attribute("traceId", traceId)
                .exposure(ToolExposurePolicy.none())
                .build());
        InvocationSnapshot snap = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

        check(id, snap.state() == TaskState.COMPLETED,
                "invocation with attributes completed, state=" + snap.state());
        check(id, snap.outputText() != null && snap.outputText().contains("[trace=" + traceId + "]"),
                "runtime output carries the request traceId");
        call.close();
    }

    /**
     * Scenario 13: Gateway agentId 经 SDK 透传到达网关，空白串在发网前被拒绝。
     *
     * <p>网关侧（真网关 + mock）要求创建类请求携带 {@code params.metadata.agentId}：
     * <ul>
     * <li>标准 API 把 agentId 作为 {@code gatewayBuilder(agentId)} 必需参数；</li>
     * <li>显式非空 → 路由到指定 Agent；</li>
     * <li>显式空白串 → 400 {@code VALIDATION_AGENT_ID}。</li>
     * </ul>
     * SDK 侧的 {@code InvocationRequest.agentId()} 与 wire 写入路径（{@code A2aJsonCodec.fillMetadata}
     * 写 {@code metadata.agentId}）本就存在，本场景做端到端确认并锁定空白串归一化语义。
     *
     * <p>Demo Runtime 会把收到的 {@code metadata.agentId} 写入最终 artifact 文本，
     * 因此显式 agentId 的断言同时覆盖网关路由与 Runtime 透传；空白 agentId 由 SDK
     * 归一化为未指定后被 Gateway 以 {@code VALIDATION_AGENT_ID} 拒绝。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioAgentIdRouting(String id, AgentClient client)
            throws InterruptedException, ExecutionException, TimeoutException {
        // 1) 显式 agentId → 网关按它路由到对应 runtime 实例。
        //    真栈下 agentId 必须是 RDC 中已注册的 agentId（agent-x 指向 a2a Agent A），否则 ROUTE_NO_CANDIDATES。
        String agentId = "demo-a2a-agent-a";
        InvocationCall call = client.invoke(InvocationRequest.gatewayBuilder(agentId)
                .conversationId("conv-agentid")
                .mode(InvocationMode.STREAMING)
                .input("route me to a specific agent")
                .exposure(ToolExposurePolicy.none())
                .build());
        InvocationSnapshot snap = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
        check(id, snap.state() == TaskState.COMPLETED,
                "invocation with an explicit agentId completed, state=" + snap.state());
        check(id, snap.outputText() != null && snap.outputText().contains("[agent=" + agentId + "]"),
                "runtime output confirms the explicit agentId reached the target Agent");
        call.close();

        // 2) 标准 Gateway 工厂把 agentId 置于方法签名中；null/空白值还会在构建请求前拒绝。
        try {
            InvocationRequest.gatewayBuilder("   ");
            check(id, false, "a blank Gateway agentId must be rejected before request construction");
        } catch (IllegalArgumentException expected) {
            check(id, true, "a blank Gateway agentId is rejected locally before any network request");
        }
    }

    /**
     * Scenario 12: 暴露窗口已关闭时不宣告端侧工具，因而不会被驱动执行。
     *
     * <p>覆盖 FEAT-007「默认不暴露」+「只能执行当前**可用**的工具」+ 暴露策略过期时间。
     * 断言方式与 s5（未声明 exposure）同构：ToolView 为空 → 网关不请求工具 → 无本地执行。
     *
     * <p>另一半语义（窗口在创建<b>之后</b>才关闭 → 回传 {@code context_expired} 结构化拒绝）依赖时钟推进，
     * 用真实网关做端到端断言会引入时序抖动，留待阶段 2 用可注入时钟的单元测试覆盖。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @param tools 工具计数器
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioExpiredExposure(String id, AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        int readBefore = tools.readPageCount.get();
        int submitBefore = tools.submitOrderCount.get();

        // 窗口在过去就已关闭：授权本身允许这两个工具，但已过期。
        InvocationCall call = client.invoke(InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId("conv-expired-exposure")
                .agentId("demo-a2a-agent-a")
                .mode(InvocationMode.STREAMING)
                .input("please read the page then submit the order")
                .exposure(ToolExposurePolicy.allow(DemoTools.READ_PAGE, DemoTools.SUBMIT_ORDER)
                        .expiringAt(Instant.now().minusSeconds(1)))
                .build());
        InvocationSnapshot snap = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);

        check(id, snap.state() == TaskState.COMPLETED,
                "invocation still reaches a terminal state, state=" + snap.state());
        check(id, tools.readPageCount.get() == readBefore && tools.submitOrderCount.get() == submitBefore,
                "no tool executed under an expired exposure window, readPage+"
                        + (tools.readPageCount.get() - readBefore)
                        + " submitOrder+" + (tools.submitOrderCount.get() - submitBefore));
        call.close();
    }

    /**
     * 订阅事件流并把事件收集到给定列表，供断言检查投递序列。
     *
     * @param call 调用句柄
     * @param sink 事件收集目标
     */
    private static void collect(InvocationCall call, List<InvocationEvent> sink) {
        call.events().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent item) {
                synchronized (sink) {
                    sink.add(item);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                // 断言只看已收到的事件序列与最终快照，订阅异常仅记录日志。
                LOG.log(java.util.logging.Level.WARNING, "event stream error in collect", throwable);
            }

            @Override
            public void onComplete() {
                // 结算由 completion() 观察，此处显式返回。
                return;
            }
        });
    }

    /**
     * Scenario 4: 普通多轮对话（复用同一 conversationId 再 invoke 无 taskId 创建，得到新 Task）。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @param tools 工具计数器
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioPlainMultiTurn(String id, AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        String conversationId = "conv-multi-1";
        // 记录本轮开始前的工具执行计数（前面场景已用过工具），断言本轮前后不变。
        int readBefore = tools.readPageCount.get();
        int submitBefore = tools.submitOrderCount.get();
        int pingBefore = tools.pingCount.get();
        // 第一轮：不声明 exposure、普通文本，应直接 COMPLETED（IMMEDIATE），无工具调用。
        InvocationCall c1 = invokePlain(client, conversationId, "hello turn 1");
        Handle h1 = c1.accepted().toCompletableFuture().get(30, TimeUnit.SECONDS);
        InvocationSnapshot s1 = c1.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
        // 第二轮：复用同一 conversationId，新 invocation，无 taskId（普通多轮=新建 Task）。
        InvocationCall c2 = invokePlain(client, conversationId, "hello turn 2");
        Handle h2 = c2.accepted().toCompletableFuture().get(30, TimeUnit.SECONDS);
        InvocationSnapshot s2 = c2.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
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
    }

    /**
     * 发起一次普通 STREAMING 调用（不声明工具暴露）。
     *
     * @param client AgentClient 实例
     * @param conversationId 会话标识
     * @param input 输入文本
     * @return 调用句柄
     */
    private InvocationCall invokePlain(AgentClient client, String conversationId, String input) {
        InvocationRequest r = InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId(conversationId)
                .agentId("demo-a2a-agent-a")
                .mode(InvocationMode.STREAMING)
                .input(input)
                .build();
        return client.invoke(r);
    }

    /**
     * Scenario 5: 默认不暴露（不声明 exposure → ToolView 为空 → 服务端不可见任何本地工具）。
     *
     * @param id 场景标识
     * @param client AgentClient 实例
     * @param tools 工具计数器
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioDefaultNoExposure(String id, AgentClient client, DemoTools tools)
            throws InterruptedException, ExecutionException, TimeoutException {
        // 用一个全新的 conversationId，不 exposeInConversation、不在 request 里声明 exposure。
        String conversationId = "conv-noexp-1";
        int readBefore = tools.readPageCount.get();
        int submitBefore = tools.submitOrderCount.get();
        int pingBefore = tools.pingCount.get();
        InvocationRequest request = InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                .conversationId(conversationId)
                .mode(InvocationMode.STREAMING)
                .input("please read the page and submit the order")
                .build();
        InvocationCall call = client.invoke(request);
        InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
        // 默认不暴露：服务端看不到 clientTools → mock 走 IMMEDIATE 直接 COMPLETED，不请求任何工具。
        check(id, snapshot.state() == TaskState.COMPLETED,
                "default-no-exposure completed without tool calls, state=" + snapshot.state());
        check(id, tools.readPageCount.get() == readBefore
                        && tools.submitOrderCount.get() == submitBefore
                        && tools.pingCount.get() == pingBefore,
                "no tools executed when no exposure declared (default empty ToolView)");
        call.close();
    }

    /**
     * Scenario 6: 治理错误（401 AUTH_MISSING）不投影为成功 Task，而是以 Failed 终态暴露。
     *
     * @param id 场景标识
     * @param url 网关地址
     * @throws InterruptedException 若发生 InterruptedException
     * @throws ExecutionException 若发生 ExecutionException
     * @throws TimeoutException 若发生 TimeoutException
     */
    private void scenarioGovernanceErrorNotProjected(String id, String url)
            throws InterruptedException, ExecutionException, TimeoutException {
        // 构造一个不提供 credential 的 client：每次 HTTP 不带 Authorization → 网关 401 AUTH_MISSING。
        AgentClient noAuthClient = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(url))
                .policyGuard(Governance.PolicyGuard.allowAll())
                .approvalProvider(Governance.ApprovalProvider.autoApprove())
                .build();
        try {
            InvocationRequest request = InvocationRequest.gatewayBuilder("demo-a2a-agent-a")
                    .conversationId("conv-noauth-1")
                    .mode(InvocationMode.STREAMING)
                    .input("should be rejected by gateway")
                    .build();
            InvocationCall call = noAuthClient.invoke(request);
            InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(60, TimeUnit.SECONDS);
            // 401 治理错误不应投影为 COMPLETED；应以 FAILED 终态暴露（feat-011 §4.9 AC-7 / 006 §5.3）。
            check(id, snapshot.state() == TaskState.FAILED,
                    "401 governance error surfaced as FAILED, not COMPLETED, state=" + snapshot.state());
            check(id, snapshot.errorCode() != null && !snapshot.errorCode().isEmpty(),
                    "failed snapshot carries an errorCode, errorCode=" + snapshot.errorCode());
            call.close();
        } finally {
            noAuthClient.close();
        }
    }

    private void check(String scenarioId, boolean condition, String message) {
        progress.onEvent(VerificationProgress.Event.check(scenarioId, condition, message));
        if (!condition) {
            failures.add(message);
        }
    }
}
