/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 digest of Agent Card JSON for change detection.
 *
 * @since 0.1.0 (2026)
  */
public final class CardDigest {

    private CardDigest() {
    }

    /**
     * sha256.
     * @param cardJson cardJson
     * @return result
     * @since 0.1.0
     */
    public static String sha256(String cardJson) {
        if (cardJson == null) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cardJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
