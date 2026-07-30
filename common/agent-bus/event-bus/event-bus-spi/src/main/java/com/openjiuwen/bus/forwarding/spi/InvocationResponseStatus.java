/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

/**
 * Gateway-side observed status of a client / A2A invocation (FEAT-013 §2.3.3,
 * shared by FEAT-014 for the remote-Task viewpoint). This is the
 * <em>invocation-semantics</em> layer observed by the gateway / caller; it is
 * orthogonal to the bus-delivery layer {@link ForwardingStatus}
 * (PENDING / DISPATCHING / ACKED / RETRY_SCHEDULED / DLQ / EXPIRED).
 *
 * <p>{@link #UNKNOWN} is the accept-window unknown state — the gateway could
 * not confirm within the accept window whether the server built a Task. It is
 * neither success nor failure; the client retries with the same
 * {@code idempotencyKey} (FEAT-013 §2.3.4 / §4.3, RFC 2119 MUST).
 *
 * <p>{@link #INPUT_REQUIRED} (FEAT-017) is a non-terminal observed state: the server
 * Task is accepted but waiting for human / external input. The gateway surfaces the
 * wait-for-input projection promptly (a {@code *_INPUT_REQUIRED} bus event) rather
 * than only via a later GetTask; it coexists with the FEAT-005 shadow-task resume
 * mechanism.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md §2.3.3};
 * {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-014-a2a-call-event-forwarding.md §2.3.2};
 * {@code version-scope/FEAT-017-bus-event-subscription-consumption.md §5.1}.
 *
 * @since 0.1.0
 */
// scope: forwarding SPI — pure Java invocation observed-status; no payload body
public enum InvocationResponseStatus {
    /** Final A2A response obtained within the blocking window. */
    COMPLETED_RESPONSE,
    /** Server built/reused a Task and returned a taskId, but the final response is not yet in. */
    ACCEPTED_WITH_TASK,
    /**
     * Task entered a wait-for-input state (FEAT-017). Non-terminal: the Task is accepted but
     * needs human / external input before it can continue; the caller may resume it. Maps from
     * {@code INVOCATION_INPUT_REQUIRED} / {@code A2A_CALL_INPUT_REQUIRED}.
     */
    INPUT_REQUIRED,
    /** Server Task's A2A SSE stream is ready to subscribe; the gateway can bridge. */
    STREAM_READY,
    /** Server explicitly rejected; no Task was created. */
    REJECTED,
    /** The invocation event or server-side handling definitively failed. */
    FAILED,
    /** Accept-window unknown: could not confirm whether the server built a Task. */
    UNKNOWN
}
