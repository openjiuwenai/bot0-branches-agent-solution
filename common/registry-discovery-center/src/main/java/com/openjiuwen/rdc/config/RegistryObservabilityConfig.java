/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
 *       logger. Operations register/deregister/probe/resolve emit 10 fields
 *       via shared audit helper: {@code traceId}, {@code tenantId},
 *       {@code agentId}, {@code contractVersion}, {@code capabilityVersion},
 *       {@code health}, {@code routeHandleId}, {@code outcome}, {@code latencyMs}.
 *       Discover emits 12 fields: {@code registryOp, traceId, tenantId, queryDimension,
 *       queryValue, contractVersion, capabilityVersion, health, routeHandleId, outcome,
 *       latencyMs, resultCount}. The {@code registryOp} tag prefixes each line.
 *       Unavailable fields are emitted as {@code "-"}</li>
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
 *
 * @since 2026-07-10
 */
@Configuration
public class RegistryObservabilityConfig {
    private static final Logger AUDIT = LoggerFactory.getLogger("registry.audit");

    private static final String PLACEHOLDER = "-";

    private final MeterRegistry meterRegistry;

    /**
     * Constructs the facade with the injected meter registry.
     *
     * @param meterRegistry the Micrometer registry used to record op counters/timers
     */
    public RegistryObservabilityConfig(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records a register operation's audit line + metrics.
     *
     * @param context the audit context (traceId/tenantId/agentId/contractVersion/
     *                capabilityVersion/health/routeHandleId)
     * @param outcome the operation outcome tag (success/error/...)
     * @param latencyMs the operation latency in milliseconds
     */
    public void observeRegister(RegistryOpContext context, String outcome, long latencyMs) {
        audit("register", context, outcome, latencyMs);
        recordMetrics("register", outcome, latencyMs);
    }

    /**
     * Records a deregister operation's audit line + metrics.
     *
     * @param context the audit context (traceId/tenantId/agentId)
     * @param outcome the operation outcome tag
     * @param latencyMs the operation latency in milliseconds
     */
    public void observeDeregister(RegistryOpContext context, String outcome, long latencyMs) {
        audit("deregister", context, outcome, latencyMs);
        recordMetrics("deregister", outcome, latencyMs);
    }

    /**
     * Records a probe operation's audit line + metrics.
     *
     * @param context the audit context (traceId/tenantId/agentId/health)
     * @param outcome the operation outcome tag
     * @param latencyMs the operation latency in milliseconds
     */
    public void observeProbe(RegistryOpContext context, String outcome, long latencyMs) {
        audit("probe", context, outcome, latencyMs);
        recordMetrics("probe", outcome, latencyMs);
    }

    /**
     * FEAT-016: takes {@code queryDimension} (agentId / serviceId /
     * capability) + {@code queryValue} instead of {@code agentId}. The audit
     * line records the dimension and value searched so dashboards can
     * distinguish the three query paths.
     *
     * @param context the audit context (traceId/tenantId/queryDimension/queryValue)
     * @param outcome the operation outcome tag
     * @param resultCount the number of rows returned by the discovery query
     * @param latencyMs the operation latency in milliseconds
     */
    public void observeDiscover(RegistryOpContext context, String outcome,
                                int resultCount, long latencyMs) {
        String queryDimension = context.getQueryDimension();
        String queryValue = context.getQueryValue();
        AUDIT.info("registryOp=discover traceId={} tenantId={} queryDimension={} queryValue={} "
                        + "contractVersion={} capabilityVersion={} health={} routeHandleId={} outcome={} "
                        + "latencyMs={} resultCount={}",
                context.getTraceId(), context.getTenantId(),
                queryDimension == null ? PLACEHOLDER : queryDimension,
                queryValue == null ? PLACEHOLDER : queryValue,
                PLACEHOLDER, PLACEHOLDER, PLACEHOLDER, PLACEHOLDER, outcome, latencyMs,
                resultCount < 0 ? PLACEHOLDER : String.valueOf(resultCount));
        recordMetrics("discover", outcome, latencyMs);
    }

    /**
     * Records a resolve operation's audit line + metrics.
     *
     * @param context the audit context (traceId/tenantId/routeHandleId)
     * @param outcome the operation outcome tag
     * @param latencyMs the operation latency in milliseconds
     */
    public void observeResolve(RegistryOpContext context, String outcome, long latencyMs) {
        audit("resolve", context, outcome, latencyMs);
        recordMetrics("resolve", outcome, latencyMs);
    }

    /**
     * Emits the shared 10-field audit log line for an operation.
     *
     * @param op the operation name (register/deregister/probe/resolve)
     * @param context the audit context carrying the per-op fields
     * @param outcome the operation outcome tag
     * @param latencyMs the operation latency in milliseconds
     */
    private void audit(String op, RegistryOpContext context, String outcome, long latencyMs) {
        String contractVersion = context.getContractVersion();
        String capabilityVersion = context.getCapabilityVersion();
        String health = context.getHealth();
        String routeHandleId = context.getRouteHandleId();
        AUDIT.info("registryOp={} traceId={} tenantId={} agentId={} "
                        + "contractVersion={} capabilityVersion={} health={} routeHandleId={} outcome={} "
                        + "latencyMs={}",
                op, context.getTraceId(), context.getTenantId(), context.getAgentId(),
                contractVersion == null ? PLACEHOLDER : contractVersion,
                capabilityVersion == null ? PLACEHOLDER : capabilityVersion,
                health == null ? PLACEHOLDER : health,
                routeHandleId == null ? PLACEHOLDER : routeHandleId, outcome, latencyMs);
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
