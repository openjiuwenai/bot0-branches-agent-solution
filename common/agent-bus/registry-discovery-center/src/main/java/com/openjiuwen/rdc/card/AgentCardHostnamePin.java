/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import java.lang.reflect.Method;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Optional;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Pins TLS hostname verification to the pre-resolve Agent Card host when the
 * request URI uses a literal IP (DNS pin / TOCTOU mitigation).
 *
 * @since 0.1.0 (2026)
 */
final class AgentCardHostnamePin {
    private static final ThreadLocal<String> EXPECTED_HOSTNAME = new ThreadLocal<>();
    private static final HostnameVerifier HOSTNAME_VERIFIER = resolveHostnameVerifier();
    private static final X509Certificate[] NO_ISSUERS = new X509Certificate[0];

    private AgentCardHostnamePin() {
    }

    static void setExpectedHostname(String hostname) {
        EXPECTED_HOSTNAME.set(hostname);
    }

    static void clear() {
        EXPECTED_HOSTNAME.remove();
    }

    static TrustManager[] wrap(TrustManager[] managers) {
        Objects.requireNonNull(managers, "managers");
        TrustManager[] out = new TrustManager[managers.length];
        for (int index = 0; index < managers.length; index++) {
            TrustManager current = managers[index];
            out[index] = current instanceof X509TrustManager x509
                    ? new HostPinnedTrust(x509)
                    : current;
        }
        return out;
    }

    private static HostnameVerifier resolveHostnameVerifier() {
        for (String typeName : new String[] {
                "java.net.HttpsURLConnection",
                "javax.net.ssl.HttpsURLConnection"
        }) {
            try {
                Method getter = Class.forName(typeName).getMethod("getDefaultHostnameVerifier");
                Object value = getter.invoke(null);
                if (value instanceof HostnameVerifier verifier) {
                    return verifier;
                }
            } catch (ReflectiveOperationException ignored) {
                // try alternate JDK package
            }
        }
        return (hostname, session) -> false;
    }

    /**
     * Trusts the peer via the configured PKIX manager, then verifies the leaf
     * against {@link #EXPECTED_HOSTNAME} (not against the connect IP).
     */
    private static final class HostPinnedTrust extends X509ExtendedTrustManager {
        private final X509TrustManager backend;

        HostPinnedTrust(X509TrustManager backend) {
            this.backend = Objects.requireNonNull(backend, "backend");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] issuers = backend.getAcceptedIssuers();
            return issuers == null ? NO_ISSUERS : issuers;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            backend.checkClientTrusted(requireChain(chain), authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            backend.checkServerTrusted(requireChain(chain), authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            trustClient(requireChain(chain), authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            trustClient(requireChain(chain), authType, engine);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            // Skip JSSE endpoint-ID against the IP literal; pin to ThreadLocal hostname.
            backend.checkServerTrusted(requireChain(chain), authType);
            assertExpectedHostname(handshakeSession(socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            backend.checkServerTrusted(requireChain(chain), authType);
            assertExpectedHostname(handshakeSession(engine));
        }

        private void trustClient(X509Certificate[] chain, String authType, Object connection)
                throws CertificateException {
            if (backend instanceof X509ExtendedTrustManager extended) {
                if (connection instanceof Socket socket) {
                    extended.checkClientTrusted(chain, authType, socket);
                    return;
                }
                if (connection instanceof SSLEngine engine) {
                    extended.checkClientTrusted(chain, authType, engine);
                    return;
                }
            }
            backend.checkClientTrusted(chain, authType);
        }

        private static X509Certificate[] requireChain(X509Certificate[] chain)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("empty certificate chain");
            }
            return chain;
        }

        private static Optional<SSLSession> handshakeSession(Socket socket) {
            if (!(socket instanceof SSLSocket sslSocket)) {
                return Optional.empty();
            }
            SSLSession handshake = sslSocket.getHandshakeSession();
            return Optional.ofNullable(handshake != null ? handshake : sslSocket.getSession());
        }

        private static Optional<SSLSession> handshakeSession(SSLEngine engine) {
            if (engine == null) {
                return Optional.empty();
            }
            SSLSession handshake = engine.getHandshakeSession();
            return Optional.ofNullable(handshake != null ? handshake : engine.getSession());
        }

        private static void assertExpectedHostname(Optional<SSLSession> maybeSession)
                throws CertificateException {
            if (maybeSession.isEmpty()) {
                throw new CertificateException("missing SSL session for hostname verification");
            }
            SSLSession session = maybeSession.get();
            String expected = EXPECTED_HOSTNAME.get();
            if (expected == null || expected.isBlank()) {
                expected = session.getPeerHost();
            }
            if (expected == null || expected.isBlank()) {
                throw new CertificateException("missing expected hostname for Agent Card TLS");
            }
            if (!HOSTNAME_VERIFIER.verify(expected, session)) {
                throw new CertificateException("hostname verification failed for " + expected);
            }
        }
    }
}
