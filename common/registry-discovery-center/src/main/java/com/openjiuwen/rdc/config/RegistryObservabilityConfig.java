package com.openjiuwen.rdc.config;

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
 * <p>REQ-2026-004 changes (baseline-breaking):
 * <ul>
 *   <li>Removed {@code capability} parameter from {@link #observeRegister}
 *       and {@link #observeDiscover} — audit log format goes from 10 fields
 *       to 9 fields. Operations dashboards parsing the audit log must update
 *       their field count.</li>
 *   <li>{@code observeDiscover} now takes {@code agentId} instead of
 *       {@code capability} — single-value point lookup reports the agentId
 *       it searched for, not a capability scope.</li>
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

    public void observeDiscover(String traceId, String tenantId, String agentId,
                                String outcome, int resultCount, long latencyMs) {
        AUDIT.info("registryOp=discover traceId={} tenantId={} agentId={} "
                        + "contractVersion={} capabilityVersion={} health={} routeHandleId={} outcome={} "
                        + "latencyMs={} resultCount={}",
                traceId, tenantId, agentId == null ? PLACEHOLDER : agentId,
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

    /** Governance events per 0711 §5.1.8 SHOULD (independent of query failures). */
    public void observeCardRefreshFailed(String sourceId, String tenantId, String instanceId,
                                         String failureCode) {
        recordGovernance("card_refresh_failed", sourceId, tenantId, instanceId, failureCode);
    }

    public void observeSourceStale(String sourceId) {
        recordGovernance("source_stale", sourceId, PLACEHOLDER, PLACEHOLDER, PLACEHOLDER);
    }

    public void observeReconciliationConflict(String sourceId, long revision) {
        recordGovernance("reconciliation_conflict", sourceId, PLACEHOLDER, PLACEHOLDER,
                String.valueOf(revision));
    }

    public void observeInstanceDraining(String sourceId, String tenantId, String agentId) {
        recordGovernance("draining", sourceId, tenantId, agentId, PLACEHOLDER);
    }

    public void observeLeaseExpired(String tenantId, String agentId) {
        recordGovernance("lease_expired", PLACEHOLDER, tenantId, agentId, PLACEHOLDER);
    }

    public void observeUnhealthy(String tenantId, String agentId, String health) {
        recordGovernance("unhealthy", PLACEHOLDER, tenantId, agentId, health);
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

    private void recordGovernance(String event, String sourceId, String tenantId,
                                  String agentOrInstance, String detail) {
        AUDIT.info("registryOp=governance event={} sourceId={} tenantId={} target={} detail={}",
                event, sourceId, tenantId, agentOrInstance, detail);
        Counter.builder("agent_bus_registry_governance_total")
                .tag("event", event)
                .register(meterRegistry)
                .increment();
    }
}
