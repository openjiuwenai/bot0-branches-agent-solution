/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit.AuditEventCollector;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.util.function.Supplier;

/**
 * 执行树装饰注册器：BeanPostProcessor，把 {@link RunTreeTaskStoreDecorator} 包到任意
 * {@link TaskStore} Bean 上（{@code WriteThrottlingTaskStore} 是 Bean 定义本体，BPP 装饰器
 * 天然在其外侧、能看到全部 save）。依赖经 Supplier 惰性解析——BPP 实例化早于普通 Bean，
 * 与 {@code TaskStoreProjectionPostProcessor} 同一先例。显式 Ordered 保证与投影装饰器的
 * 相对顺序确定性（谁在最外层只影响观察者顺序，记录语义不受影响）。
 *
 * @since 2026-08-26
 */
public class RunTreeRegistrar implements BeanPostProcessor, Ordered {
    private final Supplier<TraceContextCarrier> carrier;
    private final Supplier<RedisTrajectoryStore> store;
    private final Supplier<AsyncTrajectoryWriter> writer;
    private final Supplier<AuditEventCollector> audit;

    /**
     * Creates the registrar.
     *
     * @param carrierProvider carrier provider
     * @param storeProvider   store provider
     * @param writerProvider  writer provider
     */
    public RunTreeRegistrar(ObjectProvider<TraceContextCarrier> carrierProvider,
                            ObjectProvider<RedisTrajectoryStore> storeProvider,
                            ObjectProvider<AsyncTrajectoryWriter> writerProvider,
                            ObjectProvider<AuditEventCollector> auditProvider) {
        this.carrier = carrierProvider::getIfAvailable;
        this.store = storeProvider::getIfAvailable;
        this.writer = writerProvider::getIfAvailable;
        this.audit = auditProvider::getIfAvailable;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof TaskStore taskStoreBean) || bean instanceof RunTreeTaskStoreDecorator) {
            return bean;
        }
        TraceContextCarrier resolvedCarrier = carrier.get();
        RedisTrajectoryStore resolvedStore = store.get();
        AsyncTrajectoryWriter resolvedWriter = writer.get();
        if (resolvedCarrier == null || resolvedStore == null || resolvedWriter == null) {
            return bean;
        }
        return new RunTreeTaskStoreDecorator(taskStoreBean, resolvedCarrier, resolvedStore, resolvedWriter,
                audit.get());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 100;
    }
}
