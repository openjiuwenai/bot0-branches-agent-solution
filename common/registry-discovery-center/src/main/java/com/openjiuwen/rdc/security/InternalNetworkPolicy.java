/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Restricts Agent Card fetch targets to configured CIDR allowlists (0711 §5.1.3).
 *
 * <p>Resolves DNS once and returns a {@link PinnedTarget} so callers can connect to the
 * literal IP (Host header keeps the original name), closing DNS rebinding / TOCTOU gaps.
 *
 * @since 0.1.0 (2026)
 */
public final class InternalNetworkPolicy {
    private final List<CidrBlock> allowedCidrs;

    private InternalNetworkPolicy(List<CidrBlock> allowedCidrs) {
        this.allowedCidrs = List.copyOf(allowedCidrs);
    }

    /**
     * permissive.
     *
     * @return result
     * @since 0.1.0
     */
    public static InternalNetworkPolicy permissive() {
        return new InternalNetworkPolicy(List.of());
    }

    /**
     * from.
     *
     * @param options options
     * @return result
     * @since 0.1.0
     */
    public static InternalNetworkPolicy from(RdcCardFetchOptions options) {
        List<CidrBlock> blocks = new ArrayList<>();
        for (String cidr : options.getTargetCidrs()) {
            if (cidr != null && !cidr.isBlank()) {
                blocks.add(CidrBlock.parse(cidr.trim()));
            }
        }
        return new InternalNetworkPolicy(blocks);
    }

    /**
     * isAllowed.
     *
     * @param baseUrl baseUrl
     * @return result
     * @since 0.1.0
     */
    public boolean isAllowed(URI baseUrl) {
        return resolve(baseUrl).isPresent();
    }

    /**
     * Resolves {@code baseUrl}'s host once, enforces the CIDR allowlist on every address,
     * and returns a target pinned to a single allowed address (IPv4 preferred when present).
     *
     * @param baseUrl fetch base URL
     * @return pinned target when allowed; empty when rejected or unresolvable
     * @since 0.1.0
     */
    public Optional<PinnedTarget> resolve(URI baseUrl) {
        if (baseUrl == null) {
            return Optional.empty();
        }
        String host = baseUrl.getHost();
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            return Optional.empty();
        }
        if (addresses.length == 0) {
            return Optional.empty();
        }
        List<InetAddress> candidates;
        if (allowedCidrs.isEmpty()) {
            candidates = List.of(addresses);
        } else {
            candidates = filterAndEnforceAllowlist(addresses);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
        }
        InetAddress pinned = selectPinnedAddress(candidates);
        return Optional.of(new PinnedTarget(baseUrl.getScheme(), host, pinned, baseUrl.getPort()));
    }

    /**
     * Enforces CIDR policy on resolved addresses. Only address families that appear in the
     * configured CIDR list are considered (so {@code 127.0.0.0/8} alone does not reject
     * {@code localhost} merely because it also has an {@code ::1} AAAA record). Every
     * considered address must fall in the allowlist to block mixed public/private answers.
     */
    private List<InetAddress> filterAndEnforceAllowlist(InetAddress[] addresses) {
        boolean hasIpv4Policy = false;
        boolean hasIpv6Policy = false;
        for (CidrBlock block : allowedCidrs) {
            if (block.network() instanceof Inet4Address) {
                hasIpv4Policy = true;
            } else if (block.network() instanceof Inet6Address) {
                hasIpv6Policy = true;
            }
        }
        List<InetAddress> candidates = new ArrayList<>();
        for (InetAddress address : addresses) {
            boolean ipv4 = address instanceof Inet4Address;
            if (ipv4 && !hasIpv4Policy) {
                continue;
            }
            if (!ipv4 && !hasIpv6Policy) {
                continue;
            }
            if (!isAddressAllowed(address)) {
                return List.of();
            }
            candidates.add(address);
        }
        return candidates;
    }

    private static InetAddress selectPinnedAddress(List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            if (address instanceof Inet4Address) {
                return address;
            }
        }
        return addresses.get(0);
    }

    private boolean isAddressAllowed(InetAddress address) {
        for (CidrBlock block : allowedCidrs) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * DNS-resolved connect target for Agent Card fetch.
     *
     * @param scheme original scheme ({@code http}/{@code https})
     * @param originalHost hostname from the caller URL (used for {@code Host} / SNI verify)
     * @param address pinned connect address
     * @param port explicit port or {@code -1} for scheme default
     * @since 0.1.0
     */
    public record PinnedTarget(String scheme, String originalHost, InetAddress address, int port) {
        /**
         * Host header value (includes non-default port when present on the original URL).
         */
        public String hostHeaderValue() {
            if (port < 0) {
                return originalHost;
            }
            return originalHost + ":" + port;
        }

        /**
         * Builds a request URI that connects to the pinned IP while preserving path.
         *
         * @param path absolute path beginning with {@code /}
         * @return pinned request URI
         */
        public URI requestUri(String path) {
            try {
                String normalizedPath = path == null || path.isBlank() ? "/" : path;
                return new URI(scheme, null, literalHost(), effectivePort(), normalizedPath, null, null);
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException("invalid pinned URI for host " + originalHost, ex);
            }
        }

        private String literalHost() {
            String hostAddress = address.getHostAddress();
            int zone = hostAddress.indexOf('%');
            if (zone >= 0) {
                hostAddress = hostAddress.substring(0, zone);
            }
            return hostAddress;
        }

        private int effectivePort() {
            if (port >= 0) {
                return port;
            }
            if ("https".equalsIgnoreCase(scheme)) {
                return 443;
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return 80;
            }
            return -1;
        }
    }

    private record CidrBlock(InetAddress network, int prefixLength) {
        static CidrBlock parse(String cidr) {
            int slash = cidr.indexOf('/');
            if (slash <= 0) {
                throw new IllegalArgumentException("invalid CIDR: " + cidr);
            }
            String ip = cidr.substring(0, slash);
            int prefix;
            try {
                prefix = Integer.parseInt(cidr.substring(slash + 1));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid CIDR prefix: " + cidr, ex);
            }
            try {
                InetAddress network = InetAddress.getByName(ip);
                int maxPrefix = network.getAddress().length * 8;
                if (prefix < 0 || prefix > maxPrefix) {
                    throw new IllegalArgumentException(
                            "invalid CIDR prefix length: " + cidr + " (expected 0.." + maxPrefix + ")");
                }
                return new CidrBlock(network, prefix);
            } catch (UnknownHostException ex) {
                throw new IllegalArgumentException("invalid CIDR address: " + cidr, ex);
            }
        }

        boolean contains(InetAddress address) {
            byte[] networkBytes = network.getAddress();
            byte[] addressBytes = address.getAddress();
            if (networkBytes.length != addressBytes.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != addressBytes[i]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = (-1) << (8 - remainingBits);
            return (networkBytes[fullBytes] & mask) == (addressBytes[fullBytes] & mask);
        }
    }
}
