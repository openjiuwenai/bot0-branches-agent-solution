package com.openjiuwen.rdc.card;

import com.openjiuwen.rdc.security.AgentCardFetchSecurityProperties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Objects;

/**
 * Builds {@link HttpClient} for Agent Card fetch, optionally with mTLS client credentials.
 */
final class AgentCardMtlsHttpClientFactory {

    private AgentCardMtlsHttpClientFactory() {
    }

    static HttpClient create(AgentCardFetchSecurityProperties properties) {
        Objects.requireNonNull(properties, "properties");
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (properties.isMtlsEnabled()) {
            builder.sslContext(buildSslContext(properties));
        }
        return builder.build();
    }

    private static SSLContext buildSslContext(AgentCardFetchSecurityProperties properties) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            KeyManagerFactory kmf = null;
            if (properties.getKeyStorePath() != null && !properties.getKeyStorePath().isBlank()) {
                KeyStore keyStore = loadKeyStore(
                        properties.getKeyStorePath(),
                        properties.getKeyStorePassword(),
                        properties.getKeyStoreType());
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                char[] password = passwordChars(properties.getKeyStorePassword());
                kmf.init(keyStore, password);
            }
            TrustManagerFactory tmf = null;
            if (properties.getTrustStorePath() != null && !properties.getTrustStorePath().isBlank()) {
                KeyStore trustStore = loadKeyStore(
                        properties.getTrustStorePath(),
                        properties.getTrustStorePassword(),
                        properties.getTrustStoreType());
                tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
            }
            sslContext.init(
                    kmf != null ? kmf.getKeyManagers() : null,
                    tmf != null ? tmf.getTrustManagers() : null,
                    null);
            return sslContext;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to configure mTLS for Agent Card fetch", ex);
        }
    }

    private static KeyStore loadKeyStore(String path, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type != null ? type : "PKCS12");
        char[] pwd = passwordChars(password);
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            keyStore.load(in, pwd);
        }
        return keyStore;
    }

    private static char[] passwordChars(String password) {
        return password != null ? password.toCharArray() : new char[0];
    }
}
