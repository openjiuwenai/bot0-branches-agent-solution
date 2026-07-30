/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.runtime.persistence.jdbc.JdbcForwardingInbox;
import com.openjiuwen.bus.forwarding.runtime.persistence.jdbc.JdbcForwardingOutbox;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerClientProperties;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.AgentBusBrokerClientBaseAutoConfiguration;
import com.openjiuwen.bus.forwarding.spi.ForwardingInboxPort;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;

import javax.sql.DataSource;

/**
 * TDD for the Task 2 broker-client-base / JDBC-reliability split
 * (role-based-assembly). Pins the conditional wiring so a caller-role app with no
 * {@code DataSource} still gets the broker base WITHOUT the JDBC outbox/inbox, while
 * the relay/gateway (with a {@code DataSource}) still gets the JDBC layer.
 *
 * <ul>
 *   <li><b>Test A</b> — base autoconfig ALONE (no {@code DataSource} bean, reliability
 *       autoconfig not registered): the broker base ({@link BrokerClientProperties} +
 *       {@link AgentBusBrokerProperties} + {@code defaultProducer}) is present, but the
 *       JDBC reliability beans ({@link JdbcForwardingOutbox} / {@link JdbcForwardingInbox})
 *       are ABSENT — a caller-role app with no {@code DataSource} still gets the broker
 *       base so its role autoconfigs can build their ports, WITHOUT the outbox/inbox;</li>
 *   <li><b>Test B</b> — base + reliability autoconfigs + a {@code DataSource} bean: the
 *       JDBC outbox/inbox beans are now PRESENT (the reliability layer activates when a
 *       {@code DataSource} is on the classpath and a {@code DataSource} bean exists).</li>
 * </ul>
 *
 * <p><b>Test harness notes.</b> Uses Spring Boot's {@link ApplicationContextRunner} +
 * {@link AutoConfigurations} (no full {@code @SpringBootTest}). The {@code defaultProducer}
 * bean starts a real {@link DefaultMQProducer} against an unreachable nameserver
 * ({@code 127.0.0.1:1}); RocketMQ's {@code start()} is non-blocking — it kicks off
 * background netty/scheduled services without contacting the nameserver, so the bean
 * is created and later shut down by {@code destroyMethod="shutdown"} on context close.
 * Test B's {@link DataSource} is a stub ({@link AbstractDataSource}) that throws on
 * {@code getConnection} — the outbox/inbox constructors store the datasource and never
 * touch the DB at construction, so bean creation succeeds without a real database.
 *
 * @since 0.1.0
 */
class AgentBusReliabilityAutoConfigurationTest {
    /** Required broker properties (nameserver/namespace/producer-group are validated non-blank). */
    private static final String[] BASE_PROPS = {
            "agent-bus.nameserver=127.0.0.1:1",
            "agent-bus.namespace=test-ns",
            "agent-bus.producer-group=test-producer-group"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    /**
     * Test A — base autoconfig only, no DataSource bean: JDBC reliability beans absent,
     * broker base + shared producer present.
     */
    @Test
    void baseOnlyWithoutDataSourceHasNoJdbcReliabilityBeans() {
        runner.withConfiguration(AutoConfigurations.of(AgentBusBrokerClientBaseAutoConfiguration.class))
                .withPropertyValues(BASE_PROPS)
                .run(context -> {
                    // broker base present
                    assertThat(context).hasSingleBean(BrokerClientProperties.class);
                    assertThat(context).hasSingleBean(AgentBusBrokerProperties.class);
                    assertThat(context).hasSingleBean(DefaultMQProducer.class);
                    // JDBC reliability absent (reliability autoconfig not registered, no DataSource bean)
                    assertThat(context).doesNotHaveBean(JdbcForwardingOutbox.class);
                    assertThat(context).doesNotHaveBean(JdbcForwardingInbox.class);
                    assertThat(context).doesNotHaveBean(ForwardingInboxPort.class);
                });
    }

    /**
     * Test B — base + reliability autoconfigs + a DataSource bean: JDBC outbox/inbox present.
     */
    @Test
    void reliabilityActivatesAndCreatesJdbcBeansWhenDataSourcePresent() {
        runner.withConfiguration(AutoConfigurations.of(
                        AgentBusBrokerClientBaseAutoConfiguration.class,
                        AgentBusReliabilityAutoConfiguration.class))
                .withPropertyValues(BASE_PROPS)
                .withBean(DataSource.class, () -> new AbstractDataSource() {
                    @Override
                    public Connection getConnection() {
                        throw new UnsupportedOperationException("test stub — no real DB");
                    }

                    @Override
                    public Connection getConnection(String username, String password) {
                        throw new UnsupportedOperationException("test stub — no real DB");
                    }
                })
                .run(context -> {
                    // JDBC reliability present (DataSource class on classpath + DataSource bean)
                    assertThat(context).hasSingleBean(JdbcForwardingOutbox.class);
                    assertThat(context).hasSingleBean(JdbcForwardingInbox.class);
                    assertThat(context).hasSingleBean(ForwardingInboxPort.class);
                    // broker base still present
                    assertThat(context).hasSingleBean(BrokerClientProperties.class);
                    assertThat(context).hasSingleBean(DefaultMQProducer.class);
                });
    }
}
