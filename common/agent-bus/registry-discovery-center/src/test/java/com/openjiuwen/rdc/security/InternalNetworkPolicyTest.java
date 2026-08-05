/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.Optional;

/**
 * InternalNetworkPolicyTest coverage.
 *
 * @since 0.1.0 (2026)
 */
class InternalNetworkPolicyTest {
    @Test
    void permissive_allows_any_host() {
        InternalNetworkPolicy policy = InternalNetworkPolicy.permissive();
        assertThat(policy.isAllowed(URI.create("http://127.0.0.1:8080"))).isTrue();
        assertThat(policy.isAllowed(URI.create("http://203.0.113.1:8080"))).isTrue();
    }

    @Test
    void loopback_cidr_allows_localhost() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("127.0.0.0/8");
        InternalNetworkPolicy policy = InternalNetworkPolicy.from(props);

        assertThat(policy.isAllowed(URI.create("http://127.0.0.1:8080"))).isTrue();
    }

    @Test
    void private_cidr_rejects_loopback() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("10.0.0.0/8");
        InternalNetworkPolicy policy = InternalNetworkPolicy.from(props);

        assertThat(policy.isAllowed(URI.create("http://127.0.0.1:8080"))).isFalse();
        assertThat(policy.isAllowed(URI.create("http://10.1.2.3:8080"))).isTrue();
    }

    @Test
    void missing_host_rejected_when_restricted() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("10.0.0.0/8");
        InternalNetworkPolicy policy = InternalNetworkPolicy.from(props);

        assertThat(policy.isAllowed(URI.create("http:///path"))).isFalse();
    }

    @Test
    void resolve_pins_localhost_to_loopback_literal() throws Exception {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("127.0.0.0/8");
        InternalNetworkPolicy policy = InternalNetworkPolicy.from(props);

        Optional<InternalNetworkPolicy.PinnedTarget> pinned =
                policy.resolve(URI.create("http://localhost:8090"));

        assertThat(pinned).isPresent();
        assertThat(pinned.get().originalHost()).isEqualTo("localhost");
        assertThat(pinned.get().address().isLoopbackAddress()).isTrue();
        assertThat(pinned.get().hostHeaderValue()).isEqualTo("localhost:8090");
        assertThat(pinned.get().requestUri("/.well-known/agent-card.json").getHost())
                .isEqualTo(InetAddress.getByName(pinned.get().address().getHostAddress()).getHostAddress());
    }

    @Test
    void invalid_cidr_throws() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("not-a-cidr");

        assertThatThrownBy(() -> InternalNetworkPolicy.from(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid CIDR");
    }

    @Test
    void invalid_cidr_prefix_throws() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("10.0.0.0/40");

        assertThatThrownBy(() -> InternalNetworkPolicy.from(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid CIDR prefix length");
    }

    @Test
    void negative_cidr_prefix_throws() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("10.0.0.0/-1");

        assertThatThrownBy(() -> InternalNetworkPolicy.from(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid CIDR prefix length");
    }
}
