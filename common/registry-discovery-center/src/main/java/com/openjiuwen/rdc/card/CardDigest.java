package com.openjiuwen.rdc.card;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** SHA-256 digest of Agent Card JSON for change detection. */
public final class CardDigest {

    private CardDigest() {
    }

    public static String sha256(String cardJson) {
        if (cardJson == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cardJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
