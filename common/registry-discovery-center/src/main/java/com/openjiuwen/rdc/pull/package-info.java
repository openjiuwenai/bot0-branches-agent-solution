/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Deprecated pull-based agent registration (REQ-2026-004).
 *
 * <p><strong>Deprecated (forRemoval).</strong> Prefer
 * {@code rdc.deployment-discovery.*} (Feat-015). Startup fails if both
 * {@code rdc.pull-registration.enabled} and
 * {@code rdc.deployment-discovery.enabled} are true
 * ({@link com.openjiuwen.rdc.config.RegistrationPathGuard}). Enabling pull
 * alone logs a deprecation WARN. See README §「从 pull-registration 迁移到
 * deployment-discovery」.
 *
 * <p>When {@code rdc.pull-registration.enabled=true},
 * {@link com.openjiuwen.rdc.pull.PullRegistrationBootstrap} listens for
 * {@code ApplicationReadyEvent} and serially HTTP GETs each configured
 * runtime's {@code /.well-known/agent-card.json}, constructs an
 * {@link com.openjiuwen.rdc.model.AgentRegistryEntry}, and upserts via
 * {@link com.openjiuwen.rdc.repository.AgentRegistryRepository}.
 * Bootstrap-only — no re-pull, no scheduled refresh; does not write
 * {@code agent_card_registration} / {@code agent_card_source_ref}.
 *
 * <p>ArchUnit purity: this package is NOT a JDBC package.
 */

package com.openjiuwen.rdc.pull;
