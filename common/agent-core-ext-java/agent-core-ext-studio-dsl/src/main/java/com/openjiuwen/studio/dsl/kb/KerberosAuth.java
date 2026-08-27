/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.kb;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;

import java.io.File;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.Subject;

/**
 * Kerberos SPNEGO token for LakeSearch (Python {@code kerberos_auth.get_spnego_token}).
 *
 * @since 2026-08-26
 */

public final class KerberosAuth {
    private static final int DEFAULT_CREDS_LIFETIME_SECONDS = 3600;
    private static final ConcurrentHashMap<String, CachedSubject> SUBJECT_CACHE = new ConcurrentHashMap<>();

    private KerberosAuth() {}

    /**
     * getSpnegoToken.
     *
     * @param servicePrincipal e.g. {@code HTTP@hostname}
     * @param keytabPath keytab file path
     * @param krb5ConfPath krb5.conf path (nullable)
     * @param clientPrincipal optional principal; auto from keytab when blank
     * @return base64-encoded SPNEGO token (standard Base64, not URL-safe)
     */

    public static String getSpnegoToken(
            String servicePrincipal, String keytabPath, String krb5ConfPath, String clientPrincipal) {
        if (!new File(keytabPath).isFile()) {
        throw new IllegalStateException("Keytab file not found: " + keytabPath);
    }
        if (krb5ConfPath != null && !krb5ConfPath.isBlank() && !new File(krb5ConfPath).isFile()) {
            throw new IllegalStateException("krb5.conf file not found: " + krb5ConfPath);
        }
        String oldKrb5 = System.getProperty("java.security.krb5.conf");
        if (krb5ConfPath != null && !krb5ConfPath.isBlank()) {
            System.setProperty("java.security.krb5.conf", krb5ConfPath);
        }
        try {
            Subject subject = getOrLoginSubject(keytabPath, clientPrincipal);
            return Subject.doAs(
                    subject,
                    (PrivilegedExceptionAction<String>) () -> {
                        GSSManager manager = GSSManager.getInstance();
                        GSSName serverName =
                                manager.createName(servicePrincipal, GSSName.NT_HOSTBASED_SERVICE);
                        GSSContext context =
                                manager.createContext(serverName, null, null, GSSContext.DEFAULT_LIFETIME);
                        context.requestMutualAuth(false);
                        byte[] token = context.initSecContext(new byte[0], 0, 0);
                        if (token == null) {
                            throw new IllegalStateException("GSS initSecContext returned null token");
                        }
                        return Base64.getEncoder().encodeToString(token);
                    });
        } catch (LoginException | PrivilegedActionException e) {
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("Kerberos authentication failed: " + e.getMessage(), e);
        } finally {
            if (krb5ConfPath != null && !krb5ConfPath.isBlank()) {
                if (oldKrb5 != null) {
                    System.setProperty("java.security.krb5.conf", oldKrb5);
                } else {
                    System.clearProperty("java.security.krb5.conf");
                }
            }
        }
    }

    /**
     * clear cached subjects (tests).
     *
     * @since 0.1.0
     */
    public static void clearCache() {
        SUBJECT_CACHE.clear();
    }
    private static Subject getOrLoginSubject(String keytabPath, String clientPrincipal) throws LoginException {
        String cacheKey = keytabPath + "|" + (clientPrincipal == null ? "" : clientPrincipal);
        long now = System.currentTimeMillis();
        CachedSubject cached = SUBJECT_CACHE.get(cacheKey);
        if (cached != null && now < cached.expireAtMs) {
            return cached.subject;
        }
        Map<String, String> options = new HashMap<>();
        options.put("useKeyTab", "true");
        options.put("keyTab", keytabPath);
        options.put("storeKey", "true");
        options.put("doNotPrompt", "true");
        options.put("isInitiator", "true");
        if (clientPrincipal != null && !clientPrincipal.isBlank()) {
            options.put("principal", clientPrincipal);
        }
        Configuration jaas = new Configuration() {

            /**
             * getAppConfigurationEntry.
             *
             * @param name name
             * @return result
             * @since 0.1.0
             */

            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                return new AppConfigurationEntry[] {
                    new AppConfigurationEntry(
                            "com.sun.security.auth.module.Krb5LoginModule",
                            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                            options)
                };
            }
        };
        LoginContext lc = new LoginContext("KBClient", null, null, jaas);
        lc.login();
        Subject subject = lc.getSubject();
        SUBJECT_CACHE.put(
                cacheKey,
                new CachedSubject(subject, now + DEFAULT_CREDS_LIFETIME_SECONDS * 1000L));
        return subject;
    }

    /**
     * extractKerberosConfig.
     *
     * @param extraParams connection extra_params
     * @return config map or null when incomplete
     */

    public static Map<String, Object> extractKerberosConfig(Map<String, Object> extraParams) {
        if (extraParams == null) {
            return null;
        }
        String hostNames = KbHttp.str(extraParams.get("host_names"));
        String clusterIps = KbHttp.str(extraParams.get("cluster_ips"));
        String keytab = KbHttp.str(extraParams.get("user_keytab_file"));
        String krb5 = KbHttp.str(extraParams.get("krb5_file"));
        if (hostNames.isBlank() || clusterIps.isBlank() || keytab.isBlank() || krb5.isBlank()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("host_names", splitCsv(hostNames));
        out.put("cluster_ips", splitCsv(clusterIps));
        out.put("keytab_path", keytab);
        out.put("krb5_conf_path", krb5);
        out.put("port", KbHttp.str(extraParams.get("port")));
        out.put("protocol", KbHttp.str(extraParams.getOrDefault("protocol", "https")));
        return out;
    }

    /**
     * buildNegotiateAuthorization.
     *
     * @param hostname service host
     * @param kerberosConfig from {@link #extractKerberosConfig}
     * @return {@code Negotiate {token}}
     */

    public static String buildNegotiateAuthorization(String hostname, Map<String, Object> kerberosConfig) {
        if (hostname == null || hostname.isBlank()) {
        throw new IllegalStateException("Cannot extract hostname for Kerberos service principal");
    }
        String token =
                getSpnegoToken(
                        "HTTP@" + hostname,
                        KbHttp.str(kerberosConfig.get("keytab_path")),
                        KbHttp.str(kerberosConfig.get("krb5_conf_path")),
                        null);
        return "Negotiate " + token;
    }

    private static java.util.List<String> splitCsv(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private record CachedSubject(Subject subject, long expireAtMs) {
        }
    }