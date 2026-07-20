/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.model.CallerNotAuthorizedException;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.RegistrationInvalidException;
import com.openjiuwen.rdc.model.RegistryRequestContext;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.DiscoveryFilter;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.LogicalRegistrationRow;
import com.openjiuwen.rdc.repository.AgentRegistryRepositoryStub;
import com.openjiuwen.rdc.security.CallerAuthorizationPolicy;
import com.openjiuwen.rdc.security.RegistrySecurityProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Feat-015 logical Agent Card discovery semantics without embedded PostgreSQL.
 */
class AgentCardDiscoverySemanticsTest {
    private static final String TRACE = "trace-sem";
    private static final String TENANT = "tenant-sem";
    private static final String AGENT = "agent-sem";
    private static final String SERVICE = "svc-sem";

    private LogicalRegistrationRepository repository;
    private PgMvpDiscoveryServiceImpl discovery;
    private TestTenantContext tenantContext;

    @BeforeEach
    void setUp() {
        repository = new LogicalRegistrationRepository();
        tenantContext = new TestTenantContext();
        discovery = new PgMvpDiscoveryServiceImpl(
                repository,
                tenantContext,
                new RegistryObservabilityConfig(new SimpleMeterRegistry()),
                new CallerAuthorizationPolicy.Permissive());
    }

    @Test
    void registered_missing_card_raises_invalid() {
        repository.rows = List.of(registeredRow(null));

        assertThatThrownBy(() -> discover())
                .isInstanceOf(RegistrationInvalidException.class)
                .satisfies(ex -> {
                    if (ex instanceof RegistrationInvalidException invalid) {
                        assertThat(invalid.failure().failureCode()).isEqualTo("REGISTRATION_INVALID");
                    }
                });
    }

    @Test
    void registered_malformed_card_raises_invalid() {
        repository.rows = List.of(registeredRow("{not-json"));

        assertThatThrownBy(() -> discover())
                .isInstanceOf(RegistrationInvalidException.class);
    }

    @Test
    void valid_registered_row_returns_success_candidate() {
        repository.rows = List.of(registeredRow(validCardJson()));

        var result = discovery.discoverAgentCards(
                com.openjiuwen.rdc.model.AgentCardDiscoveryQuery.builder()
                        .context(context())
                        .agentId(AGENT)
                        .limit(10)
                        .build());

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).serviceId()).isEqualTo(SERVICE);
    }

    @Test
    void caller_allowlist_rejects_unauthorized_caller() {
        RegistrySecurityProperties props = new RegistrySecurityProperties();
        props.getCallerAllowlist().put(TENANT, Set.of("gateway"));
        PgMvpDiscoveryServiceImpl guarded = new PgMvpDiscoveryServiceImpl(
                repository,
                tenantContext,
                new RegistryObservabilityConfig(new SimpleMeterRegistry()),
                new CallerAuthorizationPolicy.Allowlist(props));
        repository.rows = List.of(registeredRow(validCardJson()));

        assertThatThrownBy(() -> guarded.discoverAgentCards(
                com.openjiuwen.rdc.model.AgentCardDiscoveryQuery.builder()
                        .context(new RegistryRequestContext(
                                TENANT, "unknown-caller", TRACE, "req-1", Instant.now().plusSeconds(30)))
                        .agentId(AGENT)
                        .limit(10)
                        .build()))
                .isInstanceOf(CallerNotAuthorizedException.class);
    }

    @Test
    void bound_tenant_mismatch_raises_tenant_scope_denied() {
        repository.rows = List.of(registeredRow(validCardJson()));
        tenantContext.set("other-tenant");

        assertThatThrownBy(() -> discover())
                .isInstanceOf(TenantIsolationViolationException.class)
                .satisfies(ex -> {
                    if (ex instanceof TenantIsolationViolationException violation) {
                        assertThat(violation.failure().failureCode()).isEqualTo("TENANT_SCOPE_DENIED");
                    }
                });
    }

    @Test
    void mark_source_stale_updates_logical_registration_freshness() {
        UUID registrationId = UUID.randomUUID();
        repository.rows = List.of(new LogicalRegistrationRow(
                registrationId, TENANT, AGENT, SERVICE, "digest-1", "1.0.0", "1.0.0",
                "REGISTERED", "FRESH", Instant.now(), 1L, validCardJson()));

        repository.markLogicalRegistrationsStaleSource("static-config");

        assertThat(repository.staleSourceMarked).isTrue();
    }

    private com.openjiuwen.rdc.model.DiscoveryResult discover() {
        return discovery.discover(DiscoveryQuery.builder()
                .context(context())
                .agentId(AGENT)
                .limit(10)
                .build());
    }

    private static RegistryRequestContext context() {
        return new RegistryRequestContext(
                TENANT, "test-client", TRACE, "req-1", Instant.now().plusSeconds(30));
    }

    private static LogicalRegistrationRow registeredRow(String cardJson) {
        return new LogicalRegistrationRow(
                UUID.randomUUID(),
                TENANT,
                AGENT,
                SERVICE,
                "digest-1",
                "1.0.0",
                "1.0.0",
                "REGISTERED",
                "FRESH",
                Instant.now(),
                1L,
                cardJson);
    }

    private static String validCardJson() {
        return "{"
                + "\"name\":\"demo\",\"description\":\"d\",\"version\":\"1.0.0\","
                + "\"defaultInputModes\":[\"text\"],\"defaultOutputModes\":[\"text\"],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"skills\":[],"
                + "\"supportedInterfaces\":[{\"protocol\":\"jsonrpc\",\"url\":\"/a2a\",\"version\":\"1.0.0\"}]"
                + "}";
    }

    private static final class LogicalRegistrationRepository extends AgentRegistryRepositoryStub {
        List<LogicalRegistrationRow> rows = List.of();
        boolean staleSourceMarked;

        @Override
        public List<LogicalRegistrationRow> queryLogicalByTargetSelector(DiscoveryFilter filter) {
            return rows.stream()
                    .filter(r -> filter.tenantId().equals(r.tenantId()))
                    .filter(r -> filter.agentId() == null || filter.agentId().isBlank()
                            || filter.agentId().equals(r.agentId()))
                    .toList();
        }

        @Override
        public void markLogicalRegistrationsStaleSource(String sourceId) {
            staleSourceMarked = true;
            rows = rows.stream()
                    .map(r -> new LogicalRegistrationRow(
                            r.registrationId(), r.tenantId(), r.agentId(), r.serviceId(),
                            r.cardDigest(), r.contractVersion(), r.capabilityVersion(),
                            r.registrationStatus(), "STALE_SOURCE", r.lastValidatedAt(),
                            r.revision(), r.a2aAgentCardJson()))
                    .toList();
        }

        @Override
        public void upsert(com.openjiuwen.rdc.model.AgentRegistryEntry card, String json) { }
        @Override
        public boolean delete(String tenantId, String agentId) {
            return false;
        }
        @Override
        public boolean delete(String tenantId, String agentId, String serviceId) {
            return false;
        }
        @Override
        public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return List.of();
        }
        @Override
        public java.util.Optional<EndpointEntry> findEndpoint(
                String tenantId, String agentId,
                String serviceId, String instanceId) {
                    return java.util.Optional.empty();
                }
        @Override
        public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) {
            return List.of();
        }
        @Override
        public void reconcileUpsert(ReconcileUpsertCommand command) { }
        @Override
        public List<InstanceKey> listInstanceKeysBySource(String sourceId) {
            return List.of();
        }
        @Override
        public void markDraining(String tenantId, String agentId, String serviceId) { }
        @Override
        public void markRemoved(String tenantId, String agentId, String serviceId) { }
        @Override
        public void markSourceStale(String sourceId) {
            markLogicalRegistrationsStaleSource(sourceId);
        }
        @Override
        public void markSourceFresh(String sourceId) { }
        @Override
        public List<InstanceKey> listDrainingPastGrace(Instant cutoff) {
            return List.of();
        }
        @Override
        public List<InstanceKey> listExpiredLeases(Instant now) {
            return List.of();
        }
        @Override
        public long getLastProcessedRevision(String sourceId) {
            return 0;
        }
        @Override
        public void updateLastProcessedRevision(String sourceId, long revision) { }
        @Override
        public void updateLastProcessedRevision(String sourceId, long revision, String fingerprint) { }
        @Override
        public java.util.Optional<String> getSnapshotFingerprint(String sourceId) {
            return java.util.Optional.empty();
        }
        @Override
        public java.util.Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
            return java.util.Optional.empty();
        }
        @Override
        public void reconcilePending(ReconcilePendingCommand command) { }
        @Override
        public void markRefreshDegraded(String tenantId, String agentId, String serviceId) {
            }
        }
    private static final class TestTenantContext implements com.openjiuwen.rdc.tenant.TenantContext {
        private String current;

        @Override
        public String current() {
            return current;
        }
        void set(String tenantId) {
            this.current = tenantId;
        }
    }
}
