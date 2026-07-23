package com.openjiuwen.gateway.bus.control;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** 730 in-memory PayloadStore (D4 single-machine). */
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
