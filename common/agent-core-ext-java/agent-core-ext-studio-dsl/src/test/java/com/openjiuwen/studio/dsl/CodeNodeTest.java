/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * jiuwen.code — Python FlowCode path only (no Java CodeLogic).
 *
 * @since 2026-08-17
 */
class CodeNodeTest {
    private static boolean pythonAvailable;

    @BeforeAll
    static void checkPython() throws Exception {
        try {
            Process p = new ProcessBuilder("python3", "-c", "print(1)").start();
            try (java.io.InputStream out = p.getInputStream();
                    java.io.InputStream err = p.getErrorStream()) {
                out.transferTo(java.io.OutputStream.nullOutputStream());
                err.transferTo(java.io.OutputStream.nullOutputStream());
                pythonAvailable = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
        } catch (IOException e) {
            pythonAvailable = false;
        }
    }

    @Test
    void pythonCode_executesMain() {
        assumeTrue(pythonAvailable, "python3 not available");
        StudioDslNodeProperties props = new StudioDslNodeProperties();
        props.setLocalExecMode("inprocess");
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of(
                        "c1",
                        "jiuwen.code",
                        Map.of(
                                "code",
                                "def main(args):\n    return {'n': int(args.get('n', 0)) * 2}\n")),
                NodeBuildContext.defaults("wf", props));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                exec.invoke(Map.of("userFields", Map.of("n", 21)), mock(NodeSessionApi.class), null);
        @SuppressWarnings("unchecked")
        Map<String, Object> uf = (Map<String, Object>) out.get("userFields");
        assertThat(uf.get("n")).isEqualTo(42L);
    }

    @Test
    void missingCode_failsConfig() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec =
                registry.create(AssembledNode.of("c1", "jiuwen.code", Map.of()), NodeBuildContext.defaults("wf"));
        assertThatThrownBy(() -> exec.invoke(Map.of(), mock(NodeSessionApi.class), null))
                .isInstanceOf(NodeExecutionException.class)
                .hasMessageContaining("code must be a non-empty string");
    }

    @Test
    void unsupportedLanguage_rejected() {
        NodeTypeRegistry registry = NodeTypeRegistry.createWithBuiltins();
        ComponentExecutable exec = registry.create(
                AssembledNode.of("c1", "jiuwen.code", Map.of("language", "java", "code", "x = 1")),
                NodeBuildContext.defaults("wf"));
        assertThatThrownBy(() -> exec.invoke(Map.of(), mock(NodeSessionApi.class), null))
                .isInstanceOf(NodeExecutionException.class)
                .extracting(e -> e instanceof NodeExecutionException ne ? ne.causeCode() : null)
                .isEqualTo(NodeCauseCode.NODE_CONFIG_INVALID);
    }
}
