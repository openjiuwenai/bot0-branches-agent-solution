# workflow_node 用例迁移清单

源：`agent-studio/0812/agent-studio/agent-runtime/jiuwen/test/cases/workflow_node/`  
目标：`agent-core-ext-studio-dsl/src/test/java/.../WorkflowNodeCasesTest.java`（及既有 `*ParityTest`）

| Python 文件 | 状态 | Java 落点 |
| --- | --- | --- |
| `test_flow_aggregate_cases.py` | **已迁**（节点级；端到端图调度延后） | `WorkflowNodeCasesTest.AggregateCases` |
| `test_flow_message_cases.py` | **已迁**（节点 invoke/stream/校验；父子工作流延后） | `WorkflowNodeCasesTest.MessageCases` |
| `test_flow_input.py`（unit） | **已迁**（Utils + hang/resume） | `WorkflowNodeCasesTest.InputUtilsCases` + `P5P6ParityTest` |
| ExceptionInfo abort | **已迁**（默认 abort + `workflow_exception`） | `WorkflowNodeCasesTest.EndAndExceptionCases` |
| End 模板 / `#end_` / 幂等 | **已迁**（节点级） | 同上 + `P5P6ParityTest` |
| `test_flow_exception.py`（完整图+Questioner+Branch） | 延后 | 依赖编排 |
| `test_start_end_components.py`（父子记忆变量） | 延后 | 依赖 SubWorkflow |
| 真 LLM / MCP / multi-agent / card real | 延后 | 外部依赖 |

运行：

```bash
cd agent-solution/.../agent-core-ext-studio-dsl
mvn -Dtest=WorkflowNodeCasesTest,P5P6ParityTest test
```

说明：Java invoke 统一包在 `userFields` 下；Python Message 顶层 `{"result":...}` 在断言时按 `userFields.result` 对齐。
