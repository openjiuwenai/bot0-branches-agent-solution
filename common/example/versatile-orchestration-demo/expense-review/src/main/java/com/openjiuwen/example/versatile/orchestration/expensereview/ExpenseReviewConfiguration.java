/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.expensereview;

import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.workflow.Workflow;
import com.openjiuwen.core.workflow.WorkflowCard;
import com.openjiuwen.core.workflow.component.BranchComponent;
import com.openjiuwen.core.workflow.component.End;
import com.openjiuwen.core.workflow.component.Start;
import com.openjiuwen.core.workflow.component.llm.LLMCompConfig;
import com.openjiuwen.core.workflow.component.llm.LLMComponent;
import com.openjiuwen.core.workflow.component.llm.QuestionerComponent;
import com.openjiuwen.core.workflow.component.llm.QuestionerConfig;
import com.openjiuwen.core.workflow.component.tool.ToolComponent;
import com.openjiuwen.core.workflow.component.tool.ToolComponentConfig;
import com.openjiuwen.example.versatile.orchestration.expensereview.tool.CompanyPolicyTool;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles the Expense Review Workflow Agent.
 *
 * <p>The 8-node imperative DAG (analyze → check_policy → audit → branch →
 * approve/auto_approve → end) is built by {@link #buildExpenseReviewWorkflow} and
 * registered on a {@link WorkflowAgent} in single-workflow mode (no intent LLM).
 * The agent is hosted by the stock {@link JiuwenCoreAgentHandler} — no subclassing.
 *
 * @since 2026-07-08
 */
@Configuration(proxyBeanMethods = false)
public class ExpenseReviewConfiguration {
    private static final SystemMessage ANALYZE_PROMPT = new SystemMessage("""
            你是一个报销单分析专家。分析以下报销申请，提取所有报销条目。
            对于每个条目，提取名称(name)、金额(amount)、类别(category)。
            类别包括：交通、住宿、餐饮、其他。
            每个条目还要拆出单价(unit_price)和数量(quantity)：amount = unit_price × quantity。
            住宿的单价为每晚房价、数量为入住晚数；交通/餐饮/其他单价即该项金额、数量为1。
            同时计算总金额(total)。
            以 JSON 格式返回结果，字段为 intent("expense_report")、items(数组)、total(数字)。""");

    private static final SystemMessage AUDIT_PROMPT = new SystemMessage("""
            你是一个费用合规审核专家。根据公司费用政策和报销条目，进行合规比对。
            比对时务必对齐口径：政策限额(limit)是单价上限——住宿按每晚单价(unit_price)比对，而非总价(amount)。
            对于每个条目，判断其单价是否超出政策限额。
            如果任何条目单价超出限额，设置 risk_level 为 "high"；否则为 "none"。
            输出违规项列表(violations)和审核摘要(summary)。
            以 JSON 格式返回。""");

    private static final SystemMessage AUTO_APPROVE_PROMPT = new SystemMessage("""
            你是一个报销审核员。所有条目均在政策范围内，生成审核通过报告。
            总结报销总额、条目数，说明已通过自动审核。
            以自然语言输出。""");

    @Bean
    AgentHandler expenseReviewHandler(
            @Value("${expense-review.model-provider:openai}") String modelProvider,
            @Value("${expense-review.api-key:}") String apiKey,
            @Value("${expense-review.api-base:http://localhost:4000/v1}") String apiBase,
            @Value("${expense-review.model-name:gpt-4o-mini}") String modelName,
            @Value("${expense-review.ssl-verify:true}") boolean sslVerify) {
        Workflow workflow = buildExpenseReviewWorkflow(modelProvider, apiKey, apiBase, modelName, sslVerify);
        WorkflowAgentConfig cfg = WorkflowAgentConfig.builder()
                .id("expense-review")
                .description("费用报销审核 Workflow Agent — 分析报销→查政策→合规比对→审批")
                .build();
        WorkflowAgent agent = new WorkflowAgent(cfg);
        agent.addWorkflows(List.of(workflow));
        return new JiuwenCoreAgentHandler(agent);
    }

    static Workflow buildExpenseReviewWorkflow(String modelProvider, String apiKey, String apiBase,
                                               String modelName, boolean sslVerify) {
        ModelClientConfig clientCfg = buildClientConfig(modelProvider, apiKey, apiBase, sslVerify);
        ModelRequestConfig reqCfg = buildRequestConfig(modelName);

        WorkflowCard card = WorkflowCard.builder()
                .id("expense-review")
                .name("费用报销审核")
                .version("1.0")
                .description("分析报销→查政策→合规比对→审批")
                .build();
        Workflow wf = new Workflow(card);

        addStartNode(wf);
        addAnalyzeNode(wf, clientCfg, reqCfg);
        addCheckPolicyNode(wf);
        addAuditNode(wf, clientCfg, reqCfg);
        addRouteBranch(wf);
        addApproveNode(wf, clientCfg, reqCfg);
        addAutoApproveNode(wf, clientCfg, reqCfg);
        addEndNode(wf);
        addConnections(wf);

        return wf;
    }

    private static ModelClientConfig buildClientConfig(String modelProvider, String apiKey,
                                                       String apiBase, boolean sslVerify) {
        return ModelClientConfig.builder()
                .clientProvider(modelProvider)
                .apiKey(apiKey)
                .apiBase(apiBase)
                .verifySsl(sslVerify)
                .build();
    }

    private static ModelRequestConfig buildRequestConfig(String modelName) {
        return ModelRequestConfig.builder()
                .modelName(modelName)
                .temperature(0.0)
                .maxTokens(1024)
                .build();
    }

    private static void addStartNode(Workflow wf) {
        wf.setStartComp("start", new Start(),
                Map.of("query", "${query}"), null);
    }

    private static void addAnalyzeNode(Workflow wf, ModelClientConfig clientCfg, ModelRequestConfig reqCfg) {
        LLMCompConfig analyzeCfg = new LLMCompConfig();
        analyzeCfg.setModelClientConfig(clientCfg);
        analyzeCfg.setModelConfig(reqCfg);
        analyzeCfg.setSystemPromptTemplate(ANALYZE_PROMPT);
        // Two distinct template mechanisms meet here:
        //  - PromptTemplate (below) uses {{localName}} delimiters and resolves
        //    against THIS component's local input map, whose keys are the LEFT-hand
        //    side of the input mapping — i.e. "query", not the global "start.query".
        //  - The input mapping's RIGHT-hand side "${start.query}" is a graph-engine
        //    reference path, resolved by the engine into the local "query" key.
        // Writing ${query} here would be left literal (wrong delimiters); writing
        // {{start.query}} would also miss (global path, not a local key).
        analyzeCfg.setUserPromptTemplate(new UserMessage("请分析以下报销申请：{{query}}"));
        analyzeCfg.setResponseFormat(new LinkedHashMap<>(Map.of("type", "json")));
        analyzeCfg.setOutputConfig(new LinkedHashMap<>(Map.of(
                "intent", Map.of("type", "string", "description", "意图类型"),
                "items", Map.of("type", "array", "description", "报销条目列表",
                        "items", Map.of("type", "object", "properties", Map.of(
                                "name", Map.of("type", "string", "description", "条目名称"),
                                "amount", Map.of("type", "number", "description", "金额(总价)"),
                                "unit_price", Map.of("type", "number", "description", "单价(住宿为每晚)"),
                                "quantity", Map.of("type", "number", "description", "数量(住宿为晚数)"),
                                "category", Map.of("type", "string", "description", "类别")))),
                "total", Map.of("type", "number", "description", "总金额"))));
        wf.addWorkflowComp("analyze", new LLMComponent(analyzeCfg),
                Map.of("query", "${start.query}"), null);
    }

    private static void addCheckPolicyNode(Workflow wf) {
        Tool policyTool = new CompanyPolicyTool();
        ToolComponentConfig toolCfg = new ToolComponentConfig();
        ToolComponent toolComp = new ToolComponent(toolCfg).bindTool(policyTool);
        wf.addWorkflowComp("check_policy", toolComp,
                Map.of("items", "${analyze.items}"), null);
    }

    private static void addAuditNode(Workflow wf, ModelClientConfig clientCfg, ModelRequestConfig reqCfg) {
        LLMCompConfig auditCfg = new LLMCompConfig();
        auditCfg.setModelClientConfig(clientCfg);
        auditCfg.setModelConfig(reqCfg);
        auditCfg.setSystemPromptTemplate(AUDIT_PROMPT);
        // {{items}} / {{policy_rules}} resolve against this component's local input
        // map (PromptTemplate default {{}} delimiters, LEFT-hand keys of the mapping
        // below). The policy_rules binding must read ${check_policy.data.policy_rules}
        // — ToolComponentOutput wraps every non-restful tool's result under a "data"
        // key (ToolComponentOutput.RESTFUL_DATA), so the rules live one level down.
        // If either the delimiters were wrong (${items}) or the path omitted .data,
        // the audit LLM saw literal/empty values, classified everything risk=none,
        // and the branch correctly routed to auto_approve.
        auditCfg.setUserPromptTemplate(
                new UserMessage("报销条目：{{items}}\n公司政策：{{policy_rules}}"));
        auditCfg.setResponseFormat(new LinkedHashMap<>(Map.of("type", "json")));
        auditCfg.setOutputConfig(new LinkedHashMap<>(Map.of(
                "violations", Map.of("type", "array", "description", "违规项列表",
                        "items", Map.of("type", "object", "properties", Map.of(
                                "item", Map.of("type", "string", "description", "违规条目名称"),
                                "rule", Map.of("type", "string", "description", "违反的政策规则"),
                                "gap", Map.of("type", "number", "description", "超出金额")))),
                "risk_level", Map.of("type", "string", "description", "风险等级: high 或 none"),
                "summary", Map.of("type", "string", "description", "审核摘要"))));
        wf.addWorkflowComp("audit", new LLMComponent(auditCfg),
                Map.of("items", "${analyze.items}",
                        "policy_rules", "${check_policy.data.policy_rules}"), null);
    }

    private static void addRouteBranch(Workflow wf) {
        BranchComponent branch = new BranchComponent();
        branch.addBranch("${audit.risk_level} == \"high\"", "approve", "high_risk");
        branch.addBranch("true", "auto_approve", "low_risk");
        wf.addWorkflowComp("route", branch,
                Map.of("risk_level", "${audit.risk_level}"), null);
    }

    private static void addApproveNode(Workflow wf, ModelClientConfig clientCfg, ModelRequestConfig reqCfg) {
        QuestionerConfig qCfg = new QuestionerConfig();
        qCfg.setModelClientConfig(clientCfg);
        qCfg.setModelConfig(reqCfg);
        qCfg.setResponseType("reply_directly");
        qCfg.setExtractFieldsFromResponse(false);
        qCfg.setQuestionContent("费用报销审核需要您的审批。请审核后输入 'approved' 通过，或说明拒绝理由。");
        wf.addWorkflowComp("approve", new QuestionerComponent(qCfg),
                Map.of("summary", "${audit.summary}",
                        "violations", "${audit.violations}"), null);
    }

    private static void addAutoApproveNode(Workflow wf, ModelClientConfig clientCfg, ModelRequestConfig reqCfg) {
        LLMCompConfig autoCfg = new LLMCompConfig();
        autoCfg.setModelClientConfig(clientCfg);
        autoCfg.setModelConfig(reqCfg);
        autoCfg.setSystemPromptTemplate(AUTO_APPROVE_PROMPT);
        autoCfg.setUserPromptTemplate(new UserMessage("审核结果：{{summary}}\n请生成审核通过报告。"));
        autoCfg.setResponseFormat(new LinkedHashMap<>(Map.of("type", "text")));
        autoCfg.setOutputConfig(new LinkedHashMap<>(Map.of(
                "text", Map.of("type", "string", "description", "审核通过报告"))));
        wf.addWorkflowComp("auto_approve", new LLMComponent(autoCfg),
                Map.of("summary", "${audit.summary}"), null);
    }

    private static void addEndNode(Workflow wf) {
        wf.setEndComp("end", new End(),
                Map.of("result", "${approve.user_response}",
                        "auto_result", "${auto_approve.text}"), null);
    }

    private static void addConnections(Workflow wf) {
        wf.addConnection("start", "analyze");
        wf.addConnection("analyze", "check_policy");
        wf.addConnection("check_policy", "audit");
        wf.addConnection("audit", "route");
        wf.addConnection("approve", "end");
        wf.addConnection("auto_approve", "end");
    }
}
