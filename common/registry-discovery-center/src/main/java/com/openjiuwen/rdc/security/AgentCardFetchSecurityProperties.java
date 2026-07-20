package com.openjiuwen.rdc.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Card fetch security controls (0711 §5.1.3 — mTLS + optional signature verification).
 */
@Component
@ConfigurationProperties(prefix = "rdc.registry.card-fetch")
public class AgentCardFetchSecurityProperties {

    private boolean mtlsEnabled;
    private String keyStorePath;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";
    private String trustStorePath;
    private String trustStorePassword;
    private String trustStoreType = "PKCS12";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    private boolean signatureVerificationEnabled;
    /** keyId → PEM-encoded RSA public key. */
    private Map<String, String> trustedSignerKeys = new HashMap<>();
    /** Optional CIDR allowlist; empty = permissive (scheme checks only). */
    private List<String> allowedCidrs = new ArrayList<>();

    public boolean isMtlsEnabled() {
        return mtlsEnabled;
    }

    public void setMtlsEnabled(boolean mtlsEnabled) {
        this.mtlsEnabled = mtlsEnabled;
    }

    public String getKeyStorePath() {
        return keyStorePath;
    }

    public void setKeyStorePath(String keyStorePath) {
        this.keyStorePath = keyStorePath;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStoreType() {
        return keyStoreType;
    }

    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType != null ? keyStoreType : "PKCS12";
    }

    public String getTrustStorePath() {
        return trustStorePath;
    }

    public void setTrustStorePath(String trustStorePath) {
        this.trustStorePath = trustStorePath;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType != null ? trustStoreType : "PKCS12";
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(10);
    }

    public boolean isSignatureVerificationEnabled() {
        return signatureVerificationEnabled;
    }

    public void setSignatureVerificationEnabled(boolean signatureVerificationEnabled) {
        this.signatureVerificationEnabled = signatureVerificationEnabled;
    }

    public Map<String, String> getTrustedSignerKeys() {
        return trustedSignerKeys;
    }

    public void setTrustedSignerKeys(Map<String, String> trustedSignerKeys) {
        this.trustedSignerKeys = trustedSignerKeys != null ? trustedSignerKeys : new HashMap<>();
    }

    public List<String> getAllowedCidrs() {
        return allowedCidrs;
    }

    public void setAllowedCidrs(List<String> allowedCidrs) {
        this.allowedCidrs = allowedCidrs != null ? allowedCidrs : new ArrayList<>();
    }
}
