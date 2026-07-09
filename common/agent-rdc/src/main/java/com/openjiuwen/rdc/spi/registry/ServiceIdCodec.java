package com.openjiuwen.rdc.spi.registry;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Derive the {@code service_id} column value from a runtime {@code endpointUrl}.
 *
 * <p>Authority: REQ-2026-006 — the registry PK evolves from
 * {@code (tenant_id, agent_id)} to {@code (tenant_id, agent_id, service_id)}
 * so the same agentId can host N runtime instances (horizontal scaling). The
 * {@code service_id} is server-derived from the endpoint URL so callers
 * cannot forge or collide it; the derivation rule is {@code host + '-' + port}
 * with the port defaulting to the scheme default (http→80, https→443) when
 * the URL omits it.
 *
 * <p>Pure Java (ADR-0160 decision 1) — uses only {@link java.net.URI} from the
 * JDK. No Jackson / Spring / JDBC imports so the {@code spi.registry} package
 * stays ArchUnit-pure.
 */
public final class ServiceIdCodec {

    private ServiceIdCodec() {
    }

    /**
     * Derive {@code service_id} from a runtime endpoint URL.
     *
     * <p>Rule: {@code host + '-' + port}. When the URL omits the port, the
     * scheme default is used ({@code http→80}, {@code https→443}). When the
     * URL has no scheme, {@code http} is assumed. The host is lower-cased so
     * {@code HTTP://Foo:8080} and {@code http://foo:8080} produce the same
     * {@code service_id}.
     *
     * @param endpointUrl the runtime endpoint URL (e.g.
     *                    {@code http://10.0.0.1:8080}, {@code https://host})
     * @return the derived {@code service_id} (e.g. {@code 10.0.0.1-8080},
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
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("endpointUrl has no host: " + endpointUrl);
        }
        host = host.toLowerCase();
        int port = uri.getPort();
        if (port == -1) {
            String scheme = uri.getScheme();
            if (scheme == null) {
                scheme = "http";
            }
            scheme = scheme.toLowerCase();
            port = "https".equals(scheme) ? 443 : 80;
        }
        return host + "-" + port;
    }

    /**
     * Derive {@code service_id} from an entry's {@code endpointUrl} and stamp
     * it onto the entry. This is the bridge the runtime layer (push register
     * controller / pull bootstrap) uses to populate {@code serviceId} — the
     * entry's setter is package-private (H2-1 decision,方案 a), so only
     * {@code spi.registry} classes can set it. {@code ServiceIdCodec} lives
     * in the same package and exposes this helper as the single derivation
     * entry point.
     *
     * @param entry the registry entry whose {@code endpointUrl} is the
     *              derivation source and whose {@code serviceId} will be
     *              overwritten
     * @throws IllegalArgumentException if {@code entry.endpointUrl} is blank
     *         or malformed
     */
    public static void applyTo(AgentRegistryEntry entry) {
        entry.setServiceId(derive(entry.getEndpointUrl()));
    }
}
