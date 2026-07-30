/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * AgentCardValidatorTest coverage.
 *
 * @since 0.1.0 (2026)
 */
class AgentCardValidatorTest {
    @Test
    void valid_jsonrpc_card_passes() {
        String json = """
                {
                  "name": "demo",
                  "description": "demo agent",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [],
                  "supportedInterfaces": [{"protocol": "jsonrpc", "url": "/a2a"}]
                }
                """;
        AgentCardValidator.ValidationResult result = AgentCardValidator.validate(json);
        assertThat(result.valid()).isTrue();
        assertThat(result.capabilityVersion()).isEqualTo("1.0.0");
    }

    @Test
    void missing_jsonrpc_interface_fails() {
        String json = """
                {
                  "name": "demo",
                  "description": "demo agent",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [],
                  "supportedInterfaces": [{"protocol": "grpc", "url": "/rpc"}]
                }
                """;
        AgentCardValidator.ValidationResult result = AgentCardValidator.validate(json);
        assertThat(result.valid()).isFalse();
        assertThat(result.failureCode()).isEqualTo("AGENT_CARD_INVALID");
    }

    @Test
    void missing_capabilities_fails() {
        String json = """
                {
                  "name": "demo",
                  "description": "demo agent",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "skills": [],
                  "supportedInterfaces": [{"protocol": "jsonrpc", "url": "/a2a"}]
                }
                """;
        assertThat(AgentCardValidator.validate(json).valid()).isFalse();
    }

    @Test
    void skill_missing_tags_fails() {
        String json = """
                {
                  "name": "demo",
                  "description": "demo agent",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [{"id": "s1", "name": "n", "description": "d"}],
                  "supportedInterfaces": [{"protocol": "jsonrpc", "url": "/a2a"}]
                }
                """;
        assertThat(AgentCardValidator.validate(json).valid()).isFalse();
    }

    @Test
    void extracts_contract_version_from_interface() {
        String json = """
                {
                  "name": "demo",
                  "description": "demo agent",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [],
                  "supportedInterfaces": [{
                    "protocol": "jsonrpc",
                    "url": "/a2a",
                    "protocolVersion": "3.1.0"
                  }]
                }
                """;
        AgentCardValidator.ValidationResult result = AgentCardValidator.validate(json);
        assertThat(result.valid()).isTrue();
        assertThat(result.contractVersion()).isEqualTo("3.1.0");
    }
}
