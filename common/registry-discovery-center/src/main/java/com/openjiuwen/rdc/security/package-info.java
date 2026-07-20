/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — caller / card-fetch security
 * (Feat-015).
 *
 * <p>Hosts {@link com.openjiuwen.rdc.security.CallerAuthorizationPolicy},
 * {@link com.openjiuwen.rdc.security.InternalNetworkPolicy}, and properties
 * for {@code rdc.registry.security.*} / {@code rdc.registry.card-fetch.*}.
 *
 * <p>Pure policy types + Spring {@code @ConfigurationProperties}; JDBC is
 * forbidden.
 *
 * <p>Authority: Feat-015 security boundaries + PR #389 follow-ups.
 */
package com.openjiuwen.rdc.security;
