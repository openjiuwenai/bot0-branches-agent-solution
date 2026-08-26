/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.graph.pregel.GraphInterrupt;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.session.interaction.WorkflowInteraction;
import com.openjiuwen.core.workflow.WorkflowComponent;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowexception.WorkflowAbortException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared invoke → userFields wrapping for Studio DSL nodes.
 *
 * @since 2026-08-17
 */
public abstract class AbstractStudioNode extends WorkflowComponent {
    /**
     * node.
     *
     * @return result
     */
    protected final AssembledNode node;

    /**
     * AbstractStudioNode.
     *
     * @param node node
     */
    protected AbstractStudioNode(AssembledNode node) {
        this.node = node;
    }

    /**
     * invoke.
     *
     * @param inputs inputs
     * @param session session
     * @param context context
     * @return result
     */
    @Override
    public Object invoke(Object inputs, NodeSessionApi session, ModelContext context) {
        try {
            Map<String, Object> in = asMap(inputs);
            NodePayload out = doInvoke(in, session, context);
            return out.toInvokeMap();
        } catch (NodeExecutionException e) {
            throw e;
        } catch (WorkflowAbortException e) {
            // Python ExceptionInfo — do not wrap as NodeExecutionException
            throw e;
        } catch (RuntimeException e) {
            if (isGraphInterrupt(e) || e instanceof WorkflowAbortException) {
                throw e;
            }
            throw new NodeExecutionException(
                    node.id(), node.canonicalType(), NodeCauseCode.NODE_INVOKE_FAILED, e.getMessage(), e);
        } catch (GraphInterrupt e) {
            throw new WorkflowInteraction.GraphInterruptRuntimeWrapper(e);
        } catch (Exception e) {
            throw new NodeExecutionException(
                    node.id(), node.canonicalType(), NodeCauseCode.NODE_INVOKE_FAILED, e.getMessage(), e);
        }
    }

    static boolean isGraphInterrupt(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof GraphInterrupt
                    || cur instanceof WorkflowInteraction.GraphInterruptRuntimeWrapper) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
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
    protected abstract NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
            throws Exception;

    /**
     * asMap.
     *
     * @param inputs inputs
     * @return result
     */
    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object inputs) {
        if (inputs == null) {
            return new LinkedHashMap<>();
        }
        if (inputs instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(Map.of("value", inputs));
    }

    /**
     * userFieldsOf.
     *
     * @param inputs inputs
     * @return result
     */
    protected static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        Object uf = inputs.get("userFields");
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }
}
