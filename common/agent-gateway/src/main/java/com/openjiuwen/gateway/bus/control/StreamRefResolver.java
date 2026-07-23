package com.openjiuwen.gateway.bus.control;

import java.util.Optional;

/** Resolves a streamRef (opaque internal auth ref, AC-017-3) to a runtime SSE endpoint. */
public interface StreamRefResolver {
    Optional<String> resolve(String streamRef);
}
