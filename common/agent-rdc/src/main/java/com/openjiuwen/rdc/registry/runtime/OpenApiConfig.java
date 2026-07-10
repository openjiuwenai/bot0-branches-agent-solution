/*
 * Copyright (C) 2026 Huawei Technologies Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openjiuwen.rdc.registry.runtime;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for the agent-rdc standalone Spring Boot application
 * (REQ-2026-007). {@code springdoc-openapi-starter-webmvc-ui} 3.0.x
 * autoconfigure picks up this bean and exposes:
 * <ul>
 *   <li>{@code /swagger-ui.html} — Swagger UI (302 → /swagger-ui/index.html)</li>
 *   <li>{@code /v3/api-docs} — OpenAPI 3.1 JSON</li>
 *   <li>{@code /v3/api-docs.yaml} — OpenAPI 3.1 YAML</li>
 * </ul>
 *
 * <p>Programmatic {@code @Bean OpenAPI} is preferred over
 * {@code @OpenAPIDefinition} so the metadata lives in one place and stays
 * free of swagger-annotations on the SPI / controller layer (VR-3 / VR-4 —
 * {@code com.openjiuwen.rdc.spi.registry} stays pure Java per ADR-0160
 * decision 1, and {@code MvpRegistryController} stays annotation-free).
 *
 * <p>{@code servers} / {@code contact} / {@code license} are intentionally
 * omitted: agent-rdc is an internal service consumed by sibling planes, so
 * springdoc's default server derivation (current request URL) and the absence
 * of external consumers make them noise rather than signal (api-metadata
 * decision, VR-2).
 *
 * <p>Authority: REQ-2026-007 step-1 decisions (library-choice / doc-scope /
 * ui-exposure / prod-exposure / api-metadata / openapi-version /
 * external-types-coverage).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agent Registry API")
                        .version("0.1.0")
                        .description("agent-rdc — Agent Registry & Discovery Center："
                                + "agent 注册/注销/发现/路由句柄解析，多租户隔离。"));
    }
}
