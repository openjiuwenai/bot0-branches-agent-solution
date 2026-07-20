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
 * @since 0.1.0
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

    public static RdcCardFetchOptions defaults() {
        return new RdcCardFetchOptions();
    }

    public boolean isMutualTls() {
        return mutualTls;
    }

    public void setMutualTls(boolean mutualTls) {
        this.mutualTls = mutualTls;
    }

    public String getClientPkcs12Location() {
        return clientPkcs12Location;
    }

    public void setClientPkcs12Location(String clientPkcs12Location) {
        this.clientPkcs12Location = clientPkcs12Location;
    }

    public String getClientPkcs12Secret() {
        return clientPkcs12Secret;
    }

    public void setClientPkcs12Secret(String clientPkcs12Secret) {
        this.clientPkcs12Secret = clientPkcs12Secret;
    }

    public String getClientPkcs12Format() {
        return clientPkcs12Format;
    }

    public void setClientPkcs12Format(String clientPkcs12Format) {
        this.clientPkcs12Format = clientPkcs12Format != null ? clientPkcs12Format : "PKCS12";
    }

    public String getTrustPkcs12Location() {
        return trustPkcs12Location;
    }

    public void setTrustPkcs12Location(String trustPkcs12Location) {
        this.trustPkcs12Location = trustPkcs12Location;
    }

    public String getTrustPkcs12Secret() {
        return trustPkcs12Secret;
    }

    public void setTrustPkcs12Secret(String trustPkcs12Secret) {
        this.trustPkcs12Secret = trustPkcs12Secret;
    }

    public String getTrustPkcs12Format() {
        return trustPkcs12Format;
    }

    public void setTrustPkcs12Format(String trustPkcs12Format) {
        this.trustPkcs12Format = trustPkcs12Format != null ? trustPkcs12Format : "PKCS12";
    }

    public Duration getDialDeadline() {
        return dialDeadline;
    }

    public void setDialDeadline(Duration dialDeadline) {
        this.dialDeadline = dialDeadline != null ? dialDeadline : Duration.ofSeconds(5);
    }

    public Duration getResponseDeadline() {
        return responseDeadline;
    }

    public void setResponseDeadline(Duration responseDeadline) {
        this.responseDeadline = responseDeadline != null ? responseDeadline : Duration.ofSeconds(10);
    }

    public boolean isVerifySignatures() {
        return verifySignatures;
    }

    public void setVerifySignatures(boolean verifySignatures) {
        this.verifySignatures = verifySignatures;
    }

    public Map<String, String> getSignerPemsByKid() {
        return signerPemsByKid;
    }

    public void setSignerPemsByKid(Map<String, String> signerPemsByKid) {
        this.signerPemsByKid = signerPemsByKid != null ? signerPemsByKid : new HashMap<>();
    }

    public List<String> getTargetCidrs() {
        return targetCidrs;
    }

    public void setTargetCidrs(List<String> targetCidrs) {
        this.targetCidrs = targetCidrs != null ? targetCidrs : new ArrayList<>();
    }
}
