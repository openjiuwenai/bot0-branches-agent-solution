/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

/**
 * TrajectoryLinkAutoConfiguration 的装配条件测试：默认关闭零 Bean；enabled=true 但
 * Redis 缺失时 WARN 保持关闭（V-8）；enabled=true 且 Redis 可用时正常装配。
 */
class TrajectoryLinkAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TrajectoryLinkAutoConfiguration.class));

    @Test
    void noBeansWhenDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(TraceContextCarrier.class);
            assertThat(context).doesNotHaveBean(RedisTrajectoryStore.class);
            assertThat(context).doesNotHaveBean(AsyncTrajectoryWriter.class);
        });
    }

    @Test
    void carrierOnlyWhenEnabledWithoutRedis() {
        runner.withPropertyValues("openjiuwen.service.trajectory.link.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(TraceContextCarrier.class);
            // V-8：Redis 缺失时 RedisAssembly 整体不装配（@ConditionalOnBean）
            assertThat(context).doesNotHaveBean(RedisTrajectoryStore.class);
            assertThat(context).doesNotHaveBean(AsyncTrajectoryWriter.class);
            assertThat(context).doesNotHaveBean(
                    org.springframework.boot.web.servlet.FilterRegistrationBean.class);
        });
    }

    @Test
    void fullAssemblyWhenEnabledWithRedis() {
        runner.withPropertyValues("openjiuwen.service.trajectory.link.enabled=true")
                .withBean(RuntimeRedisClient.class, () -> new StubRedisClient())
                .run(context -> {
                    assertThat(context).hasSingleBean(TraceContextCarrier.class);
                    assertThat(context).hasSingleBean(RedisTrajectoryStore.class);
                    assertThat(context).hasSingleBean(AsyncTrajectoryWriter.class);
                });
    }

    /** 最小 RuntimeRedisClient stub（装配测试不触碰命令）。 */
    private static final class StubRedisClient implements RuntimeRedisClient {
        @Override
        public Object get(String key) {
            return null;
        }

        @Override
        public byte[] get(byte[] key) {
            return new byte[0];
        }

        @Override
        public String set(String key, String value) {
            return "OK";
        }

        @Override
        public String set(String key, byte[] value) {
            return "OK";
        }

        @Override
        public String set(byte[] key, byte[] value) {
            return "OK";
        }

        @Override
        public String setex(String key, long seconds, String value) {
            return "OK";
        }

        @Override
        public String setex(byte[] key, long seconds, byte[] value) {
            return "OK";
        }

        @Override
        public long setnx(String key, String value) {
            return 1L;
        }

        @Override
        public long setnx(byte[] key, byte[] value) {
            return 1L;
        }

        @Override
        public long del(String... keys) {
            return 0;
        }

        @Override
        public long del(byte[]... keys) {
            return 0;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public long expire(String key, long seconds) {
            return 0;
        }

        @Override
        public long expire(byte[] key, long seconds) {
            return 0;
        }

        @Override
        public List<Object> mget(String... keys) {
            return List.of();
        }

        @Override
        public List<String> scanIter(String pattern) {
            return List.of();
        }
    }
}
