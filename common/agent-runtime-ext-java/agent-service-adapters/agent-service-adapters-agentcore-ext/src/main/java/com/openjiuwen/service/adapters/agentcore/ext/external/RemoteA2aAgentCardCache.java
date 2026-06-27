/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class RemoteA2aAgentCardCache {

    private static final Logger log = LoggerFactory.getLogger(RemoteA2aAgentCardCache.class);
    private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofSeconds(30);
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "remoteInput", Map.of(
                            "type", "string",
                            "description", "Text to send as the remote A2A user message.")),
            "required", List.of("remoteInput"),
            "additionalProperties", true);

    private final A2ARemoteAgentCardRegistry registry;
    private final ScheduledExecutorService retryExecutor;
    private final Duration retryInterval;
    private final Object refreshMonitor = new Object();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);

    private volatile Map<String, Entry> entries;
    private volatile ScheduledFuture<?> retryTask;

    public RemoteA2aAgentCardCache(RemoteA2aProperties properties, A2ARemoteAgentCardRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.retryExecutor = newDaemonScheduler();
        this.retryInterval = DEFAULT_RETRY_INTERVAL;
        this.entries = initialEntries(properties);
    }

    public List<RemoteA2aToolSpec> availableToolSpecs() {
        return entries.values().stream()
                .filter(entry -> entry.state() == State.READY && entry.spec() != null)
                .map(Entry::spec)
                .toList();
    }

    public void refreshPendingOnce() {
        refresh(State.PENDING);
    }

    private void retryFailedOnce() {
        refresh(State.FAILED);
    }

    public void scheduleRetryUntilAllReady() {
        if (entries.values().stream().noneMatch(entry -> entry.state() == State.FAILED)) {
            stopRetry();
            return;
        }
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        retryTask = retryExecutor.scheduleWithFixedDelay(
                this::retryFailedAndStopIfReady,
                retryInterval.toMillis(),
                retryInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void retryFailedAndStopIfReady() {
        retryFailedOnce();
        if (entries.values().stream().noneMatch(entry -> entry.state() == State.FAILED)) {
            stopRetry();
        }
    }

    private void stopRetry() {
        ScheduledFuture<?> currentTask = retryTask;
        if (currentTask != null) {
            currentTask.cancel(false);
            retryTask = null;
        }
        scheduled.set(false);
    }

    private void refresh(State targetState) {
        synchronized (refreshMonitor) {
            Map<String, Entry> current = this.entries;
            Map<String, Entry> updated = new LinkedHashMap<>(current);
            Map<String, Integer> usedNames = current.values().stream()
                    .filter(entry -> entry.state() == State.READY && entry.spec() != null)
                    .collect(Collectors.toMap(
                            entry -> entry.spec().toolName(),
                            entry -> 1,
                            Integer::sum,
                            LinkedHashMap::new));
            for (Entry entry : current.values()) {
                if (entry.state() != targetState) {
                    continue;
                }
                updated.put(entry.key(), refreshOne(entry, usedNames));
            }
            this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(updated));
        }
    }

    private Entry refreshOne(Entry entry, Map<String, Integer> usedNames) {
        try {
            AgentCard card = fetchAgentCard(entry.url());
            validateCallableInterface(card);
            RemoteA2aToolSpec spec = toSpec(entry, card, usedNames);
            if (spec == null) {
                log.info("Remote A2A card '{}' has no skills; no tool will be installed", card.name());
                return entry.ready(null);
            }
            registry.register(spec.remoteAgentId(), card);
            return entry.ready(spec);
        } catch (Exception ex) {
            log.warn("Failed to discover remote A2A card at {}", entry.url(), ex);
            return entry.failed();
        }
    }

    private static RemoteA2aToolSpec toSpec(Entry entry, AgentCard card, Map<String, Integer> usedNames) {
        if (card.skills() == null || card.skills().isEmpty()) {
            return null;
        }
        String baseName;
        if (entry.configuredName() != null && !entry.configuredName().isBlank()) {
            baseName = entry.configuredName().trim();
            if (!baseName.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Invalid remote A2A tool name: " + baseName);
            }
        } else {
            baseName = normalizeName(firstNonBlank(card.name(), "remote-agent"));
        }
        String remoteAgentId = dedupe(baseName, usedNames);
        String description = card.skills().stream()
                .map(RemoteA2aAgentCardCache::describe)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n"));
        if (description.isBlank()) {
            description = firstNonBlank(card.description(), "Delegate to remote A2A agent.");
        }
        return new RemoteA2aToolSpec(remoteAgentId, remoteAgentId, description, INPUT_SCHEMA);
    }

    private static String describe(AgentSkill skill) {
        if (skill.description() != null && !skill.description().isBlank()) {
            return skill.description();
        }
        return firstNonBlank(skill.name(), skill.id(), "");
    }

    private static String dedupe(String baseName, Map<String, Integer> usedNames) {
        int count = usedNames.getOrDefault(baseName, 0) + 1;
        usedNames.put(baseName, count);
        if (count == 1) {
            return baseName;
        }
        String candidate;
        do {
            candidate = baseName + "-" + count;
            count++;
        } while (usedNames.containsKey(candidate));
        usedNames.put(candidate, 1);
        return candidate;
    }

    private static void validateCallableInterface(AgentCard card) {
        if (card.supportedInterfaces() == null || card.supportedInterfaces().isEmpty()
                || card.supportedInterfaces().get(0).url() == null
                || card.supportedInterfaces().get(0).url().isBlank()) {
            throw new IllegalStateException("AgentCard has no supportedInterfaces[0].url");
        }
    }

    private static Map<String, Entry> initialEntries(RemoteA2aProperties properties) {
        Map<String, Entry> result = new LinkedHashMap<>();
        if (properties == null || properties.getRemoteA2a() == null) {
            return result;
        }
        for (RemoteA2aProperties.Agent agent : properties.getRemoteA2a().getAgents()) {
            if (agent.getUrl() == null || agent.getUrl().isBlank()) {
                continue;
            }
            String key = normalizeUrlKey(agent.getUrl());
            result.put(key, Entry.pending(key, agent.getUrl(), agent.getName()));
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static String normalizeUrlKey(String url) {
        String trimmed = url.trim();
        for (String suffix : List.of("/.well-known/agent-card.json", "/.well-known/agent.json")) {
            if (trimmed.endsWith(suffix)) {
                return trimmed.substring(0, trimmed.length() - suffix.length());
            }
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static AgentCard fetchAgentCard(String configuredUrl) {
        return RestClient.create()
                .get()
                .uri(cardUri(configuredUrl))
                .retrieve()
                .body(AgentCard.class);
    }

    private static URI cardUri(String configuredUrl) {
        String normalized = configuredUrl.trim();
        if (normalized.endsWith("/.well-known/agent-card.json")
                || normalized.endsWith("/.well-known/agent.json")) {
            return URI.create(normalized);
        }
        String base = normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
        return URI.create(base + "/.well-known/agent-card.json");
    }

    private static ScheduledExecutorService newDaemonScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "remote-a2a-card-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "remote-agent" : normalized;
    }

    private enum State {
        PENDING,
        READY,
        FAILED
    }

    private record Entry(String key, String url, String configuredName, State state, RemoteA2aToolSpec spec) {
        static Entry pending(String key, String url, String configuredName) {
            return new Entry(key, url, configuredName, State.PENDING, null);
        }

        Entry ready(RemoteA2aToolSpec spec) {
            return new Entry(key, url, configuredName, State.READY, spec);
        }

        Entry failed() {
            return new Entry(key, url, configuredName, State.FAILED, null);
        }
    }

    public record RemoteA2aToolSpec(String remoteAgentId, String toolName, String description,
                                    Map<String, Object> inputSchema) {
    }
}
