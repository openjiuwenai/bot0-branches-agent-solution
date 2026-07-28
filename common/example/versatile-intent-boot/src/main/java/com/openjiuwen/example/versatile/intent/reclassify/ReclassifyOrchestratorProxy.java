/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds a transparent JDK proxy around the runtime's
 * {@code A2AEnabledServeOrchestrator} so the reclassify decorator can
 * intercept {@link ServeOrchestrator} methods <em>without</em> dropping the
 * bean's other interfaces.
 *
 * <p>The runtime's {@code A2AEnabledServeOrchestrator} implements both
 * {@link ServeOrchestrator} and {@code A2aPushNotificationCallbackHandler}.
 * Replacing that bean with a bare {@link ReclassifyServeOrchestrator} (which
 * only implements {@code ServeOrchestrator}) used to break every injection
 * point that depended on the callback-handler interface, surfacing as a
 * {@code BeanNotOfRequiredTypeException} at startup — see issue #50.
 *
 * <p>The proxy returned here implements <strong>every</strong> interface of
 * the wrapped bean: {@link ServeOrchestrator} calls are routed through the
 * reclassify decorator, while every other call (e.g.
 * {@code A2aPushNotificationCallbackHandler.onAccepted}) falls through to the
 * wrapped bean unchanged. Because the interface set is derived from the
 * wrapped bean at runtime, the decorator keeps working even if the runtime
 * later adds more interfaces to {@code A2AEnabledServeOrchestrator}.
 *
 * @since 2026-07-28
 */
final class ReclassifyOrchestratorProxy {
    private ReclassifyOrchestratorProxy() {
    }

    /**
     * Wraps {@code wrapped} in a proxy that exposes every interface it
     * implements.
     *
     * @param wrapped the original orchestrator bean; must implement
     *                {@link ServeOrchestrator} plus any secondary interfaces
     * @param decorator the reclassify decorator that {@link ServeOrchestrator}
     *                  calls are routed through
     * @return a proxy implementing every interface of {@code wrapped}
     */
    static Object wrap(Object wrapped, ReclassifyServeOrchestrator decorator) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        collectInterfaces(wrapped.getClass(), interfaces);
        InvocationHandler handler = new ReclassifyInvocationHandler(wrapped, decorator);
        ClassLoader classLoader = wrapped.getClass().getClassLoader();
        if (classLoader == null) {
            classLoader = ReclassifyOrchestratorProxy.class.getClassLoader();
        }
        return Proxy.newProxyInstance(classLoader, interfaces.toArray(new Class<?>[0]), handler);
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> out) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Class<?> iface : current.getInterfaces()) {
                if (out.add(iface)) {
                    collectInterfaces(iface, out);
                }
            }
            current = current.getSuperclass();
        }
    }

    /**
     * Routes {@link ServeOrchestrator}-declared methods to the reclassify
     * decorator and delegates every other call to the wrapped bean unchanged.
     */
    private static final class ReclassifyInvocationHandler implements InvocationHandler {
        private final Object wrapped;
        private final ReclassifyServeOrchestrator decorator;

        ReclassifyInvocationHandler(Object wrapped, ReclassifyServeOrchestrator decorator) {
            this.wrapped = wrapped;
            this.decorator = decorator;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (ServeOrchestrator.class.equals(method.getDeclaringClass())) {
                return method.invoke(decorator, args);
            }
            return method.invoke(wrapped, args);
        }
    }
}
