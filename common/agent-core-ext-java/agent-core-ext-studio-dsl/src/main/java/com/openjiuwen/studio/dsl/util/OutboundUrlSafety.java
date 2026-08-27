/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.util.Locale;

/**
 * Outbound URL validation for workflow-configured HTTP/MCP endpoints (SSRF mitigation).
 *
 * @since 2026-08-27
 */

public final class OutboundUrlSafety {
    private OutboundUrlSafety() {}

    /**
     * Validate URL before outbound HTTP/MCP calls.
     *
     * @param url rendered URL
     * @throws IllegalArgumentException when URL is unsafe or malformed
     */

    public static void validateOutbound(String url) {
        if (url == null || url.isBlank()) {
        throw new IllegalArgumentException("outbound URL is empty");
    }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid outbound URL: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("outbound URL missing scheme: " + url);
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(s) && !"https".equals(s)) {
            throw new IllegalArgumentException("outbound URL scheme not allowed: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("outbound URL missing host: " + url);
        }
        if (allowsPrivateOutbound()) {
            return;
        }
        if (isBlockedHost(host)) {
            throw new IllegalArgumentException("outbound URL targets restricted host: " + host);
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (isBlockedAddress(addr)) {
                    throw new IllegalArgumentException(
                            "outbound URL resolves to restricted address: " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            // Allow DNS failure at validation time; connect will fail later
        }
    }

    private static boolean isBlockedHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(h) || h.endsWith(".localhost")) {
            return true;
        }
        if ("metadata.google.internal".equals(h)) {
            return true;
        }
        return false;
    }

    private static boolean allowsPrivateOutbound() {
        String env = System.getenv("STUDIO_DSL_OUTBOUND_ALLOW_PRIVATE");
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env.trim());
        }
        return Boolean.getBoolean("studio.dsl.outbound.allowPrivate");
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
        return true;
    }
        if (addr.isSiteLocalAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // 169.254.0.0/16 link-local / cloud metadata
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            // 127.0.0.0/8 loopback
            if (b0 == 127) {
                return true;
            }
            // 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 private
            if (b0 == 10) {
                return true;
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
            if (b0 == 192 && b1 == 168) {
                return true;
            }
            // 0.0.0.0/8
            if (b0 == 0) {
                return true;
            }
        }
        return false;
    }
}
