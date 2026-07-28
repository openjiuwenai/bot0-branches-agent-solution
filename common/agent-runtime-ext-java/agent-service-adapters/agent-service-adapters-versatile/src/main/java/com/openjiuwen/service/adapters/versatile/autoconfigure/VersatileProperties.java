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
    private List<Intent> intents = new ArrayList<>();
    private Messages messages = new Messages();
    private Map<String, List<MappingCandidate>> intentAgentMapping = new LinkedHashMap<>();
    private IntentAgentMappingStrategy intentAgentMappingStrategy = IntentAgentMappingStrategy.FIRST;
    private List<ResultExtraction> resultExtractions = new ArrayList<>();
    private Interrupt interrupt = new Interrupt();
    private boolean logMaskSensitive = true;
    private String ambiguousIntentId = "1";
    private DefaultWorkflow defaultWorkflow = new DefaultWorkflow();

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

    public List<Intent> getIntents() {
        return intents;
    }

    public void setIntents(List<Intent> intents) {
        this.intents = intents != null ? new ArrayList<>(intents) : new ArrayList<>();
    }

    public Messages getMessages() {
        return messages;
    }

    public void setMessages(Messages messages) {
        this.messages = messages != null ? messages : new Messages();
    }

    public Map<String, List<MappingCandidate>> getIntentAgentMapping() {
        return intentAgentMapping;
    }

    public void setIntentAgentMapping(Map<String, List<MappingCandidate>> intentAgentMapping) {
        this.intentAgentMapping = intentAgentMapping != null
                ? new LinkedHashMap<>(intentAgentMapping) : new LinkedHashMap<>();
    }

    public IntentAgentMappingStrategy getIntentAgentMappingStrategy() {
        return intentAgentMappingStrategy;
    }

    public void setIntentAgentMappingStrategy(IntentAgentMappingStrategy intentAgentMappingStrategy) {
        this.intentAgentMappingStrategy = intentAgentMappingStrategy != null
                ? intentAgentMappingStrategy : IntentAgentMappingStrategy.FIRST;
    }

    public List<ResultExtraction> getResultExtractions() {
        return resultExtractions;
    }

    public void setResultExtractions(List<ResultExtraction> resultExtractions) {
        this.resultExtractions = resultExtractions != null ? new ArrayList<>(resultExtractions) : new ArrayList<>();
    }

    public Interrupt getInterrupt() {
        return interrupt;
    }

    public void setInterrupt(Interrupt interrupt) {
        this.interrupt = interrupt != null ? interrupt : new Interrupt();
    }

    /**
     * Whether to mask sensitive fields ({@code messages[].content},
     * {@code response_content}, metadata values) in DEBUG logs. Defaults to
     * {@code true} per L2 §4.6.3 / DFX-001. Set to {@code false} only for
     * local debugging where the log sink is already access-controlled.
     *
     * @return true if sensitive fields should be masked in logs
     */
    public boolean isLogMaskSensitive() {
        return logMaskSensitive;
    }

    public void setLogMaskSensitive(boolean logMaskSensitive) {
        this.logMaskSensitive = logMaskSensitive;
    }

    public String getAmbiguousIntentId() {
        return ambiguousIntentId;
    }

    public void setAmbiguousIntentId(String ambiguousIntentId) {
        this.ambiguousIntentId = ambiguousIntentId;
    }

    public DefaultWorkflow getDefaultWorkflow() {
        return defaultWorkflow;
    }

    public void setDefaultWorkflow(DefaultWorkflow defaultWorkflow) {
        this.defaultWorkflow = defaultWorkflow != null ? defaultWorkflow : new DefaultWorkflow();
    }

    /**
     * Candidate intent for the workflow input {@code intents} array.
     */
    public static class Intent {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * Controls how the {@code messages} input array is sourced from the serve request.
     */
    public static class Messages {
        private String source = "serve_request_messages";
        private boolean required = true;

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    /**
     * One candidate agentCard entry in the {@code intent-agent-mapping} list.
     */
    public static class MappingCandidate {
        private String agentCard;
        private int priority = 0;

        public String getAgentCard() {
            return agentCard;
        }

        public void setAgentCard(String agentCard) {
            this.agentCard = agentCard;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }
    }

    /**
     * Strategy for selecting one agentCard from a 1:N candidate list.
     */
    public enum IntentAgentMappingStrategy {
        FIRST,
        PRIORITY,
        ROUND_ROBIN
    }

    /**
     * One three-field result extraction rule: extract value at {@code get} JSON path
     * and store under {@code match} key.
     */
    public static class ResultExtraction {
        private String match;
        private String get;

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public String getGet() {
            return get;
        }

        public void setGet(String get) {
            this.get = get;
        }
    }

    /**
     * Explicit user-interaction interrupt detection and resume mapping.
     */
    public static class Interrupt {
        private String signalMatch;
        private String promptGet;
        private String inputRequirementGet;
        private String resumeTokenGet;
        private ResumeRequestTemplate resumeRequestTemplate;

        public String getSignalMatch() {
            return signalMatch;
        }

        public void setSignalMatch(String signalMatch) {
            this.signalMatch = signalMatch;
        }

        public String getPromptGet() {
            return promptGet;
        }

        public void setPromptGet(String promptGet) {
            this.promptGet = promptGet;
        }

        public String getInputRequirementGet() {
            return inputRequirementGet;
        }

        public void setInputRequirementGet(String inputRequirementGet) {
            this.inputRequirementGet = inputRequirementGet;
        }

        public String getResumeTokenGet() {
            return resumeTokenGet;
        }

        public void setResumeTokenGet(String resumeTokenGet) {
            this.resumeTokenGet = resumeTokenGet;
        }

        public ResumeRequestTemplate getResumeRequestTemplate() {
            return resumeRequestTemplate;
        }

        public void setResumeRequestTemplate(ResumeRequestTemplate resumeRequestTemplate) {
            this.resumeRequestTemplate = resumeRequestTemplate;
        }
    }

    /**
     * Optional template for building the resume request body when the workflow's
     * resume endpoint differs from the new-call endpoint.
     */
    public static class ResumeRequestTemplate {
        private Map<String, Object> body = new LinkedHashMap<>();

        public Map<String, Object> getBody() {
            return body;
        }

        public void setBody(Map<String, Object> body) {
            this.body = body != null ? new LinkedHashMap<>(body) : new LinkedHashMap<>();
        }
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

    /**
     * Configuration for the L2 self-heal default workflow. When {@code agent-card}
     * is set, the extractor emits an {@code a2a_delegate} interrupt pointing at
     * this workflow on ambiguous intent; when unset, the extractor falls back to
     * L1 by emitting a {@code VERSATILE_INTENT_AMBIGUOUS} error.
     *
     * @since 2026-07-24
     */
    public static class DefaultWorkflow {
        private String agentCard;

        public String getAgentCard() {
            return agentCard;
        }

        public void setAgentCard(String agentCard) {
            this.agentCard = agentCard;
        }
    }
}
