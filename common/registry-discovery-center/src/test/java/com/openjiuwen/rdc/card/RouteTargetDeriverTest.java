package com.openjiuwen.rdc.card;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteTargetDeriverTest {

    private static final String BASE = "http://runtime.internal:8090";

    @Test
    void derives_contract_version_from_protocol_version() {
        String card = """
                {
                  "name": "demo",
                  "description": "demo",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [],
                  "supportedInterfaces": [{
                    "protocol": "jsonrpc",
                    "url": "/a2a",
                    "protocolVersion": "2.5.0"
                  }]
                }
                """;
        RouteTargetDeriver.DerivedRoute route = RouteTargetDeriver.derive(BASE, card, "/fallback");
        assertThat(route.contractVersion()).isEqualTo("2.5.0");
        assertThat(route.routeTargetJson()).contains("/a2a");
    }

    @Test
    void relative_interface_url_allowed() {
        String card = minimalCard("/relative");
        assertThat(RouteTargetDeriver.derive(BASE, card, "/fallback").routeTargetJson())
                .contains("/relative");
    }

    @Test
    void foreign_host_interface_url_rejected() {
        String card = minimalCard("http://evil.example/rpc");
        assertThatThrownBy(() -> RouteTargetDeriver.derive(BASE, card, "/fallback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failed to derive route target");
    }

    private static String minimalCard(String interfaceUrl) {
        return """
                {
                  "name": "demo",
                  "description": "demo",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [],
                  "supportedInterfaces": [{"protocol": "jsonrpc", "url": "%s"}]
                }
                """.formatted(interfaceUrl);
    }
}
