/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for G3 {@link ParamValidator} (FEAT-011 L2 §3.5 T-G3-1..T-G3-6).
 */
class ParamValidatorTest {
    private final ParamValidator validator = new ParamValidator();

    private static final String CREATE_NO_AGENT =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]}}}";
    private static final String CREATE_WITH_AGENT =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]},"
                    + "\"metadata\":{\"agentId\":\"agent-9\"}}}";
    private static final String CREATE_EMPTY_AGENT =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m1\",\"parts\":[{\"text\":\"hi\"}]},"
                    + "\"metadata\":{\"agentId\":\"\"}}}";
    private static final String RESUME =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m2\",\"taskId\":\"task-7\",\"parts\":[]}}}";
    private static final String BAD_METHOD =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\",\"params\":{}}";
    private static final String STREAMING =
            "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"SendStreamingMessage\","
                    + "\"params\":{\"message\":{\"messageId\":\"m3\",\"parts\":[{\"text\":\"hi\"}]}}}";

    private static GovernanceContext validate(ParamValidator v, String body) {
        GovernanceContext ctx = new GovernanceContext();
        v.validate(body, ctx);
        return ctx;
    }

    private static GovernanceException govern(Runnable action) {
        Throwable thrown = catchThrowable(action::run);
        assertThat(thrown).as("expected a GovernanceException").isNotNull();
        if (thrown instanceof GovernanceException ge) {
            return ge;
        }
        throw new AssertionError("expected GovernanceException but got: " + thrown);
    }

    @Test
    void createWithNonEmptyAgentIdPopulatesContext() {
        GovernanceContext ctx = validate(validator, CREATE_WITH_AGENT);
        assertThat(ctx.method()).isEqualTo("SendMessage");
        assertThat(ctx.agentId()).isEqualTo("agent-9");
        assertThat(ctx.taskId()).isNull();
        assertThat(ctx.messageId()).isEqualTo("m1");
    }

    @Test
    void createWithoutAgentIdIsAccepted() {
        GovernanceContext ctx = validate(validator, CREATE_NO_AGENT);
        assertThat(ctx.method()).isEqualTo("SendMessage");
        assertThat(ctx.agentId()).isNull();
        assertThat(ctx.taskId()).isNull();
    }

    @Test
    void createWithEmptyAgentIdReturns400ValidationAgentId() {
        GovernanceException ge = govern(() -> validate(validator, CREATE_EMPTY_AGENT));
        assertThat(ge.code()).isEqualTo("VALIDATION_AGENT_ID");
        assertThat(ge.httpStatus().value()).isEqualTo(400);
    }

    @Test
    void resumeWithTaskIdPopulatesTaskId() {
        GovernanceContext ctx = validate(validator, RESUME);
        assertThat(ctx.taskId()).isEqualTo("task-7");
        assertThat(ctx.agentId()).isNull();
    }

    @Test
    void resumeMissingTaskIdIsTreatedAsCreate() {
        // No taskId -> classified as create (no failure, agentId absent is OK).
        GovernanceContext ctx = validate(validator, CREATE_NO_AGENT);
        assertThat(ctx.taskId()).isNull();
    }

    @Test
    void malformedBodyReturns400ValidationJsonrpc() {
        GovernanceException ge = govern(() -> validate(validator, "{not json"));
        assertThat(ge.code()).isEqualTo("VALIDATION_JSONRPC");
    }

    @Test
    void methodNotInWhitelistReturns400ValidationMethod() {
        GovernanceException ge = govern(() -> validate(validator, BAD_METHOD));
        assertThat(ge.code()).isEqualTo("VALIDATION_METHOD");
        assertThat(ge.httpStatus().value()).isEqualTo(400);
    }

    @Test
    void streamingMethodIsAccepted() {
        GovernanceContext ctx = validate(validator, STREAMING);
        assertThat(ctx.method()).isEqualTo("SendStreamingMessage");
    }
}
