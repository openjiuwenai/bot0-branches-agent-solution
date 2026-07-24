/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import java.util.Optional;

/**
 * Stash A2A body → payloadRef (HD4: body never in envelope; large payload via ref).
 *
 * @since 2026-07-24
 */
public interface PayloadStore {

    /**
     * @param body raw A2A request body
     * @return opaque payload reference
     */
    String stash(String body);

    /**
     * @param ref payload reference from stash
     * @return stored body, or empty when unknown
     */
    Optional<String> fetch(String ref);
}
