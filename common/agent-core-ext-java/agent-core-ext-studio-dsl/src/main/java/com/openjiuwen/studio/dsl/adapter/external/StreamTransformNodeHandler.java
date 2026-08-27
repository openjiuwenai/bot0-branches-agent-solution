/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.external;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.flowstreamtransform.FlowStreamTransformEngine.StreamMetadata;
import com.openjiuwen.studio.dsl.flowstreamtransform.FlowStreamTransformEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * jiuwen.streamTransform — strict 1:1 with Python {@code flow_stream_transform.FlowStreamTransform}.
 *
 * <p>Thin adapter; all behaviour lives in {@link FlowStreamTransformEngine}.
 *
 * @since 2026-08-17
 */

public final class StreamTransformNodeHandler implements NodeHandlerFactory {

    /**
     * * * userFields key.
     */
    public static final String USER_FIELDS = FlowStreamTransformEngine.USER_FIELDS;

    /**
     * * * Python STREAM_TYPE_PARTIAL_CONTENT.
     */
    public static final String STREAM_TYPE_PARTIAL_CONTENT = FlowStreamTransformEngine.STREAM_TYPE_PARTIAL_CONTENT;

    /**
     * * * Python STREAM_TYPE_MESSAGE_END.
     */
    public static final String STREAM_TYPE_MESSAGE_END = FlowStreamTransformEngine.STREAM_TYPE_MESSAGE_END;

    /**
     * * * Python CONFIG_ERROR code.
     */
    public static final int CONFIG_ERROR = FlowStreamTransformEngine.CONFIG_ERROR;

    /**
     * * * Python INPUT_INVALID code.
     */
    public static final int INPUT_INVALID = FlowStreamTransformEngine.INPUT_INVALID;

    /**
     * * * Python TRANSFORMER_CONFIG_ERROR code.
     */
    public static final int TRANSFORMER_CONFIG_ERROR = FlowStreamTransformEngine.TRANSFORMER_CONFIG_ERROR;

    /**
     * canonicalType.
     *
     * @return result
     * @since 0.1.0
     */

    @Override
    public String canonicalType() {
        return "jiuwen.streamTransform";
    }

    /**
     * aliases.
     *
     * @return result
     * @since 0.1.0
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
     * @since 0.1.0
     */

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new StreamTransformExecutable(node);
    }

    /**
     * Build streaming payload with node metadata (Python {@code get_data_of_streaming_with_metadata}).
     *
     * @param answer answer
     * @param metadata metadata
     * @param outputs optional user-field outputs
     * @return payload map
     */

    public static Map<String, Object> getDataOfStreamingWithMetadata(
            Object answer, StreamMetadata metadata, Map<String, Object> outputs) {
        return FlowStreamTransformEngine.getDataOfStreamingWithMetadata(answer, metadata, outputs);
    }

    /**
     * Build a NodeExecutionException mirroring Python FlowStreamTransform errors.
     *
     * @param nodeId nodeId
     * @param errorCode python error code
     * @param message message
     * @return exception
     */

    public static NodeExecutionException buildFlowStreamTransformError(String nodeId, int errorCode, String message) {
        return FlowStreamTransformEngine.buildError(nodeId, errorCode, message);
    }
    static final class StreamTransformExecutable extends AbstractStudioNode {
        private final FlowStreamTransformEngine engine;

        StreamTransformExecutable(AssembledNode node) {
            super(node);
            this.engine = new FlowStreamTransformEngine(node);
        }

        FlowStreamTransformEngine engine() {
            return engine;
        }
        String sourceField() {
            return engine.sourceField();
        }
        String outputField() {
            return engine.outputField();
        }
        boolean directAssignOutput() {
            return engine.directAssignOutput();
        }
        boolean parseJsonStrings() {
            return engine.parseJsonStrings();
        }
        Map<String, Object> transformerConf() {
            return engine.transformerConf();
        }

        /**
         * doInvoke.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            return NodePayload.ofFields(engine.invoke(inputs, session, context));
        }

        /**
         * collect.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        public Object collect(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.collect(inputs, session, context);
        }

        /**
         * transform.
         *
         * @param inputs inputs
         * @param session session
         * @param context context
         * @return result
         * @since 0.1.0
         */

        @Override
        public Iterator<Object> transform(Object inputs, NodeSessionApi session, ModelContext context) {
            return engine.transform(inputs, session, context);
        }
    }
}
