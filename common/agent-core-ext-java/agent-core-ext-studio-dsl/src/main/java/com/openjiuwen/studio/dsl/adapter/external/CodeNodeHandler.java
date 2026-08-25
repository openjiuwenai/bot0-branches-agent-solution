/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.python.PythonExecRequest;
import com.openjiuwen.studio.dsl.python.PythonExecResult;
import com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor;
import com.openjiuwen.studio.dsl.spi.CodeLogic;
import com.openjiuwen.studio.dsl.spi.CodeLogicContext;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.spi.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.util.TypeCoercer;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.code — Java SPI + Python subprocess (FEAT-031 / L2 §3.8 / §4.4).
 *
 * @since 2026-08-17
 */
public final class CodeNodeHandler implements NodeHandlerFactory {
    /**
     * canonicalType.
     *
     * @return result
     */
    @Override
    public String canonicalType() {
        return "jiuwen.code";
    }

    /**
     * aliases.
     *
     * @return result
     */
    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    /**
     * create.
     *
     * @param node node
     * @param ctx ctx
     * @return result
     */
    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new CodeExecutable(node, ctx);
    }

    static final class CodeExecutable extends AbstractStudioNode {
        private final NodeBuildContext ctx;

        CodeExecutable(AssembledNode node, NodeBuildContext ctx) {
            super(node);
            this.ctx = ctx;
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @throws Exception when the call fails
         */
        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
                throws Exception {
            Map<String, Object> configs = node.configs();
            String language = stringVal(configs.get("language"));
            String codeLogicRef = stringVal(configs.get("codeLogicRef"));
            String code = stringVal(configs.get("code"));
            boolean javaPath = "java".equalsIgnoreCase(language) || !codeLogicRef.isBlank();
            boolean pyPath = "python".equalsIgnoreCase(language) || !code.isBlank();

            if (!language.isBlank()
                    && !"java".equalsIgnoreCase(language)
                    && !"python".equalsIgnoreCase(language)) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.code",
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "unsupported language=" + language + " (only java|python; D13)");
            }
            if (javaPath && pyPath && language.isBlank()) {
                throw new NodeExecutionException(
                        node.id(), "jiuwen.code", NodeCauseCode.CODE_PATH_AMBIGUOUS, "both java and python declared");
            }
            if (javaPath && (language.isBlank() || "java".equalsIgnoreCase(language))) {
                return runJava(codeLogicRef, inputs);
            }
            if (pyPath) {
                return runPython(code, inputs, configs);
            }
            throw new NodeExecutionException(
                    node.id(), "jiuwen.code", NodeCauseCode.NODE_CONFIG_INVALID, "missing code or codeLogicRef");
        }

        private NodePayload runJava(String ref, Map<String, Object> inputs) throws Exception {
            StudioDslNodeProperties props = ctx.properties();
            if (props != null && !props.isJavaSpiEnabled()) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.code",
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "java SPI disabled (studio-dsl.code.java-spi-enabled=false)");
            }
            if (ref == null || ref.isBlank()) {
                throw new NodeExecutionException(
                        node.id(), "jiuwen.code", NodeCauseCode.NODE_CONFIG_INVALID, "codeLogicRef required");
            }
            CodeLogic logic = ctx.codeLogicRegistry()
                    .find(ref)
                    .orElseThrow(() -> new NodeExecutionException(
                            node.id(), "jiuwen.code", NodeCauseCode.CODE_LOGIC_NOT_FOUND, "ref=" + ref));
            Map<String, Object> coerced = coerceInputs(userFieldsOf(inputs), node.configs());
            Map<String, Object> result = logic.execute(coerced, new CodeLogicContext(node.id(), node.configs()));
            return NodePayload.userFields(result);
        }

        private NodePayload runPython(String script, Map<String, Object> inputs, Map<String, Object> configs) {
            PythonCodeExecutor executor = ctx.pythonExecutor() == null
                    ? new SubprocessPythonCodeExecutor()
                    : ctx.pythonExecutor();
            StudioDslNodeProperties props = ctx.properties();
            long timeoutMs = props != null ? props.getPythonDefaultTimeoutMs() : 30_000L;
            Object t = configs.get("timeoutMs");
            if (t instanceof Number n) {
                timeoutMs = n.longValue();
            }
            String interpreter = stringVal(configs.get("interpreter"));
            if (interpreter.isBlank() && props != null) {
                interpreter = props.getPythonInterpreter();
            }
            Map<String, Object> coerced = coerceInputs(userFieldsOf(inputs), configs);
            PythonExecResult result = executor.execute(new PythonExecRequest(
                    node.id(),
                    script,
                    coerced,
                    timeoutMs,
                    interpreter,
                    ctx.tenantId(),
                    ctx.workflowId(),
                    props == null ? null : props.getPythonWorkdirRoot(),
                    props != null && props.isPythonInheritEnv(),
                    props == null ? null : props.getPythonEnvWhitelist()));
            return NodePayload.userFields(result.outputs());
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> coerceInputs(Map<String, Object> inputs, Map<String, Object> configs) {
            Object schema = configs.getOrDefault("userFieldsSchema", configs.get("userFields"));
            if (schema instanceof Map<?, ?> m) {
                Map<String, Object> sch = new java.util.LinkedHashMap<>();
                m.forEach((k, v) -> sch.put(String.valueOf(k), v));
                return TypeCoercer.coerceMap(inputs, sch);
            }
            return inputs;
        }

        private static String stringVal(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
