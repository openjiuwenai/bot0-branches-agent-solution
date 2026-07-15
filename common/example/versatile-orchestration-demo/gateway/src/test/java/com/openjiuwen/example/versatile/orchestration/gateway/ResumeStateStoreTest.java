/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * ResumeStateStoreTest
 *
 * @since 2026-07-08
 */
class ResumeStateStoreTest {
    @Test
    void recordInputRequiredThenResume() {
        ResumeStateStore store = new ResumeStateStore();
        store.recordInputRequired("ctx-1", "task-7");

        assertThat(store.openTaskId("ctx-1")).isEqualTo(Optional.of("task-7"));
    }

    @Test
    void clearRemovesTask() {
        ResumeStateStore store = new ResumeStateStore();
        store.recordInputRequired("ctx-1", "task-7");
        store.clear("ctx-1");

        assertThat(store.openTaskId("ctx-1")).isEmpty();
    }

    @Test
    void latestTaskWins() {
        ResumeStateStore store = new ResumeStateStore();
        store.recordInputRequired("ctx-1", "task-7");
        store.recordInputRequired("ctx-1", "task-9");

        assertThat(store.openTaskId("ctx-1")).isEqualTo(Optional.of("task-9"));
    }

    @Test
    void nullContextOrTaskIsIgnored() {
        ResumeStateStore store = new ResumeStateStore();
        store.recordInputRequired(null, "task-1");
        store.recordInputRequired("ctx-1", null);

        assertThat(store.openTaskId("ctx-1")).isEmpty();
        assertThat(store.openTaskId(null)).isEmpty();
    }

    @Test
    void oneContextDoesNotLeakIntoAnother() {
        ResumeStateStore store = new ResumeStateStore();
        store.recordInputRequired("ctx-1", "task-1");
        store.recordInputRequired("ctx-2", "task-2");
        store.clear("ctx-1");

        assertThat(store.openTaskId("ctx-2")).isEqualTo(Optional.of("task-2"));
    }
}
