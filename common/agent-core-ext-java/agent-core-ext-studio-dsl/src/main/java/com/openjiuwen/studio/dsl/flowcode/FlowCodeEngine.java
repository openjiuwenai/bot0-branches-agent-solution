/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowcode;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.contract.PythonCodeExecutor;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.python.PythonCodeRunners;
import com.openjiuwen.studio.dsl.python.PythonExecRequest;
import com.openjiuwen.studio.dsl.python.PythonExecResult;
import com.openjiuwen.studio.dsl.util.FlowCodeSchemaSupport;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;

/**
 * Strict 1:1 of Python {@code agent_runtime...flow_code.FlowCode}.
 *
 * <p>Runners: local inprocess|subprocess + sandbox (fallback). Schema coerce + {@code CODE_BLACK_LIST}.
 * Traces {@code function_log} / {@code code_info} like Python.
 *
 * @since 2026-08-26
 */

public final class FlowCodeEngine {
    private static final Logger LOG = Logger.getLogger(FlowCodeEngine.class.getName());
    /**
     * USER_FIELDS.
     * @since 0.1.0
     */
    public static final String USER_FIELDS = "userFields";
    /**
     * JIUWEN_CODE_TYPE.
     * @since 0.1.0
     */
    public static final String JIUWEN_CODE_TYPE = "jiuwen.code";

    private final String nodeId;
    private Map<String, Object> conf = Map.of();
    private String code = "";
    private String execEnv = "local";
    private Map<String, Object> userFieldsConf = Map.of();
    private StudioDslNodeProperties props;
    private PythonCodeExecutor fallbackSubprocess;
    private String tenantId;
    private String workflowId;

    /**
     * FlowCodeEngine.
     * @param nodeId nodeId
     * @since 0.1.0
     */
    public FlowCodeEngine(String nodeId) {
        this.nodeId = nodeId == null ? "code" : nodeId;
    }

    /**
     * setBuildContext.
     *
     * @param props props
     * @param fallbackSubprocess fallbackSubprocess
     * @param tenantId tenantId
     * @param workflowId workflowId
     * @since 0.1.0
     */

    public void setBuildContext(
            StudioDslNodeProperties props,
            PythonCodeExecutor fallbackSubprocess,
            String tenantId,
            String workflowId) {
        this.props = props;
        this.fallbackSubprocess = fallbackSubprocess;
        this.tenantId = tenantId;
        this.workflowId = workflowId;
    }

    /**
     * Python {@code _init_conf} / {@code init}.
     *
     * @param conf node configs
     */

    @SuppressWarnings("unchecked")
    public void init(Map<String, Object> conf) {
        Map<String, Object> c = conf == null ? Map.of() : new LinkedHashMap<>(conf);
        this.conf = c;
        this.code = str(c.get("code"));
        if (this.code.isBlank()) {
            throw new NodeExecutionException(
                    nodeId,
                    JIUWEN_CODE_TYPE,
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "code must be a non-empty string");
        }
        String env = str(c.get("exec_env"));
        if (env.isBlank()) {
            env = str(c.get("execEnv"));
        }
        this.execEnv = PythonCodeRunners.normalizeExecEnv(env);
        Object uf = c.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            this.userFieldsConf = copy;
        } else {
            this.userFieldsConf = Map.of();
        }
    }

    /**
     * code.
     *
     * @return result
     * @since 0.1.0
     */

    public String code() {
        return code;
    }

    /**
     * execEnv.
     *
     * @return result
     * @since 0.1.0
     */

    public String execEnv() {
        return execEnv;
    }

    /**
     * Python {@code invoke}.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return {userFields: result}
     */

    public Map<String, Object> invoke(
            Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        try {
            FlowCodeSchemaSupport.checkBlacklist(nodeId, code);

            String localMode = resolveLocalExecMode();
            PythonCodeExecutor executor =
                    PythonCodeRunners.resolve(execEnv, localMode, fallbackSubprocess);

            Map<String, Object> userFields = userFieldsOf(inputs);
            Object inputsSchema = userFieldsConf.get("inputs");
            if (inputsSchema instanceof List<?> list && !list.isEmpty()) {
                userFields = FlowCodeSchemaSupport.coerceInputs(userFields, conf);
            }

            long timeoutMs = resolveTimeoutMs();
            PythonExecRequest request = buildRequest(userFields, timeoutMs);

            PythonExecResult result;
            PythonCodeExecutor active = executor;
            try {
                result = executor.execute(request);
            } catch (NodeExecutionException e) {
                boolean wantSandbox = "sandbox".equals(execEnv);
                boolean isTimeout = e.causeCode() == NodeCauseCode.PYTHON_TIMEOUT
                        || (e.getMessage() != null && e.getMessage().toLowerCase().contains("timeout"));
                if (wantSandbox && isTimeout) {
                    throw new NodeExecutionException(
                            nodeId,
                            JIUWEN_CODE_TYPE,
                            NodeCauseCode.PYTHON_TIMEOUT,
                            "代码执行超时（已超过 " + (timeoutMs / 1000) + " 秒限制），"
                                    + "请优化代码或调整 SECURITY_SANDBOX_TIMEOUT 配置",
                            e);
                }
                if (wantSandbox && !isTimeout) {
                    if (PythonCodeRunners.isSandboxStrict()) {
                        throw e;
                    }
                    LOG.log(
                            Level.WARNING,
                            "node "
                                    + nodeId
                                    + ": sandbox execution failed; falling back to local subprocess: "
                                    + e.getMessage());
                    active = PythonCodeRunners.resolveLocal(localMode, fallbackSubprocess);
                    result = active.execute(request);
                } else {
                    throw e;
                }
            }

            String functionLog = functionLogOf(result);
            if (functionLog != null && !functionLog.isBlank()) {
                trace(session, Map.of("function_log", functionLog));
            }

            Map<String, Object> resultDict = result.outputs() == null ? Map.of() : result.outputs();
            Object outputsSchema = userFieldsConf.get("outputs");
            if (outputsSchema instanceof List<?> list && !list.isEmpty()) {
                resultDict = FlowCodeSchemaSupport.coerceOutputs(resultDict, conf);
            }

            Map<String, Object> codeInfo = new LinkedHashMap<>();
            codeInfo.put("inputs", userFields);
            codeInfo.put("outputs", resultDict);
            trace(session, Map.of("code_info", codeInfo));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put(USER_FIELDS, resultDict);
            return out;
        } catch (NodeExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NodeExecutionException(
                    nodeId, JIUWEN_CODE_TYPE, NodeCauseCode.NODE_INVOKE_FAILED, String.valueOf(e.getMessage()), e);
        }
    }

    /**
     * * Python {@code stream} — single yield of invoke result.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     * @since 0.1.0
     */
    public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
        Map<String, Object> in = asMap(inputs);
        Map<String, Object> result = invoke(in, session, context);
        return List.<Object>of(result).iterator();
    }

    private PythonExecRequest buildRequest(Map<String, Object> userFields, long timeoutMs) {
        String interpreter = str(conf.get("interpreter"));
        if (interpreter.isBlank() && props != null) {
            interpreter = props.getPythonInterpreter();
        }
        return new PythonExecRequest(
                nodeId,
                code,
                userFields,
                timeoutMs,
                interpreter,
                tenantId,
                workflowId,
                props == null ? null : props.getPythonWorkdirRoot(),
                props != null && props.isPythonInheritEnv(),
                props == null ? null : props.getPythonEnvWhitelist());
    }

    private long resolveTimeoutMs() {
        Object t = conf.get("timeoutMs");
        if (t instanceof Number n) {
            return n.longValue();
        }
        // Python: settings.security_sandbox.timeout_seconds
        String envSec = System.getenv("SECURITY_SANDBOX_TIMEOUT");
        if (envSec != null && !envSec.isBlank()) {
            try {
                return Long.parseLong(envSec.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (props != null) {
            return props.getPythonDefaultTimeoutMs();
        }
        return 30_000L;
    }

    private String resolveLocalExecMode() {
        String fromConfig = str(conf.get("localExecMode"));
        if (!fromConfig.isBlank()) {
            return fromConfig;
        }
        if (props != null) {
            String fromProps = props.getLocalExecMode();
            if (fromProps != null && !fromProps.isBlank()) {
                return fromProps;
            }
            String pe = props.getPythonExecutor();
            if ("inprocess".equalsIgnoreCase(pe) || "subprocess".equalsIgnoreCase(pe)) {
                return pe;
            }
        }
        return PythonCodeRunners.defaultLocalExecMode();
    }

    /**
     * Console log: wrapped runner writes print capture to stderr.
     *
     * @param result result
     * @return result
     * @since 0.1.0
     */
    private static String functionLogOf(PythonExecResult result) {
        if (result == null) {
        return "";
    }
        String err = result.stderr();
        if (err != null && !err.isBlank()) {
            return err;
        }
        return "";
    }

    private static void trace(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
        return;
    }
        try {
            session.trace(data);
        } catch (RuntimeException ignored) {
            // Python soft-fails function_log / code_info
        }
    }

    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        Object uf = inputs.get(USER_FIELDS);
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o == null) {
            return new LinkedHashMap<>();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(Map.of("value", o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
