/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.common;

import com.openjiuwen.bus.forwarding.runtime.persistence.jdbc.JdbcForwardingInbox;
import com.openjiuwen.bus.forwarding.runtime.persistence.jdbc.JdbcForwardingOutbox;
import com.openjiuwen.bus.forwarding.spi.ForwardingInboxPort;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * JDBC reliability auto-configuration — the durable outbox/inbox layer, created ONLY
 * when a {@link DataSource} is on the classpath. This is the JDBC half of the
 * role-based-assembly base/reliability split: a caller-role application with no
 * {@code DataSource} still gets the broker base from
 * {@link com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.AgentBusBrokerClientBaseAutoConfiguration}
 * WITHOUT the outbox/inbox (or Flyway) being created.
 *
 * <p>Owns the JDBC beans previously provided by the (now split) shared
 * {@code AgentBusInfrastructureConfiguration}:
 * <ul>
 *   <li>{@link JdbcForwardingOutbox} — durable outbox, implements BOTH
 *       {@code ForwardingOutboxPort} AND {@code ForwardingOutboxClaimPort}; shared by
 *       the gateway (request dispatch) and the event-bus relay (re-publish);</li>
 *   <li>{@link ForwardingInboxPort} — durable inbox for event-bus relay governance
 *       dedup (the gateway does not use it, but the bean is harmless when idle).</li>
 * </ul>
 *
 * <p><b>Flyway stays Spring Boot's concern.</b> There is no explicit Flyway bean here
 * — the {@code V1} schema migration at {@code db/migration} is applied by Spring
 * Boot's own {@code FlywayAutoConfiguration}, which is itself conditional on a
 * {@code DataSource}, so it activates together with this autoconfig. The relay process
 * form owns schema migration and disables Flyway on caller roles (no
 * {@code DataSource} {@code ⇒} no migration, no JDBC beans).
 *
 * <p><b>Conditional.</b>
 * <ul>
 *   <li>{@link ConditionalOnClass @ConditionalOnClass(DataSource.class)} gates on the
 *       JDBC dependency being present at all — when a downstream app omits
 *       {@code spring-boot-starter-jdbc}, this autoconfig is skipped and the broker
 *       base loads alone (Spring evaluates the condition via ASM bytecode inspection
 *       before loading this class, so the {@code DataSource} method-parameter
 *       reference is never resolved when the class is absent);</li>
 *   <li>{@link ConditionalOnProperty @ConditionalOnProperty("agent-bus.reliability.enabled",
 *       matchIfMissing=true)} is a kill-switch — the relay/gateway still gets JDBC by
 *       default; set {@code agent-bus.reliability.enabled=false} to suppress it.</li>
 * </ul>
 * The outbox/inbox bean methods take a {@link DataSource} parameter, so this autoconfig
 * is paired with a configured {@code DataSource} bean (the relay/gateway both configure
 * one); a runtime with JDBC on the classpath but no {@code DataSource} bean is a
 * misconfiguration that fails fast at startup — by design.
 *
 * <p><b>Package: {@code forwarding.common}</b> — the Spring-licensed shared-config
 * plane (mirrors the former {@code AgentBusInfrastructureConfiguration}); no RocketMQ
 * types are imported here (ArchUnit confines those to
 * {@code transport.broker.rocketmq}).
 *
 * <p>Registered as an {@link AutoConfiguration @AutoConfiguration} so a later task
 * lists it in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <p>Authority: arch-driven role-based-assembly (broker-client base / JDBC-reliability
 * split); ADR-0163 (forwarding-reorg layering).
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass(DataSource.class)
@ConditionalOnProperty(prefix = "agent-bus.reliability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentBusReliabilityAutoConfiguration {
    /**
     * Durable outbox — implements {@code ForwardingOutboxPort} AND
     * {@code ForwardingOutboxClaimPort}; typed for injection as either SPI port. Shared
     * by the gateway (request dispatch) and the event-bus relay (re-publish).
     *
     * @param dataSource the JDBC datasource for the outbox tables
     * @return a durable JDBC outbox implementing both SPI ports
     */
    @Bean
    JdbcForwardingOutbox forwardingOutbox(DataSource dataSource) {
        return new JdbcForwardingOutbox(dataSource);
    }

    /**
     * Durable inbox — event-bus relay governance dedup. Declared on this autoconfig so
     * the event-bus process form injects it without owning the bean (the gateway does
     * not use it, but the bean is harmless when idle).
     *
     * @param dataSource the JDBC datasource for the inbox table
     * @return a durable JDBC inbox for governance dedup
     */
    @Bean
    ForwardingInboxPort forwardingInbox(DataSource dataSource) {
        return new JdbcForwardingInbox(dataSource);
    }
}
