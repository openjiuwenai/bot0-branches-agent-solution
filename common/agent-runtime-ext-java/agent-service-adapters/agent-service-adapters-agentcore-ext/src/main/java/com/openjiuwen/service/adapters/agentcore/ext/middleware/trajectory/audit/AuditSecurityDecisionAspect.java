/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit;

import com.openjiuwen.service.app.security.AuthorizationDeniedException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 安全决策审计观察切面：以环绕通知观察主仓
 * {@code ResourceAuthorizationAspect.authorize} 切点，allow/deny 均落审计决策记录
 * （decision、resource、reason、tenant、traceId）。纯观察语义，不改变授权行为。
 *
 * <p>租户取自当前请求的 {@code x-tenant-id} header；conversationId 在切面上下文中不可
 * 直接获得，缺失时以 "unknown" 落键（在场证据优先于键的完备性）。
 *
 * @since 2026-08-26
 */
@Aspect
@Order(0)
public class AuditSecurityDecisionAspect {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuditSecurityDecisionAspect.class);
    private static final String TENANT_HEADER = "x-tenant-id";
    private static final String UNKNOWN = "unknown";

    /**
     * Observes the authorization decision and records it.
     *
     * @param joinPoint proceeding join point
     * @return the controller method result
     * @throws Throwable propagated from the target method
     */
    @Around("@annotation(com.openjiuwen.service.app.security.AuthorizedResource)")
    public Object observe(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            Object result = joinPoint.proceed();
            record("allow", null, null, null);
            return result;
        } catch (AuthorizationDeniedException e) {
            record("deny", e.getReason(), e.getResource(), e.getAction());
            throw e;
        }
    }

    private void record(String decision, String reason, String resource, String action) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("decision", decision);
        if (reason != null) {
            fields.put("reason", reason);
        }
        if (resource != null) {
            fields.put("resource", resource);
        }
        if (action != null) {
            fields.put("action", action);
        }
        AuditEventBridge.recordDecision(tenantId().orElse(UNKNOWN), UNKNOWN, "security", fields);
    }

    private static Optional<String> tenantId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String tenant = attrs.getRequest().getHeader(TENANT_HEADER);
            if (tenant != null && !tenant.isBlank()) {
                return Optional.of(tenant);
            }
        }
        return Optional.empty();
    }
}
