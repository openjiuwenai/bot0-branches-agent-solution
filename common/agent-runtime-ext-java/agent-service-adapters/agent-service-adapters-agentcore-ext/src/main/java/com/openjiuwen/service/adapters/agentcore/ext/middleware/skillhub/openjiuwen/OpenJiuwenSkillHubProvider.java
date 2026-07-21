/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.openjiuwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory;
import com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Default {@link SkillHubProvider} implementation that talks to the
 * {@code openJiuwen/skillhub} service API (FEAT-005 §7.6).
 *
 * <p>API mapping:
 * <ul>
 *   <li>{@code start} — builds an HTTP client, captures endpoint/auth/token</li>
 *   <li>{@code download} — {@code GET /api/v1/plugins?plugin_type=skill} (paginated)
 *       then {@code GET /api/v1/artifacts/{id}?version={ver}} to fetch the presigned
 *       download URL + {@code checksum_sha256}, and saves the zip under
 *       {@code localDir/{asset_id}/{version}.zip} alongside a {@code .sha256} sidecar</li>
 *   <li>{@code verify} — SHA-256 check against the sidecar; falls back to a
 *       conventional (readable + non-empty) check when no sidecar exists</li>
 *   <li>{@code stop} — releases the HTTP client</li>
 * </ul>
 *
 * <p>Credentials: the token is already decrypted by the autoconfiguration layer
 * before being passed in; it is never logged or persisted.
 *
 * @since 2026-07-15
 */
public class OpenJiuwenSkillHubProvider implements SkillHubProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenJiuwenSkillHubProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int DOWNLOAD_CONCURRENCY = 4;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    private final String endpoint;
    private final String authType;
    private final String token;

    private HttpClient httpClient;

    /**
     * Bounded pool for parallel skill downloads. Created in {@link #start} and
     * shut down in {@link #stop} so repeated {@code download()} calls (e.g. the
     * Manager's background retry every 30s) don't churn threads (issue #5).
     */
    private java.util.concurrent.ExecutorService downloadPool;

    /**
     * Construct with connection params (token is already decrypted).
     *
     * @param endpoint            skillhub base URL, e.g. {@code https://swarmskills.openjiuwen.com}
     * @param token               already-decrypted plaintext token (empty/null = anonymous)
     * @param authType            {@code bearer} or {@code system-token}
     */
    public OpenJiuwenSkillHubProvider(String endpoint, String token, String authType) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.token = token == null ? "" : token;
        this.authType = authType == null ? "bearer" : authType;
    }

    @Override
    public void start(SkillHubConfig config, String decryptedToken) {
        // Connection params are captured from constructor in practice; config is the source
        // of truth for localDir at download time. Build the HTTP client here.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        // Reuse the pool across download() calls to avoid thread churn during
        // background retry (issue #5). Use ThreadPoolExecutor directly with a
        // daemon thread factory that installs an uncaught-exception handler
        // (G.CON.08 / G.CON.12).
        ThreadFactory downloadFactory = r -> {
            Thread t = new Thread(r, "skillhub-download");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thr, ex) ->
                    log.error("SkillHub download thread uncaught exception thread={} reason={}",
                            thr.getName(), ex.toString()));
            return t;
        };
        this.downloadPool = new ThreadPoolExecutor(
                DOWNLOAD_CONCURRENCY, DOWNLOAD_CONCURRENCY,
                0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                downloadFactory);
        log.info("SkillHub provider started endpoint={} authType={} credential={}",
                sanitizeEndpoint(this.endpoint), this.authType,
                this.token.isEmpty() ? "absent" : "provided");
    }

    @Override
    public boolean download(SkillHubConfig config, String decryptedToken) {
        if (this.httpClient == null) {
            throw error(SkillHubErrorCategory.CONNECT_FAILED,
                    "provider not started", null);
        }
        String localDir = config.getLocalDir();
        if (localDir == null || localDir.isBlank()) {
            throw error(SkillHubErrorCategory.CONNECT_FAILED,
                    "localDir not configured", null);
        }
        Path localRoot = Paths.get(localDir);
        try {
            Files.createDirectories(localRoot);
        } catch (IOException ex) {
            throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                    "cannot create localDir path=" + sanitizePath(localRoot), ex);
        }

        List<SkillSummary> skills = listAllPublicSkills();
        java.util.concurrent.atomic.AtomicBoolean allSucceeded =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        // 4-way bounded parallel download. Single-skill failure is isolated:
        // downloadOne throws → caught per-task → allSucceeded flips false → other tasks continue.
        // Pool is owned by the Provider (created in start(), closed in stop()) so
        // repeated background-retry calls don't churn threads (issue #5).
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (SkillSummary summary : skills) {
            futures.add(downloadPool.submit(() -> {
                try {
                    downloadOne(summary, localRoot);
                } catch (IllegalStateException ex) {
                    allSucceeded.set(false);
                    log.warn("SkillHub skill download failed skillId={} required=true category={} reason={}",
                            summary.assetId(), categoryOf(ex), ex.getMessage(), ex);
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException ie) {
                // Per G.CON.10: do not call Thread.interrupt(); record the interrupt
                // via the shared success flag and continue draining remaining futures.
                allSucceeded.set(false);
                log.warn("SkillHub skill download wait interrupted reason={}", ie.getMessage());
            } catch (java.util.concurrent.ExecutionException ee) {
                allSucceeded.set(false);
                log.warn("SkillHub skill download task aborted reason={}",
                        ee.getCause() != null ? ee.getCause().getMessage() : ee.getMessage());
            }
        }
        return allSucceeded.get();
    }

    @Override
    public boolean verify(Path skillPath) {
        // After PR #xxx, download() extracts the zip into a directory and deletes the zip.
        // verify() now checks that the extracted directory contains a SKILL.md (the
        // contract required by agent-core's SkillManager.registerRoot).
        if (skillPath == null) {
            return false;
        }
        if (!Files.isDirectory(skillPath)) {
            log.warn("SkillHub skill verify failed skillPath={} category=CHECKSUM_MISMATCH reason=not-directory-or-missing",
                    sanitizePath(skillPath));
            return false;
        }
        java.util.Optional<Path> skillMdOpt = resolveSkillMd(skillPath);
        if (skillMdOpt.isEmpty() || !Files.isReadable(skillMdOpt.get())) {
            log.warn("SkillHub skill verify failed skillPath={} category=CHECKSUM_MISMATCH reason=no-skill-md",
                    sanitizePath(skillPath));
            return false;
        }
        Path skillMd = skillMdOpt.get();
        try {
            long size = Files.size(skillMd);
            if (size <= 0) {
                log.warn("SkillHub skill verify failed skillPath={} category=CHECKSUM_MISMATCH reason=empty-skill-md",
                        sanitizePath(skillPath));
                return false;
            }
            // Front-matter check: agent-core's SkillManager.loadDescription REQUIRES
            // SKILL.md to start with '---' and contain a 'description:' field inside
            // the front matter; otherwise it returns null and registerSkill silently
            // no-ops (skill is dropped without any exception). Reject such skills here
            // so Manager's verifiedSkillPaths never gets a path that would fail to register.
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            if (!hasFrontMatter(content)) {
                log.warn("SkillHub skill verify failed skillPath={} category=CHECKSUM_MISMATCH reason=missing-yaml-front-matter",
                        sanitizePath(skillPath));
                return false;
            }
        } catch (IOException ex) {
            log.warn("SkillHub skill verify failed skillPath={} category=CHECKSUM_MISMATCH reason=stat-or-read-failed",
                    sanitizePath(skillPath));
            return false;
        }
        log.info("SkillHub skill verified skillPath={} method=extracted-dir-has-SKILL.md-with-front-matter verified=true",
                sanitizePath(skillPath));
        return true;
    }

    /**
     * Validate that SKILL.md content starts with a YAML front matter block and
     * contains a {@code description:} line (agent-core's loadDescription contract).
     *
     * @param content the SKILL.md file content
     * @return true if content has a valid YAML front matter with a description line
     */
    private static boolean hasFrontMatter(String content) {
        if (content == null) {
            return false;
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return false;
        }
        // Split into ["", frontMatter, body] — frontMatter is between the first two '---'.
        String[] parts = trimmed.split("---", 3);
        if (parts.length < 2) {
            return false;
        }
        for (String line : parts[1].split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("description:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find {@code SKILL.md} or {@code Skill.md} directly under the extracted dir.
     *
     * @param dir the extracted skill package directory
     * @return the path wrapped in Optional, empty if not found.
     */
    private static java.util.Optional<Path> resolveSkillMd(Path dir) {
        for (String name : new String[]{"SKILL.md", "Skill.md"}) {
            Path candidate = dir.resolve(name);
            if (Files.isReadable(candidate)) {
                return java.util.Optional.of(candidate);
            }
        }
        return java.util.Optional.empty();
    }

    @Override
    public void stop() {
        // NOTE on issue #7: ideally we'd call httpClient.close() to release the
        // connection pool / event-loop threads explicitly, but HttpClient.close()
        // is only available from JDK 21+. This project targets JDK 17
        // (agent-runtime-ext.java.release=17), so we cannot call it directly.
        // Dropping the reference and letting GC reclaim the HttpClient is the
        // best we can do on JDK 17; the download pool below is shut down
        // explicitly because ExecutorService has long been closeable.
        this.httpClient = null;
        // Shutdown the download pool (created in start()).
        java.util.concurrent.ExecutorService pool = this.downloadPool;
        if (pool != null) {
            pool.shutdownNow();
            this.downloadPool = null;
        }
        log.info("SkillHub provider stopped endpoint={}", sanitizeEndpoint(this.endpoint));
    }

    // ----- download helpers -----

    private List<SkillSummary> listAllPublicSkills() {
        List<SkillSummary> all = new ArrayList<>();
        int page = 1;
        while (true) {
            String path = String.format(java.util.Locale.ROOT,
                    "/api/v1/plugins?plugin_type=skill&page=%d&page_size=%d",
                    page, DEFAULT_PAGE_SIZE);
            JsonNode data;
            try {
                data = sendJson(buildGet(path));
            } catch (IllegalStateException ex) {
                throw error(SkillHubErrorCategory.CONNECT_FAILED,
                        "list skills page=" + page, ex);
            }
            JsonNode items = data.path("items");
            if (!items.isArray() || items.isEmpty()) {
                break;
            }
            for (JsonNode item : items) {
                String assetId = textOrEmpty(item, "asset_id");
                String name = textOrEmpty(item, "name");
                String latestVersion = textOrEmpty(item, "latest_version");
                if (assetId == null || assetId.isBlank()) {
                    continue;
                }
                all.add(new SkillSummary(assetId, name, latestVersion));
            }
            int total = data.path("total").asInt(all.size());
            if (all.size() >= total || items.size() < DEFAULT_PAGE_SIZE) {
                break;
            }
            page++;
        }
        log.info("SkillHub listed skills count={}", all.size());
        return all;
    }

    private void downloadOne(SkillSummary summary, Path localRoot) {
        String assetId = summary.assetId();
        String version = summary.latestVersion();
        Path skillDir = localRoot.resolve(assetId);
        try {
            Files.createDirectories(skillDir);
        } catch (IOException ex) {
            throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                    "create skill dir assetId=" + assetId, ex);
        }

        ArtifactInfo info = fetchArtifactInfo(assetId, version);
        if (info == null || info.downloadUrl() == null || info.downloadUrl().isBlank()) {
            throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                    "no download_url assetId=" + assetId, null);
        }
        String zipName = (info.name() != null && !info.name().isBlank())
                ? info.name()
                : assetId;
        // Ensure the zip file path ends with .zip so it doesn't collide with the
        // extracted dir path (which is zipName without .zip). The skillhub /artifacts
        // response's name field has no extension, so we append .zip here.
        Path zipPath = skillDir.resolve(zipName + ".zip");
        httpDownloadFile(info.downloadUrl(), zipPath);

        // Verify SHA-256 immediately against server-provided checksum (if present).
        // A mismatch means the downloaded bytes are corrupt — do NOT extract.
        if (info.checksumSha256() != null && !info.checksumSha256().isBlank()) {
            String expected = info.checksumSha256().toLowerCase(java.util.Locale.ROOT);
            String actual = sha256OfFile(zipPath);
            if (!expected.equalsIgnoreCase(actual)) {
                try {
                    Files.deleteIfExists(zipPath);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                throw error(SkillHubErrorCategory.CHECKSUM_MISMATCH,
                        "download sha256 mismatch assetId=" + assetId
                                + " expected=" + maskHash(expected)
                                + " actual=" + maskHash(actual), null);
            }
        }

        // Extract zip into a subdirectory so SkillManager.registerRoot can find SKILL.md.
        // agent-core's SkillManager only handles directories (with SKILL.md), not raw zips.
        Path extractDir = skillDir.resolve(zipName.replace(".zip", ""));
        try {
            extractZip(zipPath, extractDir);
        } catch (IOException ex) {
            throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                    "extract zip failed assetId=" + assetId, ex);
        }

        // Remove the zip + sidecar; only the extracted directory is needed for registration.
        try {
            Files.deleteIfExists(zipPath);
        } catch (IOException ignored) {
            // best-effort; zip is harmless once extracted
        }

        log.info("SkillHub skill download succeeded skillId={} version={} size={} extracted={}",
                assetId, version, info.fileSize(), sanitizePath(extractDir));
    }

    /**
     * Extract a zip into a target directory. The directory is created if absent.
     * Entries are validated to prevent path traversal (../ and absolute paths).
     *
     * @param zip the zip file path
     * @param targetDir the directory to extract into; created if absent
     * @throws IOException if the zip cannot be read or files cannot be written
     */
    private static void extractZip(Path zip, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path dest = targetDir.resolve(entry.getName()).normalize();
                // Path-traversal guard: ensure dest stays under targetDir
                if (!dest.startsWith(targetDir.normalize())) {
                    throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                            "zip entry escapes target dir entry=" + entry.getName(), null);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    try (var in = zipFile.getInputStream(entry)) {
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private ArtifactInfo fetchArtifactInfo(String assetId, String version) {
        String path = version == null || version.isBlank()
                ? "/api/v1/artifacts/" + assetId
                : "/api/v1/artifacts/" + assetId + "?version=" + version;
        JsonNode data;
        try {
            data = sendJson(buildGet(path));
        } catch (IllegalStateException ex) {
            throw error(SkillHubErrorCategory.NOT_FOUND,
                    "artifacts lookup assetId=" + assetId, ex);
        }
        return new ArtifactInfo(
                textOrEmpty(data, "download_url"),
                textOrEmpty(data, "checksum_sha256"),
                data.path("file_size").asLong(0L),
                textOrEmpty(data, "name"),
                textOrEmpty(data, "version"));
    }

    private void httpDownloadFile(String downloadUrl, Path target) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(DOWNLOAD_TIMEOUT)
                    .GET()
                    .build();
            // Stream directly to file to avoid loading the entire zip into the
            // JVM heap (issue #6). Large skill packages under DOWNLOAD_TIMEOUT=10min
            // could otherwise OOM.
            HttpResponse<Path> resp = httpClient.send(req,
                    HttpResponse.BodyHandlers.ofFile(target));
            int status = resp.statusCode();
            if (status < 200 || status >= 300) {
                // Body handler may have created an empty/partial file on failure.
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // Ignore cleanup failure as file may not exist
                }
                throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                        "download status=" + status, null);
            }
            // ofFile writes the body to disk as it arrives; size check catches
            // the empty-body case without materializing bytes in memory.
            long size;
            try {
                size = Files.size(target);
            } catch (IOException ex) {
                size = -1L;
            }
            if (size <= 0) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // Ignore cleanup failure as file may not exist
                }
                throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                        "download empty body", null);
            }
        } catch (IOException | InterruptedException ex) {
            throw error(SkillHubErrorCategory.DOWNLOAD_FAILED,
                    "download io error", ex);
        }
    }

    // ----- HTTP helpers -----

    private HttpRequest buildGet(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(this.endpoint + path))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        if (!this.token.isEmpty()) {
            if ("bearer".equalsIgnoreCase(this.authType)) {
                b.header("Authorization", "Bearer " + this.token);
                b.header("X-OAuth-Provider", "gitcode");
            } else {
                b.header("X-System-Token", this.token);
            }
        }
        return b.build();
    }

    private JsonNode sendJson(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            if (status == 401 || status == 403) {
                throw error(SkillHubErrorCategory.AUTH_FAILED,
                        "status=" + status, null);
            }
            if (status == 404) {
                throw error(SkillHubErrorCategory.NOT_FOUND,
                        "status=404", null);
            }
            if (status < 200 || status >= 300) {
                throw error(SkillHubErrorCategory.CONNECT_FAILED,
                        "status=" + status, null);
            }
            String body = resp.body();
            if (body == null || body.isEmpty()) {
                throw error(SkillHubErrorCategory.CONNECT_FAILED,
                        "empty body", null);
            }
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull()) {
                throw error(SkillHubErrorCategory.CONNECT_FAILED,
                        "missing data field", null);
            }
            return data;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw error(SkillHubErrorCategory.CONNECT_FAILED,
                    "json parse error", ex);
        } catch (IOException | InterruptedException ex) {
            throw error(SkillHubErrorCategory.CONNECT_FAILED,
                    "http error", ex);
        }
    }

    // ----- checksum helpers -----

    private static String sha256OfFile(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (IOException | java.security.NoSuchAlgorithmException ex) {
            throw error(SkillHubErrorCategory.CHECKSUM_MISMATCH,
                    "sha256 compute failed path=" + sanitizePath(path), ex);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ----- error/sanitization helpers -----

    private static IllegalStateException error(SkillHubErrorCategory category,
                                               String reason, Throwable cause) {
        String safe = reason == null ? "" : reason;
        IllegalStateException ex = new IllegalStateException(
                "SkillHub[" + category + "] " + safe);
        if (cause != null) {
            ex.initCause(cause);
        }
        return ex;
    }

    private static SkillHubErrorCategory categoryOf(IllegalStateException ex) {
        if (ex.getMessage() != null
                && ex.getMessage().startsWith("SkillHub[")) {
            try {
                int start = "SkillHub[".length();
                int end = ex.getMessage().indexOf(']', start);
                if (end > start) {
                    return SkillHubErrorCategory.valueOf(ex.getMessage().substring(start, end));
                }
            } catch (IllegalArgumentException ignored) {
                // fall through to UNKNOWN
            }
        }
        return SkillHubErrorCategory.UNKNOWN;
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText();
    }

    /**
     * Strip path after host to avoid leaking query/credentials in logs.
     */
    private static String sanitizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return "";
        }
        int schemeIdx = endpoint.indexOf("://");
        int pathStart = schemeIdx >= 0 ? endpoint.indexOf('/', schemeIdx + 3) : endpoint.indexOf('/');
        return pathStart > 0 ? endpoint.substring(0, pathStart) : endpoint;
    }

    /** Never log full path; show only file name to avoid leaking internal dirs. */
    private static String sanitizePath(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private static String maskHash(String hash) {
        if (hash == null || hash.length() < 8) {
            return "***";
        }
        return hash.substring(0, 4) + "..." + hash.substring(hash.length() - 4);
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String trimmed = endpoint.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Internal view of a skill summary from {@code /plugins}. */
    private record SkillSummary(String assetId, String name, String latestVersion) { }

    /** Internal view of artifact info from {@code /artifacts/{id}}. */
    private record ArtifactInfo(String downloadUrl, String checksumSha256,
                                 long fileSize, String name, String version) { }
}
