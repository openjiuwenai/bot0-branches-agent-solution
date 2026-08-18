/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Unit tests for {@link SseBridge} (FEAT-011 L2 §4 P3b / SC-2).
 */
class SseBridgeTest {
    private final SseBridge bridge = new SseBridge();

    @Test
    void writesJsonrpcEventPerFrame() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bridge.writeSse(out, Stream.of("{\"a\":1}", "{\"a\":2}"));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("event: jsonrpc\ndata: {\"a\":1}\n\nevent: jsonrpc\ndata: {\"a\":2}\n\n");
    }

    @Test
    void releasesDownstreamStreamOnCompletion() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<String> frames = Stream.of("x", "y").onClose(() -> closed.set(true));
        bridge.writeSse(new ByteArrayOutputStream(), frames);
        assertThat(closed).isTrue();
    }

    @Test
    void emptyFrameStreamWritesNothingButStillReleases() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<String> frames = Stream.<String>empty().onClose(() -> closed.set(true));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bridge.writeSse(out, frames);
        assertThat(out.toByteArray()).isEmpty();
        assertThat(closed).isTrue();
    }

    @Test
    void returnsFirstFrameForReplay() throws IOException {
        String first = bridge.writeSse(new ByteArrayOutputStream(),
                Stream.of("{\"result\":{\"id\":\"t1\"}}", "{\"result\":{\"status\":\"working\"}}"));
        assertThat(first).isEqualTo("{\"result\":{\"id\":\"t1\"}}");
    }

    @Test
    void returnsNullForEmptyStream() throws IOException {
        assertThat(bridge.writeSse(new ByteArrayOutputStream(), Stream.empty())).isNull();
    }

    @Test
    void clientDisconnectReleasesDownstreamAndLogs() throws IOException {
        // S8-4 forward: client SSE disconnects (broken pipe / Ctrl+C) → SseBridge must (1) propagate
        // the IOException, (2) release the runtime frame stream (try-with-resources close = forward
        // bridge release, AC-CFG-6), and (3) log the release so disconnect propagation is observable.
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SseBridge.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var levelBefore = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            AtomicBoolean closed = new AtomicBoolean();
            Stream<String> frames = Stream.of("frame-1", "frame-2").onClose(() -> closed.set(true));
            var brokenClient = new java.io.OutputStream() {
                @Override public void write(int b) throws IOException {
                    throw new IOException("broken pipe (client gone)");
                }
                @Override public void write(byte[] b, int off, int len) throws IOException {
                    throw new IOException("broken pipe (client gone)");
                }
            };
            IOException thrown = null;
            try {
                bridge.writeSse(brokenClient, frames);
            } catch (IOException ex) {
                thrown = ex;
            }
            assertThat(thrown).isNotNull().hasMessageContaining("client gone");
            assertThat(closed.get()).as("runtime frame stream released on client disconnect").isTrue();
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("client disconnected")
                    && e.getFormattedMessage().contains("runtime stream released"));
        } finally {
            logger.setLevel(levelBefore);
            logger.detachAppender(appender);
        }
    }

    @Test
    void runtimeStreamDisconnectClosesClientAndLogs() {
        // S8-4 reverse: the runtime frame stream errors (UncheckedIOException wrapping IOException —
        // e.g. the HttpClient read got Connection-reset when the runtime died). SseBridge must rethrow
        // the IOException (so the controller aborts G4 / closes the client SSE) AND log the reverse
        // bridge release so runtime→client disconnect propagation is observable.
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SseBridge.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var levelBefore = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            AtomicBoolean closed = new AtomicBoolean();
            // a runtime stream whose iterator throws UncheckedIOException (runtime read failure)
            Stream<String> frames = Stream.<String>generate(
                    () -> { throw new java.io.UncheckedIOException(new IOException("runtime Connection reset")); })
                    .onClose(() -> closed.set(true));
            IOException thrown = null;
            try {
                bridge.writeSse(new ByteArrayOutputStream(), frames);
            } catch (IOException ex) {
                thrown = ex;
            }
            assertThat(thrown).isNotNull().hasMessageContaining("runtime Connection reset");
            assertThat(closed.get()).as("runtime frame stream released on runtime disconnect").isTrue();
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("runtime stream disconnected")
                    && e.getFormattedMessage().contains("closing client SSE"));
        } finally {
            logger.setLevel(levelBefore);
            logger.detachAppender(appender);
        }
    }

    // --- S8-4 BUS streaming: the 3-arg + 1-arg writeSse overloads (used by bridgeStreamToClient)
    // and the Spring AsyncRequestNotUsableException (client abort during async SSE flush) ---

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureLogs() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SseBridge.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        return appender;
    }

    private void releaseLogs(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SseBridge.class);
        logger.detachAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    private static java.io.OutputStream brokenClient() {
        return new java.io.OutputStream() {
            @Override public void write(int b) throws IOException { throw new IOException("client gone"); }
            @Override public void write(byte[] b, int off, int len) throws IOException { throw new IOException("client gone"); }
        };
    }

    private static java.io.OutputStream asyncAbortClient() {
        return new java.io.OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                        "client abort", new IOException("Connection reset"));
            }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException(
                        "client abort", new IOException("Connection reset"));
            }
        };
    }

    @Test
    void threeArgWriteSseClientDisconnectLogsAndRethrows() {
        // BUS SendStreamingMessage (bridgeStreamToClient uses the 3-arg overload). Client Ctrl+C ->
        // writeFrame IOException -> log "SSE client disconnected" + rethrow (so the controller aborts G4).
        var appender = captureLogs();
        try {
            Throwable thrown = catchThrowable(() ->
                    bridge.writeSse(brokenClient(), java.util.List.of("f1", "f2").iterator(), null));
            assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("client gone");
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("SSE client disconnected"));
        } finally { releaseLogs(appender); }
    }

    @Test
    void threeArgWriteSseAsyncAbortLoggedAndNotRethrown() {
        // Spring 6+ LifecycleServletOutputStream.flush on a gone client throws AsyncRequestNotUsableException
        // (a RuntimeException, not IOException). Catch + log + DON'T rethrow -> suppresses the noisy
        // container stack (the response is no longer usable; propagating only produces a Spring async stack).
        var appender = captureLogs();
        try {
            Throwable thrown = catchThrowable(() ->
                    bridge.writeSse(asyncAbortClient(), java.util.List.of("f1").iterator(), null));
            assertThat(thrown).isNull();
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("SSE client disconnected"));
        } finally { releaseLogs(appender); }
    }

    @Test
    void oneArgWriteSseClientDisconnectLogsAndRethrows() {
        // The 1-arg overload (synthesized accept/terminal frames in bridgeStreamToClient).
        var appender = captureLogs();
        try {
            Throwable thrown = catchThrowable(() -> bridge.writeSse(brokenClient(), "f1"));
            assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("client gone");
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("SSE client disconnected"));
        } finally { releaseLogs(appender); }
    }

    @Test
    void twoArgWriteSseAsyncAbortLoggedAndNotRethrown() {
        // The 2-arg overload (forwardSubscribe). AsyncRequestNotUsableException -> log + return (not rethrow).
        var appender = captureLogs();
        try {
            Throwable thrown = catchThrowable(() -> bridge.writeSse(asyncAbortClient(), java.util.stream.Stream.of("f1")));
            assertThat(thrown).isNull();
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("SSE client disconnected"));
        } finally { releaseLogs(appender); }
    }
}
