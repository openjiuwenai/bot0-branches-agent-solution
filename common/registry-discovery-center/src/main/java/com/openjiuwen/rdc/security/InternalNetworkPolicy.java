/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Restricts Agent Card fetch targets to configured CIDR allowlists (0711 §5.1.3).
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
        if (allowedCidrs.isEmpty()) {
            return true;
        }
        String host = baseUrl.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            // All resolved addresses must fall in the allowlist (reject mixed public+private).
            for (InetAddress address : addresses) {
                if (!isAddressAllowed(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private boolean isAddressAllowed(InetAddress address) {
        for (CidrBlock block : allowedCidrs) {
            if (block.contains(address)) {
                return true;
            }
        }
        return false;
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
