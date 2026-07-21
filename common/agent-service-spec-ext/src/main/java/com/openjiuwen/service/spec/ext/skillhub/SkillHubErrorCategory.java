/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.ext.skillhub;

/**
 * Error categories for SkillHub access failures (FEAT-005 §4.10).
 *
 * <p>Used by {@code SkillHubProvider} implementations to classify failures via
 * exception message prefix {@code SkillHub[CATEGORY]}. The {@code SkillHubManager}
 * reads the category to decide fail-fast vs degrade+retry semantics per
 * required/optional policy.
 *
 * @since 2026-07-15
 */
public enum SkillHubErrorCategory {
    /** Endpoint missing or unreachable. */
    CONNECT_FAILED,
    /** Credential missing / invalid / expired / 401 / 403. */
    AUTH_FAILED,
    /** Skill Hub rejected access to the resource. */
    ACCESS_DENIED,
    /** Skill does not exist or caller has no visibility. */
    NOT_FOUND,
    /** Download interrupted or package corrupted. */
    DOWNLOAD_FAILED,
    /** Integrity check (SHA-256 or conventional) failed. */
    CHECKSUM_MISMATCH,
    /** registerSkill did not grow skillCount (handover failed). */
    INSTALL_FAILED,
    /** Skill Hub does not support artifact download. */
    UNSUPPORTED,
    /** Unclassified exception fallback. */
    UNKNOWN
}
