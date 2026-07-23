/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link PathMode} and {@link PathSelector} (FEAT-012 §0.2).
 *
 * <p>Covers: config parsing (direct/bus/case-insensitive/whitespace/blank/null
 * → DIRECT / invalid → fail-fast), selector mode/isBus/isDirect for both modes.
 */
class PathSelectorTest {

    // --- PathMode.fromConfig: valid values ---

    @ParameterizedTest
    @CsvSource({
            "direct,   DIRECT",
            "DIRECT,   DIRECT",
            "' direct ', DIRECT",
            "Direct,   DIRECT",
            "bus,      BUS",
            "BUS,      BUS",
            "' Bus ',  BUS",
    })
    void fromConfigAcceptsValidModes(String input, PathMode expected) {
        assertThat(PathMode.fromConfig(input)).isEqualTo(expected);
    }

    // --- PathMode.fromConfig: blank/null → DIRECT (lenient default) ---

    @Test
    void fromConfigBlankOrNullDefaultsDirect() {
        assertThat(PathMode.fromConfig(null)).isEqualTo(PathMode.DIRECT);
        assertThat(PathMode.fromConfig("")).isEqualTo(PathMode.DIRECT);
        assertThat(PathMode.fromConfig("   ")).isEqualTo(PathMode.DIRECT);
    }

    // --- PathMode.fromConfig: invalid → fail-fast ---

    @ParameterizedTest
    @ValueSource(strings = {"tube", "highway", "DIRECT_PATH", "bus!", "123", "null"})
    void fromConfigInvalidFailsFast(String invalid) {
        assertThatThrownBy(() -> PathMode.fromConfig(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gateway.path-mode");
    }

    // --- PathSelector: BUS mode ---

    @Test
    void selectorBusMode() {
        PathSelector selector = new PathSelector("bus");
        assertThat(selector.mode()).isEqualTo(PathMode.BUS);
        assertThat(selector.isBus()).isTrue();
        assertThat(selector.isDirect()).isFalse();
    }

    // --- PathSelector: DIRECT mode (explicit + default) ---

    @Test
    void selectorDirectModeExplicit() {
        PathSelector selector = new PathSelector("direct");
        assertThat(selector.mode()).isEqualTo(PathMode.DIRECT);
        assertThat(selector.isDirect()).isTrue();
        assertThat(selector.isBus()).isFalse();
    }

    @Test
    void selectorDirectModeDefault() {
        PathSelector selector = new PathSelector("");
        assertThat(selector.isDirect()).isTrue();
        assertThat(selector.isBus()).isFalse();
    }
}
