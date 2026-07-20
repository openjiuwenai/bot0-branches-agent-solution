/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import com.openjiuwen.rdc.security.RdcCardFetchOptions;

import java.io.InputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.Objects;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds {@link HttpClient} for Agent Card fetch, optionally with mTLS client credentials.
 */
final class AgentCardMtlsHttpClientFactory {

    private AgentCardMtlsHttpClientFactory() {
    }

    static HttpClient create(RdcCardFetchOptions options) {
        Objects.requireNonNull(options, "options");
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(options.getDialDeadline())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (options.isMutualTls()) {
            builder.sslContext(buildSslContext(options));
        }
        return builder.build();
    }

    private static SSLContext buildSslContext(RdcCardFetchOptions options) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            KeyManagerFactory kmf = null;
            if (options.getClientPkcs12Location() != null && !options.getClientPkcs12Location().isBlank()) {
                KeyStore identity = loadStore(
                        options.getClientPkcs12Location(),
                        options.getClientPkcs12Secret(),
                        options.getClientPkcs12Format());
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(identity, secretChars(options.getClientPkcs12Secret()));
            }
            TrustManagerFactory tmf = null;
            if (options.getTrustPkcs12Location() != null && !options.getTrustPkcs12Location().isBlank()) {
                KeyStore trust = loadStore(
                        options.getTrustPkcs12Location(),
                        options.getTrustPkcs12Secret(),
                        options.getTrustPkcs12Format());
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trust);
            }
            sslContext.init(
                    kmf != null ? kmf.getKeyManagers() : null,
                    tmf != null ? tmf.getTrustManagers() : null,
                    null);
            return sslContext;
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException
                | CertificateException | KeyManagementException ex) {
            throw new IllegalStateException("failed to configure mTLS for Agent Card fetch", ex);
        }
    }

    private static KeyStore loadStore(String path, String secret, String format)
            throws IOException, KeyStoreException, NoSuchAlgorithmException, CertificateException {
        KeyStore store = KeyStore.getInstance(format != null ? format : "PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, secretChars(secret));
        }
        return store;
    }

    private static char[] secretChars(String secret) {
        return secret != null ? secret.toCharArray() : new char[0];
    }
}
