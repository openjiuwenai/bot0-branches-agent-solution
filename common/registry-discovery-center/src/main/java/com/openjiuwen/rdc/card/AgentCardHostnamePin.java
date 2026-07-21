/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import java.lang.reflect.Method;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

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
    private static final HostnameVerifier DEFAULT_HOSTNAME_VERIFIER = loadDefaultHostnameVerifier();

    private AgentCardHostnamePin() {
    }

    static void setExpectedHostname(String hostname) {
        EXPECTED_HOSTNAME.set(hostname);
    }

    static void clear() {
        EXPECTED_HOSTNAME.remove();
    }

    static TrustManager[] wrap(TrustManager[] managers) {
        TrustManager[] wrapped = new TrustManager[managers.length];
        for (int i = 0; i < managers.length; i++) {
            TrustManager manager = managers[i];
            if (manager instanceof X509ExtendedTrustManager extended) {
                wrapped[i] = new PinningTrustManager(extended);
            } else if (manager instanceof X509TrustManager plain) {
                wrapped[i] = new PinningTrustManager(plain);
            } else {
                wrapped[i] = manager;
            }
        }
        return wrapped;
    }

    private static HostnameVerifier loadDefaultHostnameVerifier() {
        for (String className : new String[] {
                "java.net.HttpsURLConnection",
                "javax.net.ssl.HttpsURLConnection"
        }) {
            try {
                Class<?> type = Class.forName(className);
                Method method = type.getMethod("getDefaultHostnameVerifier");
                Object verifier = method.invoke(null);
                if (verifier instanceof HostnameVerifier hostnameVerifier) {
                    return hostnameVerifier;
                }
            } catch (ReflectiveOperationException ignored) {
                // try next location (JDK builds differ on the declaring package)
            }
        }
        return (hostname, session) -> false;
    }

    private static final class PinningTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;
        private final X509ExtendedTrustManager extendedDelegate;

        PinningTrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
            this.extendedDelegate = delegate instanceof X509ExtendedTrustManager ext ? ext : null;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (extendedDelegate != null) {
                extendedDelegate.checkClientTrusted(chain, authType, socket);
            } else {
                delegate.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            // Two-arg path skips JSSE endpoint-ID against the IP literal; we verify the pinned host.
            delegate.checkServerTrusted(chain, authType);
            if (socket instanceof SSLSocket sslSocket) {
                verifyHostname(sslSocket.getHandshakeSession() != null
                        ? sslSocket.getHandshakeSession()
                        : sslSocket.getSession());
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (extendedDelegate != null) {
                extendedDelegate.checkClientTrusted(chain, authType, engine);
            } else {
                delegate.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verifyHostname(engine.getHandshakeSession() != null
                    ? engine.getHandshakeSession()
                    : engine.getSession());
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }

        private static void verifyHostname(SSLSession session) throws CertificateException {
            if (session == null) {
                throw new CertificateException("missing SSL session for hostname verification");
            }
            String expected = EXPECTED_HOSTNAME.get();
            if (expected == null || expected.isBlank()) {
                expected = session.getPeerHost();
            }
            if (expected == null || expected.isBlank()) {
                throw new CertificateException("missing expected hostname for Agent Card TLS");
            }
            if (!DEFAULT_HOSTNAME_VERIFIER.verify(expected, session)) {
                throw new CertificateException("hostname verification failed for " + expected);
            }
        }
    }
}
