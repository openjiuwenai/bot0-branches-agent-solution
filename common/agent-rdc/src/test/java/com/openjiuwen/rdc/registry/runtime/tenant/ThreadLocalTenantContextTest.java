/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.spi.registry.TenantContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link ThreadLocalTenantContext} (HD3-003 tenant isolation,
 * ESC-2 design pivot — replaces the dropped {@code TenantFilter} approach).
 *
 * <p>Authority: ADR-0160 decision 6 + ESC-2 design pivot. The original
 * Stage 3 plan called for a Servlet {@code Filter} that populated the
 * ThreadLocal from {@code X-Tenant-Id}; ESC-2 dropped that filter (no
 * spring-web / Servlet API on agent-bus's compile classpath). The
 * {@code ThreadLocalTenantContext} stays — it serves two purposes:
 * <ul>
 *   <li>Background scheduling paths (e.g. {@code MvpHealthProbeScheduler})
 *       call {@link ThreadLocalTenantContext#bindForScope(String, Runnable)}
 *       to scope tenant state on the worker thread before issuing JDBC calls
 *       that set {@code app.tenant_id} (Stage 24 RLS wiring).</li>
 *   <li>The {@link TenantContext} SPI port is still implemented so phase-2
 *       reactor-context-backed implementations can swap in without touching
 *       callers.</li>
 * </ul>
 *
 * <p>Covers:
 * <ul>
 *   <li><b>RB-TC1</b> — {@code bindForScope} sets the tenant for the scope
 *       of the work, then clears it (no leak).</li>
 *   <li><b>RB-TC2</b> — {@code bindForScope} clears the tenant even when the
 *       work throws.</li>
 *   <li><b>RB-TC3</b> — nested {@code bindForScope} restores the prior
 *       tenant on exit (not just clears).</li>
 *   <li><b>RB-TC4</b> — {@code bindForScope} rejects null / blank tenant.</li>
 *   <li><b>RB-TC5</b> — ThreadLocal isolation: tenant bound on thread A is
 *       not visible on thread B.</li>
 *   <li><b>RB-TC6</b> — {@link TenantContext#current()} returns null when
 *       no tenant is bound (unbound scope).</li>
 * </ul>
 */
class ThreadLocalTenantContextTest {
    private final ThreadLocalTenantContext context = new ThreadLocalTenantContext();

    @AfterEach
    void tearDown() {
        // Defensive: clear any leaked binding.
        ThreadLocalTenantContext.clear();
    }

    // ---- RB-TC1: bindForScope sets then clears ---------------------------

    @Test
    void bind_for_scope_sets_tenant_then_clears() {
        assertThat(context.current()).isNull();

        ThreadLocalTenantContext.bindForScope("tenant-A", () -> {
            assertThat(context.current()).isEqualTo("tenant-A");
        });

        assertThat(context.current()).isNull();
    }

    @Test
    void bind_for_scope_supplier_returns_value_and_clears() {
        assertThat(context.current()).isNull();

        String result = ThreadLocalTenantContext.bindForScope("tenant-A", () -> {
            assertThat(context.current()).isEqualTo("tenant-A");
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(context.current()).isNull();
    }

    // ---- RB-TC2: bindForScope clears even on exception -------------------

    @Test
    void bind_for_scope_clears_even_when_work_throws() {
        assertThat(context.current()).isNull();

        assertThatThrownBy(() ->
                ThreadLocalTenantContext.bindForScope("tenant-A", () -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(context.current()).isNull();
    }

    @Test
    void bind_for_scope_supplier_clears_even_when_work_throws() {
        assertThatThrownBy(() ->
                ThreadLocalTenantContext.bindForScope("tenant-A", () -> {
                    throw new IllegalStateException("supplier boom");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(context.current()).isNull();
    }

    // ---- RB-TC3: nested bindForScope restores prior tenant ---------------

    @Test
    void nested_bind_for_scope_restores_outer_tenant() {
        ThreadLocalTenantContext.bindForScope("tenant-outer", () -> {
            assertThat(context.current()).isEqualTo("tenant-outer");

            ThreadLocalTenantContext.bindForScope("tenant-inner", () -> {
                assertThat(context.current()).isEqualTo("tenant-inner");
            });

            // Outer tenant restored, not cleared.
            assertThat(context.current()).isEqualTo("tenant-outer");
        });

        // Now fully cleared.
        assertThat(context.current()).isNull();
    }

    @Test
    void nested_bind_for_scope_restores_outer_tenant_even_if_inner_throws() {
        ThreadLocalTenantContext.bindForScope("tenant-outer", () -> {
            assertThatThrownBy(() ->
                    ThreadLocalTenantContext.bindForScope("tenant-inner", () -> {
                        throw new IllegalStateException("inner boom");
                    }))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(context.current()).isEqualTo("tenant-outer");
        });

        assertThat(context.current()).isNull();
    }

    // ---- RB-TC4: bindForScope rejects blank / null -----------------------

    @Test
    void bind_for_scope_rejects_null_tenant() {
        assertThatThrownBy(() -> ThreadLocalTenantContext.bindForScope(null, () -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThat(context.current()).isNull();
    }

    @Test
    void bind_for_scope_rejects_blank_tenant() {
        assertThatThrownBy(() -> ThreadLocalTenantContext.bindForScope("   ", () -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
        assertThat(context.current()).isNull();
    }

    @Test
    void bind_for_scope_rejects_null_work() {
        Runnable nullWork = null;
        assertThatThrownBy(() -> ThreadLocalTenantContext.bindForScope("tenant-A", nullWork))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("work");
    }

    // ---- RB-TC5: thread isolation ---------------------------------------

    @Test
    void tenant_bound_on_one_thread_is_not_visible_on_another() throws Exception {
        // Use a raw ThreadPoolExecutor (G.CON.12) instead of Executors factory;
        // cooperative cancellation via the 'release' CountDownLatch avoids
        // Thread.interrupt() (G.CON.10).
        ExecutorService pool = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch bound = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> otherThreadSees = new AtomicReference<>("unset");

        try {
            pool.submit(() -> {
                ThreadLocalTenantContext.bindForScope("tenant-A", () -> {
                    bound.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        // Cooperative cancellation: the 'release' latch is the
                        // shutdown signal; interrupt status is not relied upon.
                    }
                });
                return null;
            });

            assertThat(bound.await(2, TimeUnit.SECONDS)).isTrue();
            // On the test main thread, no tenant is bound (thread isolation).
            otherThreadSees.set(context.current());
            release.countDown();

            assertThat(otherThreadSees.get())
                    .as("Tenant bound on the pool thread must NOT be visible on the test thread")
                    .isNull();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ---- RB-TC6: unbound current() returns null -------------------------

    @Test
    void current_returns_null_when_no_tenant_bound() {
        assertThat(context.current()).isNull();
    }

    @Test
    void current_implements_tenant_context_interface() {
        assertThat(context).isInstanceOf(com.openjiuwen.rdc.spi.registry.TenantContext.class);
    }
}
