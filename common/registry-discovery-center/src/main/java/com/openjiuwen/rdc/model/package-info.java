/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — data model layer (FEAT-016 §3.1).
 *
 * <p>Holds the system route view DTO {@link com.openjiuwen.rdc.model.AgentCardDto},
 * the forwarding-layer-only {@link com.openjiuwen.rdc.model.RouteResolution},
 * the persistence entity {@link com.openjiuwen.rdc.model.AgentRegistryEntry},
 * the server-derived id codecs
 * {@link com.openjiuwen.rdc.model.InstanceIdCodec} and
 * {@link com.openjiuwen.rdc.model.ServiceIdCodec}, the
 * {@link com.openjiuwen.rdc.model.FrameworkType} enum, the local
 * {@link com.openjiuwen.rdc.model.Nullable} marker annotation, and the
 * {@link com.openjiuwen.rdc.model.TenantIsolationViolationException}.
 *
 * <p>Per FEAT-016 §3.1 the SPI types and the implementation share the same
 * top-level package root ({@code com.openjiuwen.rdc}); the previous
 * {@code spi.registry} physical boundary is folded into {@code model} /
 * {@code service} / {@code tenant}. {@link com.openjiuwen.rdc.model.Nullable}
 * stays a local marker so the model layer does not pull
 * {@code jakarta.annotation} or {@code org.springframework.lang} across the
 * boundary.
 *
 * <p>{@link com.openjiuwen.rdc.model.InstanceIdCodec} and
 * {@link com.openjiuwen.rdc.model.ServiceIdCodec} live here (not in
 * {@code service}) because {@code AgentRegistryEntry.setInstanceId} is
 * package-private — the FEAT-016 §H2-1 anti-forge constraint — and
 * {@code InstanceIdCodec.applyTo} is the single server-side mediator that
 * may call it. {@code ServiceIdCodec} is co-located as the sibling
 * server-derived id derivator. {@code RouteHandleCodec} (opaque handle
 * encoding, no package-private access) lives in {@code service}.
 *
 * <p>DTO fields obey FEAT-016 §2.3.2: {@code routeHandle} opaque,
 * {@code serviceId} visible as logical service identifier, no
 * {@code endpointUrl} / {@code routeKey} / {@code instanceId} plaintext,
 * no Task execution state.
 *
 * <p>Authority: ADR-0160 + FEAT-016 §2.3.2 / §3.1 / §H2-1.
 */
package com.openjiuwen.rdc.model;
