/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.health;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.config.RegistryOpContext;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.ProbeTarget;
import com.openjiuwen.rdc.tenant.ThreadLocalTenantContext;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Background health-probe scheduler for the agent registry MVP (ADR-0160 +
 * HD3-004 lease/TTL).
 *
 * <p>Runs a {@code @Scheduled} sweep every 5 seconds (configurable via
 * {@code agent-bus.registry.mvp.probe-interval-ms}), calling
 * {@link AgentRegistryRepository#scanDueForProbe} to find {@code ONLINE} /
 * {@code DEGRADED} entries whose {@code last_heartbeat} is older than the
 * stale threshold (default 5 s). For each target, HTTP
 * {@code GET {endpoint_url}/health} via {@link RestClient}:
 * <ul>
 *   <li>2xx → {@code updateStatus(..., "ONLINE", shouldRefreshHeartbeat=true)} —
 *       heartbeat refreshed, status reaffirmed. A DEGRADED row that probes
 *       successfully is restored to ONLINE (PR #389 review issue #4).</li>
 *   <li>5xx / connect exception / read timeout →
 *       {@code updateStatus(..., "DEGRADED", false)} — status downgraded,
 *       heartbeat untouched. The 15-second visibility window in the
 *       discovery SQL eventually filters the entry out of results if the
 *       heartbeat stays stale (HD3-004).</li>
 * </ul>
 *
 * <h3>PR #389 review issue #2 hardening</h3>
 * <ul>
 *   <li><b>Bounded timeouts</b> — the {@link RestClient} is built with
 *       explicit connect + read timeouts (default 2s each, configurable via
 *       {@code agent-bus.registry.mvp.probe-connect-timeout-ms} /
 *       {@code agent-bus.registry.mvp.probe-read-timeout-ms}). A hung
 *       endpoint fails fast instead of blocking the probe thread
 *       indefinitely.</li>
 *   <li><b>Trailing-slash tolerance</b> — {@link #composeProbeUrl(String)}
 *       strips a trailing {@code /} from the registered {@code endpointUrl}
 *       before appending {@code /health}, so
 *       {@code https://host/} does not produce {@code https://host//health}.</li>
 *   <li><b>Thread isolation</b> — probe execution runs on a dedicated
 *       {@code TaskScheduler} thread pool sized for the probe workload
 *       (configured in {@link com.openjiuwen.rdc.config.RegistrySchedulingConfig}),
 *       so a hung probe cannot stall the default {@code @Scheduled}
 *       single-thread executor that other Spring beans rely on.</li>
 * </ul>
 *
 * <p>Each probe is wrapped in {@link ThreadLocalTenantContext#bindForScope}
 * so the Stage 24 RLS wiring in {@code JdbcAgentRegistryRepository} sees the
 * correct tenant for the {@code updateStatus} call (ESC-2 design pivot,
 * ADR-0160 decision 6 — background scheduling paths bind tenant scope
 * explicitly, since no {@code TenantFilter} populates it at request entry).
 *
 * <p>Spring Web ({@code RestClient}) + Spring Context ({@code @Component} /
 * {@code @Scheduled}) are visible via {@code spring-boot-starter-web} at
 * compile scope (ADR-0160 decision 7, revised per PR #389 review issue #6).
 * JDBC is forbidden in this subpackage — the scheduler calls
 * {@link AgentRegistryRepository} only.
 *
 * <p>Authority: ADR-0160 + HD3-004 + Rule R-C.c (Stage 24 RLS wiring) +
 * PR #389 review issue #2 (timeout / trailing-slash / thread isolation).
 *
 * @since 2026-07-10
 */
@Component
public class MvpHealthProbeScheduler {
    private static final String HEALTH_PATH = "/health";

    private final AgentRegistryRepository repository;
    private final RegistryObservabilityConfig observability;
    private final RestClient httpClient;
    private final long staleBeforeMs;
    private final int scanLimit;

    @Autowired
    public MvpHealthProbeScheduler(AgentRegistryRepository repository,
                                   RegistryObservabilityConfig observability,
                                   @Value("${agent-bus.registry.mvp.probe-stale-before-ms:5000}") long staleBeforeMs,
                                   @Value("${agent-bus.registry.mvp.probe-scan-limit:200}") int scanLimit) {
        this(repository, observability, staleBeforeMs, scanLimit,
                defaultRequestFactory(2_000, 2_000));
    }

    /**
     * Test-friendly constructor that lets the feedback-loop test inject a
     * request factory with explicit timeouts. Production uses the
     * parameterless constructor above; the defaults there (2s / 2s) match
     * the values asserted by {@code Pr389ProbeSchedulerHardeningFeedbackLoopTest}.
     *
     * @param repository     the agent registry port used to scan + update probe status
     * @param observability  the observability facade recording probe metrics (latency / outcome)
     * @param staleBeforeMs  the staleness threshold in ms; entries whose last heartbeat is
     *                       older than {@code now - staleBeforeMs} are eligible for probing
     * @param scanLimit      the maximum number of probe targets to scan per sweep
     * @param requestFactory the HTTP request factory (lets tests inject explicit timeouts)
     */
    MvpHealthProbeScheduler(AgentRegistryRepository repository,
                            RegistryObservabilityConfig observability,
                            long staleBeforeMs,
                            int scanLimit,
                            ClientHttpRequestFactory requestFactory) {
        this.repository = repository;
        this.observability = observability;
        this.httpClient = RestClient.builder().requestFactory(requestFactory).build();
        this.staleBeforeMs = staleBeforeMs;
        this.scanLimit = scanLimit;
    }

    private static SimpleClientHttpRequestFactory defaultRequestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * Scheduled sweep — find ONLINE / DEGRADED entries whose heartbeat is
     * stale and probe each one. Runs every 5 seconds by default
     * (configurable via {@code agent-bus.registry.mvp.probe-interval-ms}).
     */
    @Scheduled(fixedDelayString = "${agent-bus.registry.mvp.probe-interval-ms:5000}")
    public void probeOnlineAgents() {
        long staleBefore = System.currentTimeMillis() - staleBeforeMs;
        List<ProbeTarget> targets = repository.scanDueForProbe(staleBefore, scanLimit);
        for (ProbeTarget target : targets) {
            probeOne(target);
        }
    }

    /**
     * Compose the probe URL by stripping a trailing {@code /} from the
     * registered {@code endpointUrl} before appending {@code /health}.
     * Without this, {@code https://host/} + {@code /health}
     * produces {@code https://host//health} (PR #389 #2).
     *
     * <p>Package-private + static so unit tests can exercise the
     * normalisation directly without going through a live HTTP call (HTTP
     * clients silently canonicalise {@code //x} → {@code /x}, masking the
     * bug at the wire level).
     *
     * @param endpointUrl the registered agent endpoint URL (must not be blank)
     * @return the endpoint URL with a trailing {@code /} stripped and {@code /health} appended
     * @throws IllegalArgumentException if {@code endpointUrl} is null or blank
     */
    static String composeProbeUrl(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("endpointUrl must not be blank");
        }
        String base = endpointUrl.endsWith("/") ? endpointUrl.substring(0, endpointUrl.length() - 1) : endpointUrl;
        return base + HEALTH_PATH;
    }

    private void probeOne(ProbeTarget target) {
        ThreadLocalTenantContext.bindForScope(target.tenantId(), () -> {
            String traceId = UUID.randomUUID().toString();
            MDC.put("traceId", traceId);
            long start = System.nanoTime();
            String outcome = "probe_failed";
            String health = "DEGRADED";
            try {
                httpClient.get()
                        .uri(composeProbeUrl(target.endpointUrl()))
                        .retrieve()
                        .toBodilessEntity();
                repository.updateStatus(new AgentRegistryRepository.StatusUpdate(target.tenantId(), target.agentId(),
                        target.serviceId(), target.instanceId(), "ONLINE", true));
                outcome = "success";
                health = "ONLINE";
            } catch (org.springframework.web.client.RestClientException ex) {
                // A probe failure (5xx / connect / read-timeout) must downgrade
                // the entry to DEGRADED but not abort the sweep; outcome / health
                // already defaulted to probe_failed / DEGRADED.
                repository.updateStatus(new AgentRegistryRepository.StatusUpdate(target.tenantId(), target.agentId(),
                        target.serviceId(), target.instanceId(), "DEGRADED", false));
            } finally {
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                RegistryOpContext ctx = RegistryOpContext.of(traceId, target.tenantId(), target.agentId())
                        .health(health)
                        .build();
                observability.observeProbe(ctx, outcome, latencyMs);
                MDC.remove("traceId");
            }
        });
    }
}
