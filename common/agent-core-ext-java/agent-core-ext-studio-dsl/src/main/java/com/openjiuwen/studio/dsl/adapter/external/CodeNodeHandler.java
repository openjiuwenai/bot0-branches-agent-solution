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
import com.openjiuwen.studio.dsl.python.PythonCodeRunners;
import com.openjiuwen.studio.dsl.python.PythonExecRequest;
import com.openjiuwen.studio.dsl.python.PythonExecResult;
import com.openjiuwen.studio.dsl.contract.CodeLogic;
import com.openjiuwen.studio.dsl.contract.CodeLogicContext;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.util.FlowCodeSchemaSupport;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.code — Java CodeLogic + Python runners (local/inprocess/subprocess/sandbox) (FEAT-031 / Python FlowCode).
 *
 * @since 2026-08-17
 */
public final class CodeNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.code";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

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
            if (props != null && !props.isJavaCodeLogicEnabled()) {
                throw new NodeExecutionException(
                        node.id(),
                        "jiuwen.code",
                        NodeCauseCode.NODE_CONFIG_INVALID,
                        "java CodeLogic disabled (studio-dsl.code.java-code-logic-enabled=false)");
            }
            if (ref == null || ref.isBlank()) {
                throw new NodeExecutionException(
                        node.id(), "jiuwen.code", NodeCauseCode.NODE_CONFIG_INVALID, "codeLogicRef required");
            }
            CodeLogic logic = ctx.codeLogicRegistry()
                    .find(ref)
                    .orElseThrow(() -> new NodeExecutionException(
                            node.id(), "jiuwen.code", NodeCauseCode.CODE_LOGIC_NOT_FOUND, "ref=" + ref));
            Map<String, Object> coerced = FlowCodeSchemaSupport.coerceInputs(userFieldsOf(inputs), node.configs());
            Map<String, Object> result = logic.execute(coerced, new CodeLogicContext(node.id(), node.configs()));
            return NodePayload.userFields(FlowCodeSchemaSupport.coerceOutputs(result, node.configs()));
        }

        private NodePayload runPython(String script, Map<String, Object> inputs, Map<String, Object> configs) {
            FlowCodeSchemaSupport.checkBlacklist(node.id(), script);

            StudioDslNodeProperties props = ctx.properties();
            String execEnv = stringVal(configs.get("exec_env"));
            if (execEnv.isBlank()) {
                execEnv = stringVal(configs.get("execEnv"));
            }
            String localMode = resolveLocalExecMode(configs, props);
            PythonCodeExecutor executor =
                    PythonCodeRunners.resolve(execEnv, localMode, ctx.pythonExecutor());

            long timeoutMs = props != null ? props.getPythonDefaultTimeoutMs() : 30_000L;
            Object t = configs.get("timeoutMs");
            if (t instanceof Number n) {
                timeoutMs = n.longValue();
            }
            String interpreter = stringVal(configs.get("interpreter"));
            if (interpreter.isBlank() && props != null) {
                interpreter = props.getPythonInterpreter();
            }
            Map<String, Object> coerced = FlowCodeSchemaSupport.coerceInputs(userFieldsOf(inputs), configs);
            PythonExecRequest request = new PythonExecRequest(
                    node.id(),
                    script,
                    coerced,
                    timeoutMs,
                    interpreter,
                    ctx.tenantId(),
                    ctx.workflowId(),
                    props == null ? null : props.getPythonWorkdirRoot(),
                    props != null && props.isPythonInheritEnv(),
                    props == null ? null : props.getPythonEnvWhitelist());

            PythonExecResult result;
            try {
                result = executor.execute(request);
            } catch (NodeExecutionException e) {
                // Python: sandbox non-timeout failure → fallback to local
                boolean wantSandbox = "sandbox".equals(PythonCodeRunners.normalizeExecEnv(execEnv));
                boolean isTimeout = e.causeCode() == NodeCauseCode.PYTHON_TIMEOUT;
                if (wantSandbox && !isTimeout) {
                    PythonCodeExecutor local = PythonCodeRunners.resolveLocal(localMode, ctx.pythonExecutor());
                    result = local.execute(request);
                } else {
                    throw e;
                }
            }

            Map<String, Object> outputs = FlowCodeSchemaSupport.coerceOutputs(result.outputs(), configs);
            return NodePayload.userFields(outputs);
        }

        private static String resolveLocalExecMode(Map<String, Object> configs, StudioDslNodeProperties props) {
            String fromConfig = stringVal(configs.get("localExecMode"));
            if (!fromConfig.isBlank()) {
                return fromConfig;
            }
            if (props != null) {
                String fromProps = props.getLocalExecMode();
                if (fromProps != null && !fromProps.isBlank()) {
                    return fromProps;
                }
                // legacy: pythonExecutor property sometimes stores inprocess|subprocess
                String pe = props.getPythonExecutor();
                if ("inprocess".equalsIgnoreCase(pe) || "subprocess".equalsIgnoreCase(pe)) {
                    return pe;
                }
            }
            return PythonCodeRunners.defaultLocalExecMode();
        }

        private static String stringVal(Object o) {
            return o == null ? "" : String.valueOf(o);
        }
    }
}
