/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "openjiuwen.service.agentcore-ext")
public class RemoteA2aProperties {

    private RemoteA2a remoteA2a = new RemoteA2a();

    public RemoteA2a getRemoteA2a() {
        return remoteA2a;
    }

    public void setRemoteA2a(RemoteA2a remoteA2a) {
        this.remoteA2a = remoteA2a != null ? remoteA2a : new RemoteA2a();
    }

    public static class RemoteA2a {
        private List<Agent> agents = new ArrayList<>();

        public List<Agent> getAgents() {
            return agents;
        }

        public void setAgents(List<Agent> agents) {
            this.agents = agents != null ? agents : new ArrayList<>();
        }
    }

    public static class Agent {
        private String url;
        private String name;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
