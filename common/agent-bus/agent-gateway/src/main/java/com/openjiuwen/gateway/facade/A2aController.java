/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.facade;

import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.auth.AuthRule;
import com.openjiuwen.gateway.governance.auth.Principal;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.governance.tenant.TenantResolver;
import com.openjiuwen.gateway.governance.validate.ParamValidator;
import com.openjiuwen.gateway.obs.GovernanceAuditor;
import com.openjiuwen.gateway.bus.BusForwarder;
import com.openjiuwen.gateway.path.PathSelector;
import com.openjiuwen.gateway.routing.Router;
import com.openjiuwen.gateway.sse.SseBridge;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * A2A JSON-RPC facade entry — {@code POST /a2a} (FEAT-011 L2 §1.1 / §4.9 GW-1).
 *
 * <p>Governance (G1–G4) runs first and is audited (G5); then the create path
 * either forwards synchronously ({@code SendMessage}) or writes an SSE stream
 * ({@code SendStreamingMessage}) to the resolved runtime. Resume (taskId present)
 * uses the sticky path (later slice). {@link GovernanceException} maps to the
 * stable HTTP error body via the advice.
 *
 * @since 0.1.0
 */
@RestController
public class A2aController {
    private final AuthRule authRule;
    private final TenantResolver tenantResolver;
    private final ParamValidator paramValidator;
    private final IdempotencyRule idempotencyRule;
    private final GovernanceAuditor auditor;
    private final Router router;
    private final SseBridge sseBridge;
    private final PathSelector pathSelector;
    private final Optional<BusForwarder> busForwarder;

    /**
     * Construct.
     *
     * @param authRule        G1 authentication rule
     * @param tenantResolver  G2 tenant resolver
     * @param paramValidator  G3 parameter validator
     * @param idempotencyRule G4 create idempotency
     * @param auditor         G5 governance auditor
     * @param router          direct-route router
     * @param sseBridge       SSE stream bridge
     * @param pathSelector    BUS/DIRECT path selector
     * @param busForwarder    BUS forwarder (empty when the BUS path is disabled)
     */
    public A2aController(AuthRule authRule, TenantResolver tenantResolver, ParamValidator paramValidator,
                         IdempotencyRule idempotencyRule, GovernanceAuditor auditor, Router router,
                         SseBridge sseBridge, PathSelector pathSelector, Optional<BusForwarder> busForwarder) {
        this.authRule = authRule;
        this.tenantResolver = tenantResolver;
        this.paramValidator = paramValidator;
        this.idempotencyRule = idempotencyRule;
        this.auditor = auditor;
        this.router = router;
        this.sseBridge = sseBridge;
        this.pathSelector = pathSelector;
        this.busForwarder = busForwarder;
    }

    /**
     * Receive an A2A JSON-RPC request.
     *
     * @param authorization     raw {@code Authorization} header (may be absent)
     * @param traceparent       W3C {@code traceparent} header (may be absent)
     * @param selfReportedTenant raw {@code X-Tenant-Id} header (may be absent; discarded by G2)
     * @param jsonRpcBody       raw JSON-RPC envelope (parsed by G3)
     * @param response          servlet response (used to write the SSE stream)
     * @return sync response body, or {@code null} once an SSE stream has been written
     * @throws IOException if writing the SSE stream to the client fails (disconnect)
     */
    @PostMapping("/a2a")
    public Object postA2a(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "traceparent", required = false) String traceparent,
            @RequestHeader(value = "X-Tenant-Id", required = false) String selfReportedTenant,
            @RequestBody String jsonRpcBody,
            HttpServletResponse response) throws IOException {
        GovernanceContext context = new GovernanceContext();
        context.setRawBody(jsonRpcBody);
        context.setTraceId(resolveTraceId(traceparent));

        try {
            Principal principal = authRule.authenticate(authorization);
            String tenantId = tenantResolver.resolve(principal, selfReportedTenant);
            paramValidator.validate(jsonRpcBody, context);
            context.setPrincipalId(principal.principalId());
            context.setTenantId(tenantId);
            if (context.taskId() == null) {
                IdempotencyRule.Decision idem = idempotencyRule.check(tenantId, context.messageId(),
                        context.idempotencyFingerprint());
                IdempotencyRule.Outcome outcome = idem.outcome();
                if (outcome == IdempotencyRule.Outcome.REPLAY) {
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(idem.result());
                }
                if (outcome == IdempotencyRule.Outcome.CONFLICT) {
                    throw new GovernanceException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_PAYLOAD_MISMATCH",
                            "Create idempotency key conflict: payload differs from the first attempt");
                }
                if (outcome == IdempotencyRule.Outcome.IN_FLIGHT_DUPLICATE) {
                    throw new GovernanceException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_IN_FLIGHT",
                            "A create with this idempotency key is already in progress");
                }
                // NEW / SKIP: proceed to later stages
            }
        } catch (GovernanceException ex) {
            ex.setTraceId(context.traceId());
            auditor.auditRejected(context, ex);
            throw ex;
        }

        auditor.auditPassed(context);

        if (context.taskId() == null) {
            return forwardCreate(context, response);
        }
        return forwardResume(context, response);
    }

    /**
     * Forward a create call: streaming (SSE) or sync (JSON), with idempotency complete/abort.
     *
     * @param context  governance context after G1–G4
     * @param response servlet response (used when streaming)
     * @return sync JSON body, or {@code null} when an SSE stream has been written
     * @throws IOException if writing the SSE stream fails
     */
    private Object forwardCreate(GovernanceContext context, HttpServletResponse response) throws IOException {
        if (pathSelector.isBus() && busForwarder.isPresent()) {
            if ("SendStreamingMessage".equals(context.method())) {
                // FEAT-012 IN-4 BUS streaming: enqueue → poll ACCEPTED + STREAM_READY → SSE bridge.
                Optional<String> firstFrame = busForwarder.get().forwardStreaming(context, response, sseBridge);
                if (firstFrame.isEmpty()) {
                    // Spring MVC contract: null tells the framework the SSE stream is already committed.
                    return null;
                }
                String frame = firstFrame.get();
                idempotencyRule.complete(context.tenantId(), context.messageId(), frame);
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(frame);
            }
            // FEAT-012 BUS sync path: the blocking wait window + projection fold run inside BusForwarder,
            // which also completes/aborts G4 via G4BusWiring — skip the DIRECT router + G4 here.
            return busForwarder.get().forwardSync(context);
        }
        if ("SendStreamingMessage".equals(context.method())) {
            return forwardStreaming(context, response);
        }
        String runtimeResponse;
        try {
            runtimeResponse = router.routeCreate(context);
        } catch (GovernanceException ex) {
            idempotencyRule.abort(context.tenantId(), context.messageId());
            ex.setTraceId(context.traceId());
            throw ex;
        }
        idempotencyRule.complete(context.tenantId(), context.messageId(), runtimeResponse);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(runtimeResponse);
    }

    /**
     * Stream SSE frames to the client; complete with first frame on success, abort on failure.
     *
     * @param context  governance context after G1–G4
     * @param response servlet response used to write {@code text/event-stream}
     * @return {@code null} after the SSE stream has been committed (Spring MVC contract)
     * @throws IOException if writing the SSE stream fails
     */
    private Object forwardStreaming(GovernanceContext context, HttpServletResponse response) throws IOException {
        String firstFrame;
        try {
            Stream<String> frames = router.routeStream(context);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding("UTF-8");
            firstFrame = sseBridge.writeSse(response.getOutputStream(), frames);
        } catch (GovernanceException | IOException ex) {
            idempotencyRule.abort(context.tenantId(), context.messageId());
            if (ex instanceof GovernanceException ge) {
                ge.setTraceId(context.traceId());
            }
            throw ex;
        }
        String replayResult = firstFrame != null ? firstFrame
                : "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"completed\"}}";
        idempotencyRule.complete(context.tenantId(), context.messageId(), replayResult);
        return null;
    }

    /**
     * Forward a resume call to the original Task owner via the sticky index.
     *
     * <p>STREAMING 续跑（{@code SendStreamingMessage} + taskId）走 sticky 路由 + SSE 桥接
     * （{@link Router#routeResumeStream}），与流式创建同构；其余续跑走同步 JSON
     * （{@link Router#routeResume}）。
     *
     * <p>续跑（含 BUS 模式）统一走 DIRECT sticky 路由：续跑针对已有 taskId，sticky index
     * 在创建时已写入（BUS 创建于首个 taskId 投影处绑定），只需读 sticky 解析 owner runtime
     * 直接转发；不经过 BUS 控制面（无 control.forward → 投影轮询 → STREAM_READY），与同步
     * 续跑走 {@link Router#routeResume} 完全对称。runtime 原生支持
     * {@code SendStreamingMessage + taskId} 续跑（恢复过程以 SSE 返回）。
     *
     * @param context governance context with taskId bound
     * @param response servlet response (used to write the SSE stream for streaming resume)
     * @return sync JSON response from the sticky owner runtime, or {@code null} once an SSE
     *         stream has been written
     * @throws IOException if writing the SSE stream to the client fails (disconnect)
     */
    private Object forwardResume(GovernanceContext context, HttpServletResponse response) throws IOException {
        if ("SendStreamingMessage".equals(context.method())) {
            // 流式续跑：sticky 路由 + SSE 桥接。续跑针对已有 taskId，走 DIRECT sticky 路由
            // （与同步续跑 routeResume 对称）；不走 BUS 控制面（续跑不产生新 STREAM_READY，
            // 故无需 control.forward → 投影轮询）。BUS 创建已在首个 taskId 投影写入 stickyIndex。
            // runtime 原生支持 SendStreamingMessage + taskId（对话接口输入与输出.md §恢复请求）。
            Stream<String> frames = router.routeResumeStream(context);
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding("UTF-8");
            sseBridge.writeSse(response.getOutputStream(), frames);
            // Spring MVC contract: null tells the framework the SSE stream is already committed.
            return null;
        }
        try {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(router.routeResume(context));
        } catch (GovernanceException ex) {
            ex.setTraceId(context.traceId());
            throw ex;
        }
    }

    private static String resolveTraceId(String traceparent) {
        if (traceparent != null && !traceparent.isBlank()) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && !parts[1].isBlank()) {
                return parts[1];
            }
        }
        return UUID.randomUUID().toString();
    }
}
