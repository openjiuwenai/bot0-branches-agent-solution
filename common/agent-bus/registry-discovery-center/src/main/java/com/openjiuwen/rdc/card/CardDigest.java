/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 digest of Agent Card JSON for change detection.
 *
 * @since 0.1.0 (2026)
 */
public final class CardDigest {
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private static final MessageDigest SHARED;

    static {
        try {
            SHARED = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private CardDigest() {
    }

    /**
     * sha256.
     *
     * @param cardJson cardJson
     * @return result
     * @since 0.1.0
     */
    public static String sha256(String cardJson) {
        if (cardJson == null) {
            return "";
        }
        MessageDigest digest;
        byte[] hash;
        try {
            synchronized (SHARED) {
                digest = (MessageDigest) SHARED.clone();
            }
            hash = digest.digest(cardJson.getBytes(StandardCharsets.UTF_8));
        } catch (CloneNotSupportedException ex) {
            throw new IllegalStateException("SHA-256 digest not cloneable", ex);
        }
        char[] chars = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            int b = hash[i] & 0xFF;
            chars[i << 1] = HEX_DIGITS[b >>> 4];
            chars[(i << 1) + 1] = HEX_DIGITS[b & 0x0F];
        }
        return new String(chars);
    }
}
