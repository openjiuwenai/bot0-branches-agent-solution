/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.stream;

import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;

import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;

/**
 * Validates FEAT-017 stream references before the standard A2A Task subscription is opened.
 *
 * @since 2026-07-26
 */
@Aspect
public final class StreamReferenceSubscriptionAspect {
    static final String STREAM_REFERENCE_HEADER = "X-OpenJiuwen-Stream-Ref";

    private final StreamReferenceService references;
    private final InMemoryBusTaskAdmissionStore admissions;
    private final String runtimeTenantId;
    private final Clock clock;

    /**
     * Creates the stream-reference subscription aspect.
     *
     * @param references
     *            the stream reference service
     * @param admissions
     *            the Bus Task admission store
     * @param runtimeTenantId
     *            the agent-bus tenant scope of this runtime
     * @param clock
     *            the validation clock
     */
    public StreamReferenceSubscriptionAspect(StreamReferenceService references, InMemoryBusTaskAdmissionStore admissions,
            String runtimeTenantId, Clock clock) {
        this.references = references;
        this.admissions = admissions;
        this.runtimeTenantId = runtimeTenantId;
        this.clock = clock;
    }

    /**
     * Validates the stream reference carried by the current HTTP request.
     *
     * @param invocation
     *            the intercepted subscription invocation
     * @param params
     *            the standard A2A Task subscription parameters
     * @return the original subscription result
     * @throws Throwable
     *             when validation or the original invocation fails
     */
    @Around("execution(* org.a2aproject.sdk.server.requesthandlers.RequestHandler+"
            + ".onSubscribeToTask(..)) && args(params,..)")
    public Object validate(ProceedingJoinPoint invocation, TaskIdParams params) throws Throwable {
        boolean busTask = admissions.findByTaskId(runtimeTenantId, params.id()).isPresent();
        ServletRequestAttributes request = currentRequest();
        if (request == null) {
            if (busTask) {
                throw new TaskNotFoundError();
            }
            return invocation.proceed();
        }
        String streamReference = request.getRequest().getHeader(STREAM_REFERENCE_HEADER);
        if (streamReference == null || streamReference.isBlank()) {
            if (busTask) {
                throw new TaskNotFoundError();
            }
            return invocation.proceed();
        }
        String tenantId = params.tenant() == null || params.tenant().isBlank()
                ? runtimeTenantId
                : params.tenant();
        try {
            references.validate(streamReference, tenantId, params.id(), clock.instant().getEpochSecond());
        } catch (IllegalArgumentException ignored) {
            throw new TaskNotFoundError();
        }
        return invocation.proceed();
    }

    private static ServletRequestAttributes currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes;
        }
        return null;
    }
}
