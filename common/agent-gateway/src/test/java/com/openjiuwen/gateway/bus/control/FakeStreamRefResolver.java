/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import java.util.Optional;

/**
 * Test double for {@link StreamRefResolver}.
 *
 * @since 2026-07-24
 */
public class FakeStreamRefResolver implements StreamRefResolver {
    private String endpoint = "http://rt:8000";

    /**
     * Overrides the resolved endpoint URL.
     *
     * @param endpoint resolved endpoint URL
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Simulates resolution failure by clearing the endpoint.
     */
    public void setFail() {
        this.endpoint = null;
    }

    @Override
    public Optional<String> resolve(String streamRef) {
        return Optional.ofNullable(endpoint);
    }
}
