/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReclassifyPropertiesTest {
    @Test
    void defaultsToDisabledAndMaxOne() {
        ReclassifyProperties props = new ReclassifyProperties();
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getMaxReclassify()).isEqualTo(1);
    }

    @Test
    void canEnableAndSetMax() {
        ReclassifyProperties props = new ReclassifyProperties();
        props.setEnabled(true);
        props.setMaxReclassify(3);
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxReclassify()).isEqualTo(3);
    }

    @Test
    void negativeMaxCoercesToZero() {
        ReclassifyProperties props = new ReclassifyProperties();
        props.setMaxReclassify(-1);
        assertThat(props.getMaxReclassify()).isEqualTo(0);
    }
}
