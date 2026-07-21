/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub;

import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;
import com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates skill download / verify / register with background retry and
 * uninstalled/installed list maintenance (FEAT-005 §2.3, §4.1).
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Constructor → {@code provider.start(config, token)} + trigger first download</li>
 *   <li>download() success → verify each downloaded path → add verified paths to
 *       the "uninstalled list"</li>
 *   <li>download() failure → start background thread that retries download on a
 *       fixed schedule; on success verify + add to "uninstalled list"</li>
 *   <li>{@link #register(Object)} (called from query/streamQuery request thread) —
 *       if the uninstalled list is non-empty, delegate to
 *       {@link SkillHubInstaller#install(Object, List)} and move paths to the
 *       installed list</li>
 *   <li>{@link #reregister(Object)} — clear installed list and re-register everything</li>
 *   <li>{@link #stop()} — stop background thread + {@code provider.stop()}</li>
 * </ul>
 *
 * <p>Thread-safety:
 * <ul>
 *   <li>The background thread only touches download + verify + the uninstalled list
 *       (CopyOnWriteArrayList). It never touches agent-core's SkillManager.</li>
 *   <li>Registration is strictly executed on the request thread to avoid the
 *       non-thread-safe SkillManager.</li>
 * </ul>
 *
 * <p>Failure semantics (PR #415):
 * <ul>
 *   <li>start() phase download/verify failures are degraded (agent ready, skill
 *       unavailable) and retried in background; start() is never blocked</li>
 *   <li>register(agent) phase install failures (INSTALL_FAILED) throw on the
 *       request thread so the request can observe the error</li>
 * </ul>
 *
 * @since 2026-07-15
 */
public class SkillHubManager {

    private static final Logger log = LoggerFactory.getLogger(SkillHubManager.class);

    /** Fixed retry interval; FEAT-005 does not fix a strategy, this is a simple default. */
    private static final long RETRY_INITIAL_DELAY_MS = 5000L;
    private static final long RETRY_PERIOD_MS = 30000L;

    private final SkillHubProvider provider;
    private final SkillHubInstaller installer;
    private final SkillHubConfig config;
    private final String decryptedToken;

    /**
     * Lock guarding compound operations on {@link #verifiedSkillPaths} and
     * {@link #registeredList}. Single-element array so the monitor is final
     * (the array reference never changes; its single slot holds the monitor).
     *
     * <p>Why a separate lock instead of synchronizing on the lists themselves:
     * the two lists are mutated together (move path from unregistered to
     * registered) and the lock must protect the compound operation across
     * both. Synchronizing on one list wouldn't guard mutations of the other.
     *
     * <p>What the lock protects:
     * <ul>
     *   <li>{@code register}: snapshot unregistered → release lock → install →
     *       re-acquire lock → move snapshot paths to registered</li>
     *   <li>{@code reregister}: snapshot both → clear → release lock → install →
     *       re-acquire lock → add to registered</li>
     *   <li>{@code scanAndVerifyLocalDir} (background thread): contains-check
     *       + addIfAbsent per candidate</li>
     * </ul>
     *
     * <p>The lock is intentionally NOT held during {@code installer.install} /
     * {@code provider.download} / {@code provider.verify} because those calls
     * touch agent-core's non-thread-safe SkillManager and may block; holding
     * the lock would let a background retry starve request-thread registration.
     */
    private final Object listLock = new Object();

    /**
     * Paths downloaded + verified, available to be handed to the next agent that
     * asks. This list is GLOBAL across agents because skill files on disk are
     * shared; registration into agent-core's per-agent SkillManager is what
     * makes a skill agent-private (see {@link SkillHubInstaller}).
     *
     * <p>A path leaves this list only when it has been successfully handed to
     * EVERY agent that will ever ask — which is unknowable in general. So we
     * instead keep the path here permanently and rely on per-agent bookkeeping
     * ({@link #processedForAgent}) to avoid re-installing into the same agent
     * twice. See issue #10.
     */
    private final CopyOnWriteArrayList<Path> verifiedSkillPaths = new CopyOnWriteArrayList<>();

    /**
     * Per-agent set of paths already handed to that agent (success OR
     * INSTALL_FAILED). Keyed by agent reference via {@link WeakHashMap} so
     * agent instances can be GC'd once they're no longer reachable elsewhere.
     * Access guarded by {@link #listLock}.
     *
     * <p>Why per-agent, not global: agent-core's SkillManager is agent-private;
     * a skill registered into agent A is invisible to agent B. So the Manager
     * must hand the same path to each agent that asks, and per-agent tracking
     * is exactly what prevents duplicate registration into the same agent.
     * See issue #10.
     *
     * <p>Why this also covers INSTALL_FAILED (#1): a failed handover is still
     * "processed for this agent" — re-attempting it on every subsequent request
     * would just re-throw the same exception forever. The path stays in
     * {@link #verifiedSkillPaths} so other agents can still try, but this agent
     * won't be bothered again.
     */
    private final WeakHashMap<Object, Set<Path>> processedForAgent = new WeakHashMap<>();

    private final AtomicBoolean firstDownloadTriggered = new AtomicBoolean(false);
    private final AtomicBoolean backgroundRetryStarted = new AtomicBoolean(false);

    private final Object bgLock = new Object();
    private ScheduledExecutorService backgroundExecutor;

    /**
     * Construct the manager. The provider is started and the first download is
     * triggered here per design doc §3.2 / §4.7.
     */
    public SkillHubManager(SkillHubProvider provider,
                           SkillHubInstaller installer,
                           SkillHubConfig config,
                           String decryptedToken) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.config = Objects.requireNonNull(config, "config");
        this.decryptedToken = decryptedToken == null ? "" : decryptedToken;
        this.provider.start(this.config, this.decryptedToken);
        log.info("SkillHub manager initialized credential={}",
                this.decryptedToken.isEmpty() ? "absent" : "provided");
    }

    /**
     * Trigger download (synchronous, used from Handler.start()).
     *
     * <p>Idempotent: only the first call triggers a real download; subsequent
     * calls are no-ops (background retry handles ongoing failures).
     *
     * <p><b>Singleton caveat (issue #4):</b> SkillHubManager is a Spring
     * singleton bean, so {@code firstDownloadTriggered} is process-global.
     * If the handler is re-created or {@code start()} is invoked again after
     * a reload, this method will silently no-op — the download cycle started
     * at construction time is the only one. Callers must not assume
     * {@code download()} re-triggers on every {@code handler.start()}.
     *
     * <p><b>Blocking (issue #8):</b> the first call is synchronous and may
     * take up to {@code REQUEST_TIMEOUT} per skill (plus download time).
     * Call {@code handler.start()} during container startup, NOT on the
     * first request path, otherwise the request thread will block on
     * download. Failures are swallowed and retried in background per PR #415.
     */
    public void download() {
        if (!firstDownloadTriggered.compareAndSet(false, true)) {
            return;
        }
        try {
            boolean ok = doDownloadAndVerify();
            if (!ok) {
                startBackgroundRetry();
            }
        } catch (RuntimeException ex) {
            log.warn("SkillHub first download failed, will retry in background reason={}",
                    ex.getMessage());
            startBackgroundRetry();
        }
    }

    /**
     * Register pending skills to the agent instance (called from
     * query/streamQuery request thread).
     *
     * <p>Snapshots all paths in {@link #verifiedSkillPaths} that have not yet been
     * processed for THIS agent, delegates to
     * {@link SkillHubInstaller#install(Object, List)}, and marks each path as
     * processed-for-this-agent (regardless of success or INSTALL_FAILED).
     *
     * <p>Why paths stay in {@code verifiedSkillPaths} after a successful install
     * (issue #10): agent-core's SkillManager is agent-private; a skill
     * registered into agent A is invisible to agent B. Since SkillHubManager
     * is a singleton bean shared across agents, the same downloaded skill
     * directory must be handed to every agent that asks. We track
     * "already installed for this agent" via a per-agent set keyed by
     * WeakHashMap so duplicate registration into the same agent is avoided
     * while different agents can still pick the skill up.
     *
     * <p>Why INSTALL_FAILED paths are still marked processed-for-this-agent
     * (issue #1): a failed handover (e.g. SkillManager rejected the skill)
     * won't fix itself by retrying on every subsequent request from the same
     * agent — re-attempting would just re-throw the same exception forever,
     * turning a single-skill failure into a permanent agent outage. Marking
     * it processed means the next request from the same agent is a no-op,
     * letting the request proceed without the skill (degraded service). Other
     * agents still get to try their own handover since the path remains in
     * {@code verifiedSkillPaths}.
     *
     * <p>Thread-safety: the snapshot and the per-agent update are each guarded
     * by {@link #listLock}, but {@code installer.install} runs WITHOUT the
     * lock so the background retry thread can keep updating
     * {@code verifiedSkillPaths} while the request thread is installing
     * (SkillManager is non-thread-safe and must only be touched on the
     * request thread; holding listLock during install would also let
     * background retry starve request registration).
     *
     * @throws IllegalStateException when installer reports INSTALL_FAILED
     *         (required skill handover failure) — thrown AFTER the failing
     *         path has been marked processed-for-this-agent so a subsequent
     *         request from the same agent doesn't re-throw
     */
    public void register(Object agent) {
        List<Path> toInstall;
        synchronized (listLock) {
            if (verifiedSkillPaths.isEmpty()) {
                return;
            }
            Set<Path> processed = processedForAgent.get(agent);
            if (processed != null && processed.size() == verifiedSkillPaths.size()) {
                // All known paths already processed for this agent — nothing to do.
                return;
            }
            toInstall = new ArrayList<>();
            for (Path p : verifiedSkillPaths) {
                if (processed == null || !processed.contains(p)) {
                    toInstall.add(p);
                }
            }
            if (toInstall.isEmpty()) {
                return;
            }
        }
        // install() may throw INSTALL_FAILED — we still mark these paths as
        // processed-for-this-agent below so the same agent isn't retried.
        RuntimeException installError = null;
        try {
            installer.install(agent, toInstall);
        } catch (RuntimeException ex) {
            installError = ex;
        }
        synchronized (listLock) {
            Set<Path> processed = processedForAgent.get(agent);
            if (processed == null) {
                processed = new HashSet<>();
                processedForAgent.put(agent, processed);
            }
            for (Path p : toInstall) {
                processed.add(p);
            }
        }
        if (installError != null) {
            throw installError;
        }
        log.info("SkillHub register completed forAgent={} registered={} verifiedTotal={}",
                Integer.toHexString(System.identityHashCode(agent)),
                toInstall.size(), verifiedSkillPaths.size());
    }

    /**
     * Re-register all skills (clear per-agent processed set and register everything again).
     * Used for hot-reload scenarios.
     */
    public void reregister(Object agent) {
        List<Path> all;
        synchronized (listLock) {
            all = new ArrayList<>(verifiedSkillPaths);
            // Clear per-agent processed set so the same agent gets all skills again.
            processedForAgent.remove(agent);
        }
        if (all.isEmpty()) {
            return;
        }
        installer.install(agent, all);
        synchronized (listLock) {
            Set<Path> processed = processedForAgent.get(agent);
            if (processed == null) {
                processed = new HashSet<>();
                processedForAgent.put(agent, processed);
            }
            processed.addAll(all);
        }
    }

    /**
     * Stop: terminate background thread + provider.stop().
     */
    public void stop() {
        synchronized (bgLock) {
            if (backgroundExecutor != null) {
                backgroundExecutor.shutdownNow();
                backgroundExecutor = null;
            }
        }
        try {
            provider.stop();
        } catch (RuntimeException ex) {
            log.warn("SkillHub provider.stop() failed reason={}", ex.getMessage());
        }
    }

    // ----- package-private for testing -----

    List<Path> getVerifiedSkillPaths() {
        synchronized (listLock) {
            return Collections.unmodifiableList(new ArrayList<>(verifiedSkillPaths));
        }
    }

    /**
     * Returns the union of all paths marked processed-for-some-agent across
     * every agent seen so far. Retained for test compatibility; production
     * code should treat the result as "any agent has been handed this skill".
     */
    List<Path> getRegisteredList() {
        synchronized (listLock) {
            Set<Path> union = new HashSet<>();
            for (Set<Path> s : processedForAgent.values()) {
                union.addAll(s);
            }
            return Collections.unmodifiableList(new ArrayList<>(union));
        }
    }

    /**
     * Test probe: true while a background retry executor is running OR while
     * {@link #backgroundRetryStarted} is still set (i.e. retry has not yet
     * been reset after success).
     */
    boolean isBackgroundRetryActiveForTest() {
        synchronized (bgLock) {
            return backgroundExecutor != null || backgroundRetryStarted.get();
        }
    }

    // ----- internal helpers -----

    /**
     * Execute provider.download + verify cycle. Returns true if download fully
     * succeeded and at least one path was verified.
     */
    private boolean doDownloadAndVerify() {
        boolean downloadOk;
        try {
            downloadOk = provider.download(config, decryptedToken);
        } catch (RuntimeException ex) {
            log.warn("SkillHub download threw category={} reason={}",
                    categoryOf(ex), ex.getMessage());
            return false;
        }
        if (!downloadOk) {
            log.warn("SkillHub download returned false (partial/total failure)");
            return false;
        }
        scanAndVerifyLocalDir();
        synchronized (listLock) {
            return !verifiedSkillPaths.isEmpty();
        }
    }

    /**
     * Scan config.localDir for downloaded skill paths, verify each, add verified
     * paths to the unregistered list.
     */
    private void scanAndVerifyLocalDir() {
        String localDir = config.getLocalDir();
        if (localDir == null || localDir.isBlank()) {
            return;
        }
        Path root = Paths.get(localDir);
        if (!Files.isDirectory(root)) {
            return;
        }
        // After PR #xxx, Provider downloads a zip, sha256-checks it, then extracts it
        // into a directory containing SKILL.md. We scan for directories containing
        // SKILL.md (the agent-core registerRoot contract).
        //
        // Bounded depth (issue #2): the real layout is
        //   localDir/<asset_id>/<extracted_name>/SKILL.md
        // so 4 levels is more than enough. Unbounded Files.walk would otherwise
        // traverse deep dependency trees (e.g. node_modules shipped inside a
        // skill zip) on every background retry, which is wasteful.
        List<Path> candidates;
        try (var stream = Files.walk(root, 4)) {
            candidates = stream
                    .filter(Files::isDirectory)
                    .filter(p -> Files.isReadable(p.resolve("SKILL.md"))
                            || Files.isReadable(p.resolve("Skill.md")))
                    .toList();
        } catch (Exception ex) {
            // Don't abort the whole downloadAndVerify cycle just because the
            // local walk hit an unreadable subdir; log and proceed with an
            // empty candidate list. Other paths already in verifiedSkillPaths
            // remain valid.
            log.warn("SkillHub localDir walk failed reason={}", ex.getMessage());
            return;
        }
        for (Path candidate : candidates) {
            // Skip if already in the global unregistered list — path is already
            // available for every agent to pick up. (No per-agent check here:
            // "already processed for some agent" is not a reason to skip
            // re-adding to the unregistered list, since other agents may still
            // need it. See issue #10.)
            synchronized (listLock) {
                if (verifiedSkillPaths.contains(candidate)) {
                    continue;
                }
            }
            boolean verified;
            try {
                verified = provider.verify(candidate);
            } catch (RuntimeException ex) {
                log.warn("SkillHub verify threw skillPath={} category={} reason={}",
                        sanitize(candidate), categoryOf(ex), ex.getMessage());
                continue;
            }
            if (verified) {
                synchronized (listLock) {
                    verifiedSkillPaths.addIfAbsent(candidate);
                }
            }
        }
    }

    private void startBackgroundRetry() {
        if (!backgroundRetryStarted.compareAndSet(false, true)) {
            return;
        }
        synchronized (bgLock) {
            if (backgroundExecutor != null) {
                return;
            }
            backgroundExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "skillhub-retry");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thr, ex) ->
                        log.error("SkillHub background retry thread uncaught exception thread={} reason={}",
                                thr.getName(), ex.toString()));
                return t;
            });
            backgroundExecutor.scheduleWithFixedDelay(this::retryOnce,
                    RETRY_INITIAL_DELAY_MS, RETRY_PERIOD_MS, TimeUnit.MILLISECONDS);
            log.info("SkillHub background retry started initialDelayMs={} periodMs={}",
                    RETRY_INITIAL_DELAY_MS, RETRY_PERIOD_MS);
        }
    }

    private void retryOnce() {
        try {
            boolean ok = doDownloadAndVerify();
            if (ok) {
                log.info("SkillHub background retry succeeded, stopping retry loop");
                synchronized (bgLock) {
                    if (backgroundExecutor != null) {
                        backgroundExecutor.shutdown();
                        backgroundExecutor = null;
                    }
                    // Reset the guard so a LATER download failure can start a
                    // fresh retry loop. Without this, the first successful retry
                    // permanently disables background retry (issue #3).
                    backgroundRetryStarted.set(false);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("SkillHub background retry failed reason={}", ex.getMessage());
        }
    }

    /** Best-effort category extraction from a SkillHub[CATEGORY] message. */
    private static String categoryOf(RuntimeException ex) {
        if (ex instanceof IllegalStateException
                && ex.getMessage() != null
                && ex.getMessage().startsWith("SkillHub[")) {
            try {
                int start = "SkillHub[".length();
                int end = ex.getMessage().indexOf(']', start);
                if (end > start) {
                    return ex.getMessage().substring(start, end);
                }
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return "UNKNOWN";
    }

    private static String sanitize(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }
}
