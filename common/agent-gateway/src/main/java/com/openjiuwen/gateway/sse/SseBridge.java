/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Bridges runtime SSE frames to the client response (FEAT-011 L2 §4 P3b / §4.10
 * AC-RT-2). Each runtime frame is written as an SSE event {@code event: jsonrpc}
 * with the frame as {@code data}. The gateway does not generate or cache tokens —
 * frames pass through. Closing the input stream (try-with-resources) releases the
 * downstream connection on normal completion or client disconnect.
 *
 * @since 0.1.0
 */
@Component
public class SseBridge {
    /**
     * Write runtime frames as SSE events to the client output stream.
     *
     * @param out    client output stream
     * @param frames runtime SSE data payloads (closed on return / on failure)
     * @return the first frame written (the task-accept/result surface), or {@code null}
     *         if the stream was empty — used as the idempotency REPLAY body (approach A)
     * @throws IOException if writing to the client fails (e.g. disconnect)
     */
    public String writeSse(OutputStream out, Stream<String> frames) throws IOException {
        String firstFrame = null;
        try (Stream<String> stream = frames) {
            for (var it = stream.iterator(); it.hasNext(); ) {
                String frame = it.next();
                if (firstFrame == null) {
                    firstFrame = frame;
                }
                out.write(("event: jsonrpc\ndata: " + frame + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
        return firstFrame;
    }
}
