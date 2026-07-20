package com.openjiuwen.rdc.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryRequestDeadlineTest {

    @Test
    void future_deadline_passes() {
        assertThatCode(() -> RegistryRequestDeadline.enforce(
                Instant.now().plusSeconds(5), "trace-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void past_deadline_raises_deadline_exceeded() {
        assertThatThrownBy(() -> RegistryRequestDeadline.enforce(
                Instant.now().minusSeconds(1), "trace-deadline"))
                .isInstanceOf(DeadlineExceededException.class)
                .satisfies(ex -> assertThat(((DeadlineExceededException) ex).failure().failureCode())
                        .isEqualTo("DEADLINE_EXCEEDED"));
    }
}
