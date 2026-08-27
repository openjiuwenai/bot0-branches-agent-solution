/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl;

import com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import com.openjiuwen.studio.dsl.questioner.QuestionerState;
import com.openjiuwen.studio.dsl.registry.NodeTypeRegistry;
import com.openjiuwen.studio.dsl.testsupport.LinearWorkflowTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * D-tier controller / agent-controller workflow files reduced to user-visible
 * Start→…→End chains (no multi-agent controller reimplementation).
 *
 * <p>Each {@code @Test} is named after its Python source file under
 * {@code test_case_controller_*} / {@code test_case_agent_controller_*}.
 * Plan-execute FlowApi suites live in {@link WorkflowNodePlanExecuteCasesTest}.
 *
 * @since 2026-08-26
 */
class WorkflowNodeControllerSuiteCasesTest {
    private NodeTypeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = NodeTypeRegistry.createWithBuiltins();
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

    private Map<String, Object> runLinear(AssembledWorkflow wf, Map<String, Object> inputs) {
        return LinearWorkflowTestSupport.executeLinear(
                registry,
                wf,
                StudioEngineTestSupport.context(wf.workflowId()),
                inputs,
                mock(NodeSessionApi.class),
                mock(ModelContext.class));
    }

    private static AssembledWorkflow startMessageEnd(String id, String msgTemplate, String endTemplate) {
        return new AssembledWorkflow(
                id,
                List.of(
                        AssembledNode.of("start", "jiuwen.start", Map.of()),
                        AssembledNode.of("message", "jiuwen.message", Map.of("template", msgTemplate)),
                        AssembledNode.of("end", "jiuwen.end", Map.of("responseTemplate", endTemplate))));
    }

    private static AssembledWorkflow startEnd(String id, String endTemplate) {
        return new AssembledWorkflow(
                id,
                List.of(
                        AssembledNode.of("start", "jiuwen.start", Map.of()),
                        AssembledNode.of("end", "jiuwen.end", Map.of("responseTemplate", endTemplate))));
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

    /** Start → Questioner(hang) → resume → Message → End. */
    private Map<String, Object> hangResumeMessageEnd(
            String question, String msgTemplate, String endTemplate, String resumeQuery) {
        NodeSessionApi session = sessionWithState();
        ComponentExecutable start =
                registry.create(AssembledNode.of("start", "jiuwen.start", Map.of()), StudioEngineTestSupport.context("wf"));
        ComponentExecutable questioner = registry.create(
                AssembledNode.of(
                        "q",
                        "jiuwen.questioner",
                        Map.of("questionContent", question, "extractFieldsFromResponse", false)),
                StudioEngineTestSupport.context("wf"));
        ComponentExecutable message = registry.create(
                AssembledNode.of("message", "jiuwen.message", Map.of("template", msgTemplate)),
                StudioEngineTestSupport.context("wf"));
        ComponentExecutable end = registry.create(
                AssembledNode.of("end", "jiuwen.end", Map.of("responseTemplate", endTemplate)),
                StudioEngineTestSupport.context("wf"));

        Map<String, Object> current = Map.of("userFields", Map.of("query", "init"));
        current = asMap(start.invoke(current, session, null));
        current = asMap(questioner.invoke(current, session, null));
        assertThat(uf(current).get("hangState")).isEqualTo("INPUT_REQUIRED");

        Map<String, Object> resumeIn = new LinkedHashMap<>();
        resumeIn.put("query", resumeQuery);
        resumeIn.put("__single_debug_recovery__", true);
        Object saved = uf(current).get(QuestionerState.KEY);
        if (saved != null) {
            resumeIn.put(QuestionerState.KEY, saved);
        }
        resumeIn.putAll(uf(current));
        current = asMap(questioner.invoke(Map.of("userFields", resumeIn), session, null));
        assertThat(uf(current).get("questionerState")).isEqualTo("answered");

        current = asMap(message.invoke(current, session, null));
        current = asMap(end.invoke(current, session, null));
        return current;
    }

    /** Start → Questioner(hang) → resume → End. */
    private Map<String, Object> hangResumeEnd(String question, String endTemplate, String resumeQuery) {
        NodeSessionApi session = sessionWithState();
        ComponentExecutable start =
                registry.create(AssembledNode.of("start", "jiuwen.start", Map.of()), StudioEngineTestSupport.context("wf"));
        ComponentExecutable questioner = registry.create(
                AssembledNode.of(
                        "q",
                        "jiuwen.questioner",
                        Map.of("questionContent", question, "extractFieldsFromResponse", false)),
                StudioEngineTestSupport.context("wf"));
        ComponentExecutable end = registry.create(
                AssembledNode.of("end", "jiuwen.end", Map.of("responseTemplate", endTemplate)),
                StudioEngineTestSupport.context("wf"));

        Map<String, Object> current = Map.of("userFields", Map.of("query", "init"));
        current = asMap(start.invoke(current, session, null));
        current = asMap(questioner.invoke(current, session, null));
        assertThat(uf(current).get("hangState")).isEqualTo("INPUT_REQUIRED");

        Map<String, Object> resumeIn = new LinkedHashMap<>(uf(current));
        resumeIn.put("query", resumeQuery);
        resumeIn.put("__single_debug_recovery__", true);
        current = asMap(questioner.invoke(Map.of("userFields", resumeIn), session, null));
        current = asMap(end.invoke(current, session, null));
        return current;
    }

    /** Start → Questioner → Questioner → End (two hangs; fresh session per questioner). */
    private Map<String, Object> doubleQuestionerEnd() {
        ComponentExecutable start =
                registry.create(AssembledNode.of("start", "jiuwen.start", Map.of()), StudioEngineTestSupport.context("wf"));
        ComponentExecutable q1 = registry.create(
                AssembledNode.of(
                        "q1",
                        "jiuwen.questioner",
                        Map.of("questionContent", "请输入金额", "extractFieldsFromResponse", false)),
                StudioEngineTestSupport.context("wf"));
        ComponentExecutable q2 = registry.create(
                AssembledNode.of(
                        "q2",
                        "jiuwen.questioner",
                        Map.of("questionContent", "请输入收款人", "extractFieldsFromResponse", false)),
                StudioEngineTestSupport.context("wf"));
        ComponentExecutable end = registry.create(
                AssembledNode.of("end", "jiuwen.end", Map.of("responseTemplate", "转账完成")),
                StudioEngineTestSupport.context("wf"));

        NodeSessionApi session1 = sessionWithState();
        Map<String, Object> current = Map.of("userFields", Map.of("query", "init"));
        current = asMap(start.invoke(current, session1, null));
        current = asMap(q1.invoke(current, session1, null));
        assertThat(uf(current).get("hangState")).isEqualTo("INPUT_REQUIRED");
        Map<String, Object> r1 = new LinkedHashMap<>(uf(current));
        r1.put("query", "100");
        r1.put("__single_debug_recovery__", true);
        r1.remove(QuestionerState.KEY);
        current = asMap(q1.invoke(Map.of("userFields", r1), session1, null));

        // Fresh session so q2 does not inherit q1 answered QuestionerState
        NodeSessionApi session2 = sessionWithState();
        Map<String, Object> intoQ2 = new LinkedHashMap<>(uf(current));
        intoQ2.remove(QuestionerState.KEY);
        intoQ2.remove("hangState");
        intoQ2.remove("questionerState");
        intoQ2.remove("USER_RESPONSE");
        intoQ2.remove("userAnswer");
        intoQ2.remove("answer");
        intoQ2.remove("STATUS");
        intoQ2.remove("__single_debug_recovery__");
        intoQ2.remove("query");
        current = asMap(q2.invoke(Map.of("userFields", intoQ2), session2, null));
        assertThat(uf(current).get("hangState")).isEqualTo("INPUT_REQUIRED");
        Map<String, Object> r2 = new LinkedHashMap<>(uf(current));
        r2.put("query", "张三");
        r2.put("__single_debug_recovery__", true);
        current = asMap(q2.invoke(Map.of("userFields", r2), session2, null));
        current = asMap(end.invoke(current, session2, null));
        return current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object out) {
        return (Map<String, Object>) out;
    }

    // --- test_case_controller_multi_agent_27 ---

    @Test
    void test_faq_workflow() {
        Map<String, Object> fields = uf(runLinear(
                startMessageEnd("faq", "低至 3.35%", " "),
                Map.of("userFields", Map.of("query", "利率是多少"))));
        assertThat(String.valueOf(fields.get("result"))).contains("3.35%");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_start_workflow_multi_agent_27() {
        Map<String, Object> fields = uf(runLinear(
                startMessageEnd(
                        "ssq_start",
                        "【开始工作流】{{query}}\n开始工作流开始",
                        "【开始工作流-结束节点】{{result}}"),
                Map.of("userFields", Map.of("query", "理财"))));
        assertThat(String.valueOf(fields.get("result"))).contains("开始工作流");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_default_workflow() {
        Map<String, Object> fields = uf(runLinear(
                startMessageEnd("default", "这是默认工作流", "默认工作流结束"),
                Map.of("userFields", Map.of("query", "其他"))));
        assertThat(String.valueOf(fields.get("result"))).contains("默认工作流");
        assertThat(String.valueOf(fields.get("answer"))).contains("默认工作流结束");
    }

    @Test
    void test_end_workflow_multi_agent_27() {
        Map<String, Object> fields = uf(runLinear(
                startEnd("end_wf", "## 流程结束"), Map.of("userFields", Map.of("query", "结束"))));
        assertThat(String.valueOf(fields.get("answer"))).contains("流程结束");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_financial_service_workflow() {
        Map<String, Object> fields = uf(hangResumeMessageEnd(
                "请问你要赎回哪一笔理财？1.xxx 2.xxx\n",
                "好的，赎回成功，已实时到账。\n\n\n",
                " ",
                "1"));
        assertThat(String.valueOf(fields.get("result"))).contains("赎回成功");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    // --- test_case_controller_multi_agent_02 ---

    @Test
    void test_financial_default_workflow() {
        Map<String, Object> fields = uf(runLinear(
                startMessageEnd("fin_default", "这是默认工作流", "默认工作流结束"),
                Map.of("userFields", Map.of("query", "默认"))));
        assertThat(String.valueOf(fields.get("result"))).contains("默认工作流");
    }

    @Test
    void test_end_workflow_multi_agent_02() {
        Map<String, Object> fields =
                uf(runLinear(startEnd("end_02", "## 流程结束"), Map.of("userFields", Map.of())));
        assertThat(String.valueOf(fields.get("answer"))).contains("流程结束");
    }

    @Test
    void test_financial_start_workflow() {
        Map<String, Object> fields = uf(hangResumeEnd(
                "欢迎进入理财开始工作流，请输入需求", "【开始工作流-结束节点】{{result}}", "理财"));
        assertThat(fields.get("__terminal__")).isEqualTo(true);
        assertThat(String.valueOf(fields.get("answer"))).contains("开始工作流");
    }

    @Test
    void test_financial_workflow() {
        Map<String, Object> fields = uf(hangResumeMessageEnd(
                "请问你要赎回哪一笔理财？",
                "好的，赎回成功，已实时到账。\n\n\n",
                "理财服务流程已完成",
                "xxx"));
        // End responseTemplate becomes final answer/result; message text was produced mid-chain
        assertThat(String.valueOf(fields.get("answer"))).contains("理财服务流程已完成");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_transfer_workflow_multi_agent_02() {
        Map<String, Object> fields = uf(doubleQuestionerEnd());
        assertThat(String.valueOf(fields.get("answer"))).contains("转账完成");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    // --- test_case_agent_controller_enhance_01 ---

    @Test
    void test_intent_detection_workflow() {
        // Python: start → end with responseTemplate "{{intent_id}}"
        Map<String, Object> fields = uf(runLinear(
                startEnd("intent_end", "{{intent_id}}"),
                Map.of("userFields", Map.of("intent_id", "branch_0"))));
        assertThat(String.valueOf(fields.get("answer"))).contains("branch_0");
    }

    @Test
    void test_smart_outbound_workflow() {
        Map<String, Object> fields = uf(runLinear(
                startMessageEnd(
                        "outbound",
                        "本次来电是邀请您体验我行闲钱管理服务'大天盈'的。收益每日计算，1分起购，赎回最快实时到账，让您的闲钱不闲置，详情给您发条短信，您有空的时候了解下，可以吗",
                        "【理财】工作流执行结束"),
                Map.of("userFields", Map.of("query", "来电"))));
        assertThat(String.valueOf(fields.get("answer"))).contains("工作流执行结束");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_rongejie_workflow() {
        Map<String, Object> fields = uf(hangResumeMessageEnd(
                "请问您是否有意向了解融e借？",
                "好的，感谢您的信任！祝您生活愉快，再见。",
                "ssq_融e借产品推荐_1--结束",
                "有意向"));
        assertThat(String.valueOf(fields.get("answer"))).contains("融e借");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    // --- test_case_agent_controller_new_06 ---

    @Test
    void test_end_workflow_new_06() {
        Map<String, Object> fields =
                uf(runLinear(startEnd("new06_end", "## 流程结束"), Map.of("userFields", Map.of())));
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_start_workflow_new_06() {
        // Python: start → questioner → intent_detection → branch → end; capture hang/resume + stub LLM
        StudioEngineTestSupport.installIntent(messages -> "{\"class\": \"分类1\", \"reason\": \"理财相关\"}");
        try {
            NodeSessionApi session = sessionWithState();
            ComponentExecutable start =
                    registry.create(AssembledNode.of("start", "jiuwen.start", Map.of()), StudioEngineTestSupport.context("wf"));
            ComponentExecutable questioner = registry.create(
                    AssembledNode.of(
                            "q",
                            "jiuwen.questioner",
                            Map.of("questionContent", "请输入您的需求", "extractFieldsFromResponse", false)),
                    StudioEngineTestSupport.context("wf"));
            ComponentExecutable intent = registry.create(
                    AssembledNode.of(
                            "intent",
                            "jiuwen.intentDetection",
                            Map.of(
                                    "llm",
                                    Map.of(
                                            "model",
                                            Map.of(
                                                    "modelName",
                                                    "gpt-test",
                                                    "modelType",
                                                    "OpenAI",
                                                    "extension",
                                                    Map.of("api_key", "k", "api_base", "http://localhost"))),
                                    "branches",
                                    List.of(
                                            Map.of("id", "branch_0", "catalog", "其他意图"),
                                            Map.of("id", "branch_1", "catalog", "理财")),
                                    "enableHistory",
                                    true,
                                    "enableInput",
                                    true)),
                    StudioEngineTestSupport.context("wf"));
        ComponentExecutable branch = registry.create(
                AssembledNode.of(
                        "branch",
                        "jiuwen.branch",
                        Map.of(
                                "branches",
                                List.of(
                                        Map.of(
                                                "branchId",
                                                "理财路径",
                                                "condition",
                                                Map.of(
                                                        "operator",
                                                        "eq",
                                                        "left",
                                                        Map.of("value", "classificationId"),
                                                        "right",
                                                        1)),
                                        Map.of("branchId", "其他路径", "isDefault", true)))),
                StudioEngineTestSupport.context("wf"));
        ComponentExecutable end = registry.create(
                AssembledNode.of(
                        "end",
                        "jiuwen.end",
                        Map.of("responseTemplate", "continue=True age_temp=25")),
                StudioEngineTestSupport.context("wf"));

        Map<String, Object> current = Map.of("userFields", Map.of("query", "init", "input", "我想理财"));
        current = asMap(start.invoke(current, session, null));
        current = asMap(questioner.invoke(current, session, null));
        assertThat(uf(current).get("hangState")).isEqualTo("INPUT_REQUIRED");
        Map<String, Object> resume = new LinkedHashMap<>(uf(current));
        resume.put("query", "理财");
        resume.put("input", "理财");
        resume.put("__single_debug_recovery__", true);
        current = asMap(questioner.invoke(Map.of("userFields", resume), session, null));
        Map<String, Object> intentIn = new LinkedHashMap<>(current);
        intentIn.put("input", "理财");
        current = asMap(intent.invoke(intentIn, session, null));
        assertThat(uf(current).get("classificationId")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> branchOut = (Map<String, Object>) branch.invoke(current, session, null);
        assertThat(branchOut.get("branchId")).isEqualTo("理财路径");
        current = asMap(end.invoke(current, session, null));
        assertThat(String.valueOf(uf(current).get("answer"))).contains("continue=True");
        } finally {
            StudioEngineTestSupport.clear();
        }
    }

    @Test
    void test_shengjin_youli_workflow() {
        // Branch selects 升金 path → Message → End (full multi-branch graph still deferred)
        NodeSessionApi session = mock(NodeSessionApi.class);
        ComponentExecutable branch = registry.create(
                AssembledNode.of(
                        "branch",
                        "jiuwen.branch",
                        Map.of(
                                "branches",
                                List.of(
                                        Map.of(
                                                "branchId",
                                                "升金路径",
                                                "condition",
                                                "('升金' in ${query})"),
                                        Map.of("branchId", "default", "isDefault", true)))),
                StudioEngineTestSupport.context("shengjin"));
        @SuppressWarnings("unchecked")
        Map<String, Object> branchOut = (Map<String, Object>)
                branch.invoke(Map.of("userFields", Map.of("query", "升金有礼")), session, null);
        assertThat(branchOut.get("branchId")).isEqualTo("升金路径");

        Map<String, Object> fields = uf(runLinear(
                startMessageEnd(
                        "shengjin",
                        "#BDD#,#GD#/感谢您的支持，请留意查收95588短信并登录银行APP参与活动，祝您生活愉快，再见！",
                        "##升金结束continue: true。action_after_completion：Terminal"),
                Map.of("userFields", Map.of("query", "升金有礼"))));
        assertThat(String.valueOf(fields.get("answer"))).contains("升金结束");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }

    @Test
    void test_smart_workflow() {
        // Branch selects continue path → Message → End
        ComponentExecutable branch = registry.create(
                AssembledNode.of(
                        "branch",
                        "jiuwen.branch",
                        Map.of(
                                "branches",
                                List.of(
                                        Map.of(
                                                "branchId",
                                                "继续路径",
                                                "condition",
                                                "('继续' in ${query})"),
                                        Map.of("branchId", "default", "isDefault", true)))),
                StudioEngineTestSupport.context("smart"));
        @SuppressWarnings("unchecked")
        Map<String, Object> branchOut = (Map<String, Object>)
                branch.invoke(Map.of("userFields", Map.of("query", "继续")), null, null);
        assertThat(branchOut.get("branchId")).isEqualTo("继续路径");

        Map<String, Object> fields = uf(runLinear(
                startMessageEnd("smart", "智能工作流主路径完成", "##智能工作流结束"),
                Map.of("userFields", Map.of("query", "继续"))));
        assertThat(String.valueOf(fields.get("answer"))).contains("智能工作流结束");
        assertThat(fields.get("__terminal__")).isEqualTo(true);
    }
}
