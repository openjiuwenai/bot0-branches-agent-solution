/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — Agent Card fetch / validate / digest
 * (Feat-015).
 *
 * <p>Hosts {@link com.openjiuwen.rdc.card.AgentCardFetcher},
 * {@link com.openjiuwen.rdc.card.AgentCardValidator},
 * {@link com.openjiuwen.rdc.card.CardDigest},
 * {@link com.openjiuwen.rdc.card.RouteTargetDeriver}, and optional mTLS /
 * signature helpers used by the reconciliation path.
 *
 * <p>JDBC is forbidden here — callers persist via
 * {@link com.openjiuwen.rdc.repository.AgentRegistryRepository}. Jackson is
 * licensed for A2A Agent Card JSON handling.
 *
 * <p>Authority: Feat-015 §5.1 + ADR-0160.
 */
package com.openjiuwen.rdc.card;
