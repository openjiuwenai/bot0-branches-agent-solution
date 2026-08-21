package com.openjiuwen.studio.dsl.adapter;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.MediaPart;
import com.openjiuwen.studio.dsl.model.NodePayload;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Wraps an agent-core-java ComponentExecutable while preserving Studio media passthrough. */
public final class DelegatingStudioNode extends AbstractStudioNode {
    private final ComponentExecutable delegate;

    public DelegatingStudioNode(AssembledNode node, ComponentExecutable delegate) {
        super(node);
        this.delegate = delegate;
    }

    @Override
    protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context)
            throws Exception {
        List<MediaPart> media = PassthroughStudioNode.extractMedia(inputs);
        Object out = delegate.invoke(inputs, session, context);
        Map<String, Object> map = asMap(out);
        Object uf = map.get("userFields");
        NodePayload payload;
        if (uf instanceof Map<?, ?>) {
            payload = NodePayload.userFields(userFieldsOf(map));
        } else {
            payload = NodePayload.ofFields(map);
        }
        return payload.withMediaPassthrough(media);
    }

    @Override
    public Iterator<Object> stream(Object inputs, NodeSessionApi session, ModelContext context) {
        return delegate.stream(inputs, session, context);
    }

    @Override
    public Iterator<Object> transform(Object inputs, NodeSessionApi session, ModelContext context) {
        return delegate.transform(inputs, session, context);
    }

    public ComponentExecutable delegate() {
        return delegate;
    }
}
