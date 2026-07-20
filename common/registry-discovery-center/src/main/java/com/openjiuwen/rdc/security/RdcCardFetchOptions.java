/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feat-015 Agent Card fetch guard options ({@code rdc.registry.card-fetch.*}).
 *
 * <p>Domain-specific naming (mutual TLS material, dial/response deadlines, signer
 * PEMs, fetch CIDRs) — intentionally not shaped like a generic REST client SSL bean.
 *
 * @since 0.1.0 (2026)
 */
@Component
@ConfigurationProperties(prefix = "rdc.registry.card-fetch")
public class RdcCardFetchOptions {
    private boolean mutualTls;
    private String clientPkcs12Location;
    private String clientPkcs12Secret;
    private String clientPkcs12Format = "PKCS12";
    private String trustPkcs12Location;
    private String trustPkcs12Secret;
    private String trustPkcs12Format = "PKCS12";
    private Duration dialDeadline = Duration.ofSeconds(5);
    private Duration responseDeadline = Duration.ofSeconds(10);
    private boolean verifySignatures;
    private Map<String, String> signerPemsByKid = new HashMap<>();
    private List<String> targetCidrs = new ArrayList<>();

    /**
     * defaults.
     *
     * @return result
     * @since 0.1.0
     */
    public static RdcCardFetchOptions defaults() {
        return new RdcCardFetchOptions();
    }
    /**
     * isMutualTls.
     *
     * @return result
     * @since 0.1.0
     */
    public boolean isMutualTls() {
        return mutualTls;
    }
    /**
     * setMutualTls.
     *
     * @param mutualTls mutualTls
     * @since 0.1.0
     */
    public void setMutualTls(boolean mutualTls) {
        this.mutualTls = mutualTls;
    }
    /**
     * getClientPkcs12Location.
     *
     * @return result
     * @since 0.1.0
     */
    public String getClientPkcs12Location() {
        return clientPkcs12Location;
    }
    /**
     * setClientPkcs12Location.
     *
     * @param clientPkcs12Location clientPkcs12Location
     * @since 0.1.0
     */
    public void setClientPkcs12Location(String clientPkcs12Location) {
        this.clientPkcs12Location = clientPkcs12Location;
    }
    /**
     * getClientPkcs12Secret.
     *
     * @return result
     * @since 0.1.0
     */
    public String getClientPkcs12Secret() {
        return clientPkcs12Secret;
    }
    /**
     * setClientPkcs12Secret.
     *
     * @param clientPkcs12Secret clientPkcs12Secret
     * @since 0.1.0
     */
    public void setClientPkcs12Secret(String clientPkcs12Secret) {
        this.clientPkcs12Secret = clientPkcs12Secret;
    }
    /**
     * getClientPkcs12Format.
     *
     * @return result
     * @since 0.1.0
     */
    public String getClientPkcs12Format() {
        return clientPkcs12Format;
    }
    /**
     * setClientPkcs12Format.
     *
     * @param clientPkcs12Format clientPkcs12Format
     * @since 0.1.0
     */
    public void setClientPkcs12Format(String clientPkcs12Format) {
        this.clientPkcs12Format = clientPkcs12Format != null ? clientPkcs12Format : "PKCS12";
    }
    /**
     * getTrustPkcs12Location.
     *
     * @return result
     * @since 0.1.0
     */
    public String getTrustPkcs12Location() {
        return trustPkcs12Location;
    }
    /**
     * setTrustPkcs12Location.
     *
     * @param trustPkcs12Location trustPkcs12Location
     * @since 0.1.0
     */
    public void setTrustPkcs12Location(String trustPkcs12Location) {
        this.trustPkcs12Location = trustPkcs12Location;
    }
    /**
     * getTrustPkcs12Secret.
     *
     * @return result
     * @since 0.1.0
     */
    public String getTrustPkcs12Secret() {
        return trustPkcs12Secret;
    }
    /**
     * setTrustPkcs12Secret.
     *
     * @param trustPkcs12Secret trustPkcs12Secret
     * @since 0.1.0
     */
    public void setTrustPkcs12Secret(String trustPkcs12Secret) {
        this.trustPkcs12Secret = trustPkcs12Secret;
    }
    /**
     * getTrustPkcs12Format.
     *
     * @return result
     * @since 0.1.0
     */
    public String getTrustPkcs12Format() {
        return trustPkcs12Format;
    }
    /**
     * setTrustPkcs12Format.
     *
     * @param trustPkcs12Format trustPkcs12Format
     * @since 0.1.0
     */
    public void setTrustPkcs12Format(String trustPkcs12Format) {
        this.trustPkcs12Format = trustPkcs12Format != null ? trustPkcs12Format : "PKCS12";
    }
    /**
     * getDialDeadline.
     *
     * @return result
     * @since 0.1.0
     */
    public Duration getDialDeadline() {
        return dialDeadline;
    }
    /**
     * setDialDeadline.
     *
     * @param dialDeadline dialDeadline
     * @since 0.1.0
     */
    public void setDialDeadline(Duration dialDeadline) {
        this.dialDeadline = dialDeadline != null ? dialDeadline : Duration.ofSeconds(5);
    }
    /**
     * getResponseDeadline.
     *
     * @return result
     * @since 0.1.0
     */
    public Duration getResponseDeadline() {
        return responseDeadline;
    }
    /**
     * setResponseDeadline.
     *
     * @param responseDeadline responseDeadline
     * @since 0.1.0
     */
    public void setResponseDeadline(Duration responseDeadline) {
        this.responseDeadline = responseDeadline != null ? responseDeadline : Duration.ofSeconds(10);
    }
    /**
     * isVerifySignatures.
     *
     * @return result
     * @since 0.1.0
     */
    public boolean isVerifySignatures() {
        return verifySignatures;
    }
    /**
     * setVerifySignatures.
     *
     * @param verifySignatures verifySignatures
     * @since 0.1.0
     */
    public void setVerifySignatures(boolean verifySignatures) {
        this.verifySignatures = verifySignatures;
    }
    public Map<String, String> getSignerPemsByKid() {
        return signerPemsByKid;
    }

    /**
     * setSignerPemsByKid.
     *
     * @param signerPemsByKid signerPemsByKid
     * @since 0.1.0
     */
    public void setSignerPemsByKid(Map<String, String> signerPemsByKid) {
        this.signerPemsByKid = signerPemsByKid != null ? signerPemsByKid : new HashMap<>();
    }
    /**
     * getTargetCidrs.
     *
     * @return result
     * @since 0.1.0
     */
    public List<String> getTargetCidrs() {
        return targetCidrs;
    }
    /**
     * setTargetCidrs.
     *
     * @param targetCidrs targetCidrs
     * @since 0.1.0
     */
    public void setTargetCidrs(List<String> targetCidrs) {
        this.targetCidrs = targetCidrs != null ? targetCidrs : new ArrayList<>();
    }
}
