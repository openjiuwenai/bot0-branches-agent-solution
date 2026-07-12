/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.retrieval.common.RetrievalResult;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.core.session.WorkflowSessionApi;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowExecutionState;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.example.intent.support.IntentDemoContext;
import com.openjiuwen.example.intent.support.IntentDemoProperties;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "openjiuwen.demo.intent.llm.provider=OpenAI", "openjiuwen.demo.intent.llm.api-key=test-chat-key",
        "openjiuwen.demo.intent.llm.api-base=https://chat.example/v1",
        "openjiuwen.demo.intent.llm.model-name=test-chat-model",
        "openjiuwen.demo.intent.reranker.api-key=test-reranker-key",
        "openjiuwen.demo.intent.reranker.api-base=https://reranker.example/v1",
        "openjiuwen.demo.intent.reranker.model-name=test-reranker-model" })
class WorkflowIntentRuntimeApplicationTest {
    @Autowired
    private AgentHandler handler;

    @Autowired
    private WorkflowAgent agent;

    @Autowired
    private Workflow workflow;

    @Test
    void mountsAndExecutesIntentWorkflow() {
        WorkflowOutput output = workflow.invoke(Map.of("query", "查询订单物流"),
                new WorkflowSessionApi(null, UUID.randomUUID().toString(), Map.of()), null);

        assertThat(handler).isInstanceOf(JiuwenCoreAgentExtHandler.class);
        assertThat(agent.getAbilityManager().get(workflow.getCard().getName())).isNotNull();
        assertThat(output.getState()).isEqualTo(WorkflowExecutionState.COMPLETED);
        assertThat(output.getResult()).isInstanceOfSatisfying(Map.class, result -> {
            assertThat(result.get("output")).isInstanceOfSatisfying(Map.class, intent -> {
                assertThat(intent.get("matched")).isEqualTo(true);
                assertThat(intent.get("reason")).isEqualTo("MATCHED");
                assertThat(intent.get("target")).isInstanceOfSatisfying(Map.class,
                        target -> assertThat(target.get("name")).isEqualTo("Order Agent"));
            });
        });
    }

    @TestConfiguration
    static class DeterministicRerankerConfiguration {
        @Bean
        @Primary
        IntentDemoContext deterministicIntentDemoContext(IntentDemoProperties properties) {
            return IntentDemoContext.create(properties, orderReranker());
        }

        private static Reranker orderReranker() {
            return new Reranker() {
                @Override
                public Map<String, Double> rerankScores(String query, List<?> documents, Object instruct,
                        Map<String, Object> options) {
                    Map<String, Double> result = new LinkedHashMap<>();
                    for (Object document : documents) {
                        RetrievalResult candidate = (RetrievalResult) document;
                        double score = candidate.getText().contains("[Target]\nOrder Agent") ? 0.95 : 0.20;
                        result.put(candidate.getChunkId(), score);
                    }
                    return result;
                }

                @Override
                public List<RetrievalResult> rerank(String query, List<RetrievalResult> candidates, int topK) {
                    throw new UnsupportedOperationException("not used by intent recognition");
                }
            };
        }
    }
}
