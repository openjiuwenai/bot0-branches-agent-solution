/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * ControllerHandoffProperties 验收：默认值契约与启动校验（识别条件不完整即失败，
 * spec 1.3 case 1 / 6.4）。
 *
 * @since 2026-08-19
 */
class ControllerHandoffPropertiesTest {
    private ControllerHandoffProperties full() {
        ControllerHandoffProperties p = new ControllerHandoffProperties();
        ControllerHandoffProperties.Classify c = new ControllerHandoffProperties.Classify();
        c.setFieldPath("/data/code");
        c.setFieldValue(List.of("14000"));
        p.setClassify(c);
        return p;
    }

    @Test
    void defaultsMatchSpec() {
        ControllerHandoffProperties p = new ControllerHandoffProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getTarget().getResolutionPriority())
                .containsExactly("direct", "intent", "domain");
        assertThat(p.getSignal().getHandoffTypes()).isEmpty();
        assertThat(p.getForwardMetadataKeys()).isEmpty();
    }

    @Test
    void validatePassesWhenIdentifiersComplete() {
        assertThatCode(full()::validateRequiredIdentifiers).doesNotThrowAnyException();
    }

    @Test
    void validateFailsWhenFieldPathMissing() {
        ControllerHandoffProperties p = full();
        p.getClassify().setFieldPath(null);
        assertThatThrownBy(p::validateRequiredIdentifiers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classify.field-path");
    }

    @Test
    void validateFailsWhenFieldValueEmpty() {
        ControllerHandoffProperties p = full();
        p.getClassify().setFieldValue(List.of());
        assertThatThrownBy(p::validateRequiredIdentifiers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classify.field-value");
    }

    @Test
    void validateFailsWhenClassifyAbsent() {
        assertThatThrownBy(new ControllerHandoffProperties()::validateRequiredIdentifiers)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classify");
    }

    @Test
    void eventTypeAloneIsNotACompleteIdentifier() {
        ControllerHandoffProperties p = full();
        p.getClassify().setFieldPath(null);
        p.getClassify().setFieldValue(List.of());
        p.getClassify().setEventType("controller_message");
        assertThatThrownBy(p::validateRequiredIdentifiers)
                .isInstanceOf(IllegalStateException.class);
    }
}
