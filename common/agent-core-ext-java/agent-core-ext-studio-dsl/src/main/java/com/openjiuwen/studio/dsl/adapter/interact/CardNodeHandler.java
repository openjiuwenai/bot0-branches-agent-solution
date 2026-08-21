package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.PassthroughStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** jiuwen.card — emit card payload via session stream (Studio FlowCard). */
public final class CardNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.card";
    }

    @Override
    public Set<String> aliases() {
        return Set.of("jiuwen.flowCard");
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        return new CardExecutable(node);
    }

    static final class CardExecutable extends AbstractStudioNode {
        CardExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            Map<String, Object> uf = new LinkedHashMap<>(userFieldsOf(inputs));
            Object card = node.configs().getOrDefault("card", node.configs().get("cardConfig"));
            if (card == null) {
                card = node.configs();
            }
            uf.put("card", card);
            uf.put("componentType", "card");
            if (session != null) {
                try {
                    Map<String, Object> frame = new LinkedHashMap<>();
                    frame.put("type", "jiuwen.card");
                    frame.put("event", "card");
                    frame.put("card", card);
                    frame.put("nodeId", node.id());
                    session.writeCustomStream(frame);
                } catch (RuntimeException ignored) {
                    // mock session
                }
            }
            return NodePayload.userFields(uf).withMediaPassthrough(PassthroughStudioNode.extractMedia(inputs));
        }
    }
}
