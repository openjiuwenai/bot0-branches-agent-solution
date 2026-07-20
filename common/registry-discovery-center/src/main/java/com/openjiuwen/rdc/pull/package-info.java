/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — pull-based agent registration runtime
 * (REQ-2026-004; FEAT-015 registration side).
 *
 * <p>Complements the push-based {@code POST /api/registry/register} flow:
 * when {@code rdc.pull-registration.enabled=true},
 * {@link com.openjiuwen.rdc.pull.PullRegistrationBootstrap} listens for
 * {@code ApplicationReadyEvent} and serially HTTP GETs each configured
 * runtime's {@code /.well-known/agent-card.json}, constructs an
 * {@link com.openjiuwen.rdc.model.AgentRegistryEntry} from the card + the
 * operator-pinned {@link com.openjiuwen.rdc.model.FrameworkType} + tenantId
 * / routeKey / region / contractVersion / capabilityVersion from config,
 * and upserts it via
 * {@link com.openjiuwen.rdc.repository.AgentRegistryRepository}.
 * {@code serviceId} is caller-optional (default: host-derived from
 * {@code baseUrl} via {@link com.openjiuwen.rdc.model.ServiceIdCodec}) and
 * {@code capabilities} is caller-optional (default: empty) per FEAT-016.
 * Single runtime failure is logged + skipped; startup never blocks.
 *
 * <p>Bootstrap-only — no re-pull, no scheduled refresh. The
 * {@link com.openjiuwen.rdc.health.MvpHealthProbeScheduler} picks up the
 * inserted entries automatically.
 *
 * <p>ArchUnit purity: this package is NOT a JDBC package. It imports Spring
 * ({@code @Component}, {@code @ConfigurationProperties}, {@code RestClient})
 * and Jackson (A2A AgentCard JSON deserialization, REQ-2026-004) but never
 * {@code java.sql} / {@code javax.sql} / {@code org.springframework.jdbc.*}
 * — enforced by {@code req-2026-004-pull-bootstrap-no-jdbc-import} /
 * {@code req-2026-004-pull-properties-no-jdbc-import} gate rules and the
 * {@code AgentRdcRegistryJdbcPurityTest} JDBC-confinement + Jackson-license
 * rules.
 */

package com.openjiuwen.rdc.pull;
