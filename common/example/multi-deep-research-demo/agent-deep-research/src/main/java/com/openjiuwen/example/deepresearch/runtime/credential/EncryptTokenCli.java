/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime.credential;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 生成 {@link DemoAesGcmCredentialDecryptor} 可解密的密文的 CLI 工具（纯 main，非 Spring bean）。
 *
 * <p>用法：
 * <pre>
 *   # 1) 生成 32 字节 AES-256 密钥（hex）
 *   openssl rand -hex 32
 *
 *   # 2) 用密钥加密明文 token
 *   java -cp agent-deep-research-0.1.0.jar \
 *       com.openjiuwen.example.deepresearch.runtime.credential.EncryptTokenCli \
 *       &lt;hex-key-64chars&gt; &lt;plaintext-token&gt;
 *
 *   # 3) 把输出（base64 密文）塞进 SKILLHUB_ENCRYPTED_TOKEN，
 *   #    密钥塞进 SKILLHUB_AES_KEY_HEX，启动 demo
 * </pre>
 *
 * <p>输出格式：{@code base64( IV[12] || AES-GCM-ciphertext-with-tag[16] )}。
 *
 * @since 2026-07-26
 */
public final class EncryptTokenCli {
    private static final Logger LOG = LoggerFactory.getLogger(EncryptTokenCli.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;

    private EncryptTokenCli() {
    }

    /**
     * CLI 入口，见类 javadoc 用法。异常路径抛出以让 JVM 以非零码退出，
     * 保持工具契约：唯一写入 {@code stdout} 的内容是 base64 密文本身。
     *
     * @param args {@code [hex-key] plaintext} 或 {@code plaintext}（此时 hex-key 取自
     *     {@code SKILLHUB_AES_KEY_HEX} 环境变量）
     * @throws IllegalArgumentException 参数缺失或 hex-key 无效
     * @throws GeneralSecurityException AES-GCM 初始化或加密失败
     * @throws IOException 写入 stdout 失败
     */
    public static void main(String[] args) throws GeneralSecurityException, IOException {
        if (args.length < 1 || args.length > 2) {
            logUsage();
            throw new IllegalArgumentException("expected 1 or 2 arguments (see usage above)");
        }

        String keyHex;
        String plaintext;
        if (args.length == 2) {
            keyHex = args[0];
            plaintext = args[1];
        } else {
            keyHex = System.getenv().getOrDefault("SKILLHUB_AES_KEY_HEX", "");
            plaintext = args[0];
        }

        if (keyHex == null || keyHex.isBlank()) {
            logUsage();
            throw new IllegalArgumentException(
                    "hex key not provided (arg[0] or SKILLHUB_AES_KEY_HEX env var)");
        }

        byte[] key = parseKey(keyHex.trim());

        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey aesKey = new SecretKeySpec(key, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);

        byte[] plainBytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] cipherBytes = cipher.doFinal(plainBytes);

        ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherBytes.length);
        buffer.put(iv);
        buffer.put(cipherBytes);
        byte[] out = buffer.array();

        writeOutput(Base64.getEncoder().encodeToString(out));
    }

    private static byte[] parseKey(String keyHex) {
        byte[] key;
        try {
            key = HexFormat.of().parseHex(keyHex);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("hex key is not a valid hex string: " + ex.getMessage(), ex);
        }
        if (key.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "AES-256-GCM requires a 32-byte key; got " + key.length + " bytes");
        }
        return key;
    }

    private static void logUsage() {
        LOG.info("Usage:");
        LOG.info("  java -cp <jar> {} <hex-key-64chars> <plaintext-token>",
                EncryptTokenCli.class.getName());
        LOG.info("  SKILLHUB_AES_KEY_HEX=<hex-key> java -cp <jar> {} <plaintext-token>",
                EncryptTokenCli.class.getName());
        LOG.info("Generate a fresh 32-byte AES key:");
        LOG.info("  openssl rand -hex 32");
    }

    // CLI contract: emit the base64 ciphertext on stdout so callers can pipe or
    // capture it. Write directly to the stdout file descriptor to keep SLF4J
    // (which prefixes every line with a timestamp) out of the tool's data path.
    // try-with-resources closes fd 1 at the end of main(); acceptable because
    // this is a one-shot CLI that exits right after and SLF4J is on stderr.
    private static void writeOutput(String base64) throws IOException {
        try (OutputStream stdout = new FileOutputStream(FileDescriptor.out)) {
            stdout.write((base64 + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        }
    }
}
