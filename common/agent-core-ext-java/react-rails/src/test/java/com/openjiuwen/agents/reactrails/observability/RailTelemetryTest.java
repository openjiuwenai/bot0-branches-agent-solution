/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * RailTelemetry 承重测试 — 验观测 IFF 绑定（铁律⑲：每个承重断言非恒真，剥 gradient 须 RED）。
 *
 * <p>四象限：
 * <ol>
 *   <li>NOOP fire 不抛 + current() 默认 noop</li>
 *   <li>setCurrent(noop().with(collector)) 后 fire 收事件</li>
 *   <li>多 listener fanout（每个 listener 都收到同一事件）</li>
 *   <li>try/catch 隔离（一个 listener 抛异常，其余仍收 + 不抛回调用方）</li>
 * </ol>
 *
 * <p><b>Honest boundary (铁律①)</b>: 这些测试只验 <em>旁路 echo 管道本身</em>
 * （fire→listener 收事件 + 隔离）。真正的承重控制流（forceFinish/pushSteering 改 exit）
 * 不在这里 — 它在 {@code CriteriaReplanBridgeRail} 等各 rail 的承重测试里。
 * fire 是旁路，绝不改控制流；如果剥 fire，控制流行为不变，只有"收 RailEvent"这一断言变 RED。
 * 这正是观测绑定的正确语义：IFF = Exit 触发 ↔ 收对应 RailEvent。
 *
 * <p><b>G.ERR.02</b>: 不裸 catch RuntimeException。隔离测试用 {@link FutureTask} 桥接，
 * 使抛出的 {@link IllegalStateException} 显式冒泡到测试线程（fire 内部吞掉 → FutureTask
 * 正常返回 → 证隔离成功；若吞失败 → ExecutionException 包装 IllegalStateException 冒泡 → RED）。
 *
 * <p><b>G.MET.04</b>: 不用 Object...。
 *
 * @since 2026-07
 */
class RailTelemetryTest {
    @BeforeEach
    void resetToNoop() {
        // Isolation: each test starts from a clean NOOP current(), never inherits a prior
        // test's listener (RailTelemetry.current is process-wide static).
        RailTelemetry.setCurrent(RailTelemetry.noop());
    }

    @AfterEach
    void cleanup() {
        RailTelemetry.setCurrent(RailTelemetry.noop());
    }

    @Test
    void noopFireDoesNotThrowAndCurrentDefaultsToNoop() {
        // current() default is the NOOP singleton (zero listeners, zero overhead)
        RailTelemetry current = RailTelemetry.current();
        assertThat(current).as("current() must default to the NOOP instance").isSameAs(RailTelemetry.noop());

        // firing any event on the default current must not throw and must not NPE
        RailEvent event = new RailEvent.VerifyEvent("TestRail", true, 0, java.util.List.of());
        assertThatCode(() -> current.fire(event))
                .as("NOOP fire must be a safe no-op, never throwing").doesNotThrowAnyException();
        // mutation-RED: if NOOP carried a stale listener from a prior test, fire could throw
        // or assert sameAs(noop()) would fail.
    }

    @Test
    void installedListenerReceivesFiredEvent() {
        CollectingRailEventListener collector = new CollectingRailEventListener();
        RailTelemetry.setCurrent(RailTelemetry.noop().with(collector));

        RailEvent.VerifyEvent event = new RailEvent.VerifyEvent("CriteriaReplanBridgeRail", false, 2,
                java.util.List.of("missing 建议", "missing 债券"));

        RailTelemetry.current().fire(event);

        // IFF: fire called ↔ exactly one event collected, and it is the same instance
        assertThat(collector.events()).as("installed listener must receive the fired event").hasSize(1);
        assertThat(collector.events().get(0)).as("collected event must be the exact fired instance")
                .isSameAs(event);
        assertThat(collector.ofType(RailEventType.VERIFY))
                .as("ofType filter must match VERIFY events").hasSize(1);
        // mutation-RED: strip fire(event) in the rail → collector receives 0 → RED
        // mutation-RED: strip setCurrent(noop().with(collector)) → fire goes to NOOP → collector 0 → RED
    }

    @Test
    void multipleListenersFanOutToAll() {
        CollectingRailEventListener collectorA = new CollectingRailEventListener();
        CollectingRailEventListener collectorB = new CollectingRailEventListener();

        // Stack two listeners: noop().with(A).with(B)
        RailTelemetry.setCurrent(RailTelemetry.noop().with(collectorA).with(collectorB));

        RailEvent event = new RailEvent.ForceFinishEvent("RootCauseRail", true, Map.of("output", "ok"));
        RailTelemetry.current().fire(event);

        // fanout: BOTH listeners receive the SAME event instance, exactly once each
        assertThat(collectorA.events()).as("listener A must receive the event on fanout").hasSize(1);
        assertThat(collectorB.events()).as("listener B must receive the event on fanout").hasSize(1);
        assertThat(collectorA.events().get(0)).as("fanout must deliver the same instance, not a copy")
                .isSameAs(event);
        assertThat(collectorB.events().get(0)).isSameAs(event);
        // mutation-RED: if fire only delivered to listeners[0] (broke the for-loop), collectorB 0 → RED
        // mutation-RED: if with() replaced instead of stacked, one collector would be 0 → RED
    }

    @Test
    void throwingListenerIsIsolatedAndDoesNotPropagate() throws Exception {
        CollectingRailEventListener goodCollector = new CollectingRailEventListener();
        // A deliberately buggy listener that throws a concrete RuntimeException.
        RailEventListener throwingListener = event -> {
            throw new IllegalStateException("listener boom on " + event.type());
        };

        // Order matters for the isolation proof: thrower FIRST, then good listener.
        // If fire re-throws, goodCollector never sees the event. If fire isolates correctly,
        // goodCollector still receives it.
        RailTelemetry.setCurrent(RailTelemetry.noop().with(throwingListener).with(goodCollector));

        // queueBound is irrelevant to this fire-isolation proof; true keeps the normal fire path.
        RailEvent event = new RailEvent.SteeringEvent("StagnationDetectionRail", "STAGNATION_OUTPUT",
                "hint excerpt", true);

        // G.ERR.02: do NOT naked-catch RuntimeException. Bridge through FutureTask so the
        // concrete IllegalStateException is forced into a value-returning Callable.
        // If fire swallowed the throw correctly → task returns normally ("OK").
        // If fire leaked the throw → FutureTask.get() throws ExecutionException wrapping
        // IllegalStateException → assertThat does not match → test fails loudly (RED).
        Callable<String> fireCall = () -> {
            RailTelemetry.current().fire(event);
            return "OK";
        };
        FutureTask<String> task = new FutureTask<>(fireCall);
        task.run();
        String outcome = task.get(); // throws ExecutionException if fire leaked the exception
        assertThat(outcome).as("fire must return normally despite a throwing listener (isolation)")
                .isEqualTo("OK");

        // The good listener after the thrower still received the event → isolation preserved
        assertThat(goodCollector.events())
                .as("a throwing listener must NOT block later listeners from receiving the event")
                .hasSize(1);
        assertThat(goodCollector.events().get(0)).isSameAs(event);
        // mutation-RED: strip the try/catch in fire → FutureTask.get() throws
        //   ExecutionException(IllegalStateException) → assertThat("OK") RED
        // mutation-RED: if catch moved BEFORE the loop body (skips later listeners) → goodCollector 0 → RED
    }
}
