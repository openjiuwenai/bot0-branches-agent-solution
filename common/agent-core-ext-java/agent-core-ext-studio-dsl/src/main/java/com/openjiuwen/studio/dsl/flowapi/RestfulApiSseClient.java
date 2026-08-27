/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * HTTP SSE reader — 1:1 with Python {@code RestfulApiToolNew.astream} line protocol.
 *
 * <p>Reads response body line-by-line; yields payloads of lines starting with {@code data:}
 * after stripping the prefix (same as {@code DATA_PREFIX} / {@code removeprefix}).
 *
 * @since 2026-08-26
 */
public final class RestfulApiSseClient {
    /** Python {@code DATA_PREFIX}. */
    public static final String DATA_PREFIX = "data:";

    private RestfulApiSseClient() {}

    /**
     * Parse SSE {@code data:} lines from an input stream (test / shared entry).
     *
     * @param in response body
     * @return iterator of payloads (prefix stripped)
     */
    public static Iterator<Object> parseSseDataLines(InputStream in) {
        return parseSseDataLines(in, 0);
    }

    /**
     * Parse SSE with optional wall-clock deadline (0 = no extra deadline beyond HttpRequest timeout).
     */
    public static Iterator<Object> parseSseDataLines(InputStream in, long deadlineMs) {
        long deadlineNanos = deadlineMs > 0 ? System.nanoTime() + deadlineMs * 1_000_000L : 0L;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        return new Iterator<>() {
            private String next;
            private boolean primed;

            private void checkDeadline() {
                if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
                    throw new UncheckedIOException(new IOException("SSE read deadline exceeded"));
                }
            }

            private void prime() {
                if (primed) {
                    return;
                }
                primed = true;
                next = null;
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        checkDeadline();
                        String stripped = line.strip();
                        if (!stripped.isEmpty() && stripped.startsWith(DATA_PREFIX)) {
                            next = stripped.substring(DATA_PREFIX.length());
                            return;
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public boolean hasNext() {
                prime();
                return next != null;
            }

            @Override
            public Object next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                String out = next;
                next = null;
                primed = false;
                return out;
            }
        };
    }

    /**
     * Eager collect for tests.
     *
     * @param body utf-8 SSE body
     * @return payloads
     */
    public static List<Object> parseSseDataLines(String body) {
        List<Object> out = new ArrayList<>();
        parseSseDataLines(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                .forEachRemaining(out::add);
        return out;
    }

    /**
     * Stream HTTP response as SSE data lines (Python {@code astream} HTTP path).
     *
     * @param url url
     * @param method method
     * @param body request body
     * @param headers headers
     * @param timeoutMs timeout
     * @return iterator of data-line payloads
     * @throws Exception on transport / non-2xx
     */
    public static Iterator<Object> stream(
            String url, String method, String body, Map<String, String> headers, long timeoutMs)
            throws Exception {
        com.openjiuwen.studio.dsl.util.OutboundUrlSafety.validateOutbound(url);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
        HttpRequest.Builder rb =
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs));
        if (headers != null) {
            headers.forEach(rb::header);
        }
        applyMethod(rb, method, body == null ? "" : body);
        HttpResponse<InputStream> resp =
                client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            try {
                resp.body().close();
            } catch (IOException ignored) {
                // ignore
            }
            throw new IOException("plugin response code " + code + " error.");
        }
        InputStream bodyStream = resp.body();
        Iterator<Object> inner = parseSseDataLines(bodyStream, timeoutMs);
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                try {
                    return inner.hasNext();
                } catch (UncheckedIOException e) {
                    closeQuietly(bodyStream);
                    throw e;
                }
            }

            @Override
            public Object next() {
                try {
                    Object v = inner.next();
                    if (!inner.hasNext()) {
                        closeQuietly(bodyStream);
                    }
                    return v;
                } catch (NoSuchElementException | UncheckedIOException e) {
                    closeQuietly(bodyStream);
                    throw e;
                }
            }
        };
    }

    private static void applyMethod(HttpRequest.Builder rb, String method, String body) {
        String payload = body == null ? "" : body;
        String m = method == null || method.isBlank() ? "POST" : method.toUpperCase(Locale.ROOT);
        switch (m) {
            case "POST" -> rb.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            case "PUT" -> rb.PUT(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            case "DELETE" -> rb.DELETE();
            default -> rb.GET();
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
