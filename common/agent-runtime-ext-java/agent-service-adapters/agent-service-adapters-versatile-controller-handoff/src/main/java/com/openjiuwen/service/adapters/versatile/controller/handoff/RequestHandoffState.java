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
}
