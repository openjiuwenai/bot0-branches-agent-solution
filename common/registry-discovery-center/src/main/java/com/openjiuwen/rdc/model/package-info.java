/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — data model / contract layer
 * (FEAT-016 + Feat-015).
 *
 * <p>FEAT-016 instance-route types: {@link com.openjiuwen.rdc.model.AgentCardDto},
 * {@link com.openjiuwen.rdc.model.RouteResolution},
 * {@link com.openjiuwen.rdc.model.AgentRegistryEntry},
 * {@link com.openjiuwen.rdc.model.InstanceIdCodec} /
 * {@link com.openjiuwen.rdc.model.ServiceIdCodec}.
 *
 * <p>Feat-015 logical-discovery contracts: {@link com.openjiuwen.rdc.model.DiscoveryQuery},
 * {@link com.openjiuwen.rdc.model.DiscoveryResult},
 * {@link com.openjiuwen.rdc.model.DiscoveryCandidate},
 * freshness / lifecycle / registration governance enums, and
 * {@link com.openjiuwen.rdc.model.RegistryFailure}. Deployment SPI types live in
 * {@code model.deployment}.
 *
 * <p>Pure Java — no Spring / JDBC / Jackson. Package-private setters on
 * {@code AgentRegistryEntry} keep {@code instanceId} server-derived
 * (FEAT-016 §H2-1); codecs in this package are the only mediators.
 *
 * <p>Authority: ADR-0160 + FEAT-016 §2.3.2 / Feat-015 DiscoveryQuery contract.
 */
package com.openjiuwen.rdc.model;
