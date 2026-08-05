/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.ext.skillhub.spi;

import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;

import java.nio.file.Path;

/**
 * SkillHub Provider: the access boundary to an external Skill Hub.
 *
 * <p>Declares four methods: {@code start} / {@code download} / {@code verify} / {@code stop}.
 * {@code start}/{@code stop} are called by {@code SkillHubManager} during its own
 * {@code start()} and {@code stop()} lifecycle; {@code download}/{@code verify}
 * are orchestrated by the manager.
 *
 * <p>The caller (SkillHubManager) passes deployment-stable config and a
 * <b>already-decrypted</b> plaintext token. Credential decryption is the
 * responsibility of the autoconfiguration layer (it invokes runtime
 * {@code CredentialDecryptor.decrypt}); the SPI never sees ciphertext and never
 * persists/logs the token.
 *
 * <p>Failure classification: implementations SHOULD throw
 * {@link IllegalStateException} with a message prefixed by
 * {@code SkillHub[CATEGORY]} where {@code CATEGORY} is a value from
 * {@link com.openjiuwen.service.spec.ext.skillhub.SkillHubErrorCategory}.
 * Unclassified exceptions are bucketed as {@code UNKNOWN} by the manager.
 *
 * @since 2026-07-15
 */
public interface SkillHubProvider {
    /**
     * Start the provider (build connection pool, warm up auth, etc.).
     * Called by {@code SkillHubManager.start()}.
     *
     * @param config        SkillHub connection config (endpoint, authType, localDir, etc.)
     * @param decryptedToken already-decrypted plaintext token; empty/null means anonymous access.
     *                       Must not be logged or persisted.
     */
    void start(SkillHubConfig config, String decryptedToken);

    /**
     * Download all skills that should be downloaded into {@code config.getLocalDir()}.
     *
     * <p>The provider decides which skills to fetch (e.g. pulls the tenant/config
     * applicable skill list from the Skill Hub). The caller does not pass skillId
     * because the caller cannot know skillIds before download. {@code localDir} is
     * also read from {@code config.getLocalDir()}, not passed by caller.
     *
     * <p>This method only downloads; verification is handled by {@link #verify}.
     *
     * @param config        SkillHub connection config (contains localDir)
     * @param decryptedToken already-decrypted plaintext token
     * @return {@code true} if all downloads succeeded; {@code false} if some/all
     *         failed (specifics logged by the provider)
     */
    boolean download(SkillHubConfig config, String decryptedToken);

    /**
     * Verify integrity of a downloaded skill at the given local path.
     *
     * <p>Verification method is implementation-defined (SHA-256 / conventional
     * file checks / custom are all acceptable). Paths that fail verification
     * must NOT return success (throw or return {@code false}).
     *
     * @param skillPath local path of the skill to verify
     * @return {@code true} if verification passed; {@code false} if failed
     *         (failed entries are excluded from the uninstalled list by the manager)
     */
    boolean verify(Path skillPath);

    /**
     * Stop the provider (close connection pool, release resources).
     * Called by {@code SkillHubManager} on close / handler stop.
     */
    void stop();
}
