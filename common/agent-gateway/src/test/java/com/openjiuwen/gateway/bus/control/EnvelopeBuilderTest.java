package com.openjiuwen.gateway.bus.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;

class EnvelopeBuilderTest {
    private final EnvelopeBuilder builder = new EnvelopeBuilder();

    @Test
    void buildsCreateEnvelopeWithAllRequiredFields() {
        ForwardingEnvelope env = builder.buildEnvelope(
                "T1", "trace-1", "idem-1", "handle-1", "svc-target", "svc-gw", "REF-1", 99999L);
        assertThat(env.eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
        assertThat(env.tenantId()).isEqualTo("T1");
        assertThat(env.routeHandle().value()).isEqualTo("handle-1");
        assertThat(env.routeHandle().tenantScope()).isEqualTo("T1");
        assertThat(env.correlationId()).isNotBlank();
        assertThat(env.idempotencyKey()).isEqualTo("idem-1");
        assertThat(env.sourceServiceId()).isEqualTo("svc-gw");
        assertThat(env.targetServiceId()).isEqualTo("svc-target");
        assertThat(env.payloadPolicy()).isEqualTo(ForwardingEnvelope.PayloadPolicy.DATA_BEARING);
        assertThat(env.payloadRef()).isEqualTo("REF-1");
    }

    @Test
    void tenantConsistency() {
        // Builder always sets routeHandle.tenantScope = tenantId (consistency by construction).
        ForwardingEnvelope env = builder.buildEnvelope(
                "T1", "trace-1", "idem-1", "handle-1", "svc-target", "svc-gw", "REF-1", 99999L);
        assertThat(env.routeHandle().tenantScope()).isEqualTo(env.tenantId());
    }

    @Test
    void correlationIdSelfGenerated() {
        ForwardingEnvelope env1 = builder.buildEnvelope("T1", "t1", "k1", "h1", "svc", "gw", "REF", 1L);
        ForwardingEnvelope env2 = builder.buildEnvelope("T1", "t2", "k2", "h2", "svc", "gw", "REF", 2L);
        assertThat(env1.correlationId()).isNotEqualTo(env2.correlationId());
        assertThat(env1.correlationId()).startsWith("gw-correlation-");
    }

    @Test
    void resumeEnvelopeCarriesTaskIdInPayloadRef() {
        ForwardingEnvelope env = builder.buildEnvelope(
                "T1", "trace-1", "idem-1", "handle-1", "svc-target", "svc-gw", "REF-task7", 99999L);
        assertThat(env.eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
        assertThat(env.payloadRef()).isEqualTo("REF-task7");
    }

    @Test
    void payloadPolicyControlOnlyWhenNoBody() {
        ForwardingEnvelope env = builder.buildEnvelope(
                "T1", "trace-1", "idem-1", "handle-1", "svc-target", "svc-gw", null, 99999L);
        assertThat(env.payloadPolicy()).isEqualTo(ForwardingEnvelope.PayloadPolicy.CONTROL_ONLY);
        assertThat(env.payloadRef()).isNull();
    }
}
