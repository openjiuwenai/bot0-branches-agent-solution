package com.openjiuwen.rdc.registry.runtime.api;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.spi.registry.AgentCard;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * HTTP entry point for the agent registry MVP (ADR-0160 decisions 4 / 6 / 7).
 *
 * <p>Exposes {@code POST /api/registry/register} (upsert an {@link AgentCard})
 * and {@code DELETE /api/registry/deregister/{tenantId}/{agentId}}. Both
 * endpoints read {@code tenantId} from the request path (deregister) or body
 * (register) and pass it down explicitly — no {@code TenantFilter} populates
 * a {@code TenantContext} at Servlet entry (ESC-2 design pivot, ADR-0160
 * decision 6: three-layer tenant isolation — explicit parameter +
 * application-layer WHERE + RLS).
 *
 * <p>The controller is a thin adapter: it validates input, delegates to
 * {@link AgentRegistryRepository} (port in {@code runtime.persistence.jdbc}),
 * and emits audit + metrics via {@link RegistryObservabilityConfig}. No JDBC
 * imports — the {@code req-2026-003-jdbc-confined-to-persistence} gate
 * enforces that.
 *
 * <p>Trace ID propagation (PR #389 review issue #8): the controller reads
 * inbound {@code traceparent} (W3C) / {@code X-Trace-Id} headers and uses
 * that as the audit/metrics trace ID. When no header is present, a fresh
 * UUID is generated. This keeps the audit chain continuous across
 * distributed hops instead of self-generating a new ID at every entry.
 *
 * <p>Spring Web annotations ({@code @RestController} / {@code @RequestMapping})
 * are visible at compile time via {@code spring-boot-starter-web} at compile
 * scope (ADR-0160 decision 7, revised per PR #389 review issue #6 — agent-bus
 * is now a runnable Spring Boot application, no longer a library jar).
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + HD3-001 / 002 / 003 + PR #389
 * review issue #8 (trace ID propagation) + #7 (deregister tenantId moved
 * off the query string) + #6 (agent-bus positioning).
 */
@RestController
@RequestMapping("/api/registry")
public class MvpRegistryController {

    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String X_TRACE_ID_HEADER = "X-Trace-Id";

    private final AgentRegistryRepository repository;
    private final RegistryObservabilityConfig observability;

    public MvpRegistryController(AgentRegistryRepository repository,
                                 RegistryObservabilityConfig observability) {
        this.repository = repository;
        this.observability = observability;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody AgentCard card,
                                         @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
                                         @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (card == null || !card.hasRegistryKey()) {
            throw new IllegalArgumentException(
                    "AgentCard must carry tenantId + agentId + capability (registry key)");
        }
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        try {
            repository.upsert(card);
            return ResponseEntity.ok().build();
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeRegister(traceId, card.getTenantId(), card.getAgentId(),
                    card.getServiceId(), card.getCapability(), card.getContractVersion(),
                    card.getCapabilityVersion(), "ONLINE", null, outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Deregister an agent. {@code tenantId} and {@code agentId} are path
     * variables (PR #389 review issue #7 / Nit): the previous query-param
     * form leaked them into access logs.
     */
    @DeleteMapping("/deregister/{tenantId}/{agentId}")
    public ResponseEntity<Void> deregister(@PathVariable String tenantId,
                                           @PathVariable String agentId,
                                           @RequestHeader(value = TRACE_PARENT_HEADER, required = false) String traceparent,
                                           @RequestHeader(value = X_TRACE_ID_HEADER, required = false) String xTraceId) {
        if (tenantId == null || tenantId.isBlank()
                || agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("tenantId and agentId are required path variables");
        }
        String traceId = resolveTraceId(traceparent, xTraceId);
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        try {
            boolean deleted = repository.delete(tenantId, agentId);
            outcome = deleted ? "success" : "not_found";
            return ResponseEntity.noContent().build();
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDeregister(traceId, tenantId, agentId, outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Resolve the trace ID for the audit / metrics path. Prefer the W3C
     * {@code traceparent} header (extract the parent span ID — the part
     * after the second dash), then {@code X-Trace-Id} (used as-is), then
     * fall back to a fresh UUID. PR #389 review issue #8.
     */
    private static String resolveTraceId(String traceparent, String xTraceId) {
        if (traceparent != null && !traceparent.isBlank()) {
            // traceparent format: 00-<trace-id>-<parent-id>-<flags>
            String[] parts = traceparent.trim().split("-");
            if (parts.length >= 3 && !parts[2].isBlank()) {
                return parts[2];
            }
        }
        if (xTraceId != null && !xTraceId.isBlank()) {
            return xTraceId.trim();
        }
        return UUID.randomUUID().toString();
    }
}
