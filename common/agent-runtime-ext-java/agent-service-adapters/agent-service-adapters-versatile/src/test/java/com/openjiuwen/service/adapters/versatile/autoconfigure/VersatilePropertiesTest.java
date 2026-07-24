/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.autoconfigure;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/**
 * Tests {@link VersatileProperties} binding for the intent workflow config tree.
 *
 * @since 2026-07-22
 */
class VersatilePropertiesTest {
    @Test
    void bindsIntentsMessagesMappingInterruptAndResultExtractions() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                entry("openjiuwen.service.versatile.url-template", "http://h/{conversation_id}"),
                entry("openjiuwen.service.versatile.intents[0].id", "intent_L1_hotel"),
                entry("openjiuwen.service.versatile.intents[0].name", "酒店"),
                entry("openjiuwen.service.versatile.messages.required", "true"),
                entry("openjiuwen.service.versatile.intent-agent-mapping.intent_L1_hotel[0].agent-card",
                        "agent_card_L2_hotel"),
                entry("openjiuwen.service.versatile.intent-agent-mapping-strategy", "first"),
                entry("openjiuwen.service.versatile.result-extractions[0].match", "response_content"),
                entry("openjiuwen.service.versatile.result-extractions[0].get",
                        "/custom_rsp_data/data/response_content"),
                entry("openjiuwen.service.versatile.interrupt.signal-match", "need_user_input"),
                entry("openjiuwen.service.versatile.interrupt.prompt-get", "/data/question"),
                entry("openjiuwen.service.versatile.interrupt.input-requirement-get", "/data/input_schema"),
                entry("openjiuwen.service.versatile.interrupt.resume-token-get", "/data/resume_token")
        ));
        VersatileProperties props = new Binder(source)
                .bind("openjiuwen.service.versatile", VersatileProperties.class).get();

        assertThat(props.getUrlTemplate()).isEqualTo("http://h/{conversation_id}");
        assertThat(props.getIntents()).hasSize(1);
        assertThat(props.getIntents().get(0).getId()).isEqualTo("intent_L1_hotel");
        assertThat(props.getIntents().get(0).getName()).isEqualTo("酒店");
        assertThat(props.getMessages().isRequired()).isTrue();
        assertThat(props.getIntentAgentMapping())
                .containsKey("intent_L1_hotel");
        assertThat(props.getIntentAgentMapping().get("intent_L1_hotel").get(0).getAgentCard())
                .isEqualTo("agent_card_L2_hotel");
        assertThat(props.getIntentAgentMappingStrategy())
                .isEqualTo(VersatileProperties.IntentAgentMappingStrategy.FIRST);
        assertThat(props.getResultExtractions()).hasSize(1);
        assertThat(props.getResultExtractions().get(0).getMatch()).isEqualTo("response_content");
        assertThat(props.getResultExtractions().get(0).getGet())
                .isEqualTo("/custom_rsp_data/data/response_content");
        assertThat(props.getInterrupt().getSignalMatch()).isEqualTo("need_user_input");
        assertThat(props.getInterrupt().getPromptGet()).isEqualTo("/data/question");
    }
}
