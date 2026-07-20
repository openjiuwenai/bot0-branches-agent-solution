package com.openjiuwen.rdc.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void invalid_cidr_throws() {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.getTargetCidrs().add("not-a-cidr");

        assertThatThrownBy(() -> InternalNetworkPolicy.from(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid CIDR");
    }
}
