/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Request wrapper that caches the body once and replays it to downstream consumers.
 * Shared by the ingress filters of both otel batches (batch-1 http root span filter and
 * batch-2 trace identity filter), which both need the body before the controller does.
 *
 * @since 2026-08-26
 */
public class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    /**
     * Wraps the request and reads its full body into memory.
     *
     * @param request the original request
     * @throws IOException when reading the body fails
     */
    public CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /**
     * Wraps the request with a replacement body (e.g. the trace identity filter's
     * contextId injection), reporting the replacement's length to downstream.
     *
     * @param request the original request
     * @param body    replacement body bytes
     */
    public CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public String getHeader(String name) {
        if ("Content-Length".equalsIgnoreCase(name)) {
            return String.valueOf(body.length);
        }
        return super.getHeader(name);
    }

    /**
     * Returns the cached body bytes.
     *
     * @return cached body
     */
    public byte[] cachedBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // synchronous replay; listener not needed
            }

            @Override
            public int read() {
                return in.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
