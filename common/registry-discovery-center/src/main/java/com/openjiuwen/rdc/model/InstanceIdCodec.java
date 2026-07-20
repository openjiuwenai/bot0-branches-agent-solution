/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Derive the {@code instance_id} column value (server-derived host-port)
 * from a runtime {@code endpointUrl}.
 *
 * <p>FEAT-016 阶段一：{@code instanceId} 承担 REQ-2026-006 时期
 * {@code serviceId} 的 host-port 派生职责，区分同一 {@code serviceId}
 * 下的具体实例。Caller 不可提供（setter package-private）；本类是唯一
 * 派生入口。
 *
 * <p>Pure Java (ADR-0160 decision 1) — uses only {@link java.net.URI} from the
 * JDK. No Jackson / Spring / JDBC imports so the {@code spi.registry} package
 * stays ArchUnit-pure.
 *
 * @since 2026-07-10
 */
public final class InstanceIdCodec {
    private InstanceIdCodec() {
    }

    /**
     * Derive {@code instance_id} from a runtime endpoint URL.
     *
     * <p>Rule: {@code host + '-' + port}. When the URL omits the port, the
     * scheme default is used ({@code http→80}, {@code https→443}). When the
     * URL has no scheme, {@code http} is assumed. The host is lower-cased so
     * {@code HTTP://Foo:8080} and {@code http://foo:8080} produce the same
     * {@code instance_id}.
     *
     * @param endpointUrl the runtime endpoint URL (e.g.
     *                    {@code http://10.0.0.1:8080}, {@code https://host})
     * @return the derived {@code instance_id} (e.g. {@code 10.0.0.1-8080},
     *         {@code host-443})
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
        int port = uri.getPort();
        // A bare "host:port" (no scheme) is parsed by URI as scheme=host,
        // ssp=port — host and port both come back as null/-1. Retry with a
        // leading "//" so URI treats it as a scheme-less authority.
        if ((host == null || host.isBlank()) && uri.getScheme() != null
                && !"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            try {
                URI authorityUri = new URI("//" + endpointUrl);
                host = authorityUri.getHost();
                port = authorityUri.getPort();
            } catch (URISyntaxException ignored) {
                // fall through to the host-null guard below
            }
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpointUrl has no host: " + endpointUrl);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (port == -1) {
            String scheme = uri.getScheme();
            if (scheme == null) {
                scheme = "http";
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            port = "https".equals(scheme) ? 443 : 80;
        }
        return host + "-" + port;
    }

    /**
     * Derive {@code instance_id} from an entry's {@code endpointUrl} and stamp
     * it onto the entry. This is the bridge the runtime layer (push register
     * controller / pull bootstrap) uses to populate {@code instanceId} — the
     * entry's setter is package-private so only {@code spi.registry} classes
     * can set it. {@code InstanceIdCodec} lives in the same package and
     * exposes this helper as the single derivation entry point.
     *
     * @param entry the registry entry whose {@code endpointUrl} is the
     *              derivation source and whose {@code instanceId} will be
     *              overwritten
     * @throws IllegalArgumentException if {@code entry.endpointUrl} is blank
     *         or malformed
     */
    public static void applyTo(AgentRegistryEntry entry) {
        entry.setInstanceId(derive(entry.getEndpointUrl()));
    }
}
