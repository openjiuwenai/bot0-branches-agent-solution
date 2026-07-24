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
     */
    public A2aController(AuthRule authRule, TenantResolver tenantResolver, ParamValidator paramValidator,
                         IdempotencyRule idempotencyRule, GovernanceAuditor auditor, Router router,
                         SseBridge sseBridge) {
        this.authRule = authRule;
        this.tenantResolver = tenantResolver;
        this.paramValidator = paramValidator;
        this.idempotencyRule = idempotencyRule;
        this.auditor = auditor;
        this.router = router;
        this.sseBridge = sseBridge;
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
                IdempotencyRule.Decision idem = idempotencyRule.check(tenantId, context.messageId(), jsonRpcBody);
                switch (idem.outcome()) {
                    case NEW, SKIP -> { 
                        // proceed to later stages
                    }
                    case REPLAY -> {
                        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(idem.result());
                    }
                    case CONFLICT -> throw new GovernanceException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_PAYLOAD_MISMATCH",
                            "Create idempotency key conflict: payload differs from the first attempt");
                    case IN_FLIGHT_DUPLICATE -> throw new GovernanceException(HttpStatus.CONFLICT,
                            "IDEMPOTENCY_IN_FLIGHT",
                            "A create with this idempotency key is already in progress");
                }
            }
        } catch (GovernanceException ex) {
            ex.setTraceId(context.traceId());
            auditor.auditRejected(context, ex);
            throw ex;
        }

        auditor.auditPassed(context);

        if (context.taskId() == null) {
            if ("SendStreamingMessage".equals(context.method())) {
                String firstFrame;
                try {
                    Stream<String> frames = router.routeStream(context);
                    // Stream frames synchronously; release happens when consumed or the
                    // client disconnects (writeSse throws IOException).
                    response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    firstFrame = sseBridge.writeSse(response.getOutputStream(), frames);
                } catch (GovernanceException | IOException ex) {
                    // failure -> release the in-flight record (P0-1); never complete a failure.
                    idempotencyRule.abort(context.tenantId(), context.messageId());
                    if (ex instanceof GovernanceException ge) {
                        ge.setTraceId(context.traceId());
                    }
                    throw ex;
                }
                // stream consumed normally -> complete (approach A, TD-8): store the first
                // frame (task-accept/result surface) as the replayable result; empty stream
                // -> stable summary. A same-key retry REPLAYs this as a single JSON body.
                String replayResult = firstFrame != null ? firstFrame
                        : "{\"jsonrpc\":\"2.0\",\"result\":{\"status\":\"completed\"}}";
                idempotencyRule.complete(context.tenantId(), context.messageId(), replayResult);
                return null;
            }
            String runtimeResponse;
            try {
                runtimeResponse = router.routeCreate(context);
            } catch (GovernanceException ex) {
                idempotencyRule.abort(context.tenantId(), context.messageId());
                ex.setTraceId(context.traceId());
                throw ex;
            }
            // Mark the create idempotency record completed so a same-key retry
            // REPLAYs this response instead of re-forwarding (T-G4-2).
            idempotencyRule.complete(context.tenantId(), context.messageId(), runtimeResponse);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(runtimeResponse);
        }
        // resume path — route to the original owner via the sticky index.
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
