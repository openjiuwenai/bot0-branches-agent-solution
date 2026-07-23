package com.openjiuwen.gateway.bus.wait;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;

class SyncDisconnectTest {
    @Test void syncDisconnectReleasesWindow() {
        var window = new WaitWindow(0L, 30_000L, 60_000L);
        var g4 = new IdempotencyRule();
        g4.check("T1", "m1", "fp"); // register IN_FLIGHT
        var handler = new SyncDisconnectHandler();
        handler.onDisconnect(window, "T1", "m1", g4);
        assertThat(window.isReleased()).isTrue();
        assertThat(g4.isCompleted("T1", "m1")).isEmpty(); // aborted, not completed
    }
    @Test void syncDisconnectAbortsG4() {
        var window = new WaitWindow(0L, 30_000L, 60_000L);
        var g4 = new IdempotencyRule();
        g4.check("T1", "m1", "fp");
        new SyncDisconnectHandler().onDisconnect(window, "T1", "m1", g4);
        var d = g4.check("T1", "m1", "fp"); assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.NEW); // abort → re-registerable
    }
}
