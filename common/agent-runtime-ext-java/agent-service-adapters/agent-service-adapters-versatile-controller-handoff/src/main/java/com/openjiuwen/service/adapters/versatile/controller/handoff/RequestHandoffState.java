/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-ServeRequest handoff bookkeeping: redirect count, dedup keys, and the
 * target sequence for same-request loop detection. Created fresh by the handler
 * for every request execution; never shared across requests (spec 4.6).
 *
 * @since 2026-08-19
 */
public final class RequestHandoffState {
    private int redirectCount;
    private final Set<String> dedupKeys = new LinkedHashSet<>();
    private final List<String> targets = new ArrayList<>();
    private boolean observerDriven;

    int redirectCount() {
        return redirectCount;
    }

    void incrementRedirect() {
        redirectCount++;
    }

    Set<String> dedupKeys() {
        return dedupKeys;
    }

    List<String> targets() {
        return targets;
    }

    /**
     * 本请求内是否已有某次转调把 observer 驱动到终态。NOT_IN_SCOPE 弹回返回时保持
     * false —— 后续 DUPLICATE_MESSAGE 判重必须据此区分"首次已终态可静默跳过"与
     * "首次未终态需补驱动终态"，否则流会挂起。
     */
    boolean observerDriven() {
        return observerDriven;
    }

    void markObserverDriven() {
        observerDriven = true;
    }
}
