package com.openjiuwen.gateway.bus.wait;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;

class G4BusWiringTest {
    private static final String T = "T1", M = "m1", FP = "fp";
    private final IdempotencyRule g4 = new IdempotencyRule();
    private final G4BusWiring wiring = new G4BusWiring(g4);

    private void register() { g4.check(T, M, FP); }

    @Test void responseComplete() { register(); wiring.onFold(InvocationResponseStatus.COMPLETED_RESPONSE, T, M, "result"); assertThat(g4.isCompleted(T, M)).contains(true); }
    @Test void rejectedComplete() { register(); wiring.onFold(InvocationResponseStatus.REJECTED, T, M, "rej"); assertThat(g4.isCompleted(T, M)).contains(true); }
    @Test void failedComplete() { register(); wiring.onFold(InvocationResponseStatus.FAILED, T, M, "fail"); assertThat(g4.isCompleted(T, M)).contains(true); }
    @Test void acceptedTimeoutComplete() { register(); wiring.onFold(InvocationResponseStatus.ACCEPTED_WITH_TASK, T, M, "accepted"); assertThat(g4.isCompleted(T, M)).contains(true); }
    @Test void produceFailAbort() { register(); wiring.onAbort(T, M); assertThat(g4.isCompleted(T, M)).isEmpty(); }
    @Test void acceptTimeoutAbort() { register(); wiring.onFold(InvocationResponseStatus.UNKNOWN, T, M, null); assertThat(g4.isCompleted(T, M)).isEmpty(); }
    @Test void replayAfterCompleteNoReEnqueue() {
        register();
        wiring.onFold(InvocationResponseStatus.COMPLETED_RESPONSE, T, M, "result");
        var d = g4.check(T, M, FP); assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.REPLAY);
    }
    @Test void retryAfterAbortReRegisters() {
        register();
        wiring.onAbort(T, M);
        var d = g4.check(T, M, FP); assertThat(d.outcome()).isEqualTo(IdempotencyRule.Outcome.NEW);
    }
}
