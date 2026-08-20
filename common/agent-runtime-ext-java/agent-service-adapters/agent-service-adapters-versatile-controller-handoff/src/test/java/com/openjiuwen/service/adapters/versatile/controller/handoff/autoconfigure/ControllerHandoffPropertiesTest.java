/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThat(p.getLoop().getMaxRedirects()).isEqualTo(3);
        assertThat(p.getLoop().getMaxRouteTraceHops()).isEqualTo(8);
        assertThat(p.getLoop().isDuplicateTargetDetection()).isTrue();
        assertThat(p.getLoopTraceMetadata().getHopCountKey()).isEqualTo("handoffHopCount");
        assertThat(p.getLoopTraceMetadata().getRouteTraceKey()).isEqualTo("handoffRouteTrace");
        assertThat(p.getLoopTraceMetadata().getSourceAgentKey()).isEqualTo("sourceAgentId");
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
