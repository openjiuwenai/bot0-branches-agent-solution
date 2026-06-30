/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration properties for the Versatile service adapter.
 *
 * @since 2026-06-30
 */
@ConfigurationProperties(prefix = "openjiuwen.service.versatile")
public class VersatileProperties {

    private String urlTemplate;
    private Duration timeout = Duration.ofSeconds(600);
    private Map<String, String> headersTemplate = new LinkedHashMap<>();
    private Set<String> forwardHeaderWhitelist = new LinkedHashSet<>();
    private String resultNodeName;
    private List<Endpoint> endpoints = new ArrayList<>();

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public void setUrlTemplate(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Map<String, String> getHeadersTemplate() {
        return headersTemplate;
    }

    public void setHeadersTemplate(Map<String, String> headersTemplate) {
        this.headersTemplate = headersTemplate != null ? new LinkedHashMap<>(headersTemplate) : new LinkedHashMap<>();
    }

    public Set<String> getForwardHeaderWhitelist() {
        return forwardHeaderWhitelist;
    }

    public void setForwardHeaderWhitelist(Set<String> forwardHeaderWhitelist) {
        this.forwardHeaderWhitelist = forwardHeaderWhitelist != null
                ? new LinkedHashSet<>(forwardHeaderWhitelist) : new LinkedHashSet<>();
    }

    public String getResultNodeName() {
        return resultNodeName;
    }

    public void setResultNodeName(String resultNodeName) {
        this.resultNodeName = resultNodeName;
    }

    public List<Endpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<Endpoint> endpoints) {
        this.endpoints = endpoints != null ? new ArrayList<>(endpoints) : new ArrayList<>();
    }

    /**
     * Endpoint override for intent-specific Versatile routing.
     *
     * @since 2026-06-30
     */
    public static class Endpoint {
        private String intent;
        private String urlTemplate;

        public String getIntent() {
            return intent;
        }

        public void setIntent(String intent) {
            this.intent = intent;
        }

        public String getUrlTemplate() {
            return urlTemplate;
        }

        public void setUrlTemplate(String urlTemplate) {
            this.urlTemplate = urlTemplate;
        }
    }
}
