package com.openjiuwen.example.versatile.intent.routecache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts {@code agentName} + {@code responseContent} from a
 * {@code a2a_delegate} interrupt payload (produced by
 * {@code VersatileResponseExtractor}) and builds synthetic payloads used by
 * {@link CachedVersatileAgentHandler} on cache hits.
 *
 * <p>Payload contract (see {@code VersatileResponseExtractor.java:281-292}):
 * <pre>
 *   {
 *     "agentName": "...",          // next-hop agent id
 *     "responseContent": "...",    // L1 response_content
 *     "resume": false,
 *     "context": {
 *       "_interrupt_kind": "a2a_delegate",
 *       "agentName": "...",
 *       "resume": false
 *     },
 *     "message": "...",            // user query (added by VersatileAgentHandler)
 *     "_stream_mode": "sse" | ""   // added by VersatileAgentHandler
 *   }
 * </pre>
 *
 * @since 2026-07-25
 */
final class A2aDelegatePayload {
    private static final String A2A_DELEGATE_KIND = "a2a_delegate";

    record Parsed(String agentName, String responseContent) {}

    private A2aDelegatePayload() {}

    static Optional<Parsed> fromResultMap(Map<String, Object> result) {
        Object interruptObj = result.get("_interrupt");
        if (!(interruptObj instanceof Map<?, ?> interrupt)) {
            return Optional.empty();
        }
        return parse(interrupt);
    }

    static Optional<Parsed> fromChunkData(Object chunkData) {
        if (!(chunkData instanceof Map<?, ?> payload)) {
            return Optional.empty();
        }
        return parse(payload);
    }

    private static Optional<Parsed> parse(Map<?, ?> payload) {
        Object contextObj = payload.get("context");
        if (!(contextObj instanceof Map<?, ?> ctx)) {
            return Optional.empty();
        }
        if (!A2A_DELEGATE_KIND.equals(ctx.get("_interrupt_kind"))) {
            return Optional.empty();
        }
        Object agentName = payload.get("agentName");
        if (!(agentName instanceof String s) || s.isBlank()) {
            return Optional.empty();
        }
        Object rc = payload.get("responseContent");
        String responseContent = rc instanceof String str ? str : "";
        return Optional.of(new Parsed(s, responseContent));
    }

    static Map<String, Object> buildSyntheticPayload(String agentName, String responseContent,
                                                     String userQuery, boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // type + toolCallId are required by A2AEnabledServeOrchestrator.isCoordinatorInterrupt
        // (single-agent path). Without them the orchestrator throws
        // CORE_INTERRUPT_CORRELATION_MISSING. Mirrors the real builder in
        // VersatileResponseExtractor.buildA2aDelegateInterrupt.
        payload.put("type", "__interaction__");
        payload.put("toolCallId", "versatile-delegate-" + java.util.UUID.randomUUID());
        payload.put("agentName", agentName);
        payload.put("responseContent", responseContent);
        payload.put("resume", false);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("_interrupt_kind", A2A_DELEGATE_KIND);
        context.put("agentName", agentName);
        context.put("resume", false);
        payload.put("context", context);
        payload.put("message", userQuery);
        payload.put("_stream_mode", stream ? "sse" : "");
        return payload;
    }
}