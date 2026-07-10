package com.openjiuwen.rdc.registry.runtime;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Audit + metrics facade for the agent registry MVP (ADR-0160 + design doc
 * §9.2 "审计与可观测字段").
 *
 * <p>Centralises two concerns so the controller / scheduler / discovery
 * service stay thin:
 * <ul>
 *   <li><b>Audit</b> — structured SLF4J log lines on the {@code registry.audit}
 *       logger, 9 fields per line (REQ-2026-004 removed {@code capability}
 *       from the audit schema): {@code traceId}, {@code tenantId},
 *       {@code agentId}, {@code contractVersion}, {@code capabilityVersion},
 *       {@code health}, {@code routeHandleId}, {@code outcome},
 *       {@code latencyMs}. The {@code registryOp} tag (register / deregister
 *       / probe / discover / resolve) prefixes each line. Unavailable fields
 *       are emitted as {@code "-"} so log parsers can rely on a stable
 *       9-field shape.</li>
 *   <li><b>Metrics</b> — Micrometer {@link Counter} ({@code agent_bus_registry_op_total},
 *       tags {@code op} + {@code outcome}) and {@link Timer}
 *       ({@code agent_bus_registry_op_duration_ms}, tag {@code op}) per
 *       registry operation. {@link MeterRegistry} is constructor-injected.</li>
 * </ul>
 *
 * <p>FEAT-016 changes (baseline-breaking):
 * <ul>
 *   <li>{@code observeDiscover} now takes {@code queryDimension} +
 *       {@code queryValue} instead of {@code agentId}. The discovery service
 *       has three query dimensions (agentId / serviceId / capability); the
 *       audit log now records which dimension was queried and the value
 *       searched. Operations dashboards parsing the audit log must update
 *       their field semantics (the agentId field now carries the
 *       queryDimension for discover ops).</li>
 * </ul>
 *
 * <p>Each {@code observeXxx} method emits BOTH the audit line and the
 * metric update for one operation, so callers make a single call per op
 * (no risk of audit-without-metric or vice versa).
 */
@Configuration
public class RegistryObservabilityConfig {

    private static final Logger AUDIT = LoggerFactory.getLogger("registry.audit");

    private static final String PLACEHOLDER = "-";

    private final MeterRegistry meterRegistry;

    public RegistryObservabilityConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ---- audit + metrics per operation ----

    public void observeRegister(String traceId, String tenantId, String agentId,
                                String contractVersion, String capabilityVersion,
                                String health, String routeHandleId, String outcome, long latencyMs) {
        audit("register", traceId, tenantId, agentId,
                contractVersion, capabilityVersion, health, routeHandleId, outcome, latencyMs);
        recordMetrics("register", outcome, latencyMs);
    }

    public void observeDeregister(String traceId, String tenantId, String agentId,
                                  String outcome, long latencyMs) {
        audit("deregister", traceId, tenantId, agentId,
                PLACEHOLDER, PLACEHOLDER, PLACEHOLDER, PLACEHOLDER, outcome, latencyMs);
        recordMetrics("deregister", outcome, latencyMs);
    }

    public void observeProbe(String traceId, String tenantId, String agentId,
                             String health, String outcome, long latencyMs) {
        audit("probe", traceId, tenantId, agentId,
                PLACEHOLDER, PLACEHOLDER, health, PLACEHOLDER, outcome, latencyMs);
        recordMetrics("probe", outcome, latencyMs);
    }

    /**
     * FEAT-016: takes {@code queryDimension} (agentId / serviceId /
     * capability) + {@code queryValue} instead of {@code agentId}. The audit
     * line records the dimension and value searched so dashboards can
     * distinguish the three query paths.
     */
    public void observeDiscover(String traceId, String tenantId, String queryDimension,
                                String queryValue, String outcome, int resultCount, long latencyMs) {
        AUDIT.info("registryOp=discover traceId={} tenantId={} queryDimension={} queryValue={} "
                        + "contractVersion={} capabilityVersion={} health={} routeHandleId={} outcome={} "
                        + "latencyMs={} resultCount={}",
                traceId, tenantId,
                queryDimension == null ? PLACEHOLDER : queryDimension,
                queryValue == null ? PLACEHOLDER : queryValue,
                PLACEHOLDER, PLACEHOLDER, PLACEHOLDER, PLACEHOLDER, outcome, latencyMs,
                resultCount < 0 ? PLACEHOLDER : resultCount);
        recordMetrics("discover", outcome, latencyMs);
    }

    public void observeResolve(String traceId, String tenantId, String routeHandleId,
                               String outcome, long latencyMs) {
        audit("resolve", traceId, tenantId, PLACEHOLDER,
                PLACEHOLDER, PLACEHOLDER, PLACEHOLDER, routeHandleId, outcome, latencyMs);
        recordMetrics("resolve", outcome, latencyMs);
    }

    // ---- internals ----

    private void audit(String op, String traceId, String tenantId, String agentId,
                       String contractVersion, String capabilityVersion,
                       String health, String routeHandleId, String outcome, long latencyMs) {
        AUDIT.info("registryOp={} traceId={} tenantId={} agentId={} "
                        + "contractVersion={} capabilityVersion={} health={} routeHandleId={} outcome={} "
                        + "latencyMs={}",
                op, traceId, tenantId, agentId,
                contractVersion, capabilityVersion, health, routeHandleId, outcome, latencyMs);
    }

    private void recordMetrics(String op, String outcome, long latencyMs) {
        Counter.builder("agent_bus_registry_op_total")
                .tag("op", op)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
        Timer.builder("agent_bus_registry_op_duration_ms")
                .tag("op", op)
                .register(meterRegistry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }
}
