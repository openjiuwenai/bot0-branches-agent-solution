/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ErrorCodes} stable-code constants, numeric mapping, and
 * retryable semantics (A1, FEAT-011 §8.5.3).
 */
class ErrorCodesTest {
    @Test
    void projection_timeout_unknown_is_retryable() {
        assertThat(ErrorCodes.PROJECTION_TIMEOUT_UNKNOWN.retryable()).isTrue();
        assertThat(ErrorCodes.PROJECTION_TIMEOUT_UNKNOWN.numericCode()).isEqualTo(-32050);
        assertThat(ErrorCodes.PROJECTION_TIMEOUT_UNKNOWN.stableCode()).isEqualTo("PROJECTION_TIMEOUT_UNKNOWN");
    }

    @Test
    void payload_ref_unavailable_is_retryable() {
        assertThat(ErrorCodes.PAYLOAD_REF_UNAVAILABLE.retryable()).isTrue();
        assertThat(ErrorCodes.PAYLOAD_REF_UNAVAILABLE.numericCode()).isEqualTo(-32054);
    }

    @Test
    void non_retryable_codes() {
        assertThat(ErrorCodes.CONTINUATION_FAILED.retryable()).isFalse();
        assertThat(ErrorCodes.STREAM_NOT_AVAILABLE.retryable()).isFalse();
        assertThat(ErrorCodes.TASK_NOT_FOUND.retryable()).isFalse();
        assertThat(ErrorCodes.VALIDATION_FAILED.retryable()).isFalse();
        assertThat(ErrorCodes.VALIDATION_METHOD.retryable()).isFalse();
        assertThat(ErrorCodes.INTERNAL_ERROR.retryable()).isFalse();
    }

    @Test
    void is_retryable_by_stable_code() {
        assertThat(ErrorCodes.isRetryable("PROJECTION_TIMEOUT_UNKNOWN")).isTrue();
        assertThat(ErrorCodes.isRetryable("PAYLOAD_REF_UNAVAILABLE")).isTrue();
        assertThat(ErrorCodes.isRetryable("CONTINUATION_FAILED")).isFalse();
        assertThat(ErrorCodes.isRetryable("TASK_NOT_FOUND")).isFalse();
    }

    @Test
    void unrecognised_code_is_not_retryable_fail_closed() {
        assertThat(ErrorCodes.isRetryable("UNKNOWN_CODE")).isFalse();
        assertThat(ErrorCodes.isRetryable(null)).isFalse();
    }

    @Test
    void from_rpc_code_maps_known_runtime_errors() {
        assertThat(ErrorCodes.fromRpcCode(-32001)).isEqualTo(ErrorCodes.TASK_NOT_FOUND);
        assertThat(ErrorCodes.fromRpcCode(-32602)).isEqualTo(ErrorCodes.VALIDATION_FAILED);
        assertThat(ErrorCodes.fromRpcCode(-32601)).isEqualTo(ErrorCodes.VALIDATION_METHOD);
        assertThat(ErrorCodes.fromRpcCode(-32603)).isEqualTo(ErrorCodes.INTERNAL_ERROR);
    }

    @Test
    void from_rpc_code_returns_null_for_unknown() {
        assertThat(ErrorCodes.fromRpcCode(-99999)).isNull();
        assertThat(ErrorCodes.fromRpcCode(0)).isNull();
    }
}
