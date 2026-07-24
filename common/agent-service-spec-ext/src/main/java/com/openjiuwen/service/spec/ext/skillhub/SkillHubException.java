/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.ext.skillhub;

/**
 * Typed SkillHub access exception carrying a {@link SkillHubErrorCategory}.
 *
 * <p>Extends {@link IllegalStateException} so existing call sites that catch
 * {@code IllegalStateException} keep working. The typed {@link #category()}
 * accessor lets {@code SkillHubManager} decide fail-fast vs degrade+retry
 * without parsing the exception message prefix.
 *
 * <p>The legacy string-prefix form {@code SkillHub[CATEGORY] ...} is still
 * produced by {@link #getMessage()} for log readability and backward
 * compatibility with older helpers that parse the prefix; new code should
 * prefer {@link #category()}.
 *
 * @since 2026-07-24
 */
public class SkillHubException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final transient SkillHubErrorCategory category;

    /**
     * Build a typed SkillHub exception.
     *
     * @param category the error category (must not be null)
     * @param reason human-readable reason; appended to the message prefix
     * @param cause optional cause; null allowed
     */
    public SkillHubException(SkillHubErrorCategory category, String reason, Throwable cause) {
        super(buildMessage(category, reason));
        this.category = java.util.Objects.requireNonNull(category, "category");
        if (cause != null) {
            initCause(cause);
        }
    }

    /**
     * Convenience constructor without a cause.
     *
     * @param category the error category (must not be null)
     * @param reason human-readable reason; appended to the message prefix
     */
    public SkillHubException(SkillHubErrorCategory category, String reason) {
        this(category, reason, null);
    }

    /**
     * @return the structured error category; never null
     */
    public SkillHubErrorCategory category() {
        return category;
    }

    private static String buildMessage(SkillHubErrorCategory category, String reason) {
        String safe = reason == null ? "" : reason;
        return "SkillHub[" + category + "] " + safe;
    }
}
