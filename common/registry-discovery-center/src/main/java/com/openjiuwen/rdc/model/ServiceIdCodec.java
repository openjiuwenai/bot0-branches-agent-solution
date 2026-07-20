/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Derive the {@code service_id} column value (logical service identifier)
 * from a runtime {@code endpointUrl}.
 *
 * <p>FEAT-016 阶段一：{@code serviceId} 语义从 "host-port"（REQ-2026-006）
 * 改为 "host only"（逻辑服务标识，可跨 agentId 共享）。原 host-port 派生
 * 逻辑移到 {@link InstanceIdCodec}。
 *
 * <p>Caller 可在 register body 显式提供 {@code serviceId} 覆盖派生值；
 * 本类的 {@code derive} 仅在 caller 未提供时作为默认派生。
 *
 * <p>Pure Java (ADR-0160 decision 1) — uses only {@link java.net.URI} from the
 * JDK. No Jackson / Spring / JDBC imports so the {@code spi.registry} package
 * stays ArchUnit-pure.
 *
 * @since 2026-07-10
 */
public final class ServiceIdCodec {
    private ServiceIdCodec() {
    }

    /**
     * Derive {@code service_id} from a runtime endpoint URL.
     *
     * <p>Rule: {@code host} (lower-cased), port and scheme stripped. The host
     * is lower-cased so {@code HTTP://Foo:8080} and {@code http://foo:8080}
     * produce the same {@code service_id}.
     *
     * @param endpointUrl the runtime endpoint URL (e.g.
     *                    {@code http://10.0.0.1:8080}, {@code https://host})
     *
     * @return the derived {@code service_id} (e.g. {@code 10.0.0.1},
     *         {@code host.example.com})
     *
     * @throws IllegalArgumentException if {@code endpointUrl} is blank or has
     *         no parseable host
     */
    public static String derive(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("endpointUrl must not be blank");
        }
        URI uri;
        try {
            uri = new URI(endpointUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("malformed endpointUrl: " + endpointUrl, e);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpointUrl has no host: " + endpointUrl);
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /**
     * Derive {@code service_id} from an entry's {@code endpointUrl} and stamp
     * it onto the entry. This is the bridge the runtime layer (push register
     * controller / pull bootstrap) uses to populate {@code serviceId} as the
     * default when the caller has not explicitly provided one.
     *
     * @param entry the registry entry whose {@code endpointUrl} is the
     *              derivation source and whose {@code serviceId} will be
     *              overwritten
     *
     * @throws IllegalArgumentException if {@code entry.endpointUrl} is blank
     *         or malformed
     */
    public static void applyTo(AgentRegistryEntry entry) {
        entry.setServiceId(derive(entry.getEndpointUrl()));
    }
}
