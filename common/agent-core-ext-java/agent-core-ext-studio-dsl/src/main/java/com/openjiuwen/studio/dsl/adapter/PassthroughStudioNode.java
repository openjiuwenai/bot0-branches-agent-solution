/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.model.NodePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default executable: echo userFields + passthrough media (D9).
 *
 * @since 2026-08-17
 */
public final class PassthroughStudioNode extends AbstractStudioNode {
    /**
     * PassthroughStudioNode.
     * @param node node
     */
    public PassthroughStudioNode(AssembledNode node) {
        super(node);
    }
    /**
     * doInvoke.
     * @param inputs inputs
     * @param session session
     * @param context context
     */
    @Override
    protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        Map<String, Object> uf = userFieldsOf(inputs);
        List<MediaPart> media = extractMedia(inputs);
        return NodePayload.userFields(uf).withMediaPassthrough(media);
    }
    /**
     * extractMedia.
     * @param inputs inputs
     */
    @SuppressWarnings("unchecked")
    public static List<MediaPart> extractMedia(Map<String, Object> inputs) {
        Object raw = inputs.get("__media__");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<MediaPart> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof MediaPart mp) {
                out.add(mp);
            }
        }
        return out;
    }
}
