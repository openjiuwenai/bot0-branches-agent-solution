/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RailEvent sealed 层次变体快照——加新 permitted record 时 RED，迫使同步更新
 * {@link HostLoggerRailEventListener#onRailEvent} 的 instanceof 链（mirror PEV
 * {@code replanActionSealedVariantsSnapshot}）。
 *
 * <p>背景：本模块 --release 17 无 --enable-preview，sealed 类型模式 switch 不可用，
 * HostLoggerRailEventListener 用 instanceof 链 + fail-fast throw 兜底——编译器不强制穷尽。
 * 本测试是运行期穷尽守卫：反射枚举 RailEvent 的 permitted records，断言恰为今日 7 个。
 *
 * <p>mutation-RED：给 RailEvent 加第 8 个 permitted record → getPermittedSubclasses 返回 8 →
 * containsExactlyInAnyOrder(7 个) RED → 必须同步给 instanceof 链加分支。
 */
class RailEventSealedVariantsTest {

    /**
     * Snapshot of RailEvent's permitted variants — HostLoggerRailEventListener must cover all.
     */
    @Test
    void railEventSealedVariantsSnapshot() {
        Class<?>[] permitted = RailEvent.class.getPermittedSubclasses();
        assertThat(permitted).as("RailEvent must be sealed (getPermittedSubclasses non-null)").isNotNull();
        assertThat(Arrays.stream(permitted).map(Class::getSimpleName).toList())
                .as("RailEvent permitted variants snapshot — HostLoggerRailEventListener must cover all")
                .containsExactlyInAnyOrder("ForceFinishEvent", "SteeringEvent", "VerifyEvent",
                        "ReplanCountEvent", "PhaseOverrideEvent", "ContextCompressedEvent",
                        "DeviceFailureEvent");
    }

    /**
     * Dispatch-coverage guard (complements the count snapshot): HostLoggerRailEventListener must
     * dispatch every sealed variant without throwing. Removing an instanceof branch for variant X
     * → X falls to the fail-fast else → throws → RED. (ForceFinish verified/degraded + DeviceFailure
     * MARKED/FIRED sub-branches all exercised.)
     */
    @Test
    void hostLoggerDispatchesAllSealedVariantsWithoutThrow() {
        HostLoggerRailEventListener listener = new HostLoggerRailEventListener();
        List<RailEvent> all = List.of(
                new RailEvent.ForceFinishEvent("r", true, Map.of()),
                new RailEvent.ForceFinishEvent("r", false, Map.of()),
                new RailEvent.SteeringEvent("r", "src", "hint", true),
                new RailEvent.VerifyEvent("r", true, 0, List.of()),
                new RailEvent.ReplanCountEvent("r", 1, "LLM", 2),
                new RailEvent.PhaseOverrideEvent("r", "PLAN_MODE", "excerpt"),
                new RailEvent.ContextCompressedEvent("r", 10, 5),
                new RailEvent.DeviceFailureEvent("r", "tool", RailEvent.DeviceFailurePhase.MARKED),
                new RailEvent.DeviceFailureEvent("r", "tool", RailEvent.DeviceFailurePhase.FIRED));
        for (RailEvent event : all) {
            String name = event.getClass().getSimpleName();
            assertThatCode(() -> listener.onRailEvent(event))
                    .as("HostLogger must dispatch " + name + " without throwing")
                    .doesNotThrowAnyException();
        }
    }

    /**
     * ForceFinishEvent.result() must be an eager defensive copy, not aliased to the source map
     * (mirror PEV NodeSnapshot). mutation-RED: remove the compact-ctor copy → result() aliases
     * the live map → sees the post-construction mutation → RED.
     */
    @Test
    void forceFinishEventResultIsDefensivelyCopied() {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("k", "v1");
        RailEvent.ForceFinishEvent event = new RailEvent.ForceFinishEvent("r", true, live);
        live.put("k", "v2");
        assertThat(event.result().get("k")).as("result() must be an eager copy, not aliased to the live map")
                .isEqualTo("v1");
    }
}
