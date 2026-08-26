# workflow_node 用例迁移清单（55）

源：`agent-studio/0812/agent-studio/agent-runtime/jiuwen/test/cases/workflow_node/`  
目标：`agent-core-ext-studio-dsl/src/test/java/.../WorkflowNode*CasesTest`  
策略：节点级 `registry.create` + invoke；多节点链式用例用测试侧 `LinearWorkflowTestSupport`；不做完整多 Agent Controller 复刻。

状态约定：

| 状态 | 含义 |
| --- | --- |
| **done** | 用户可见行为已有可启用 JUnit（mock / 线性链） |
| **partial** | 核心路径已迁；图编排 / 流式 Wrapper 等仍延后 |
| **out** | 真 LLM / 真网联调非 FEAT-031 验收；Java 侧仅 mock 替身，不保留 `@Disabled` 骨架 |

| # | Python 文件 | 状态 | Java 落点 |
| ---: | --- | --- | --- |
| 1 | `test_branch_multi_condition_logic_and/test_branch_multi_condition_logic_and.py` | **done** | `WorkflowNodeBranchMultiConditionCasesTest` |
| 2 | `test_case_agent_controller_enhance_01/test_intent_detection_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_intent_detection_workflow` |
| 3 | `test_case_agent_controller_enhance_01/test_rongejie_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_rongejie_workflow`（Questioner hang/resume→Message→End） |
| 4 | `test_case_agent_controller_enhance_01/test_smart_outbound_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_smart_outbound_workflow` |
| 5 | `test_case_agent_controller_new_06/test_end_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_end_workflow_new_06` |
| 6 | `test_case_agent_controller_new_06/test_shengjin_youli_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_shengjin_youli_workflow`（Branch 选路 + Message→End） |
| 7 | `test_case_agent_controller_new_06/test_smart_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_smart_workflow`（Branch 选路 + Message→End） |
| 8 | `test_case_agent_controller_new_06/test_start_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_start_workflow_new_06`（Questioner+Intent+Branch；真 LLM 延后） |
| 9 | `test_case_aggregation_common_02/test_case_aggregation_common_02.py` | **done** | `WorkflowNodeAggregationCommonCasesTest` |
| 10 | `test_case_aggregation_common_08/test_case_aggregation_common_08.py` | **done** | `WorkflowNodeAggregationCommonCasesTest` |
| 11 | `test_case_controller_multi_agent_02/test_end_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_end_workflow_multi_agent_02` |
| 12 | `test_case_controller_multi_agent_02/test_financial_default_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_financial_default_workflow` |
| 13 | `test_case_controller_multi_agent_02/test_financial_start_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_financial_start_workflow` |
| 14 | `test_case_controller_multi_agent_02/test_financial_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_financial_workflow` |
| 15 | `test_case_controller_multi_agent_02/test_transfer_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_transfer_workflow_multi_agent_02`（双 Questioner hang/resume） |
| 16 | `test_case_controller_multi_agent_27/test_default_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_default_workflow` |
| 17 | `test_case_controller_multi_agent_27/test_end_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_end_workflow_multi_agent_27` |
| 18 | `test_case_controller_multi_agent_27/test_faq_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_faq_workflow` |
| 19 | `test_case_controller_multi_agent_27/test_financial_service_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_financial_service_workflow` |
| 20 | `test_case_controller_multi_agent_27/test_start_workflow.py` | **done** | `WorkflowNodeControllerSuiteCasesTest.test_start_workflow_multi_agent_27` |
| 21 | `test_case_controller_plan_execute_common_01/test_query_account_workflow.py` | **done** | `WorkflowNodePlanExecuteCasesTest.test_query_account_workflow` |
| 22 | `test_case_controller_plan_execute_common_01/test_query_bill_workflow.py` | **done** | `WorkflowNodePlanExecuteCasesTest.test_query_bill_workflow` |
| 23 | `test_case_controller_plan_execute_common_01/test_repay_credit_workflow.py` | **done** | `WorkflowNodePlanExecuteCasesTest.test_repay_credit_workflow` |
| 24 | `test_case_controller_plan_execute_common_01/test_transfer_workflow.py` | **done** | `WorkflowNodePlanExecuteCasesTest.test_transfer_workflow` |
| 25 | `test_case_controller_plan_execute_common_01/test_workflow_parent_query_bill.py` | **done** | `WorkflowNodePlanExecuteCasesTest.test_workflow_parent_query_bill` |
| 26 | `test_case_controller_plan_execute_common_01/test_workflow_parent_repay_credit.py` | **done** | `WorkflowNodePlanExecuteCasesTest.test_workflow_parent_repay_credit` |
| 27 | `test_case_flow_input/test_flow_input_sub_workflow.py` | **done** | `WorkflowNodeInputWorkflowCasesTest`（嵌套 Input hang/resume + 父链） |
| 28 | `test_case_flow_input/test_flow_input_workflow.py` | **done** | `WorkflowNodeInputWorkflowCasesTest` |
| 29 | `test_case_llm_react_multy_tools_010/test_workflow_react_tools.py` | **done** | `WorkflowNodeDeferredParityCasesTest.ReactToolsSimplified`（Message echo + End；真 ReAct 延后） |
| 30 | `test_case_loop_multi_condition_logic_or/test_case_loop_multi_condition_logic_or.py` | **done** | `WorkflowNodeDeferredParityCasesTest.LoopMultiConditionOr`（numLoop+Message + OR breakCondition） |
| 31 | `test_case_mcp_api_test_normal_001.py` | **done** | `WorkflowNodeMcpCasesTest`（mock；真 SSE/network 延后） |
| 32 | `test_case_multi_instance_common_02/test_questioner_ssq.py` | **done** | `WorkflowNodeDeferredParityCasesTest.QuestionerSsq`（多字段 hang/partial/complete→End） |
| 33 | `test_case_plugin_multi_level_params_01.py` | **done** | `WorkflowNodeLlmMockCasesTest.test_flow_api_multi_level_params_mock`（真 HTTP stream **OUT**） |
| 34 | `test_case_workflow_agent_component_common_01/test_case_workflow_agent_component_common_01.py` | **done** | `WorkflowNodeAgentCasesTest`（ReAct stub；真 SiliconFlow 延后） |
| 35 | `test_case_workflow_card001/test_case_workflow_card001.py` | **done** | `WorkflowNodeCardCasesTest` |
| 36 | `test_case_workflow_mult_loop_common_002/test_case_workflow_mult_loop_common_002.py` | **done** | `WorkflowNodeDeferredParityCasesTest.MultLoopBranch`（Branch→Loop body mock） |
| 37 | `test_flow_agent.py` | **done** | `FlowAgentParityTest` + `WorkflowNodeAgentCasesTest`（stub ReAct；真 LLM 延后） |
| 38 | `test_flow_agent_base_ir.py` | **done** | `FlowAgentParityTest` / `WorkflowNodeAgentCasesTest` |
| 39 | `test_flow_aggregate_cases.py` | **done** | `WorkflowNodeCasesTest.AggregateCases` |
| 40 | `test_flow_api.py` | **done** | `WorkflowNodePluginApiCasesTest`（真 HTTP stream 延后） |
| 41 | `test_flow_card_use_real_llm.py` | **done** | `WorkflowNodeLlmMockCasesTest.test_complete_workflow_from_ir_card_mock`（真 LLM **OUT**） |
| 42 | `test_flow_exception.py` | **done** | `WorkflowNodeExceptionCasesTest`（Start→Questioner mock→Branch→Exception/End） |
| 43 | `test_flow_extractor.py` | **done** | `WorkflowNodeExtractorCasesTest` + `ExtractorParityTest`（stub LLM，严格 1:1 extension；真 LLM / Wrapper stream 延后） |
| 44 | `test_flow_input.py` | **done** | `WorkflowNodeCasesTest.InputUtilsCases` + `WorkflowNodeInteractControlEiParityTest` |
| 45 | `test_flow_input_base_ir.py` | **done** | `WorkflowNodeInputWorkflowCasesTest`（含 LinearWorkflowTestSupport Start→Input→End） |
| 46 | `test_flow_mcp.py` | **done** | `FlowMcpParityTest` + `WorkflowNodeMcpCasesTest`（stub client；真 SSE 网延后） |
| 47 | `test_flow_message_cases.py` | **done** | `WorkflowNodeCasesTest.MessageCases` |
| 48 | `test_flow_stream_transform.py` | **done** | `WorkflowNodeStreamTransformCasesTest` |
| 49 | `test_intent_detection.py` | **done** | `WorkflowNodeIntentDetectionCasesTest`（mock） |
| 50 | `test_intent_detection_real_llm.py` | **done** | `WorkflowNodeLlmMockCasesTest.test_workflow_intent_detection_weather_mock`（真 LLM **OUT**） |
| 51 | `test_llm_chain.py` | **done** | `WorkflowNodeLlmMockCasesTest`（`test_workflow_llm_text_invoke_mock` / `test_workflow_llm_chain_two_nodes_mock`；真 LLM **OUT**） |
| 52 | `test_loop_component.py` | **done** | `WorkflowNodeLoopCasesTest`（LoopGroup/`_request.`/connections） |
| 53 | `test_questioner_interrupt.py` | **done** | `WorkflowNodeQuestionerInterruptCasesTest` + `QuestionerLlmAndTraceTest`（LLM stub / TraceStore） |
| 54 | `test_start_end_components.py` | **done** | `WorkflowNodeStartEndCasesTest`（End stream/struct + setVariable+message + Nested resolver） |
| 55 | `test_sub_workflow.py` | **done** | `WorkflowNodeSubWorkflowCasesTest`（interrupt / REQUEST sync / stream / InteractiveInput / sanitize / core SubWorkflowComponent） |

## 汇总

| 状态 | 数量 |
| --- | ---: |
| **done** | 55 |
| **partial** | 0 |
| **out**（真 LLM/真网，非 031 验收） | 4（上表 #33/#41/#50/#51 的 Python 真网路径） |
| **合计** | **55** |

## 本批新增 / 加深

| 类 | 覆盖 |
| --- | --- |
| `WorkflowNodeDeferredParityCasesTest` | `#29` `#30` `#32` `#36` 启用 mock |
| `WorkflowNodeSubWorkflowCasesTest` | `#55` interrupt + REQUEST sync + stream + InteractiveInput + sanitize + core SubWorkflow |
| `WorkflowNodeInputWorkflowCasesTest` | `#27` `#28` `#45` linear + 嵌套 resume 链 |
| `WorkflowNodeExceptionCasesTest` | `#42` Start→Questioner→Branch→Exception/End |
| `WorkflowNodeQuestionerInterruptCasesTest` | `#53` 多轮 FieldInfo |
| `WorkflowNodeAgentCasesTest` / `Mcp` / `Extractor` | `#34` `#37` `#38` `#31` `#46` `#43` 加深 mock |
| `WorkflowNodeControllerSuiteCasesTest` | `#6` `#7` `#8` Branch 选路 |
| `WorkflowNodeEndMixGeneratorCasesTest` | End mix + Iterator 生成器 / collect / transform finish |

## 运行

```bash
cd agent-solution/.../agent-core-ext-studio-dsl
mvn -Dtest='WorkflowNode*CasesTest' test
```

说明：Java invoke 统一包在 `userFields` 下；线性链末端 `jiuwen.end` 的 `responseTemplate` 会覆盖 `result`/`answer`。Nested 在每个子节点后检查 session interrupt——子工作流若以 Start 开头，resume 时会在 Input 前短路，子图应以 Input/Questioner 为首节点。Questioner 多节点需隔离 `QuestionerState` / `USER_RESPONSE`。
