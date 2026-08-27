/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import com.openjiuwen.studio.dsl.contract.KnowledgeBaseConfigProvider;
import com.openjiuwen.studio.dsl.contract.KnowledgeStorageProvider;
import com.openjiuwen.studio.dsl.contract.SecretDecryptor;

/**
 * Global KB config provider + storage wiring (Python {@code FlowKnowledgeRetrieval.set_kb_provider}).
 *
 * <p><b>Multi-tenant note:</b> wiring is JVM-wide static state — not per {@code StudioDslModule}
 * instance. Multi-tenant hosts should either (a) implement {@link KnowledgeBaseConfigProvider}
 * with tenant routing (e.g. ThreadLocal / request context), (b) pass inline {@code kbConfig} on
 * nodes, or (c) run isolated class loaders per tenant.
 *
 * <p>{@link #setProvider(null)} resets to {@link ObsKnowledgeBaseConfigProvider}; {@link
 * #setStorageProvider(null)} clears storage (unconfigured until set again). Use {@link
 * #resetToDefaults()} in tests.
 *
 * @since 2026-08-26
 */

public final class KnowledgeBaseConfigProviders {
    private static volatile KnowledgeBaseConfigProvider provider = new ObsKnowledgeBaseConfigProvider();
    private static volatile KnowledgeStorageProvider storageProvider;
    private static volatile SecretDecryptor secretDecryptor;

    private KnowledgeBaseConfigProviders() {}

    /**
     * get.
     *
     * @return result
     * @since 0.1.0
     */

    public static KnowledgeBaseConfigProvider get() {
        return provider;
    }

    /**
     * setProvider.
     *
     * @param p p
     * @since 0.1.0
     */

    public static void setProvider(KnowledgeBaseConfigProvider p) {
        provider = p == null ? new ObsKnowledgeBaseConfigProvider() : p;
    }

    /**
     * Clears optional storage wiring; {@link #storage()} throws until configured again.
     *
     * @param storage storage
     * @since 0.1.0
     */
    public static void setStorageProvider(KnowledgeStorageProvider storage) {
        storageProvider = storage;
    }

    /**
     * Resets provider to OBS default and clears storage + decryptor (tests / dev only).
     *
     * @since 0.1.0
     */
    public static void resetToDefaults() {
        provider = new ObsKnowledgeBaseConfigProvider();
        storageProvider = null;
        secretDecryptor = null;
    }

    /**
     * storage.
     *
     * @return result
     * @since 0.1.0
     */

    public static KnowledgeStorageProvider storage() {
        KnowledgeStorageProvider s = storageProvider;
        if (s != null) {
            return s;
        }
        return key -> {
            throw new IllegalStateException(
                    "KnowledgeStorageProvider not configured; call KnowledgeBaseConfigProviders.setStorageProvider"
                            + " or provide inline kbConfig");
        };
    }

    /**
     * setSecretDecryptor.
     *
     * @param decryptor decryptor
     * @since 0.1.0
     */

    public static void setSecretDecryptor(SecretDecryptor decryptor) {
        secretDecryptor = decryptor;
    }
    static String maybeDecrypt(String code, String value) {
        if (value == null || value.isBlank()) {
        return value;
    }
        if (!ObsKnowledgeBaseConfigProvider.SECRET_PARAM_CODES.contains(code)) {
            return value;
        }
        SecretDecryptor d = secretDecryptor;
        if (d == null) {
            return value;
        }
        try {
            return d.decrypt(value);
        } catch (RuntimeException e) {
            return value;
        }
    }
}
