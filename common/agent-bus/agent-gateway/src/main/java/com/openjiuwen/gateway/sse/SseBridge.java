/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.stream.Stream;

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
    private static final Logger LOG = LoggerFactory.getLogger(SseBridge.class);

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
                writeFrame(out, frame);
            }
        } catch (java.io.UncheckedIOException ex) {
            // Runtime stream closed/disconnected (unchecked wrapper from stream iterator)
            // → propagate to close client output (bidirectional disconnect, supplement info 3).
            // Log so runtime→client disconnect propagation is observable (S8-4 reverse).
            LOG.info("runtime stream disconnected; closing client SSE (reverse bridge release)");
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioCause) {
                throw ioCause;
            }
            throw ex;
        } catch (AsyncRequestNotUsableException ex) {
            // Spring 6+ LifecycleServletOutputStream.flush on a gone client throws this (an IOException
            // subclass). The response is no longer usable — log + return (don't rethrow); rethrowing only
            // produces a noisy container stack after the controller already handled the disconnect.
            LOG.info("SSE client disconnected; runtime stream released (forward bridge release)");
            return firstFrame;
        } catch (IOException ex) {
            // Forward bridge release (AC-CFG-6 / S8-4): the client SSE disconnected (Ctrl+C / broken
            // pipe) — the try-with-resources above already closed the runtime frame stream, releasing
            // the downstream runtime SSE. Log so client→runtime disconnect propagation is observable.
            LOG.info("SSE client disconnected; runtime stream released (forward bridge release)");
            throw ex;
        }
        // Stream ended normally (runtime closed) → client output flushed by last writeFrame;
        // caller's try-with-resources or response completion closes the client side.
        return firstFrame;
    }

    /**
     * Write an already-read first frame, then drain the remaining frames from the iterator.
     * Used by the BUS streaming path (FEAT-012 IN-4), which reads the first frame with a
     * deadline before committing the response — so the iterator has already yielded it.
     *
     * @param out        client output stream
     * @param iterator   remaining runtime frames (first already consumed)
     * @param firstFrame the first frame already read (may be {@code null} if the stream was empty)
     * @throws IOException if writing to the client fails (e.g. disconnect)
     */
    public void writeSse(OutputStream out, Iterator<String> iterator, String firstFrame) throws IOException {
        try {
            if (firstFrame != null) {
                writeFrame(out, firstFrame);
            }
            while (iterator.hasNext()) {
                writeFrame(out, iterator.next());
            }
        } catch (java.io.UncheckedIOException ex) {
            LOG.info("runtime stream disconnected; closing client SSE (reverse bridge release)");
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioCause) {
                throw ioCause;
            }
            throw ex;
        } catch (AsyncRequestNotUsableException ex) {
            LOG.info("SSE client disconnected; runtime stream released (forward bridge release)");
            // don't rethrow (Spring async client-abort; see 2-arg overload)
        } catch (IOException ex) {
            LOG.info("SSE client disconnected; runtime stream released (forward bridge release)");
            throw ex;
        }
    }

    /**
     * Write a single synthesized frame as an SSE event. Used by the BUS streaming path
     * (FEAT-012 IN-4) to prepend a gateway-synthesized task-accept surface before draining
     * the runtime SubscribeToTask data stream — the runtime stream carries only data
     * chunks, so the client needs an explicit task frame (with {@code id}) to bind the
     * taskRef and settle {@code accepted()}.
     *
     * @param out   client output stream
     * @param frame the synthesized frame to write
     * @throws IOException if writing to the client fails (e.g. disconnect)
     */
    public void writeSse(OutputStream out, String frame) throws IOException {
        try {
            writeFrame(out, frame);
        } catch (java.io.UncheckedIOException ex) {
            LOG.info("runtime stream disconnected; closing client SSE (reverse bridge release)");
            Throwable cause = ex.getCause();
            if (cause instanceof IOException ioCause) {
                throw ioCause;
            }
            throw ex;
        } catch (AsyncRequestNotUsableException ex) {
            LOG.info("SSE client disconnected; runtime stream released (forward bridge release)");
            // don't rethrow (Spring async client-abort; see 2-arg overload)
        } catch (IOException ex) {
            LOG.info("SSE client disconnected; runtime stream released (forward bridge release)");
            throw ex;
        }
    }

    private static void writeFrame(OutputStream out, String frame) throws IOException {
        out.write(("event: jsonrpc\ndata: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
