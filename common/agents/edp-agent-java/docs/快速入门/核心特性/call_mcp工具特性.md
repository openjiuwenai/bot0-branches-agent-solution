# call_mcp 工具特性

## 特性概述

`call_mcp` 工具用于调用 MCP（Model Context Protocol）SSE 服务执行脚本。MCP 提供了一个安全的脚本沙箱环境，Skill 中的 Python 脚本可以通过 MCP 执行，完成数据查询、计算、接口调用等操作。

MCP 的核心价值：
- **安全隔离**：Python 脚本在沙箱中运行，不直接访问 Agent 服务的资源
- **统一环境**：所有脚本运行在统一的 Python 环境中，依赖由 MCP 服务管理
- **主备切换**：支持配置主备 MCP URL，主节点故障时自动切换到备节点
- **数据直通**：执行结果自动写入 ToolDataChannel，供后续工具使用

## 工具参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `script_command` | string | 是 | 待执行的 MCP 脚本命令或脚本标识 |
| `script_params` | object | 否 | 脚本入参，key-value 格式传递给脚本 |

## 工作机制

```
Agent调用call_mcp
      │
      ▼
声明MCP调用意图（返回mcp_intent状态）
      │
      ▼
McpInterruptRail拦截处理
      │
      ├─ 从ToolDataChannel读取input_key对应数据（如有）
      ├─ 选择MCP节点（主→备，主节点不可用时切换）
      ├─ 构造MCP SSE请求
      ├─ 携带access-token鉴权
      └─ 发起SSE调用
      │
      ▼
MCP SSE服务执行脚本
      │
      ├─ Python脚本沙箱执行
      ├─ 流式返回执行进度
      └─ 返回最终结果
      │
      ▼
结果处理
      ├─ 将结果写入ToolDataChannel
      ├─ 触发SSE事件通知前端
      └─ 将结果返回给Agent（LLM可见）
```

## 配置方式

### 环境变量配置

在 [application.yml](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/application.yml) 中配置 MCP 连接：

```yaml
edpa:
  agent:
    mcpsse:
      # 主MCP SSE URL
      master-url: ${EDP_MCP_MASTER_URL:}
      
      # 备MCP SSE URL（主URL不可用时自动切换）
      standby-url: ${EDP_MCP_STANDBY_URL:}
      
      # 鉴权Token
      access-token: ${EDP_MCP_ACCESS_TOKEN:}
      
      # 应用名称
      app-name: ${EDP_MCP_APP_NAME:}
```

### Docker 环境变量示例

```bash
docker run -d \
  -e EDP_MCP_MASTER_URL=http://mcp-master:8080/sse \
  -e EDP_MCP_STANDBY_URL=http://mcp-standby:8080/sse \
  -e EDP_MCP_ACCESS_TOKEN=your-mcp-token \
  -e EDP_MCP_APP_NAME=edp-agent \
  edp-agent-java:latest
```

### Python 依赖

MCP 服务需要的 Python 依赖在 [deploy/requirements-mcp.txt](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/deploy/requirements-mcp.txt) 中定义，Docker 构建时会安装这些依赖。

## Skill 中的 Python 脚本

Skill 的脚本放在 `skills/<skill_name>/scripts/` 目录下，脚本可以通过 MCP 执行。

### 脚本示例：产品推荐

```python
# run_product_recommend_skill.py
import json
import requests

def run_product_recommend(params):
    """
    根据用户条件推荐理财产品
    params: 包含用户风险偏好、金额等参数
    """
    risk_level = params.get("risk_level", "R2")
    amount = params.get("amount", 0)
    
    # 调用产品查询接口（示例）
    # products = query_products_api(risk_level, amount)
    
    # Mock 数据
    products = [
        {"id": "P001", "name": "稳健理财A", "rate": "3.2%", "term": "90天", "risk": "R2"},
        {"id": "P002", "name": "增值理财B", "rate": "4.1%", "term": "180天", "risk": "R3"}
    ]
    
    return {
        "products": products,
        "total_count": len(products),
        "recommend_reason": f"根据您的风险偏好{risk_level}，为您推荐以上产品"
    }

if __name__ == "__main__":
    import sys
    params = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {}
    result = run_product_recommend(params)
    print(json.dumps(result, ensure_ascii=False))
```

### 脚本规范

1. **入口函数**：脚本应有明确的入口函数（如 `main`、`run_xxx`），MCP 调用时通过 `script_command` 指定。
2. **参数接收**：通过 `script_params` 传递的参数会作为关键字参数传入。
3. **返回格式**：必须返回 JSON 可序列化的 dict，结果会自动写入 ToolDataChannel。
4. **异常处理**：脚本内部应处理异常，返回有意义的错误信息，不要直接抛出未捕获异常。
5. **无副作用**：查询类脚本应保证幂等，不要在脚本中执行不可逆操作（如扣款、提交订单），这类操作应通过 Versatile 工作流执行。

## 使用示例

### 示例1：查询推荐产品

```json
{
  "name": "call_mcp",
  "arguments": {
    "script_command": "product_recommend",
    "script_params": {
      "risk_level": "R2",
      "amount": 50000
    }
  }
}
```

### 示例2：带前序数据的脚本调用

```json
{
  "name": "call_mcp",
  "arguments": {
    "script_command": "normalize_product_data",
    "script_params": {
      "format": "simple"
    }
  }
}
```

### 示例3：理财场景 interact_finance_rec_skill 中的 MCP 调用

参考 [scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts/run_mcp_recommend.py](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts/run_mcp_recommend.py)。

## 主备切换机制

MCP 客户端实现了主备自动切换：

1. 优先使用 `master-url` 发起调用
2. 如果主节点连接失败、超时或返回错误，自动切换到 `standby-url`
3. 如果备节点也失败，返回错误给 Agent
4. 下次调用重新从主节点开始尝试

```
请求 → 主节点 ──成功──→ 返回结果
   │
   └──失败──→ 备节点 ──成功──→ 返回结果
                │
                └──失败──→ 返回错误
```

## 结果数据通道

call_mcp 执行完成后，结果会自动写入 ToolDataChannel，默认键名规则为：
- 键名格式：`mcp_result_{script_command}`
- 作用域：SESSION
- 后续工具可以通过 `input_key` 参数读取该数据

例如调用 `script_command: "product_recommend"` 后，数据存储在：
`ToolDataChannel["SESSION"]["mcp_result_product_recommend"] = {执行结果}`

## 调用次数限制

在 actrule.yaml 中配置 call_mcp 的调用次数上限：

```yaml
actrule:
  tool_limits:
    call_mcp: 50  # 单会话最多调用50次
```

## 注意事项

1. **MCP 服务必须配置**：如果 `EDP_MCP_MASTER_URL` 为空，call_mcp 工具调用会失败。在不需要 MCP 的场景中，可以不配置，但要确保 actrule.yaml 的 allowed_tools 中不包含 call_mcp。

2. **脚本安全**：MCP 沙箱提供了隔离，但脚本仍应遵循最小权限原则，不要访问敏感资源。

3. **超时处理**：MCP SSE 调用有超时限制（由 MCP 服务配置），长时间运行的脚本应实现进度上报或拆分为多个步骤。

4. **依赖管理**：脚本依赖的 Python 包需要添加到 requirements-mcp.txt 中，并重新构建 Docker 镜像或在 MCP 服务上安装。

5. **错误处理**：脚本执行失败时，McpInterruptRail 会捕获异常并返回错误信息给 Agent，Agent 应根据错误信息决定重试、换参数还是提示用户。

6. **与 Versatile 的分工**：
   - **call_mcp**：适合数据查询、计算、格式转换等**只读/无副作用**的操作
   - **call_versatile**：适合业务流程执行、状态变更、跨系统调用等**有副作用**的操作

7. **日志**：MCP 脚本执行的 stdout/stderr 会被捕获，异常时可在 Agent 日志中查看。

## 代码参考

- [CallMcpTool.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/tools/CallMcpTool.java)
- [McpInterruptRail.java](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/McpInterruptRail.java)
- [application.yml MCP配置](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/application.yml#L48-L52)
- [requirements-mcp.txt](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/deploy/requirements-mcp.txt)
- [interact_finance_rec_skill MCP脚本示例](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/scenarios/wealth-demo/skills/interact_finance_rec_skill/scripts/run_mcp_recommend.py)
