/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.pull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.config.RegistryOpAudit;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.InstanceIdCodec;
import com.openjiuwen.rdc.model.ServiceIdCodec;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pull-based agent registration bootstrap (REQ-2026-004).
 *
 * <p>Listens for {@link ApplicationReadyEvent} and serially HTTP GETs each
 * configured runtime's A2A AgentCard, constructs an
 * {@link AgentRegistryEntry}, and upserts it via
 * {@link AgentRegistryRepository}. Single runtime failure is logged + skipped
 * — startup never blocks. Bootstrap-only: no re-pull, no scheduled refresh.
 *
 * <p>Activation: {@code @ConditionalOnProperty(prefix=rdc.pull-registration,
 * name=enabled, havingValue=true)} — when disabled, Spring does not
 * instantiate this listener. {@code RegistryRuntimeBeanConfig} is NOT
 * modified (OQ-5 H2 resolution: use {@code @Component} auto-scan).
 *
 * <p>Observability: reuses {@link RegistryObservabilityConfig#observeRegister}
 * with traceId {@code pull-bootstrap-<timestamp>-<uuid>} so pull upserts are
 * distinguishable from push upserts in the audit log.
 *
 * <p>ArchUnit purity: imports Spring ({@code RestClient}, {@code @Component})
 * but never {@code java.sql} / {@code javax.sql} /
 * {@code org.springframework.jdbc.*} — runtime.pull is NOT a JDBC package
 * (ADR-0160 decision 4 unchanged).
 *
 * @since 0.1.0 (2026)
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "rdc.pull-registration",
        name = "enabled",
        havingValue = "true"
)
public class PullRegistrationBootstrap implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(PullRegistrationBootstrap.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final PullRegistrationProperties properties;
    private final AgentRegistryRepository repository;
    private final RegistryObservabilityConfig observability;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PullRegistrationBootstrap(PullRegistrationProperties properties,
                                     AgentRegistryRepository repository,
                                     RegistryObservabilityConfig observability,
                                     ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.restClient = RestClient.builder()
                .build();
    }

    /**
     * onApplicationEvent.
     *
     * @param event event
     * @since 0.1.0
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        runBootstrap();
    }

    /**
     * Package-private entry point for tests — avoids constructing a real
     * {@link ApplicationReadyEvent} (whose source must be non-null).
     *
     * @since 0.1.0
     */

    void runBootstrap() {
        if (!properties.isEnabled() || properties.getRuntimes().isEmpty()) {
            return;
        }
        long timestamp = System.currentTimeMillis();
        for (PullRegistrationProperties.RuntimeEntry runtime : properties.getRuntimes()) {
            String traceId = "pull-bootstrap-" + timestamp + "-" + UUID.randomUUID();
            MDC.put("traceId", traceId);
            try {
                pullOne(runtime, traceId);
            } catch (RestClientException | IllegalStateException ex) {
                LOG.warn("pull-bootstrap runtime {} failed: {}", runtime.getBaseUrl(), ex.getMessage(), ex);
            } finally {
                MDC.remove("traceId");
            }
        }
    }

    private void pullOne(PullRegistrationProperties.RuntimeEntry runtime, String traceId) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            requireRequired(runtime);
            String cardUrl = runtime.getBaseUrl() + runtime.getCardPath();
            var requestSpec = restClient.get().uri(cardUrl);
            if (runtime.getHeaders() != null) {
                for (Map.Entry<String, String> h : runtime.getHeaders().entrySet()) {
                    requestSpec = requestSpec.header(h.getKey(), h.getValue());
                }
            }
            String cardJson = requestSpec.retrieve().body(String.class);
            if (cardJson == null || cardJson.isBlank()) {
                throw new IllegalStateException("empty card body from " + cardUrl);
            }
            // Extract just the `name` field for the entry's agentName. Full
            // AgentCard deserialization is skipped (17-field record requires a
            // complete JSON; the raw JSON is passed to upsert as-is for the
            // a2a_agent_card jsonb column, so no information is lost).
            com.fasterxml.jackson.databind.JsonNode cardNode = objectMapper.readTree(cardJson);
            String agentName = cardNode.path("name").asText(null);
            AgentRegistryEntry entry = buildEntry(runtime, agentName);
            repository.upsert(entry, cardJson);
            LOG.info("pull-bootstrap registered tenant={} agent={} baseUrl={}",
                    entry.getTenantId(), entry.getAgentId(), entry.getEndpointUrl());
            outcome = "success";
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("pull-bootstrap failed: " + ex.getMessage(), ex);
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeRegister(new RegistryOpAudit(
                    traceId, runtime.getTenantId(), runtime.getAgentId(),
                    runtime.getContractVersion(), runtime.getCapabilityVersion(),
                    "ONLINE", null, outcome, latencyMs));
        }
    }

    private static void requireRequired(PullRegistrationProperties.RuntimeEntry runtime) {
        Objects.requireNonNull(runtime.getBaseUrl(), "baseUrl is required");
        Objects.requireNonNull(runtime.getTenantId(), "tenantId is required");
        Objects.requireNonNull(runtime.getAgentId(), "agentId is required");
        FrameworkType ft = runtime.getFrameworkType();
        if (ft == null) {
            throw new IllegalArgumentException("frameworkType is required for runtime " + runtime.getBaseUrl());
        }
    }

    private static AgentRegistryEntry buildEntry(PullRegistrationProperties.RuntimeEntry runtime,
                                                  String agentName) {
            AgentRegistryEntry entry = new AgentRegistryEntry();
            entry.setTenantId(runtime.getTenantId());
            entry.setAgentId(runtime.getAgentId());
            entry.setAgentName(agentName);
            entry.setFrameworkType(runtime.getFrameworkType());
            entry.setRouteKey(runtime.getRouteKey());
            entry.setContractVersion(runtime.getContractVersion());
            entry.setCapabilityVersion(runtime.getCapabilityVersion());
            entry.setEndpointUrl(runtime.getBaseUrl());
            entry.setRegion(runtime.getRegion());
            entry.setMaxConcurrency(runtime.getMaxConcurrency() != null
            ? runtime.getMaxConcurrency()
            : PullRegistrationProperties.DEFAULT_MAX_CONCURRENCY);
            entry.setWeight(runtime.getWeight() != null
            ? runtime.getWeight()
            : PullRegistrationProperties.DEFAULT_WEIGHT);
            ServiceIdCodec.applyTo(entry);
            entry.setCapabilities(java.util.List.of());
            InstanceIdCodec.applyTo(entry);
            return entry;
        }
}
