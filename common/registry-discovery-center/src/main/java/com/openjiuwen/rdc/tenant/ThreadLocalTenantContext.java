package com.openjiuwen.rdc.tenant;

import com.openjiuwen.rdc.tenant.TenantContext;

import java.util.Objects;

/**
 * ThreadLocal-backed implementation of {@link TenantContext} (HD3-003).
 *
 * <p>ESC-2 design pivot (ADR-0160 decision 6): the deprecated
 * {@code TenantFilter} request-header approach was dropped — HTTP-entry
 * call sites pass {@code tenantId} explicitly and leave the context unbound.
 * Background scheduling paths (e.g. the health-probe scheduler) bind tenant
 * scope explicitly via {@link #bindForScope(String, Runnable)} /
 * {@link #bindForScope(String, java.util.function.Supplier)} for the
 * duration of a unit of work, then clear on completion so no cross-call
 * leak occurs (thread pools reuse threads).
 *
 * <p>Authority: ADR-0160 + HD3-003 + ESC-2 design pivot. Pure Java — no
 * Spring / Servlet / JDBC imports. Non-HTTP-entry callers (schedulers,
 * async handlers) can use it without pulling Servlet onto the classpath.
 *
 * <p>Phase-2 swap path: a reactor-context-backed implementation may replace
 * this class without touching the {@link TenantContext} port or any caller.
 */
public final class ThreadLocalTenantContext implements TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * Bind {@code tenantId} to the current thread for the duration of
     * {@code work}, then clear the binding regardless of outcome. Use this
     * for background scheduling paths that have no HTTP request scope.
     *
     * @param tenantId tenant to bind; must not be {@code null} or blank
     * @param work     work to execute with the tenant bound
     */
    public static void bindForScope(String tenantId, Runnable work) {
        Objects.requireNonNull(work, "work is required");
        bindForScope(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Bind {@code tenantId} to the current thread for the duration of
     * {@code work}, then clear the binding regardless of outcome, returning
     * the supplier's result. Use this for background scheduling paths that
     * need to return a value.
     */
    public static <T> T bindForScope(String tenantId, java.util.function.Supplier<T> work) {
        String prior = CURRENT.get();
        try {
            set(tenantId);
            return work.get();
        } finally {
            restore(prior);
        }
    }

    /**
     * Set the current thread's tenant. Called by {@link #bindForScope};
     * package-private so external callers cannot bypass the
     * {@code bindForScope} lifecycle.
     */
    static void set(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be null or blank");
        }
        CURRENT.set(tenantId);
    }

    /**
     * Clear the current thread's tenant. Called by {@link #bindForScope}'s
     * finally block; package-private for the same reason as {@link #set}.
     */
    static void clear() {
        CURRENT.remove();
    }

    private static void restore(String prior) {
        if (prior == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(prior);
        }
    }

    @Override
    public String current() {
        return CURRENT.get();
    }
}
