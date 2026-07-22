/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.openjiuwen.OpenJiuwenSkillHubProvider;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * End-to-end test for {@link OpenJiuwenSkillHubProvider} against the real
 * {@code https://swarmskills.openjiuwen.com/} service. Verifies the full
 * download → sha256-check → extract → verify flow without mocking HTTP.
 *
 * <p>Guarded by system property {@code skillhub.e2e.enabled=true}:
 * <pre>
 *   mvn test -Dtest=OpenJiuwenSkillHubProviderE2ETest "-Dskillhub.e2e.enabled=true"
 * </pre>
 *
 * <p>Optional overrides (default is anonymous access against the public endpoint):
 * <ul>
 *   <li>{@code skillhub.e2e.endpoint} — base URL (default {@code https://swarmskills.openjiuwen.com})</li>
 *   <li>{@code skillhub.e2e.token} — plaintext Bearer token (default empty = anonymous)</li>
 *   <li>{@code skillhub.e2e.authType} — {@code bearer} or {@code system-token} (default {@code bearer})</li>
 * </ul>
 * Example:
 * <pre>
 *   mvn test -Dtest=OpenJiuwenSkillHubProviderE2ETest \
 *     "-Dskillhub.e2e.enabled=true" \
 *     "-Dskillhub.e2e.token=xxxxxx"
 * </pre>
 *
 * <p>What this proves:
 * <ul>
 *   <li>{@code GET /api/v1/plugins?plugin_type=skill} returns paginated skills</li>
 *   <li>{@code GET /api/v1/artifacts/{id}} returns presigned download_url + checksum</li>
 *   <li>zip is downloaded, SHA-256-checked against server checksum, then extracted</li>
 *   <li>extracted directory contains {@code SKILL.md}</li>
 *   <li>{@code verify(path)} returns true for extracted skill dirs</li>
 * </ul>
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "skillhub.e2e.enabled", matches = "true")
class OpenJiuwenSkillHubProviderE2ETest {
    private static final String DEFAULT_ENDPOINT = "https://swarmskills.openjiuwen.com";

    @TempDir
    Path tempDir;

    private OpenJiuwenSkillHubProvider provider;
    private SkillHubConfig config;

    @BeforeEach
    void setUp() {
        String endpoint = System.getProperty("skillhub.e2e.endpoint", DEFAULT_ENDPOINT);
        String token = System.getProperty("skillhub.e2e.token", "");
        String authType = System.getProperty("skillhub.e2e.authType", "bearer");
        provider = new OpenJiuwenSkillHubProvider(endpoint, token, authType);
        config = new SkillHubConfig();
        config.setEnabled(true);
        config.setEndpoint(endpoint);
        config.setAuthType(authType);
        config.setEncryptedToken("");
        config.setLocalDir(tempDir.toString());
        provider.start(config, token);
    }

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.stop();
        }
    }

    @Test
    void downloadExtractsZipAndVerifyPassesForExtractedDir() throws Exception {
        boolean ok = provider.download(config, "");
        assertThat(ok)
                .as("download should succeed (all skills downloaded + extracted without error)")
                .isTrue();

        List<Path> skillDirs = listSkillDirs(tempDir);
        assertThat(skillDirs)
                .as("localDir should contain at least one asset_id subdirectory")
                .isNotEmpty();

        // Find extracted skill dirs (each contains SKILL.md)
        List<Path> extracted = new java.util.ArrayList<>();
        for (Path assetDir : skillDirs) {
            try (Stream<Path> s = Files.walk(assetDir)) {
                s.filter(Files::isDirectory)
                        .filter(dir -> Files.isReadable(dir.resolve("SKILL.md"))
                                || Files.isReadable(dir.resolve("Skill.md")))
                        .forEach(extracted::add);
            }
        }
        assertThat(extracted)
                .as("should have at least one extracted dir containing SKILL.md")
                .isNotEmpty();

        // verify() on each extracted dir: at least one must pass (the top-level
        // skills all carry YAML front matter). Nested sub-skills that happen to
        // ship without front matter (e.g. architecture-designer-skill embedded
        // inside android-to-harmonyos-pipeline-en) legitimately fail verify and
        // are excluded from the unregistered list — that is the desired behavior.
        int passed = 0;
        int failed = 0;
        for (Path dir : extracted) {
            if (provider.verify(dir)) {
                passed++;
            } else {
                failed++;
            }
        }
        assertThat(passed)
                .as("at least one extracted dir should pass verify()"
                        + " (top-level skills have front matter); extracted=%s",
                        extracted)
                .isGreaterThan(0);
        // Record both counts for diagnostics; no hard assertion on failed count
        // because the upstream skill package composition is outside our control.
        java.util.logging.Logger.getLogger("e2e").info(
                "verify passed=" + passed + " failed=" + failed + " total=" + extracted.size());
    }

    @Test
    void verifyReturnsFalseForDirWithoutSkillMd(@TempDir Path otherDir) throws Exception {
        // An empty dir with no SKILL.md should fail verify
        boolean verified = provider.verify(otherDir);
        assertThat(verified)
                .as("verify() should return false for dir without SKILL.md")
                .isFalse();
    }

    @Test
    void verifyReturnsFalseForMissingFile() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        boolean verified = provider.verify(nonExistent);
        assertThat(verified)
                .as("verify() should return false for non-existent path")
                .isFalse();
    }

    private List<Path> listSkillDirs(Path root) throws IOException {
        List<Path> dirs = new java.util.ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory).forEach(dirs::add);
        }
        return dirs;
    }
}
