package com.openjiuwen.rdc.registry.runtime.health;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feedback-loop tests for PR #389 review issue #2 — the probe scheduler
 * must not be a single-threaded, no-timeout, URL-naive hot path.
 *
 * <h3>What this test pins</h3>
 * <ul>
 *   <li><b>Connection / read timeout</b> — {@code RestClient.create()} has
 *       no timeout; a hung endpoint blocks the probe thread indefinitely.
 *       The fix configures the {@link org.springframework.web.client.RestClient}
 *       with explicit connect + read timeouts.</li>
 *   <li><b>Trailing-slash tolerance</b> — {@code endpointUrl + HEALTH_PATH}
 *       produces {@code https://host//health} when the
 *       registered URL ends in {@code /}. The fix normalises the URL.</li>
 *   <li><b>Per-probe failure isolation</b> — a probe that throws must not
 *       abort the sweep for the remaining targets. The current code does
 *       loop over targets, but a hung probe (pre-timeout fix) blocks the
 *       whole sweep. The timeout fix + this test pin the property.</li>
 * </ul>
 *
 * <p>The scheduler-thread-isolation aspect of issue #2 (independent
 * {@code TaskScheduler} so a hung probe does not stall
 * {@code @Scheduled}-default single-threaded execution across the whole
 * application) is not pinnable in a unit test without booting a Spring
 * context; the fix is structural (a dedicated
 * {@code RegistrySchedulingConfig} bean) and is verified by inspection.
 *
 * <p>Authority: PR #389 review issue #2. ADR-0160 + HD3-004. Revised for
 * REQ-2026-006 (ProbeTarget adds serviceId; repo port listByAgentId +
 * findEndpoint(serviceId) + updateStatus(serviceId) + delete(serviceId)
 * overload).
 */
class Pr389ProbeSchedulerHardeningFeedbackLoopTest {

    private static MockWebServer agentServer;
    private static RegistryObservabilityConfig observability;
    private static AtomicInteger scanCallCount;

    @BeforeAll
    static void bootMockServer() throws Exception {
        agentServer = new MockWebServer();
        agentServer.start();
        observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        scanCallCount = new AtomicInteger();
    }

    @AfterAll
    static void shutDown() {
        // Best-effort shutdown — the hung-endpoint test enqueues a 30s
        // headers-delayed response that the client times out on (and thus
        // never consumes). MockWebServer.shutdown() blocks waiting for the
        // queue to drain, so it throws "Gave up waiting for queue to shut
        // down" on that test. The server is in-process; the JVM exit
        // cleans it up. Suppress the exception so it does not mask the
        // real test outcome.
        if (agentServer != null) {
            try {
                agentServer.shutdown();
            } catch (Exception ignored) {
                // best-effort — in-process server is reclaimed at JVM exit
            }
        }
    }

    @BeforeEach
    void resetCounters() {
        scanCallCount.set(0);
    }

    // ---- timeout: hung endpoint must not block the sweep ------------------

    /**
     * PR #389 #2: a hung endpoint (server accepts the connection but never
     * sends response headers) MUST NOT block the probe sweep for other
     * targets. The fix configures connect + read timeouts on the
     * {@link RestClient}; without it, this test hangs for the JDK default
     * socket timeout (which can be infinite) and the second target is never
     * probed.
     *
     * <p>RED on the unfixed code: either hangs indefinitely (test times out)
     * or only the first target is probed before the sweep stalls past the
     * asserted window. GREEN once the RestClient has a bounded read timeout
     * (e.g. 2s) and both targets are processed.
     */
    @Test
    void hung_endpoint_does_not_block_probe_sweep_for_other_targets() throws Exception {
        // First target: delay HEADERS by 30s — simulates a hung endpoint
        // that accepts the connection but never responds. setBodyDelay does
        // NOT work for toBodilessEntity() because OkHttp returns as soon as
        // headers arrive; setHeadersDelay is the right knob.
        agentServer.enqueue(new MockResponse()
                .setHeadersDelay(30, java.util.concurrent.TimeUnit.SECONDS)
                .setResponseCode(200));
        agentServer.enqueue(new MockResponse().setResponseCode(200));

        String slowUrl = agentServer.url("/slow").toString().replaceAll("/$", "");
        String fastUrl = agentServer.url("/fast").toString().replaceAll("/$", "");

        CountingRepository repo = new CountingRepository(List.of(
                new AgentRegistryRepository.ProbeTarget(
                        "tenant-A", "agent-slow", "slow-host-svc", "slow-host-8080", slowUrl),
                new AgentRegistryRepository.ProbeTarget(
                        "tenant-A", "agent-fast", "fast-host-svc", "fast-host-8080", fastUrl)));

        MvpHealthProbeScheduler scheduler = new MvpHealthProbeScheduler(
                repo, observability, /* staleBeforeMs = */ 1_000L, /* scanLimit = */ 100);

        long start = System.currentTimeMillis();
        scheduler.probeOnlineAgents();
        long elapsed = System.currentTimeMillis() - start;

        // Both targets MUST have been probed (not just the first).
        assertThat(repo.updateStatusCalls.get())
                .as("PR #389 #2: hung endpoint must not abort the sweep — both "
                    + "targets must reach updateStatus (slow → DEGRADED, fast → ONLINE)")
                .isEqualTo(2);

        // The sweep must finish well under the 30s hang window — proves the
        // read timeout fired. 10s gives plenty of headroom over a 2s timeout
        // while still catching "no timeout configured" (which would hit 30s).
        assertThat(elapsed)
                .as("Probe sweep must complete in well under the 30s hang window "
                    + "(was: %dms; without timeout fix this test hangs or hits 30s)", elapsed)
                .isLessThan(10_000L);
    }

    // ---- trailing-slash tolerance ------------------------------------------

    /**
     * PR #389 #2: an endpointUrl registered with a trailing slash must NOT
     * produce a double-slash URL ({@code https://host//health}).
     * The fix normalises the URL before appending {@code HEALTH_PATH}.
     *
     * <p>This test exercises the {@code composeProbeUrl} helper directly so
     * the assertion is not at the mercy of HTTP-client path normalisation
     * (OkHttp / RestClient canonicalise {@code //x} to {@code /x} silently,
     * which masks the bug at the wire level).
     */
    @Test
    void compose_probe_url_strips_trailing_slash_before_appending_health_path() {
        assertThat(MvpHealthProbeScheduler.composeProbeUrl("https://agent.example/agent"))
                .isEqualTo("https://agent.example/agent/health");
        assertThat(MvpHealthProbeScheduler.composeProbeUrl("https://agent.example/agent/"))
                .as("trailing slash must not produce double-slash URL")
                .isEqualTo("https://agent.example/agent/health");
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Minimal fake repository that returns a fixed list of probe targets and
     * counts {@code updateStatus} calls so the test can assert both targets
     * were processed.
     */
    private static final class CountingRepository implements AgentRegistryRepository {
        private final List<ProbeTarget> targets;
        private final AtomicInteger updateStatusCalls = new AtomicInteger();

        CountingRepository(List<ProbeTarget> targets) {
            this.targets = targets;
        }

        @Override public void upsert(com.openjiuwen.rdc.spi.registry.AgentRegistryEntry card, String a2aAgentCardJson) { }
        @Override public boolean delete(String tenantId, String agentId) { return false; }
        @Override public boolean delete(String tenantId, String agentId, String serviceId) { return false; }
        @Override public boolean delete(String tenantId, String agentId, String serviceId, String instanceId) {
            return false;
        }
        @Override public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return targets;
        }
        @Override public boolean updateStatus(String tenantId, String agentId, String serviceId,
                                              String instanceId, String newStatus, boolean refreshHeartbeat) {
            updateStatusCalls.incrementAndGet();
            return true;
        }
        @Override public List<RegistryRow> listByAgentId(String tenantId, String agentId,
                                                         String contractVersion) {
            return List.of();
        }
        @Override public List<RegistryRow> listByServiceId(String tenantId, String serviceId,
                                                           String contractVersion) {
            return List.of();
        }
        @Override public List<RegistryRow> listByCapability(String tenantId, String capability,
                                                            String contractVersion) {
            return List.of();
        }
        @Override public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                                              String serviceId, String instanceId) {
            return Optional.empty();
        }
    }
}
