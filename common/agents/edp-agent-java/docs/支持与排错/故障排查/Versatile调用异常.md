# Versatile调用异常排错指南

## 问题描述

EDPAgent-Java通过`call_versatile`工具调用Versatile工作流引擎时失败，导致业务操作无法完成、工作流执行中断。Versatile是EDPAgent的下游业务系统，负责执行具体的业务工作流（如理财推荐、转账、查询等）。

EDPAgent支持两种Versatile对接模式：
1. **REST直连模式**：直接调用Versatile REST API（配置`EDP_AGENT_VERSATILE_URL`）
2. **A2A适配器模式**：通过adapter-versatile-agent的A2A接口调用（配置`EDP_AGENT_VERSATILE_A2A_URL`，优先级更高）

## 常见症状

1. **工作流执行失败**：Agent回复"业务操作失败"、"调用下游系统异常"等错误
2. **业务操作无响应**：调用call_versatile后长时间等待，最终超时
3. **中断续传失败**：用户确认菜单后无法继续执行工作流
4. **日志报错**：
   - `VersatileInterruptRail: direct call failed`
   - `HTTP 404/500/503` 错误
   - `ConnectException: Connection refused`
   - `SocketTimeoutException`
   - `versatile config is missing`
   - `adapter A2A HTTP xxx`
5. **返回格式异常**：Versatile返回的数据格式无法解析，导致后续处理失败

## 可能原因

| 分类 | 可能原因 | 说明 |
|------|----------|------|
| 服务状态 | Versatile服务未启动 | Versatile或A2A适配器进程未运行 |
| 配置错误 | URL配置错误 | `EDP_AGENT_VERSATILE_URL`或`EDP_AGENT_VERSATILE_A2A_URL`不正确 |
| 配置错误 | URL模板变量缺失 | URL中的`{workflow_id}`、`{conversation_id}`未正确替换 |
| 超时配置 | 超时设置过短 | `EDP_AGENT_VERSATILE_TIMEOUT`默认30秒，长工作流可能不够 |
| 工作流配置 | workflow_id映射错误 | actrule.yaml中配置的workflow_id与实际不符 |
| 数据格式 | Versatile返回格式错误 | 返回非JSON、SSE格式异常、字段缺失 |
| 续传问题 | 中断续传失败 | 用户输入后无法正确恢复工作流状态 |
| 网络问题 | 网络不通/防火墙阻断 | EDPAgent无法访问Versatile服务 |
| 服务端问题 | Versatile返回5xx错误 | Versatile服务内部异常、过载 |
| 开发测试 | 未启动Mock服务器 | 开发环境未启动mock/目录下的Mock服务器 |

## 排查步骤

### 步骤1：确认使用的对接模式

首先查看日志确认使用的是REST直连还是A2A适配器模式：

```bash
# 查看Versatile相关启动日志
docker logs edp-agent 2>&1 | grep -i versatile
```

- 如果配置了`EDP_AGENT_VERSATILE_A2A_URL`且非空，优先使用A2A模式
- 否则使用REST直连模式（`EDP_AGENT_VERSATILE_URL`）

验证配置：
```bash
docker exec edp-agent env | grep VERSATILE
```

### 步骤2：使用curl测试Versatile服务连通性

**测试REST直连模式：**
```bash
# 替换为实际的URL（注意替换{workflow_id}和{conversation_id}）
curl -X POST "http://your-versatile:30001/v1/0/agent-manager/workflows/test_workflow/conversations/test-conv-001?type=controller&workspace_id=10" \
  -H "Content-Type: application/json" \
  -H "stream: true" \
  -d '{"inputs": {"query": "测试查询"}, "stream": true}' \
  -v
```

**测试A2A适配器模式：**
```bash
curl -X POST "http://your-a2a-adapter:8191/a2a" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "method": "SendStreamingMessage",
    "id": "test-001",
    "params": {
      "metadata": {"userId": "test", "agentId": "edp-agent"},
      "message": {
        "role": "ROLE_USER",
        "messageId": "msg-test",
        "contextId": "test-conv-001",
        "parts": [{"text": "{\"inputs\": {\"query\": \"测试\"}}"}]
      }
    }
  }' \
  -v
```

**判断结果：**
- 返回200 + SSE流/JSON：服务可达，继续排查
- Connection refused：服务未启动或网络不通
- 404：URL路径错误、workflow_id不存在
- 401/403：需要认证（当前代码未配置认证头）
- 500：Versatile服务端错误
- 超时：网络延迟高或服务过载

### 步骤3：检查EDP_AGENT_VERSATILE_*配置

| 环境变量 | 默认值 | 检查要点 |
|----------|--------|----------|
| `EDP_AGENT_VERSATILE_URL` | `http://localhost:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}` | URL模板必须包含`{workflow_id}`和`{conversation_id}`占位符 |
| `EDP_AGENT_VERSATILE_A2A_URL` | `http://localhost:8191/a2a` | A2A适配器地址，配置此项优先使用A2A模式 |
| `EDP_AGENT_VERSATILE_TIMEOUT` | `30s` | 超时时间，支持s(秒)/m(分钟)格式，生产环境建议60s |

**URL模板注意事项：**
- `{workflow_id}`：在application.yml的`edpa.agent.versatile.url-variables.workflow_id`配置默认值，或从场景配置中获取
- `{conversation_id}`：运行时自动替换为sessionId
- Query参数`type=controller`和`workspace_id=10`已在配置中默认设置

查看实际运行时配置：
```bash
# 查看application.yml中的versatile配置
grep -A 20 "versatile:" engine/src/main/resources/application.yml
```

### 步骤4：检查超时时间设置

如果工作流执行时间较长（如复杂查询、多步骤审批），默认30秒可能不够：

```bash
# 增大超时到60秒或更长
EDP_AGENT_VERSATILE_TIMEOUT=60s
# 或2分钟
EDP_AGENT_VERSATILE_TIMEOUT=2m
```

查看日志中的超时错误：
```bash
docker logs edp-agent 2>&1 | grep -i "timeout\|timed out"
```

### 步骤5：检查workflow_id映射

workflow_id来源：
1. 默认值：`application.yml`中的`edpa.agent.versatile.url-variables.workflow_id`（默认为`mock_workflow`）
2. 场景配置：查看对应场景的actrule.yaml或scriptconfig.yaml
3. 工具调用参数：call_versatile时传入的参数

检查当前使用的workflow_id：
```bash
# 查看application.yml配置
grep "workflow_id" engine/src/main/resources/application.yml

# 查看场景配置
cat scenarios/wealth-demo/governance/actrule.yaml
# 或
cat scenarios/hz-zhidaitong/governance/actrule.yaml
```

### 步骤6：启动Mock服务器进行开发测试（仅开发环境）

代码仓`mock/`目录提供了Versatile Mock服务器，用于开发调试：

**启动Mock服务器：**
```bash
cd mock
pip install fastapi uvicorn python-dotenv loguru
python versatile_main.py
```

默认监听`http://127.0.0.1:30001`，配置EDPAgent连接Mock：
```bash
EDP_AGENT_VERSATILE_URL=http://localhost:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
# 不配置A2A_URL，使用REST直连Mock
```

**Mock服务器内置工作流：**
| 工作流文件 | 意图 | 说明 |
|-----------|------|------|
| `wealth_recommend.json` | 理财推荐 | 推荐理财产品 |
| `balance_query.json` | 余额查询 | 含BALANCE_MENU卡片 |
| `transfer_round1.json` | 转账首轮 | TRANSFER_MENU菜单 |
| `fund_recommend.json` | 基金推荐 | 基金推荐 |
| `product_buy.json` | 购买签署 | 产品购买流程 |
| `default.json` | 兜底 | 默认响应 |

测试Mock是否正常：
```bash
curl http://localhost:30001/health
# 返回OK表示正常
```

### 步骤7：查看Versatile端日志

如果Versatile服务已部署但调用失败，需要查看Versatile服务端日志：

```bash
# 如果Versatile也是Docker部署
docker logs versatile --tail 100 -f

# 如果是A2A适配器
docker logs versatile-a2a-adapter --tail 100 -f
```

关注以下错误：
- 参数校验失败
- 工作流不存在
- 数据库连接失败
- 内部服务异常
- 权限认证失败

### 步骤8：检查A2A适配器配置（如使用A2A模式）

如果使用A2A模式，确认：
1. adapter-versatile-agent服务已启动并监听正确端口
2. `EDP_AGENT_VERSATILE_A2A_URL`指向正确的A2A端点
3. A2A适配器已正确配置Versatile后端地址

测试A2A适配器健康状态：
```bash
curl http://your-a2a-adapter:8191/health
# 或查看A2A适配器日志确认
```

### 步骤9：检查中断续传问题

如果首次调用成功但用户确认后续传失败：

1. 检查Redis是否正常（续传状态存储在Redis Checkpointer中）
2. 查看日志中是否有`adapter requested user input`和`resuming call_versatile`
3. 确认`passthrough_nodes`正确传递
4. 检查ToolDataChannel中数据是否正确存储

相关日志：
```
VersatileInterruptRail: adapter requested user input, toolCallId=xxx
VersatileInterruptRail: resuming call_versatile with adapter result
```

### 步骤10：验证响应格式解析

VersatileInterruptRail支持响应格式：
1. 标准JSON（含content/answer/data字段）
2. SSE流格式（data: {...}行）
3. A2A SSE格式（artifactUpdate/statusUpdate）

如果Versatile返回格式异常，查看日志中的响应体：
```bash
# 设置DEBUG日志查看完整响应
EDP_AGENT_LOG_LEVEL=DEBUG
docker logs edp-agent 2>&1 | grep -A 5 "response body"
```

## 解决方案

### 方案1：启动Versatile服务或Mock服务器

**生产环境：**
- 确认Versatile服务已启动并正常运行
- 确认A2A适配器（如使用）已启动
- 检查服务注册中心（如Nacos/Eureka）确认服务注册正常

**开发环境：**
启动Mock服务器（见步骤6）。

### 方案2：修正URL配置

**本地开发连Mock：**
```bash
EDP_AGENT_VERSATILE_URL=http://localhost:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
EDP_AGENT_VERSATILE_A2A_URL=  # 清空，使用REST直连
EDP_AGENT_VERSATILE_TIMEOUT=30s
```

**生产环境REST直连：**
```bash
EDP_AGENT_VERSATILE_URL=http://versatile.internal.example.com/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
EDP_AGENT_VERSATILE_A2A_URL=
EDP_AGENT_VERSATILE_TIMEOUT=60s
```

**生产环境A2A模式：**
```bash
EDP_AGENT_VERSATILE_URL=http://versatile.internal.example.com/...  # 可保留但不使用
EDP_AGENT_VERSATILE_A2A_URL=http://versatile-a2a.internal.example.com/a2a
EDP_AGENT_VERSATILE_TIMEOUT=60s
```

**Docker Compose（同网络）：**
```yaml
services:
  versatile:
    image: versatile:latest
    ports:
      - "30001:30001"
  
  edp-agent:
    image: edp-agent:latest
    environment:
      - EDP_AGENT_VERSATILE_URL=http://versatile:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id}
    depends_on:
      - versatile
```

### 方案3：增大超时时间

对于复杂工作流（涉及多个人工节点、慢查询），增大超时：

```bash
# 60秒（推荐生产环境）
EDP_AGENT_VERSATILE_TIMEOUT=60s

# 2分钟（超长工作流）
EDP_AGENT_VERSATILE_TIMEOUT=2m
```

超时支持的格式：
- `ms`：毫秒（如`5000ms`）
- `s`：秒（如`60s`）
- `m`：分钟（如`2m`）
- ISO-8601时长格式（如`PT1M30S`）

### 方案4：修正workflow_id配置

在`application.yml`中配置默认workflow_id：
```yaml
edpa:
  agent:
    versatile:
      url-variables:
        workflow_id: your_actual_workflow_id  # 修改为实际工作流ID
```

或在场景配置中指定（参考`scenarios/wealth-demo/governance/`下的配置文件）。

### 方案5：检查并修复防火墙/网络

```bash
# 测试端口连通性
telnet versatile-host 30001
nc -zv versatile-host 30001

# Docker网络检查
docker network ls
docker network inspect edp-network  # 确认两个容器在同一网络
```

如果跨网络访问：
- 确认安全组/防火墙开放对应端口
- 确认DNS能正确解析Versatile主机名
- 必要时使用IP地址代替主机名

### 方案6：处理HTTP错误码

| HTTP码 | 解决方案 |
|--------|----------|
| 404 | 检查URL路径、workflow_id是否正确 |
| 401/403 | 联系Versatile管理员配置认证（当前代码未支持，需扩展headers） |
| 415 | 检查Content-Type是否为application/json |
| 429 | Versatile限流，降低请求频率或联系扩容 |
| 500 | 查看Versatile端日志，联系Versatile开发排查 |
| 503 | Versatile过载或维护中，稍后重试 |

### 方案7：开发环境使用Mock进行端到端测试

完整开发环境配置：

```bash
# 终端1：启动Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# 终端2：启动Versatile Mock
cd mock
pip install fastapi uvicorn python-dotenv loguru
python versatile_main.py

# 终端3：启动EDPAgent
cd engine
EDP_AGENT_MODEL_API_KEY=sk-your-key \
EDPA_REDIS_HOST=localhost \
EDP_AGENT_VERSATILE_URL=http://localhost:30001/v1/0/agent-manager/workflows/{workflow_id}/conversations/{conversation_id} \
mvn spring-boot:run
```

Mock管理接口：
- `POST /admin/reload`：热加载workflows/*.json配置，无需重启
- `POST /reset_transfer_counter`：重置转账测试状态
- `GET /health`：健康检查

## 相关配置/日志关键词

### 配置项
- `edpa.agent.versatile.url` - Versatile REST URL模板
- `edpa.agent.versatile.adapter-a2a-url` - A2A适配器URL
- `edpa.agent.versatile.timeout` - 调用超时
- `edpa.agent.versatile.url-variables` - URL变量替换
- `edpa.agent.versatile.query-params` - 默认Query参数
- `edpa.agent.versatile.headers` - 默认请求头

### 日志关键词
- `VersatileInterruptRail: intercepting call_versatile` - 开始调用
- `VersatileInterruptRail: POST` - 实际请求URL
- `VersatileInterruptRail: response status=` - 响应状态码
- `VersatileInterruptRail: direct call failed` - 调用失败
- `versatile config is missing` - 配置缺失
- `adapter A2A HTTP` - A2A适配器错误
- `adapter requested user input` - 需要用户输入（正常中断）
- `resuming call_versatile with adapter result` - 续传恢复
- `queued passthrough nodes` - 透传节点排队

### 代码位置
- Versatile中断Rail：`engine/src/main/java/com/huawei/ascend/edp/rail/VersatileInterruptRail.java`
- Versatile工具定义：`engine/src/main/java/com/huawei/ascend/edp/tools/CallVersatileTool.java`
- Mock服务器：`mock/versatile_main.py`
- Mock工作流：`mock/workflows/*.json`

## 预防措施

1. **开发环境使用Mock**：开发阶段始终使用Mock服务器，不依赖真实Versatile
2. **配置健康检查**：监控Versatile服务健康状态
3. **超时合理设置**：生产环境设置足够的超时（60s+）
4. **熔断降级**：
   - Versatile调用失败时返回友好提示
   - 配置熔断机制，避免雪崩
   - 必要时提供降级方案
5. **多环境配置**：开发/测试/生产使用不同的Versatile地址
6. **联调测试**：与Versatile团队建立联调机制，版本升级前充分测试
7. **日志追踪**：每次Versatile调用记录requestId，便于跨服务排查
8. **契约测试**：维护EDPAgent与Versatile之间的接口契约测试

## 参考链接

- [Versatile工作流调用特性文档](../../getting-started/features/versatile工作流调用特性.md)
- [Mock服务器README](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/mock/README.md)
- [Adapter-Versatile-Agent使用手册](../../design/Adapter-Versatile-Agent使用手册.md)
- [环境变量参考](../../reference/环境变量参考.md#versatile-配置)
- [VersatileInterruptRail代码](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/java/com/huawei/ascend/edp/rail/VersatileInterruptRail.java)
