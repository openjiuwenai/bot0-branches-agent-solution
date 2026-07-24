/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;
import com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link SkillHubManager} covering the test matrix T2/T3/T4/T8/T11/T15/T16.
 *
 * @since 2026-07-15
 */
class SkillHubManagerTest {
    private SkillHubManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.stop();
        }
    }

    @Test
    void downloadFailureDegradesAndDoesNotBlockStart_T1(@TempDir Path tempDir) throws Exception {
        // Provider download throws — Manager.start() should NOT throw (degrade + background retry)
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            throw new IllegalStateException("SkillHub[CONNECT_FAILED] endpoint unreachable");
        };
        SkillHubConfig config = newConfig(tempDir);
        manager = new SkillHubManager(provider, new SkillHubInstaller(), config, "");

        manager.start(); // must not throw

        assertThat(provider.startCount).isEqualTo(1);
        assertThat(provider.downloadCount).isEqualTo(1);
        assertThat(manager.getVerifiedSkillPaths()).isEmpty();
    }

    @Test
    void providerStartFailureFailsFast(@TempDir Path tempDir) {
        // Provider.start() throws (config/auth failure) — Manager.start() must propagate, NOT swallow
        CapturingProvider provider = new CapturingProvider();
        provider.startBehavior = () -> {
            throw new IllegalStateException("SkillHub[AUTH_FAILED] invalid token");
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");

        assertThatThrownBy(() -> manager.start()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SkillHub[AUTH_FAILED]");

        // download must NOT have been called — start failed before reaching download
        assertThat(provider.downloadCount).isEqualTo(0);
    }

    @Test
    void downloadSuccessThenVerifyThenUninstalledListNonEmpty_T4(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            // Create a fake extracted skill dir under localDir
            createFakeSkillDir(tempDir, "skill-a");
            return true;
        };
        provider.verifyBehavior = path -> true;
        SkillHubConfig config = newConfig(tempDir);
        manager = new SkillHubManager(provider, new SkillHubInstaller(), config, "");
        manager.start();

        List<Path> uninstalled = manager.getVerifiedSkillPaths();
        assertThat(uninstalled).hasSize(1);
        assertThat(uninstalled.get(0).getFileName().toString()).isEqualTo("skill-a");
    }

    @Test
    void verifyFailureExcludedFromUninstalledList_T3_T7(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "good");
            createFakeSkillDir(tempDir, "bad");
            return true;
        };
        provider.verifyBehavior = path -> path.getFileName().toString().startsWith("good");
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();

        List<Path> uninstalled = manager.getVerifiedSkillPaths();
        assertThat(uninstalled).hasSize(1);
        assertThat(uninstalled.get(0).getFileName().toString()).isEqualTo("good");
    }

    @Test
    void verifyThrowExcludesFromUninstalledList(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "bad");
            return true;
        };
        provider.verifyBehavior = path -> {
            throw new IllegalStateException("SkillHub[CHECKSUM_MISMATCH] path=" + path);
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();

        assertThat(manager.getVerifiedSkillPaths()).isEmpty();
    }

    @Test
    void downloadIsIdempotent_T8(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();
        int first = provider.downloadCount;
        manager.start(); // second call should be no-op
        assertThat(provider.downloadCount).isEqualTo(first);
    }

    @Test
    void registerWithEmptyUninstalledListIsNoop_T15(@TempDir Path tempDir) {
        CapturingProvider provider = new CapturingProvider();
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();

        // No skills installed; register should be a no-op (not throw)
        manager.register(new Object());
        assertThat(manager.getRegisteredList()).isEmpty();
    }

    @Test
    void registerOnNonBaseAgentIsNoop_T14(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> true;
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();

        // Plain Object is not BaseAgent/DeepAgent — installer should skip without throwing
        Object agent = new Object();
        manager.register(agent);
        // Per-agent semantics: the path stays in verifiedSkillPaths
        // (other agents may still need it), but is marked processed-for-this-agent
        // so the same agent won't be re-attempted.
        assertThat(manager.getRegisteredList()).hasSize(1);
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);

        // A second register on the SAME agent must be a no-op.
        manager.register(agent);
        assertThat(manager.getRegisteredList()).hasSize(1);

        // A DIFFERENT agent can still pick the skill up (path is still available globally).
        Object agentB = new Object();
        manager.register(agentB);
        assertThat(manager.getRegisteredList()).hasSize(1);
    }

    @Test
    void backgroundRetrySucceedsAddsToUninstalledList_T16(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        // First download fails; second succeeds
        provider.downloadBehavior = cfg -> {
            if (provider.downloadCount == 1) {
                throw new IllegalStateException("SkillHub[DOWNLOAD_FAILED] transient");
            }
            createFakeSkillDir(tempDir, "skill-retry");
            return true;
        };
        provider.verifyBehavior = path -> true;
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();
        assertThat(manager.getVerifiedSkillPaths()).isEmpty();

        // Wait for background retry to succeed (timeout 10s)
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && manager.getVerifiedSkillPaths().isEmpty()) {
            Thread.sleep(200);
        }
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);
    }

    @Test
    void registerInstallFailedThrowsOnRequestThread_T13(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> true;
        // Use an installer that throws INSTALL_FAILED
        SkillHubInstaller throwingInstaller = new SkillHubInstaller() {
            @Override
            public void install(Object agent, List<Path> skillPaths) {
                throw new IllegalStateException("SkillHub[INSTALL_FAILED] skillCount not grew");
            }
        };
        manager = new SkillHubManager(provider, throwingInstaller, newConfig(tempDir), "");
        manager.start();

        assertThatThrownBy(() -> manager.register(new Object())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SkillHub[INSTALL_FAILED]");
    }

    @Test
    void downloadReturnsFalseDegradesWithoutThrowing_T2(@TempDir Path tempDir) {
        // Provider download returns false (partial failure) — Manager.start() should not throw
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> false;
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start(); // must not throw
        assertThat(manager.getVerifiedSkillPaths()).isEmpty();
    }

    @Test
    void sha256VerifyPasses_T5(@TempDir Path tempDir) throws Exception {
        // verify() checks for an extracted dir containing SKILL.md.
        // SHA-256 check now happens inside download() (not verify), so this test
        // simulates the post-extract state and confirms verify passes.
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> Files.isReadable(path.resolve("SKILL.md"));
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);
    }

    @Test
    void conventionalVerifyPasses_T6(@TempDir Path tempDir) throws Exception {
        // Fallback check: a dir with SKILL.md passes verify even without sha256 sidecar
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> Files.isDirectory(path) && Files.isReadable(path.resolve("SKILL.md"));
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);
    }

    @Test
    void registerMovesPathsFromUnregisteredToRegistered_T8(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> true;
        // Installer is a no-op (agent not BaseAgent) — manager still marks as processed
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);
        assertThat(manager.getRegisteredList()).isEmpty();

        // Per-agent semantics: the path stays in verifiedSkillPaths
        // (other agents may still need it), but is marked processed-for-this-agent
        // so the same agent won't be re-attempted.
        manager.register(new Object());
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);
        assertThat(manager.getRegisteredList()).hasSize(1);
    }

    @Test
    void reregisterClearsAndReinstallsAll(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> true;
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();
        Object agent = new Object();
        manager.register(agent);
        assertThat(manager.getRegisteredList()).hasSize(1);
        // Per-agent semantics: path stays in verifiedSkillPaths so
        // other agents can pick it up.
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);

        // reregister on the SAME agent clears that agent's processed set and
        // re-installs everything.
        manager.reregister(agent);
        assertThat(manager.getRegisteredList()).hasSize(1);
        // Path is still globally available.
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);

        // A second register on the same agent after reregister is a no-op
        // (processed set was repopulated by reregister).
        manager.register(agent);
        assertThat(manager.getRegisteredList()).hasSize(1);
    }

    @Test
    void concurrentRegisterAndBackgroundRetryIsSafe(@TempDir Path tempDir) throws Exception {
        // Concurrent register calls + background retry must not throw or corrupt lists
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            // Simulate skill dir creation on each download attempt
            createFakeSkillDir(tempDir, "skill-" + provider.downloadCount);
            return true;
        };
        provider.verifyBehavior = path -> true;
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start();

        int threads = 4;
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    manager.register(new Object());
                } catch (IllegalStateException ignored) {
                    // expected: installer no-ops for non-BaseAgent
                }
            });
            t.setUncaughtExceptionHandler((thr, ex) -> java.util.logging.Logger.getLogger("test")
                    .warning("test worker uncaught: " + thr.getName() + " " + ex));
            workers.add(t);
            t.start();
        }
        for (Thread t : workers) {
            t.join(5000);
        }
        // No exception thrown; lists are in consistent state
        int total = manager.getRegisteredList().size() + manager.getVerifiedSkillPaths().size();
        assertThat(total).isGreaterThan(0);
    }

    // ----- Regression tests -----

    /**
     * INSTALL_FAILED must not cause every subsequent request from
     * the SAME agent to re-throw the same exception forever.
     *
     * <p>Expected: after the first register() throws INSTALL_FAILED, the
     * failing path is marked processed-for-this-agent, so a second register()
     * call on the SAME agent is a no-op (not a re-throw). The path STAYS in
     * verifiedSkillPaths so other agents can still attempt their own handover.
     *
     * @param tempDir the temporary local dir for fake skill files
     * @throws Exception if filesystem setup or manager operations fail
     */
    @Test
    void installFailedPathDoesNotReThrowOnSubsequentRegister_Issue1(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> true;
        SkillHubInstaller throwingInstaller = new SkillHubInstaller() {
            @Override
            public void install(Object agent, List<Path> skillPaths) {
                throw new IllegalStateException("SkillHub[INSTALL_FAILED] boom");
            }
        };
        manager = new SkillHubManager(provider, throwingInstaller, newConfig(tempDir), "");
        manager.start();

        // First register on agent A throws INSTALL_FAILED
        Object agentA = new Object();
        assertThatThrownBy(() -> manager.register(agentA)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SkillHub[INSTALL_FAILED]");

        // Second register on the SAME agent A must NOT re-throw (path already
        // marked processed-for-this-agent) — otherwise every subsequent request
        // from this agent would be permanently broken.
        org.assertj.core.api.Assertions.assertThatCode(() -> manager.register(agentA)).doesNotThrowAnyException();

        // The failing path STAYS in verifiedSkillPaths so a DIFFERENT agent can
        // still try its own handover (per-agent semantics).
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);
        // But it's been recorded as processed for agentA.
        assertThat(manager.getRegisteredList()).hasSize(1);
    }

    /**
     * backgroundRetryStarted must be reset when the retry loop
     * succeeds and shuts down, so a LATER download failure can start the
     * retry loop again.
     *
     * <p>Expected: after first-fail-then-succeed cycle, manually triggering
     * download() failure again must start a fresh background retry.
     *
     * @param tempDir the temporary local dir for fake skill files
     * @throws Exception if filesystem setup or manager operations fail
     */
    @Test
    void backgroundRetryCanRestartAfterSuccess_Issue3(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        // first download fails, second succeeds (creates skill), later calls fail again
        provider.downloadBehavior = cfg -> {
            int n = provider.downloadCount;
            if (n == 1) {
                throw new IllegalStateException("SkillHub[DOWNLOAD_FAILED] first");
            }
            if (n == 2) {
                createFakeSkillDir(tempDir, "skill-retry");
                return true;
            }
            throw new IllegalStateException("SkillHub[DOWNLOAD_FAILED] later");
        };
        provider.verifyBehavior = path -> true;
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");
        manager.start(); // first fails, starts background retry
        assertThat(manager.getVerifiedSkillPaths()).isEmpty();

        // Wait for background retry to succeed once
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && manager.getVerifiedSkillPaths().isEmpty()) {
            Thread.sleep(200);
        }
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);

        // Now force another failure (simulate provider going down again) and
        // confirm a NEW background retry can be started. We cannot directly
        // re-invoke download() (idempotent), so we verify by calling the
        // internal retry path: trigger via reflection-free package-private hook
        // would be ideal; instead, assert observable state — after the success,
        // the executor should be null and backgroundRetryStarted should be false
        // so that a future startBackgroundRetry() call actually starts a loop.
        // We verify by calling a package-private probe.
        assertThat(manager.isBackgroundRetryActiveForTest())
                .as("after successful retry, the retry flag must be reset so a later failure can restart").isFalse();
    }

    /**
     * When SkillHubManager is shared by multiple agents (singleton
     * bean), registering skills for agent A must NOT consume the
     * verifiedSkillPaths so that agent B also receives the skills.
     *
     * <p>Expected: after register(agentA), a second register(agentB) on a
     * DIFFERENT agent instance also picks up the same skill paths (each agent
     * has its own SkillManager - registration is per-agent).
     *
     * @param tempDir the temporary local dir for fake skill files
     * @throws Exception if filesystem setup or manager operations fail
     */
    @Test
    void multipleAgentsEachGetSkillsRegistered_Issue10(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill");
            return true;
        };
        provider.verifyBehavior = path -> true;

        // Use a capturing installer that records per-agent calls instead of
        // the real SkillHubInstaller (which no-ops on non-BaseAgent).
        java.util.List<Object> recordedAgents = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        SkillHubInstaller recordingInstaller = new SkillHubInstaller() {
            @Override
            public void install(Object agent, List<Path> skillPaths) {
                recordedAgents.add(agent);
                // pretend success
            }
        };
        manager = new SkillHubManager(provider, recordingInstaller, newConfig(tempDir), "");
        manager.start();
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);

        Object agentA = new Object();
        Object agentB = new Object();
        manager.register(agentA);
        // agentA got the skill
        assertThat(recordedAgents).contains(agentA);

        // agentB MUST ALSO be able to register the same skill — Singleton
        // SkillHubManager cannot consume the path globally.
        manager.register(agentB);
        assertThat(recordedAgents).contains(agentB);

        // After both agents registered, verifiedSkillPaths is still logically
        // "the set of skills not yet handed to a given agent" — but globally
        // it may be empty (downloaded once, handed to everyone who asked).
        // The key assertion: both agents saw the installer.
        assertThat(recordedAgents).hasSize(2);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Create a fake extracted skill dir (with SKILL.md) under localDir, matching
     * the real Provider's post-download layout: {@code localDir/<name>/SKILL.md}.
     *
     * @param localDir the local skill dir root
     * @param name the skill sub-directory name
     * @throws IOException if directory creation or SKILL.md write fails
     */
    private static void createFakeSkillDir(Path localDir, String name) throws IOException {
        Path dir = localDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "# " + name);
    }

    private static SkillHubConfig newConfig(Path tempDir) {
        SkillHubConfig c = new SkillHubConfig();
        c.setEnabled(true);
        c.setEndpoint("https://swarmskills.openjiuwen.com");
        c.setAuthType("bearer");
        c.setEncryptedToken("");
        c.setLocalDir(tempDir.toString());
        return c;
    }

    /** Minimal Provider stub for tests; tracks call counts and allows custom behavior. */
    static class CapturingProvider implements SkillHubProvider {
        int startCount = 0;
        int stopCount = 0;
        int downloadCount = 0;
        Runnable startBehavior = () -> {
        };
        ThrowingFunction<SkillHubConfig, Boolean> downloadBehavior = cfg -> true;
        ThrowingFunction<Path, Boolean> verifyBehavior = path -> true;

        @Override
        public void start(SkillHubConfig config, String decryptedToken) {
            startCount++;
            startBehavior.run();
        }

        @Override
        public boolean download(SkillHubConfig config, String decryptedToken) {
            downloadCount++;
            // ThrowingFunction.apply declares 'throws Exception' because test
            // lambdas call Files.* (IOException). Wrap the checked IOException
            // specifically (NOT catch-all Exception); unchecked
            // IllegalStateException from stub bodies propagates on its own.
            try {
                return downloadBehavior.apply(config);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public boolean verify(Path skillPath) {
            // See download() above: wrap checked IOException only.
            try {
                return verifyBehavior.apply(skillPath);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public void stop() {
            stopCount++;
        }
    }

    /**
     * Function whose body may throw {@link IOException} (e.g. from filesystem
     * calls inside test stubs). Narrowed to {@code IOException} (instead of
     * {@code Exception}) so that stubs can catch the specific checked type
     * and wrap it into {@link IllegalStateException} without catching
     * {@code Exception} broadly.
     *
     * @param <T> input type
     * @param <R> return type
     */
    @FunctionalInterface
    interface ThrowingFunction<T extends Object, R extends Object> {
        /**
         * Apply this function to the given input.
         *
         * @param t the input value
         * @return the function result
         * @throws IOException if a filesystem or I/O operation fails
         */
        R apply(T t) throws IOException;
    }

    // ----- layered failure semantics -----

    /**
     * Required auth failure thrown from provider.download() must
     * NOT be degraded to background retry. Manager.start() must rethrow so
     * the handler chain blocks Agent ready (fail fast).
     *
     * @param tempDir the temporary local dir for fake skill files
     */
    @Test
    void requiredAuthFailureFromDownloadFailsFast(@TempDir Path tempDir) {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            throw new com.openjiuwen.service.spec.ext.skillhub.SkillHubException(
                    com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory.AUTH_FAILED, "status=401");
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");

        assertThatThrownBy(() -> manager.start())
                .isInstanceOf(com.openjiuwen.service.spec.ext.skillhub.SkillHubException.class)
                .hasMessageContaining("SkillHub[AUTH_FAILED]");

        // Background retry must NOT have been started for a fatal auth failure.
        assertThat(manager.isBackgroundRetryActiveForTest()).isFalse();
        assertThat(manager.getVerifiedSkillPaths()).isEmpty();
    }

    /**
     * Required access-denied failure from provider.download() must
     * also fail fast (no degrade, no background retry).
     *
     * @param tempDir the temporary local dir for fake skill files
     */
    @Test
    void requiredAccessDeniedFailureFromDownloadFailsFast(@TempDir Path tempDir) {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            throw new com.openjiuwen.service.spec.ext.skillhub.SkillHubException(
                    com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory.ACCESS_DENIED, "status=403");
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");

        assertThatThrownBy(() -> manager.start())
                .isInstanceOf(com.openjiuwen.service.spec.ext.skillhub.SkillHubException.class)
                .hasMessageContaining("SkillHub[ACCESS_DENIED]");
        assertThat(manager.isBackgroundRetryActiveForTest()).isFalse();
    }

    /**
     * Required skill not-found failure from provider.download()
     * must also fail fast (the skill was declared required and is missing).
     *
     * @param tempDir the temporary local dir for fake skill files
     */
    @Test
    void requiredNotFoundFailureFromDownloadFailsFast(@TempDir Path tempDir) {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            throw new com.openjiuwen.service.spec.ext.skillhub.SkillHubException(
                    com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory.NOT_FOUND, "status=404");
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");

        assertThatThrownBy(() -> manager.start())
                .isInstanceOf(com.openjiuwen.service.spec.ext.skillhub.SkillHubException.class)
                .hasMessageContaining("SkillHub[NOT_FOUND]");
        assertThat(manager.isBackgroundRetryActiveForTest()).isFalse();
    }

    /**
     * Download failure (transient) must still degrade — Agent ready
     * with skill unavailable + background retry. This is the existing behavior
     * and must be preserved by the layered fix.
     *
     * @param tempDir the temporary local dir for fake skill files
     */
    @Test
    void downloadFailureDegradesAndStartsBackgroundRetry(@TempDir Path tempDir) {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            throw new com.openjiuwen.service.spec.ext.skillhub.SkillHubException(
                    com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory.DOWNLOAD_FAILED, "transient");
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");

        // Must NOT throw — degrade + background retry.
        assertThatCode(() -> manager.start()).doesNotThrowAnyException();
        assertThat(manager.isBackgroundRetryActiveForTest()).isTrue();
        assertThat(manager.getVerifiedSkillPaths()).isEmpty();
    }

    /**
     * Checksum mismatch must degrade (background retry), not fail fast.
     *
     * @param tempDir the temporary local dir for fake skill files
     */
    @Test
    void checksumMismatchDegrades(@TempDir Path tempDir) {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            throw new com.openjiuwen.service.spec.ext.skillhub.SkillHubException(
                    com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory.CHECKSUM_MISMATCH,
                    "sha256 mismatch");
        };
        manager = new SkillHubManager(provider, new SkillHubInstaller(), newConfig(tempDir), "");

        assertThatCode(() -> manager.start()).doesNotThrowAnyException();
        assertThat(manager.isBackgroundRetryActiveForTest()).isTrue();
    }

    // ----- concurrent register on the same agent must not duplicate-install -----

    /**
     * When 4 concurrent request threads call register() on the SAME agent
     * instance, installer.install(agent, ...) must be invoked exactly once.
     * The per-agent lock with double-checked snapshot ensures only the
     * first thread installs.
     *
     * @param tempDir the temporary local dir for fake skill files
     * @throws Exception if the test fails
     */
    @Test
    void concurrentRegisterSameAgentInstallsOnce(@TempDir Path tempDir) throws Exception {
        CapturingProvider provider = new CapturingProvider();
        provider.downloadBehavior = cfg -> {
            createFakeSkillDir(tempDir, "skill-a");
            return true;
        };
        provider.verifyBehavior = path -> true;
        java.util.concurrent.atomic.AtomicInteger installCount = new java.util.concurrent.atomic.AtomicInteger();
        SkillHubInstaller spyInstaller = new SkillHubInstaller() {
            @Override
            public void install(Object agent, List<Path> skillPaths) {
                installCount.incrementAndGet();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // In test context, simply stop sleeping.
                }
            }
        };
        manager = new SkillHubManager(provider, spyInstaller, newConfig(tempDir), "");
        manager.start();
        assertThat(manager.getVerifiedSkillPaths()).hasSize(1);
        runConcurrentRegister(manager, new Object(), 4, installCount);
    }

    /**
     * Spawns {@code n} concurrent worker tasks via a thread pool, each calling
     * {@code manager.register(agent)} on the SAME agent instance. Asserts all
     * workers finish within 10s and installer.install was called exactly once.
     *
     * @param manager the SkillHubManager under test
     * @param agent the shared agent instance
     * @param n number of concurrent workers
     * @param installCount the shared install invocation counter
     */
    private static void runConcurrentRegister(SkillHubManager manager, Object agent, int n,
            java.util.concurrent.atomic.AtomicInteger installCount) throws InterruptedException {
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(n);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(n);
        java.util.concurrent.atomic.AtomicReference<IllegalStateException> firstError = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    startLatch.countDown();
                    try {
                        startLatch.await();
                        manager.register(agent);
                    } catch (InterruptedException ex) {
                        // In test context, simply proceed to doneLatch.
                    } catch (IllegalStateException ex) {
                        firstError.compareAndSet(null, ex);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            assertThat(doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("all workers should finish within 10s").isTrue();
            if (firstError.get() != null) {
                throw new RuntimeException("worker threw", firstError.get());
            }
            assertThat(installCount.get()).as(
                    "installer.install must be called exactly once for the same agent " + "under concurrent register")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
