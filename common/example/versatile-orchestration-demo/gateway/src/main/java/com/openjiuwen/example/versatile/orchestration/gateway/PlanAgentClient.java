/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * Forwards a rebuilt EDPA request to the plan-agent and streams the SSE reply into the caller's
 * output stream. The two implementations select the downstream wire format:
 * <ul>
 *   <li>{@link A2aPlanAgentClient} — default; JSON-RPC {@code SendStreamingMessage} to
 *       {@code <base>/a2a} with INPUT_REQUIRED task-resume state ({@link ResumeStateStore});</li>
 *   <li>{@link RestPlanAgentClient} — opt-in via {@code versatile-orchestration.gateway.plan-agent-protocol=rest};
 *       REST {@code POST <base>/v1/query} routed by {@code conversation_id} (no taskId cache).</li>
 * </ul>
 *
 * <p>The controller depends only on this interface, so the inbound contract (the minimal
 * {@code custom_data} payload, the EDPA envelope rebuilt by {@link EdpaEnvelopeBuilder}, and the
 * SSE reply shaped as EDPA {@code {"event":...,"data":{...}}}) is identical across protocols —
 * callers and tests are mode-agnostic.
 *
 * @since 2026-07-08
 */
public interface PlanAgentClient {
    /**
     * Forward {@code request} (the rebuilt full EDPA envelope) to the plan-agent and pipe its SSE
     * reply into {@code clientOutput}. Blocks until the upstream stream ends.
     *
     * @param request     the rebuilt full EDPA envelope ({@link EdpaEnvelopeBuilder#build})
     * @param headers     inbound transport headers (from the posted {@code custom_data} payload);
     *                    forwarded per the configured whitelist (A2A → {@code metadata.headers},
     *                    REST → HTTP headers)
     * @param queryParams inbound URL query params ({@code workspace_id}, {@code type}); A2A carries
     *                    them in {@code metadata.query}, REST appends them to the {@code /v1/query}
     *                    URL query string
     * @param clientOutput the response output stream to write SSE {@code data:} frames into
     * @throws IOException          if writing to {@code clientOutput} or reading the upstream fails
     * @throws InterruptedException if the calling thread is interrupted while streaming
     */
    void forward(EdpaRequest request, Map<String, Object> headers, Map<String, Object> queryParams,
                 OutputStream clientOutput) throws IOException, InterruptedException;
}
