/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.security.AuthorizationDeniedException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AuditSecurityDecisionAspect 的单元测试：allow/deny 均落决策记录，deny 原样抛出不改变授权行为。
 */
class AuditSecurityDecisionAspectTest {
    private final AuditSecurityDecisionAspect aspect = new AuditSecurityDecisionAspect();

    @AfterEach
    void cleanup() {
        AuditEventBridge.clear();
    }

    @Test
    void pointcutMatchesAuthorizedResourceMethod() throws Exception {
        // 防 FQN 漂移回归：切点表达式必须匹配打了真实注解的方法（FQN 写错时本测试即红）
        org.springframework.aop.aspectj.AspectJExpressionPointcut pointcut =
                new org.springframework.aop.aspectj.AspectJExpressionPointcut();
        pointcut.setExpression("@annotation(com.openjiuwen.service.spec.security.AuthorizedResource)");
        java.lang.reflect.Method method = DummyResource.class.getDeclaredMethod("serve");
        assertThat(pointcut.matches(method, DummyResource.class)).isTrue();
    }

    /** 打了真实 AuthorizedResource 注解的 dummy（切点匹配测试用）。 */
    static class DummyResource {
        @com.openjiuwen.service.spec.security.AuthorizedResource(resource = "query", action = "execute")
        String serve() {
            return "ok";
        }
    }

    @Test
    void allowIsRecorded() throws Throwable {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        AuditEventBridge.register(new StubCollector(captured));
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn("ok");
        assertThat(aspect.observe(joinPoint)).isEqualTo("ok");
        assertThat(captured.get()).containsEntry("decision", "allow");
    }

    @Test
    void denyIsRecordedAndRethrown() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        AuditEventBridge.register(new StubCollector(captured));
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        try {
            when(joinPoint.proceed()).thenThrow(
                    new AuthorizationDeniedException("no permission", "wallet", "read"));
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(() -> aspect.observe(joinPoint))
                .isInstanceOf(AuthorizationDeniedException.class);
        assertThat(captured.get()).containsEntry("decision", "deny")
                .containsEntry("reason", "no permission")
                .containsEntry("resource", "wallet");
    }

    /** 捕获 decision 字段的最小 collector stub。 */
    private static final class StubCollector extends AuditEventCollector {
        private final AtomicReference<Map<String, Object>> captured;

        StubCollector(AtomicReference<Map<String, Object>> captured) {
            super(null, null, null);
            this.captured = captured;
        }

        @Override
        public void recordDecision(String tenantId, String conversationId, String type,
                                   Map<String, Object> fields) {
            captured.set(fields);
        }
    }
}
