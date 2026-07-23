/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

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
}
