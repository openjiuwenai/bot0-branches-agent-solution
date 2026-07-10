/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.kernel;

/**
 * A single node's terminal state inside one execution superstep — 3-state sealed.
 *
 * <p>Replaces the untyped {@code "FAILED:" + msg} string convention with a closed
 * taxonomy the verifier/diagnoser can switch over exhaustively. Dropping a state
 * here makes any matching switch fail to compile.
 */
public sealed interface NodeResult permits NodeResult.Success, NodeResult.DeviceFailure, NodeResult.VerifierFailure {

    /** Node completed, carrying its return value. */
    record Success(Object value) implements NodeResult {
    }

    /** Tool / infrastructure failure (timeout, network, exception). */
    record DeviceFailure(String nodeId, String error, boolean isTimeout) implements NodeResult {
    }

    /** Verifier judged the node's output not meeting expectations.
    /** Verifier judged the node's output not meeting expectations.  * @since 2026-07*/
    record VerifierFailure(String nodeId, String reason) implements NodeResult {
    }
}