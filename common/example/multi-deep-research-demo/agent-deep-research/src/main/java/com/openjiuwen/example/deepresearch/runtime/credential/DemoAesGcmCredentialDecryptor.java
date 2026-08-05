/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime.credential;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * FEAT-005 参考实现：AES-256-GCM 版本的 {@link CredentialDecryptor}。
 *
 * <p>激活方式（opt-in）：yml/env 里设置
 * {@code openjiuwen.demo.deep-research.credential.mode=aes-gcm}。未设置时，
 * runtime 默认的 {@code PassthroughCredentialDecryptor} 保留（即明文透传路径不受影响），
 * 同一个 demo jar 同时支持两条路径。
 *
 * <p>密文格式：{@code base64( IV[12] || AES-GCM-ciphertext-with-tag[16] )} ——
 * NIST SP 800-38D 标准布局，与 {@link EncryptTokenCli} 的输出对齐。
 *
 * <p>密钥：32 字节 AES-256 密钥，hex 编码，通过配置项
 * {@code openjiuwen.demo.deep-research.credential.aes-key-hex}
 * （对应 env var {@code SKILLHUB_AES_KEY_HEX}）提供。仅存内存，不落盘、不入日志。
 *
 * @since 2026-07-26
 */
@Component
@ConditionalOnProperty(
        name = "openjiuwen.demo.deep-research.credential.mode",
        havingValue = "aes-gcm")
public class DemoAesGcmCredentialDecryptor implements CredentialDecryptor {
    private static final Logger LOG = LoggerFactory.getLogger(DemoAesGcmCredentialDecryptor.class);
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;

    private final byte[] key;

    public DemoAesGcmCredentialDecryptor(
            @Value("${openjiuwen.demo.deep-research.credential.aes-key-hex:}") String aesKeyHex) {
        if (aesKeyHex == null || aesKeyHex.isBlank()) {
            throw new IllegalStateException(
                    "openjiuwen.demo.deep-research.credential.aes-key-hex is required when credential.mode=aes-gcm");
        }
        byte[] decoded;
        try {
            decoded = HexFormat.of().parseHex(aesKeyHex.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("aes-key-hex is not a valid hex string", ex);
        }
        if (decoded.length != AES_KEY_BYTES) {
            throw new IllegalStateException(
                    "AES-256-GCM requires a 32-byte key; got " + decoded.length + " bytes");
        }
        this.key = decoded;
        LOG.info("DemoAesGcmCredentialDecryptor active (AES-256-GCM, key length={}B)", decoded.length);
    }

    @Override
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null || ciphertextBase64.isEmpty()) {
            return ciphertextBase64;
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(ciphertextBase64);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("ciphertext is not valid base64", ex);
        }
        if (blob.length <= IV_BYTES) {
            throw new IllegalStateException("ciphertext too short to contain a 12-byte IV");
        }
        // 密文布局：IV[12] || ciphertext-with-tag，按 IV_BYTES 切分两端
        byte[] iv = Arrays.copyOfRange(blob, 0, IV_BYTES);
        byte[] payload = Arrays.copyOfRange(blob, IV_BYTES, blob.length);

        try {
            SecretKey aesKey = new SecretKeySpec(key, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
            byte[] plaintext = cipher.doFinal(payload);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM decrypt failed: " + ex.getMessage(), ex);
        }
    }
}
