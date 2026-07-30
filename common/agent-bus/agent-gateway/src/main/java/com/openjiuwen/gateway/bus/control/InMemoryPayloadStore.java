/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory PayloadStore (D4 single-machine).
 *
 * @since 2026-07-24
 */
@Component
public class InMemoryPayloadStore implements PayloadStore {
    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String stash(String body) {
        String ref = "payload-" + UUID.randomUUID();
        store.put(ref, body);
        return ref;
    }

    @Override
    public Optional<String> fetch(String ref) {
        return Optional.ofNullable(store.get(ref));
    }
}
