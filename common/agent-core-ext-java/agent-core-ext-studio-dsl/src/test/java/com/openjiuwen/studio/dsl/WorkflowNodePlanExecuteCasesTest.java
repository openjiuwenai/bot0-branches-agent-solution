/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.WorkflowAssemblyBridge;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.support.InMemoryToolRegistry;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan-execute D-tier FlowApi paths from
 * {@code test_case_controller_plan_execute_common_01} with mock {@link InMemoryToolRegistry}.
 *
 * @since 2026-08-26
 */

class WorkflowNodePlanExecuteCasesTest {
    private NodeTypeRegistry registry;
    private InMemoryToolRegistry tools;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
        tools = new InMemoryToolRegistry();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uf(Object invokeOut) {
        Map<String, Object> out = (Map<String, Object>) invokeOut;
        Object user = out.get("userFields");
        if (user instanceof Map<?, ?>) {
            return (Map<String, Object>) user;
        }
        return out;
    }

    private NodeBuildContext ctx(String wfId) {
        return new NodeBuildContext(
                wfId,
                0,
                5,
                null,
                c -> null,
                tools,
                registry);
    }

    private void registerTool(String id, Map<String, Object> canned) {
        ToolCard card = ToolCard.builder().id(id).name(id).description(id).build();
        tools.register(id, new Tool(card) {

            /**
             * invoke.
             *
             * @param inputs inputs
             * @param kwargs kwargs
             * @return result
             * @since 0.1.0
             */

            @Override
            public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return Map.of("errCode", 0, "errMessage", "success", "data", canned);
            }

            /**
             * stream.
             *
             * @param inputs inputs
             * @param kwargs kwargs
             * @return result
             * @since 0.1.0
             */

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return List.of().iterator();
            }
        });
    }

    private Map<String, Object> runLinear(AssembledWorkflow wf, Map<String, Object> inputs) {
        return LinearWorkflowTestSupport.executeLinear(
                registry, wf, ctx(wf.workflowId()), inputs, mock(NodeSessionApi.class), mock(ModelContext.class));
    }

    private static List<Map<String, Object>> responseSchema(String... fieldNames) {
        List<Map<String, Object>> schema = new ArrayList<>();
        for (String name : fieldNames) {
            schema.add(Map.of("name", name));
        }
        return schema;
    }

    private static Map<String, Object> pluginConf(String apiId, String... responseFieldNames) {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("apiId", apiId);
        conf.put("name", apiId);
        if (responseFieldNames.length > 0) {
            conf.put("response", responseSchema(responseFieldNames));
        }
        return conf;
    }

    private NodeSessionApi sessionWithState() {
        AtomicReference<Map<String, Object>> bucket = new AtomicReference<>(new HashMap<>());
        NodeSessionApi session = mock(NodeSessionApi.class);
        when(session.getState(any())).thenAnswer(inv -> {
            Object key = inv.getArgument(0);
            if (key == null) {
                return bucket.get();
            }
            return bucket.get().get(String.valueOf(key));
        });
        doAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> patch = inv.getArgument(0);
                    Map<String, Object> cur = new HashMap<>(bucket.get());
                    cur.putAll(patch);
                    bucket.set(cur);
                    return null;
                })
                .when(session)
                .updateState(any());
        return session;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object out) {
        return (Map<String, Object>) out;
    }

    /**
     * Manual multi-node chain — accumulate userFields (nested child path uses the same merge).
     *
     * @param current current
     * @param exec exec
     * @param session session
     * @return result
     * @since 0.1.0
     */
    private static Map<String, Object> mergeInvoke(
            Map<String, Object> current, ComponentExecutable exec, NodeSessionApi session) {
        return WorkflowAssemblyBridge.mergeLinearStep(current, asMap(exec.invoke(current, session, null)), null);
    }

    private AssembledWorkflow startPluginEnd(
            String id, String apiId, String endTemplate, String... pluginResponseFields) {
        return new AssembledWorkflow(
                id,
                List.of(
                        AssembledNode.of("node_start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "node_plugin",
                                "jiuwen.plugin",
                                pluginConf(apiId, pluginResponseFields)),
                        AssembledNode.of(
                                "node_end", "jiuwen.end", Map.of("responseTemplate", endTemplate))));
    }

    @Test
    void test_query_bill_workflow() {
        registerTool(
                "plugin_query_bill",
                Map.of("bill_id", "123456", "amount", "5000.00", "due_date", "2026-05-31"));
        Map<String, Object> fields = uf(runLinear(
                startPluginEnd(
                        "workflow_plugin_query_bill",
                        "plugin_query_bill",
                        "信用卡账单金额为：{{amount}}，信用卡id为：{{card_id}}",
                        "bill_id",
                        "amount",
                        "due_date"),
                Map.of("userFields", Map.of("card_id", "card-001", "query", "查账单"))));
        assertThat(fields).containsEntry("amount", "5000.00");
        assertThat(String.valueOf(fields.get("answer"))).contains("5000.00").contains("card-001");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_transfer_workflow() {
        registerTool("plugin_transfer", Map.of("status", "success"));
        Map<String, Object> fields = uf(runLinear(
                startPluginEnd(
                        "workflow_plugin_transfer", "plugin_transfer", "转账成功：{{status}}", "status"),
                Map.of("userFields", Map.of("query", "转账"))));
        assertThat(fields).containsEntry("status", "success");
        assertThat(String.valueOf(fields.get("answer"))).contains("转账成功");
    }

    @Test
    void test_query_account_workflow() {
        registerTool("plugin_query_account", Map.of("account_id", "777666", "balance", "1000.00"));
        NodeSessionApi session = sessionWithState();
        NodeBuildContext buildCtx = ctx("workflow_plugin_query_account");
        ComponentExecutable start =
                registry.create(AssembledNode.of("node_start", "jiuwen.start", Map.of()), buildCtx);
        ComponentExecutable questioner = registry.create(
                AssembledNode.of(
                        "node_questioner",
                        "jiuwen.questioner",
                        Map.of(
                                "questionContent",
                                "请提供账户id亲",
                                "extractFieldsFromResponse",
                                true,
                                "fieldNames",
                                List.of(Map.of(
                                        "fieldName", "account_id", "type", "string", "required", true)),
                                "mockExtractedFields",
                                Map.of("account_id", "777666"))),
                buildCtx);
        ComponentExecutable plugin = registry.create(
                AssembledNode.of(
                        "node_plugin",
                        "jiuwen.plugin",
                        pluginConf("plugin_query_account", "account_id", "balance")),
                buildCtx);
        ComponentExecutable end = registry.create(
                AssembledNode.of(
                        "node_end",
                        "jiuwen.end",
                        Map.of("responseTemplate", "账户id为：{{account_id}}，账户余额为：{{balance}}")),
                buildCtx);

        Map<String, Object> current =
                Map.of("userFields", Map.of("query", "我的账户是777666", "account_id", ""));
        current = mergeInvoke(current, start, session);
        current = mergeInvoke(current, questioner, session);
        if ("INPUT_REQUIRED".equals(uf(current).get("hangState"))) {
            Map<String, Object> resume = new LinkedHashMap<>(uf(current));
            resume.put("query", "777666");
            resume.put("__single_debug_recovery__", true);
            current = mergeInvoke(Map.of("userFields", resume), questioner, session);
        }
        current = mergeInvoke(current, plugin, session);
        current = mergeInvoke(current, end, session);
        Map<String, Object> fields = uf(current);
        assertThat(String.valueOf(fields.get("answer"))).contains("777666").contains("1000.00");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_repay_credit_workflow() {
        registerTool("plugin_repay_credit", Map.of("card_id", "777666", "status", "success"));
        NodeSessionApi session = sessionWithState();
        NodeBuildContext buildCtx = ctx("workflow_plugin_repay_credit");
        ComponentExecutable start =
                registry.create(AssembledNode.of("node_start", "jiuwen.start", Map.of()), buildCtx);
        ComponentExecutable questioner = registry.create(
                AssembledNode.of(
                        "node_questioner",
                        "jiuwen.questioner",
                        Map.of(
                                "questionContent",
                                "请提供信用卡id",
                                "extractFieldsFromResponse",
                                true,
                                "fieldNames",
                                List.of(Map.of("fieldName", "card_id", "type", "string", "required", true)),
                                "mockExtractedFields",
                                Map.of("card_id", "777666"))),
                buildCtx);
        ComponentExecutable plugin = registry.create(
                AssembledNode.of(
                        "node_plugin",
                        "jiuwen.plugin",
                        pluginConf("plugin_repay_credit", "card_id", "status")),
                buildCtx);
        ComponentExecutable end = registry.create(
                AssembledNode.of(
                        "node_end",
                        "jiuwen.end",
                        Map.of("responseTemplate", "还款成功：{{status}}")),
                buildCtx);

        Map<String, Object> current = Map.of("userFields", Map.of("query", "还款 777666"));
        current = mergeInvoke(current, start, session);
        current = mergeInvoke(current, questioner, session);
        if ("INPUT_REQUIRED".equals(uf(current).get("hangState"))) {
            Map<String, Object> resume = new LinkedHashMap<>(uf(current));
            resume.put("query", "777666");
            resume.put("__single_debug_recovery__", true);
            current = mergeInvoke(Map.of("userFields", resume), questioner, session);
        }
        current = mergeInvoke(current, plugin, session);
        current = mergeInvoke(current, end, session);
        assertThat(String.valueOf(uf(current).get("answer"))).contains("还款成功");
    }

    @Test
    void test_workflow_parent_query_bill() {
        registerTool(
                "plugin_query_bill",
                Map.of("bill_id", "123456", "amount", "5000.00", "due_date", "2026-05-31"));
        AssembledWorkflow child = startPluginEnd(
                "workflow_plugin_query_bill",
                "plugin_query_bill",
                "信用卡账单金额为：{{amount}}，信用卡id为：{{card_id}}",
                "bill_id",
                "amount",
                "due_date");
        AssembledWorkflow parent = new AssembledWorkflow(
                "parent_workflow_plugin_query_bill",
                List.of(
                        AssembledNode.of("start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "workflowComposite",
                                "jiuwen.subWorkflow",
                                Map.of("workflowId", "workflow_plugin_query_bill")),
                        AssembledNode.of(
                                "end", "jiuwen.end", Map.of("responseTemplate", "{{answer}}"))));
        NodeBuildContext parentCtx = new NodeBuildContext(
                "parent_workflow_plugin_query_bill",
                0,
                5,
                null,
                c -> child,
                tools,
                registry);
        Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                registry,
                parent,
                parentCtx,
                Map.of("userFields", Map.of("card_id", "card-9", "query", "查账单")),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
        Map<String, Object> fields = uf(out);
        assertThat(String.valueOf(fields.get("answer"))).contains("5000.00");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_workflow_parent_repay_credit() {
        registerTool("plugin_repay_credit", Map.of("card_id", "777666", "status", "success"));
        AssembledWorkflow child =
                startPluginEnd("workflow_plugin_repay_credit", "plugin_repay_credit", "还款成功：{{status}}", "status");
        AssembledWorkflow parent = new AssembledWorkflow(
                "parent_workflow_plugin_repay_credit",
                List.of(
                        AssembledNode.of("start", "jiuwen.start", Map.of()),
                        AssembledNode.of(
                                "workflowComposite",
                                "jiuwen.subWorkflow",
                                Map.of("workflowId", "workflow_plugin_repay_credit")),
                        AssembledNode.of(
                                "end", "jiuwen.end", Map.of("responseTemplate", "{{answer}}"))));
        NodeBuildContext parentCtx = new NodeBuildContext(
                "parent_workflow_plugin_repay_credit",
                0,
                5,
                null,
                c -> child,
                tools,
                registry);
        Map<String, Object> out = LinearWorkflowTestSupport.executeLinear(
                registry,
                parent,
                parentCtx,
                Map.of("userFields", Map.of("card_id", "777666", "query", "还款")),
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
        assertThat(String.valueOf(uf(out).get("answer"))).contains("还款成功");
    }
}
