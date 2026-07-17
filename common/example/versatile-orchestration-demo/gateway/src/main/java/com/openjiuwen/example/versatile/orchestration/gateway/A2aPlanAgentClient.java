/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import org.a2aproject.sdk.spec.MessageSendParams;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * The default {@link PlanAgentClient}: translate the EDPA envelope into a JSON-RPC
 * {@code SendStreamingMessage} (via {@link EdpaRequestTranslator}, which injects the resume
 * {@code taskId} from {@link ResumeStateStore}) and stream it to {@code <base>/a2a} (via
 * {@link A2aStreamingClient}, which consumes status frames for resume bookkeeping and normalizes
 * artifact frames into EDPA {@code {event,data}} envelopes).
 *
 * <p>Active unless {@code versatile-orchestration.gateway.plan-agent-protocol=rest}. The two composed
 * collaborators remain plain {@code @Component}s, so their own unit tests are unaffected; this
 * class is the one-line seam that the controller depends on.
 *
 * @since 2026-07-08
 */
@Component
@ConditionalOnProperty(prefix = "versatile-orchestration.gateway", name = "plan-agent-protocol",
        havingValue = "a2a", matchIfMissing = true)
public class A2aPlanAgentClient implements PlanAgentClient {
    private final EdpaRequestTranslator translator;
    private final A2aStreamingClient streaming;

    public A2aPlanAgentClient(EdpaRequestTranslator translator, A2aStreamingClient streaming) {
        this.translator = translator;
        this.streaming = streaming;
    }

    @Override
    public void forward(EdpaRequest request, Map<String, Object> headers, Map<String, Object> queryParams,
                        OutputStream clientOutput) throws IOException, InterruptedException {
        MessageSendParams outbound = translator.translate(request, headers, queryParams);
        streaming.streamPost(outbound, clientOutput);
    }
}
