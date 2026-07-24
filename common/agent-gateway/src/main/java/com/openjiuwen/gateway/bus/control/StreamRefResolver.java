/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import java.util.Optional;

/**
 * Resolves a streamRef (opaque internal auth ref, AC-017-3) to a runtime SSE endpoint.
 *
 * @since 2026-07-24
 */
public interface StreamRefResolver {

    /**
     * @param streamRef opaque stream reference from projection
     * @return resolved SSE endpoint URL, or empty when unavailable
     */
    Optional<String> resolve(String streamRef);
}
