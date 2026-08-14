/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime.security;

import com.openjiuwen.service.spec.paths.A2AServicePaths;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates callbacks sent by the trusted Search Runtime before Runtime handles them.
 *
 * @since 2026-08-05
 */
@Component
@Profile("callback-auth")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class DeepResearchCallbackBearerTokenFilter extends OncePerRequestFilter {
    private static final String BEARER_PREFIX = "Bearer ";

    private static final byte[] UNAUTHORIZED_BODY = "{\"error\":\"unauthorized\"}"
            .getBytes(StandardCharsets.UTF_8);

    private final byte[] expectedAuthorization;

    /**
     * Creates the callback authentication filter.
     *
     * @param bearerToken token the Search Runtime must return on callback
     */
    public DeepResearchCallbackBearerTokenFilter(
            @Value("${openjiuwen.demo.deep-research.callback-auth.bearer-token:}") String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalStateException("DEEP_RESEARCH_CALLBACK_TOKEN must be configured when "
                    + "the callback-auth profile is active");
        }
        this.expectedAuthorization = (BEARER_PREFIX + bearerToken.trim()).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !A2AServicePaths.A2A_PUSH_NOTIFICATION_CALLBACK.equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        byte[] suppliedAuthorization = authorization == null
                ? new byte[0]
                : authorization.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedAuthorization, suppliedAuthorization)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getOutputStream().write(UNAUTHORIZED_BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
