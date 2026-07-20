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
 * @since 0.1.0
 */
public final class InternalNetworkPolicy {

    private final List<CidrBlock> allowedCidrs;

    private InternalNetworkPolicy(List<CidrBlock> allowedCidrs) {
        this.allowedCidrs = List.copyOf(allowedCidrs);
    }

    public static InternalNetworkPolicy permissive() {
        return new InternalNetworkPolicy(List.of());
    }

    public static InternalNetworkPolicy from(RdcCardFetchOptions options) {
        List<CidrBlock> blocks = new ArrayList<>();
        for (String cidr : options.getTargetCidrs()) {
            if (cidr != null && !cidr.isBlank()) {
                blocks.add(CidrBlock.parse(cidr.trim()));
            }
        }
        return new InternalNetworkPolicy(blocks);
    }

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
            for (InetAddress address : addresses) {
                for (CidrBlock block : allowedCidrs) {
                    if (block.contains(address)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (UnknownHostException ex) {
            return false;
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
                return new CidrBlock(InetAddress.getByName(ip), prefix);
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
