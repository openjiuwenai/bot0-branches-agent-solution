/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the Versatile controller intent-handoff adapter.
 *
 * <p>Bound to {@code openjiuwen.service.versatile.handoff.*}. Classification and
 * extraction paths have NO defaults — the controller wire format is not yet
 * confirmed, so deployments must configure them explicitly (spec 1.3/6.2).
 *
 * @since 2026-08-19
 */
@ConfigurationProperties(prefix = "openjiuwen.service.versatile.handoff")
public class ControllerHandoffProperties {

    private boolean enabled = false;

    private String selfAgentId;

    private Classify classify;

    private final Fields fields = new Fields();

    private final Target target = new Target();

    private final Signal signal = new Signal();

    private final Loop loop = new Loop();

    private final LoopTraceMetadata loopTraceMetadata = new LoopTraceMetadata();

    private List<String> forwardMetadataKeys = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSelfAgentId() {
        return selfAgentId;
    }

    public void setSelfAgentId(String selfAgentId) {
        this.selfAgentId = selfAgentId;
    }

    public Classify getClassify() {
        return classify;
    }

    public void setClassify(Classify classify) {
        this.classify = classify;
    }

    public Fields getFields() {
        return fields;
    }

    public Target getTarget() {
        return target;
    }

    public Signal getSignal() {
        return signal;
    }

    public Loop getLoop() {
        return loop;
    }

    public LoopTraceMetadata getLoopTraceMetadata() {
        return loopTraceMetadata;
    }

    public List<String> getForwardMetadataKeys() {
        return forwardMetadataKeys;
    }

    public void setForwardMetadataKeys(List<String> forwardMetadataKeys) {
        this.forwardMetadataKeys = forwardMetadataKeys;
    }

    /**
     * Fails startup when {@code enabled=true} but the required identification
     * conditions are incomplete (spec 1.3 case 1 / 6.4 / 7.3): at least one
     * complete {@code classify.field-path} + {@code classify.field-value} pair.
     */
    public void validateRequiredIdentifiers() {
        List<String> missing = new ArrayList<>();
        Classify c = classify;
        if (c == null) {
            missing.add("classify.field-path");
            missing.add("classify.field-value");
        } else {
            if (isBlank(c.getFieldPath())) {
                missing.add("classify.field-path");
            }
            if (c.getFieldValue() == null || c.getFieldValue().isEmpty()) {
                missing.add("classify.field-value");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("openjiuwen.service.versatile.handoff.enabled=true requires"
                    + " complete identification conditions; missing: " + String.join(", ", missing)
                    + " (classify.event-type alone is not sufficient)");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static class Classify {
        private String eventType;
        private String fieldPath;
        private List<String> fieldValue = new ArrayList<>();

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        public String getFieldPath() {
            return fieldPath;
        }

        public void setFieldPath(String fieldPath) {
            this.fieldPath = fieldPath;
        }

        public List<String> getFieldValue() {
            return fieldValue;
        }

        public void setFieldValue(List<String> fieldValue) {
            this.fieldValue = fieldValue;
        }
    }

    public static class Fields {
        private String handoffType;
        private String intentId;
        private String businessDomain;
        private String targetAgentId;
        private String dedupKey;

        public String getHandoffType() {
            return handoffType;
        }

        public void setHandoffType(String handoffType) {
            this.handoffType = handoffType;
        }

        public String getIntentId() {
            return intentId;
        }

        public void setIntentId(String intentId) {
            this.intentId = intentId;
        }

        public String getBusinessDomain() {
            return businessDomain;
        }

        public void setBusinessDomain(String businessDomain) {
            this.businessDomain = businessDomain;
        }

        public String getTargetAgentId() {
            return targetAgentId;
        }

        public void setTargetAgentId(String targetAgentId) {
            this.targetAgentId = targetAgentId;
        }

        public String getDedupKey() {
            return dedupKey;
        }

        public void setDedupKey(String dedupKey) {
            this.dedupKey = dedupKey;
        }
    }

    public static class Target {
        private List<String> allowedAgents = new ArrayList<>();
        private List<String> resolutionPriority = new ArrayList<>(List.of("direct", "intent", "domain"));
        private Map<String, String> intentMapping = new LinkedHashMap<>();
        private Map<String, String> domainMapping = new LinkedHashMap<>();

        public List<String> getAllowedAgents() {
            return allowedAgents;
        }

        public void setAllowedAgents(List<String> allowedAgents) {
            this.allowedAgents = allowedAgents;
        }

        public List<String> getResolutionPriority() {
            return resolutionPriority;
        }

        public void setResolutionPriority(List<String> resolutionPriority) {
            this.resolutionPriority = resolutionPriority;
        }

        public Map<String, String> getIntentMapping() {
            return intentMapping;
        }

        public void setIntentMapping(Map<String, String> intentMapping) {
            this.intentMapping = intentMapping;
        }

        public Map<String, String> getDomainMapping() {
            return domainMapping;
        }

        public void setDomainMapping(Map<String, String> domainMapping) {
            this.domainMapping = domainMapping;
        }
    }

    /**
     * Upstream signal configuration (二级退回一级): controller handoff types listed
     * here produce the {@link com.openjiuwen.service.adapters.versatile.controller.handoff.HandoffSignals}
     * not-in-scope marker instead of any outbound call — the adapter answers its
     * caller, and re-routing happens upstream (chained L1 re-recognition or caller
     * decision), never via a reverse L2&rarr;L1 invocation.
     */
    public static class Signal {
        private List<String> handoffTypes = new ArrayList<>();

        public List<String> getHandoffTypes() {
            return handoffTypes;
        }

        public void setHandoffTypes(List<String> handoffTypes) {
            this.handoffTypes = handoffTypes;
        }
    }

    public static class Loop {
        private int maxRouteTraceHops = 8;

        public int getMaxRouteTraceHops() {
            return maxRouteTraceHops;
        }

        public void setMaxRouteTraceHops(int maxRouteTraceHops) {
            this.maxRouteTraceHops = maxRouteTraceHops;
        }
    }

    public static class LoopTraceMetadata {
        private String hopCountKey = "handoffHopCount";
        private String routeTraceKey = "handoffRouteTrace";
        private String sourceAgentKey = "sourceAgentId";

        public String getHopCountKey() {
            return hopCountKey;
        }

        public void setHopCountKey(String hopCountKey) {
            this.hopCountKey = hopCountKey;
        }

        public String getRouteTraceKey() {
            return routeTraceKey;
        }

        public void setRouteTraceKey(String routeTraceKey) {
            this.routeTraceKey = routeTraceKey;
        }

        public String getSourceAgentKey() {
            return sourceAgentKey;
        }

        public void setSourceAgentKey(String sourceAgentKey) {
            this.sourceAgentKey = sourceAgentKey;
        }
    }
}
