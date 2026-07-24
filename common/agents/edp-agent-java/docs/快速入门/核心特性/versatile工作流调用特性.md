# Versatile 工作流调用特性

## 特性概述

EDPAgent 本身是 Agent 运行时，不内置工作流引擎。复杂的业务工作流（如理财产品购买、转账、开户等）通过 `call_versatile` 工具**委托**给外部 Versatile 服务执行。

这种设计的优势：
- **职责分离**：Agent 负责理解意图、规划任务、交互协调；Versatile 负责具体业务流程执行
- **复用现有系统**：企业已有的业务工作流可以直接通过 Versatile 接入，无需重写
- **中断续传**：支持工作流执行过程中的用户交互中断，用户补充信息后从断点恢复

## 两种调用模式

| 模式 | 说明 | 配置项 |
|------|------|--------|
| REST 直连 | 直接调用 Versatile REST API，通过 URL 模板和参数组装请求 | `EDP_AGENT_VERSATILE_URL` |
| A2A 适配 | 通过 A2A 协议调用 Versatile 的 A2A 适配器 | `EDP_AGENT_VERSATILE_A2A_URL` |

当前版本默认使用 REST 直连模式。

## 工具参数说明

`call_versatile` 工具的输入参数：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query_description` | string | 是 | 委托查询描述，用于 Versatile 理解任务目的 |
| `query_intent` | string | 是 | 委托查询意图，用于工作流路由 |
| `query_response_analysis_scripts` | array | 否 | 响应归一化脚本列表，对 Versatile 返回结果进行标准化处理 |
| `response_template_keys` | array | 否 | 响应话术模板 key 列表，从 SKILL.yaml 或 scriptconfig.yaml 中取话术 |
| `notice_context` | object | 否 | 非中断话术上下文，在工作流执行过程中推送给用户的状态消息 |
| `input_key` | string | 否 | 从 ToolDataChannel 读取前序数据的 key，用于跨工具数据传递 |

## 工作流程

```
用户请求 → Agent规划 → todo_create → call_versatile
                                         │
                                         ▼
                              ┌─────────────────────┐
                              │ 声明委托意图         │
                              │ (返回 delegate_intent)│
                              └──────────┬──────────┘
                                         │
                                         ▼
                              ┌─────────────────────┐
                              │ VersatileInterrupt-  │
                              │ Rail 拦截处理        │
                              └──────────┬──────────┘
                                         │
                              ┌──────────┴──────────┐
                              │                     │
                    ┌─────────▼─────────┐  ┌───────▼────────┐
                    │ REST API 调用      │  │ A2A 协议调用   │
                    │ (url+params)       │  │ (adapter)      │
                    └─────────┬─────────┘  └───────┬────────┘
                              │                     │
                              └──────────┬──────────┘
                                         │
                              ┌──────────▼──────────┐
                              │ 结果归一化处理       │
                              │ (analysis_scripts)  │
                              └──────────┬──────────┘
                                         │
                              ┌──────────▼──────────┐
                              │ 写入ToolDataChannel │
                              └──────────┬──────────┘
                                         │
                              ┌──────────▼──────────┐
                              │ 需要用户交互？       │
                              └──────────┬──────────┘
                                    是 /   \ 否
                                       /     \
                                      ▼       ▼
                              ┌──────────┐ ┌────────────┐
                              │ ask_user │ │ todo_modify│
                              │ 触发中断 │ │ 任务完成    │
                              └──────────┘ └────────────┘
```

## 中断续传机制

Versatile 工作流执行过程中，如果需要用户补充信息（如输入转账金额、确认购买操作），会触发中断：

1. Versatile 返回需要交互的状态
2. VersatileInterruptRail 自动构造 ask_user 调用
3. Agent 通过 SSE 推送追问消息给用户
4. 用户回复后，Agent 携带回复内容重新调用 call_versatile
5. Versatile 从断点恢复执行

中断状态通过 Redis 持久化，服务重启后也能恢复。

## 配置方式

### 环境变量配置

在 [application.yml](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/application.yml) 中配置：

```yaml
edpa:
  agent:
    versatile:
      # REST API 地址模板（支持 {workflow_id}、{conversation_id} 路径变量）
      url: ${EDP_AGENT_VERSATILE_URL:http://localhost:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}}
      
      # A2A 适配器地址
      adapter-a2a-url: ${EDP_AGENT_VERSATILE_A2A_URL:http://localhost:8191/a2a}
      
      # 调用超时时间
      timeout: ${EDP_AGENT_VERSATILE_TIMEOUT:30s}
      
      # URL 路径变量默认值
      url-variables:
        workflow_id: mock_workflow
      
      # 默认查询参数
      query-params:
        type: controller
        workspace_id: "10"
      
      # 默认请求头
      headers:
        content-type: application/json
        stream: "true"
```

### Docker 环境变量示例

```bash
docker run -d \
  -e EDP_AGENT_VERSATILE_URL=http://versatile-server:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id} \
  -e EDP_AGENT_VERSATILE_TIMEOUT=60s \
  edp-agent-java:latest
```

## 使用示例

### 示例1：调用理财推荐工作流

```json
{
  "name": "call_versatile",
  "arguments": {
    "query_description": "根据用户风险偏好推荐理财产品",
    "query_intent": "理财产品推荐",
    "response_template_keys": ["product_recommend_success", "product_recommend_empty"]
  }
}
```

### 示例2：带输入数据的购买流程

```json
{
  "name": "call_versatile",
  "arguments": {
    "query_description": "执行理财产品购买",
    "query_intent": "产品购买确认",
    "input_key": "selected_product",
    "notice_context": {
      "message": "正在为您办理购买手续，请稍候..."
    },
    "query_response_analysis_scripts": [
      "def normalize(result):\n    if result.get('status') == 'need_confirm':\n        return {'need_user_input': True, 'fields': ['amount']}\n    return result"
    ]
  }
}
```

### 示例3：Mock 服务器测试

代码仓提供了 Mock Versatile 服务器用于测试，位于 [mock/](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/mock/) 目录：

```bash
cd mock
pip install -r requirements.txt  # 如果有requirements
python versatile_main.py
```

Mock 服务器提供了以下模拟工作流：
- `wealth_recommend.json` - 理财推荐
- `product_buy.json` - 产品购买
- `balance_query.json` - 余额查询
- `transfer_round1.json` - 转账（第一轮）
- `transfer_confirmed.json` - 转账确认
- `fund_recommend.json` - 基金推荐

## 调用次数限制

在 actrule.yaml 中可以配置 call_versatile 的调用次数上限，防止死循环：

```yaml
actrule:
  tool_limits:
    call_versatile: 50  # 单会话最多调用50次
```

## 注意事项

1. **超时设置**：生产环境建议将 `EDP_AGENT_VERSATILE_TIMEOUT` 设置为 60s 或更长，复杂工作流可能耗时较久。

2. **幂等性**：Versatile 工作流应保证幂等性，网络重试时不会重复执行业务操作。

3. **中断处理**：工作流中断状态保存在 Redis Checkpoint 中，Checkpoint TTL 由 `EDPA_REDIS_CHECKPOINTER_TTL` 控制（默认60分钟），超时后续传失效。

4. **结果归一化**：不同工作流返回格式可能不同，建议通过 `query_response_analysis_scripts` 统一格式，便于后续 Skill 和话术处理。

5. **Mock vs 生产**：`url-variables` 中的 `mock_workflow` 仅用于本地测试，生产环境应配置真实的 workflow_id 映射（通过场景配置或动态路由）。

6. **并发限制**：当前版本同一时间只能有一个 Versatile 调用在执行（中断等待用户输入期间不算），前一个完成后才能发起下一个。

## 代码参考

- [CallVersatileTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/CallVersatileTool.java)
- [VersatileInterruptRail.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/VersatileInterruptRail.java)
- [application.yml versatile配置](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/application.yml#L36-L47)
- [Mock 服务器](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/mock/)
