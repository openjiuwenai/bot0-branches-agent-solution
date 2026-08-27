/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * RunTreeRegistrar 的单元测试：包装 TaskStore、不重复包装、依赖缺失时不包装。
 */
class RunTreeRegistrarTest {
    private final TaskStore taskStore = mock(TaskStore.class);

    @Test
    void wrapsTaskStoreBean() {
        RunTreeRegistrar registrar = new RunTreeRegistrar(
                providerOf(TraceContextCarrier.create(86400L)),
                providerOf(mock(RedisTrajectoryStore.class)),
                providerOf(mock(AsyncTrajectoryWriter.class)),
                providerOf(null));
        Object wrapped = registrar.postProcessAfterInitialization(taskStore, "taskStore");
        assertThat(wrapped).isInstanceOf(RunTreeTaskStoreDecorator.class);
    }

    @Test
    void doesNotDoubleWrap() {
        RunTreeRegistrar registrar = new RunTreeRegistrar(
                providerOf(TraceContextCarrier.create(86400L)),
                providerOf(mock(RedisTrajectoryStore.class)),
                providerOf(mock(AsyncTrajectoryWriter.class)),
                providerOf(null));
        RunTreeTaskStoreDecorator already = new RunTreeTaskStoreDecorator(taskStore,
                TraceContextCarrier.create(86400L), mock(RedisTrajectoryStore.class),
                mock(AsyncTrajectoryWriter.class), null);
        assertThat(registrar.postProcessAfterInitialization(already, "taskStore")).isSameAs(already);
    }

    @Test
    void skipsWhenDependenciesMissing() {
        RunTreeRegistrar registrar = new RunTreeRegistrar(
                providerOf(null), providerOf(null), providerOf(null), providerOf(null));
        assertThat(registrar.postProcessAfterInitialization(taskStore, "taskStore")).isSameAs(taskStore);
    }

    @Test
    void ignoresNonTaskStoreBeans() {
        RunTreeRegistrar registrar = new RunTreeRegistrar(
                providerOf(null), providerOf(null), providerOf(null), providerOf(null));
        Object bean = new Object();
        assertThat(registrar.postProcessAfterInitialization(bean, "other")).isSameAs(bean);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T instance) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(instance);
        return provider;
    }
}
