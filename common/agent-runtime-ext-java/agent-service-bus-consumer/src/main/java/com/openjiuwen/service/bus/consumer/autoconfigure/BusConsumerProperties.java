/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Provides the BusConsumerProperties component.
 *
 * @since 2026-07-22
 */
@ConfigurationProperties("openjiuwen.service.bus.consumer")
public class BusConsumerProperties {
    private boolean enabled;
    private Tuning tuning = new Tuning();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Tuning getTuning() {
        return tuning;
    }

    public void setTuning(Tuning value) {
        this.tuning = value;
    }

    /**
     * Optional operational tuning. Defaults are suitable for ordinary deployments.
     */
    public static class Tuning {
        private Duration pollInterval = Duration.ofSeconds(1);
        private int payloadMaxInFlight = 16;
        private int bridgeMaxInFlight = 16;
        private int projectionMaxInFlight = 16;
        private int responseRelayMaxAttempts = 5;
        private Duration responseRelayBackoff = Duration.ofMillis(100);
        private Duration repairInterval = Duration.ofSeconds(5);

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration value) {
            this.pollInterval = value;
        }

        public int getPayloadMaxInFlight() {
            return payloadMaxInFlight;
        }

        public void setPayloadMaxInFlight(int value) {
            this.payloadMaxInFlight = value;
        }

        public int getBridgeMaxInFlight() {
            return bridgeMaxInFlight;
        }

        public void setBridgeMaxInFlight(int value) {
            this.bridgeMaxInFlight = value;
        }

        public int getProjectionMaxInFlight() {
            return projectionMaxInFlight;
        }

        public void setProjectionMaxInFlight(int value) {
            this.projectionMaxInFlight = value;
        }

        public int getResponseRelayMaxAttempts() {
            return responseRelayMaxAttempts;
        }

        public void setResponseRelayMaxAttempts(int value) {
            this.responseRelayMaxAttempts = value;
        }

        public Duration getResponseRelayBackoff() {
            return responseRelayBackoff;
        }

        public void setResponseRelayBackoff(Duration value) {
            this.responseRelayBackoff = value;
        }

        public Duration getRepairInterval() {
            return repairInterval;
        }

        public void setRepairInterval(Duration value) {
            this.repairInterval = value;
        }
    }
}
