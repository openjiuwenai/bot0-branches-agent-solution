package com.openjiuwen.gateway.bus.control;

import java.util.Optional;

/** Stash A2A body → payloadRef (HD4: body never in envelope; large payload via ref). */
public interface PayloadStore {
    String stash(String body);
    Optional<String> fetch(String ref);
}
