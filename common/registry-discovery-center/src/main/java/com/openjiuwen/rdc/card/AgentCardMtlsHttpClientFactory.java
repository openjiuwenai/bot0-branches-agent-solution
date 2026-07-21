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
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds {@link HttpClient} for Agent Card fetch, optionally with mTLS client credentials.
 *
 * <p>Always installs a hostname-pinning {@link SSLContext} so DNS-pinned IP URIs still verify
 * certificates against the original hostname. Enables the restricted {@code Host} request header
 * required to send that original name to the origin.
 *
 * @since 0.1.0 (2026)
 */
final class AgentCardMtlsHttpClientFactory {
    private static final String ALLOW_RESTRICTED_HEADERS = "jdk.httpclient.allowRestrictedHeaders";

    private AgentCardMtlsHttpClientFactory() {
    }

    static HttpClient create(RdcCardFetchOptions options) {
        Objects.requireNonNull(options, "options");
        allowHostRequestHeader();
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(options.getDialDeadline())
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(buildSslContext(options));
        return builder.build();
    }

    private static void allowHostRequestHeader() {
        synchronized (AgentCardMtlsHttpClientFactory.class) {
            String current = System.getProperty(ALLOW_RESTRICTED_HEADERS, "");
            boolean already = Arrays.stream(current.split(","))
                    .map(String::trim)
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .anyMatch("host"::equals);
            if (already) {
                return;
            }
            System.setProperty(ALLOW_RESTRICTED_HEADERS,
                    current.isBlank() ? "host" : current + ",host");
        }
    }

    private static SSLContext buildSslContext(RdcCardFetchOptions options) {
        try {
            KeyManager[] keyManagers = null;
            if (options.isMutualTls()
                    && options.getClientPkcs12Location() != null
                    && !options.getClientPkcs12Location().isBlank()) {
                KeyStore identity = loadStore(
                        options.getClientPkcs12Location(),
                        options.getClientPkcs12Secret(),
                        options.getClientPkcs12Format());
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(identity, secretChars(options.getClientPkcs12Secret()));
                keyManagers = kmf.getKeyManagers();
            }

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            if (options.isMutualTls()
                    && options.getTrustPkcs12Location() != null
                    && !options.getTrustPkcs12Location().isBlank()) {
                KeyStore trust = loadStore(
                        options.getTrustPkcs12Location(),
                        options.getTrustPkcs12Secret(),
                        options.getTrustPkcs12Format());
                tmf.init(trust);
            } else {
                KeyStore defaultTrustStore = null;
                tmf.init(defaultTrustStore);
            }
            TrustManager[] trustManagers = AgentCardHostnamePin.wrap(tmf.getTrustManagers());

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, null);
            return sslContext;
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | UnrecoverableKeyException
                | CertificateException | KeyManagementException ex) {
            throw new IllegalStateException("failed to configure TLS for Agent Card fetch", ex);
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
