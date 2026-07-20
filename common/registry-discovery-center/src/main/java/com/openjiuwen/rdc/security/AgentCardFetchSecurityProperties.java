package com.openjiuwen.rdc.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Boot binding for Agent Card fetch hardening (Feat-015 / 0711 §5.1.3).
 *
 * <p>Covers optional mTLS material, HTTP deadlines, JWS signer trust, and CIDR
 * allowlists — not a generic Jersey/REST client configuration.
 */
@Component
@ConfigurationProperties(prefix = "rdc.registry.card-fetch")
public class AgentCardFetchSecurityProperties {

    private boolean mtlsEnabled;

    @NestedConfigurationProperty
    private final ClientTlsMaterial clientTls = new ClientTlsMaterial();

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

    public ClientTlsMaterial getClientTls() {
        return clientTls;
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

    /**
     * PKCS#12 (or JKS) locations used only when {@link #isMtlsEnabled()} is true.
     *
     * <p>YAML under {@code rdc.registry.card-fetch.client-tls.*}.
     */
    public static final class ClientTlsMaterial {

        private String identityFile;
        private String identityPassword;
        private String identityType = "PKCS12";
        private String trustAnchorFile;
        private String trustAnchorPassword;
        private String trustAnchorType = "PKCS12";

        public String getIdentityFile() {
            return identityFile;
        }

        public void setIdentityFile(String identityFile) {
            this.identityFile = identityFile;
        }

        public String getIdentityPassword() {
            return identityPassword;
        }

        public void setIdentityPassword(String identityPassword) {
            this.identityPassword = identityPassword;
        }

        public String getIdentityType() {
            return identityType;
        }

        public void setIdentityType(String identityType) {
            this.identityType = identityType != null ? identityType : "PKCS12";
        }

        public String getTrustAnchorFile() {
            return trustAnchorFile;
        }

        public void setTrustAnchorFile(String trustAnchorFile) {
            this.trustAnchorFile = trustAnchorFile;
        }

        public String getTrustAnchorPassword() {
            return trustAnchorPassword;
        }

        public void setTrustAnchorPassword(String trustAnchorPassword) {
            this.trustAnchorPassword = trustAnchorPassword;
        }

        public String getTrustAnchorType() {
            return trustAnchorType;
        }

        public void setTrustAnchorType(String trustAnchorType) {
            this.trustAnchorType = trustAnchorType != null ? trustAnchorType : "PKCS12";
        }
    }
}
