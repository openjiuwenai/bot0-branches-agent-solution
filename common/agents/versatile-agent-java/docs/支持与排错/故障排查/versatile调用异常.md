# Versatile调用异常排错指南

## 问题描述

adapter-versatile-agent-java作为A2A到Versatile REST/SSE的代理适配器，在接收A2A请求并转发调用Versatile工作流时失败，导致业务操作无法完成、SSE流异常中断。该适配器是轻量级无状态代理服务，纯Java实现，无Redis、数据库、MCP或Python依赖。

核心调用流程：
1. 接收A2A `sendStreamingMessage`请求（`/a2a`端点）
2. 提取请求参数，替换URL模板中的`{conversation_id}`占位符
3. 透传白名单Header，设置默认请求头
4. 通过HttpClient调用Versatile REST API
5. 解析Versatile返回的SSE流
6. 按节点类型三分流：
   - **Target.USER**：非结果节点透传给前端（如菜单、提示文本）
   - **Target.LLM**：结果节点（`GXZQAResponseNode`）回灌LLM
   - **Interrupt**：缺少End节点或需要用户输入时触发中断

核心实现：`VersatileAgentHandler`封装了HttpClient、请求提取（VersatileRequestExtractor）、响应提取（VersatileResponseExtractor）。

## 常见症状

1. **代理服务正常但请求无响应/超时**：
   - 发送A2A请求后长时间无SSE数据返回，最终超时
   - 日志显示SocketTimeoutException或ReadTimeout
2. **返回错误状态码**：
   - HTTP 4xx（400/401/403/404/415）
   - HTTP 5xx（500/502/503/504）
3. **SSE流中断**：
   - SSE流中途断开，未收到完整结果
   - 收到`data: [DONE]`但没有结果节点
   - SSE格式错误，无法解析为JSON
4. **结果节点未正确识别**：
   - 所有节点都透传给前端（始终透传），结果没有回灌LLM
   - 工作流提前中断（始终中断），无法完成
   - 日志显示"result node not found"或类似警告
5. **Header未正确传递**：
   - Versatile侧未收到预期Header（如x-invoke-mode、x-language）
   - stream标志未传递，Versatile返回非流式响应
6. **URL占位符未替换**：
   - 请求URL中仍包含`{conversation_id}`字面量
   - 404错误且URL中可见未替换的占位符

## 可能原因

| 分类 | 可能原因 | 说明 |
|------|----------|------|
| 服务状态 | Versatile服务未启动/不可达 | Versatile后端服务未运行，或网络不通 |
| 配置错误 | URL模板配置错误 | `VERSATILE_URL`格式错误、路径错误、缺少必要查询参数 |
| 配置错误 | 占位符未替换 | URL中`{conversation_id}`未被正确替换 |
| 网络问题 | 网络不通/防火墙/Docker网络 | 容器间网络隔离、防火墙阻断、DNS解析失败 |
| 超时配置 | 超时设置过短 | 默认600s一般够用，但某些超长工作流可能需要更长 |
| 工作流配置 | resultNodeName不匹配 | `VERSATILE_RESULT_NODE`配置与实际Versatile返回节点名不一致（默认`GXZQAResponseNode`） |
| Header问题 | Header缺失或被白名单过滤 | 白名单外的Header被过滤，默认Header未正确设置 |
| 响应格式 | Versatile返回格式异常 | 非SSE格式、节点结构异常、字段缺失 |
| 路由配置 | intent路由配置错误 | endpoints配置不正确，无法正确路由到对应工作流 |
| 资源限制 | 连接池耗尽 | HttpClient连接池满，无法创建新连接 |

## 排查步骤

### 步骤1：确认代理服务正常运行

首先确认adapter-versatile-agent-java本身运行正常：

```bash
# 健康检查
curl -sf http://localhost:8191/.well-known/agent-card.json
# 应返回Agent Card JSON

# 检查actuator健康状态
curl -s http://localhost:8191/actuator/health
# 应返回 {"status":"UP"}

# Docker容器检查
docker ps | grep adapter-versatile
# 容器应处于Up状态，健康检查为healthy（启动后90秒内）
```

如果健康检查失败，先参考[startup-failure.md](./startup-failure.md)排查服务启动问题。

### 步骤2：直接curl测试Versatile URL

绕过代理，直接测试Versatile后端服务连通性。**注意替换`{conversation_id}`为实际测试值**：

```bash
# 替换VERSATILE_URL中的{conversation_id}，例如：
curl -X POST "http://your-versatile-host:30001/v1/0/agent-manager/workflows/mock_workflow/conversations/test-conv-001?type=controller&workspace_id=10" \
  -H "Content-Type: application/json" \
  -H "accept: */*" \
  -H "stream: true" \
  -H "x-invoke-mode: DEBUG" \
  -H "x-language: zh-cn" \
  -d '{"inputs": {"query": "测试查询"}, "stream": true}' \
  -v \
  --no-buffer
```

**判断结果：**
- 返回200 + SSE流（`data: {...}`格式）：Versatile服务正常，继续排查代理配置
- `Connection refused`：Versatile服务未启动或网络不通
- `404 Not Found`：URL路径错误、workflow_id不存在、占位符未替换
- `401/403`：需要认证（需确认Versatile侧是否开启鉴权）
- `415 Unsupported Media Type`：Content-Type不正确
- `500 Internal Server Error`：Versatile服务内部错误
- 超时：网络延迟高或Versatile过载

### 步骤3：检查VERSATILE_URL配置是否正确

查看当前生效的URL模板配置：

```bash
# Docker环境查看环境变量
docker exec adapter-versatile env | grep VERSATILE

# 本地启动查看application.yml
cat src/main/resources/application.yml | grep -A 5 "url-template"
```

**URL模板正确格式（参考默认配置）：**
```
http://host:port/path/workflows/{workflow_id}/conversations/{conversation_id}?type=controller&workspace_id=10
```

**检查要点：**
1. `{conversation_id}`占位符**必须存在**，运行时会自动替换为会话ID
2. 路径和查询参数与Versatile API文档一致
3. 协议（http/https）正确
4. 主机名和端口正确（Docker网络内使用服务名，外部使用IP/域名）
5. workflow_id与实际工作流标识一致

**常见错误：**
- 缺少`{conversation_id}`占位符
- 拼写错误：`{conversion_id}`、`{conversationId}`等
- 缺少必要的查询参数（如`type=controller&workspace_id=10`）
- 路径多了或少了斜杠

### 步骤4：检查Versatile服务日志

如果直接curl也失败，需要查看Versatile服务端日志：

```bash
# 如果Versatile是Docker部署
docker logs versatile --tail 200 -f

# 查看Versatile日志中的错误
docker logs versatile 2>&1 | grep -E "ERROR|Exception|Failed|404|500"
```

重点关注：
- 请求参数校验失败（缺少必填字段）
- 工作流不存在（workflow_id错误）
- 内部服务异常（数据库连接、下游服务调用失败）
- 权限认证失败
- 请求体格式错误

### 步骤5：开启DEBUG日志查看完整请求响应

默认已开启DEBUG日志级别（`com.huawei.ascend.versatile`和`com.openjiuwen.service.adapters.versatile`包）。查看日志中完整的HTTP请求和响应：

```bash
# 查看实时日志
docker logs adapter-versatile -f

# 查看HTTP请求相关日志
docker logs adapter-versatile 2>&1 | grep -E "POST|Versatile|request|response|status"

# 查看SSE节点解析日志
docker logs adapter-versatile 2>&1 | grep -E "node|result|passthrough|interrupt"
```

开启DEBUG日志后，可以看到：
- 实际请求的URL（确认占位符是否正确替换）
- 请求Headers（确认Header是否正确传递）
- 请求Body
- 响应状态码
- SSE流中的每个节点内容
- 节点分流决策（透传/结果/中断）

### 步骤6：检查resultNodeName是否匹配实际返回节点

结果节点识别逻辑：**只有当node_name匹配`VERSATILE_RESULT_NODE`（默认`GXZQAResponseNode`）时，才会识别为结果节点回灌LLM**。如果不匹配，所有节点都会透传或导致中断。

**排查方法：**

1. **开启DEBUG日志**查看实际返回的node_name：
   ```
   # 在日志中搜索类似内容
   Received SSE node: {"node_type":"QA","node_name":"GXZQAResponseNode",...}
   ```

2. **直接curl Versatile**查看返回的SSE节点结构：
   ```bash
   # 查看SSE流中的data事件，注意node_name字段
   curl -N "http://your-versatile-url" ... 2>&1 | grep "data:"
   ```

3. **对比配置**：
   ```bash
   # 查看当前配置的resultNodeName
   docker exec adapter-versatile env | grep RESULT_NODE
   # 或查看application.yml
   grep "result-node-name" src/main/resources/application.yml
   ```

**常见不匹配情况：**
- Versatile升级后节点名变了
- 不同工作流使用不同的结果节点名
- 配置了错误的节点名（拼写错误、大小写问题）
- 节点名包含额外前缀/后缀

### 步骤7：测试Header传递

代理只透传白名单内的Header。当前白名单配置（`application.yml`）：
- `stream`
- `x-invoke-mode`
- `x-language`

默认Headers：
```
Content-Type: application/json
accept: */*
stream: true
x-invoke-mode: DEBUG
x-language: zh-cn
```

**验证Header传递：**

1. **查看DEBUG日志**中的请求Headers部分
2. **在Versatile侧**打印接收到的Headers
3. **测试自定义Header透传**：
   ```bash
   # 发送A2A请求时在metadata中携带Header
   curl -X POST "http://localhost:8191/a2a" \
     -H "Content-Type: application/json" \
     -H "Accept: text/event-stream" \
     -H "x-invoke-mode: PRODUCTION" \
     -H "x-language: en-us" \
     -d '{...}'
   ```

**注意**：不在白名单内的Header会被过滤掉，不会传递给Versatile。如果需要透传额外Header，需要修改`forward-header-whitelist`配置。

### 步骤8：检查intent路由配置（多工作流场景）

如果配置了多意图endpoints路由（不同intent路由到不同工作流），检查endpoints配置是否正确：

1. 确认`input-metadata-keys`包含`intent`（默认已包含）
2. 确认A2A请求metadata中携带了正确的intent字段
3. 确认endpoints路由映射配置正确
4. 查看DEBUG日志确认路由到了正确的endpoint

查看日志中的路由信息：
```
docker logs adapter-versatile 2>&1 | grep -E "intent|route|endpoint"
```

### 步骤9：检查超时设置

默认超时是600秒（`VERSATILE_TIMEOUT=600s`），对于大多数工作流已足够。如果工作流特别长（如涉及多个人工节点、复杂查询），可能需要更长超时。

**检查超时配置：**
```bash
docker exec adapter-versatile env | grep TIMEOUT
# 默认应为 VERSATILE_TIMEOUT=600s
```

**超时错误特征：**
- 日志中出现`SocketTimeoutException`、`ReadTimeout`、`connect timed out`
- 请求恰好到600秒左右断开
- SSE流中途停止，无错误码返回

**增大超时：**
```bash
# 设置为20分钟（1200秒）
VERSATILE_TIMEOUT=1200s
```

超时支持的单位格式：
- `ms`：毫秒
- `s`：秒（默认）
- `m`：分钟

## 解决方案

### 方案1：启动/修复Versatile服务

**生产环境：**
1. 确认Versatile服务已启动并正常运行
2. 检查Versatile服务健康检查端点
3. 确认服务注册中心（如Nacos/Eureka）中Versatile实例状态正常
4. 检查Versatile依赖的下游服务是否正常

**开发测试环境：**
使用Versatile Mock服务器进行测试（参考EDPAgent的mock目录）：
```bash
cd ../../../../../edp-agent-java/mock
pip install fastapi uvicorn python-dotenv loguru
python versatile_main.py
# 默认监听 http://localhost:30001
```

测试Mock服务是否正常：
```bash
curl http://localhost:30001/health
# 返回OK表示正常
```

### 方案2：修正VERSATILE_URL配置

**本地开发连Mock服务：**
```bash
VERSATILE_URL=http://localhost:30001/v1/0/agent-manager/workflows/mock_workflow/conversations/{conversation_id}?type=controller&workspace_id=10
```

**生产环境（Docker同网络）：**
```bash
# 使用Docker服务名
VERSATILE_URL=http://versatile:30001/v1/0/agent-manager/workflows/prod_workflow/conversations/{conversation_id}?type=controller&workspace_id=10
```

**生产环境（外部服务）：**
```bash
VERSATILE_URL=https://versatile.internal.example.com/v1/0/agent-manager/workflows/prod_workflow/conversations/{conversation_id}?type=controller&workspace_id=10
```

**Docker Compose配置示例：**
```yaml
services:
  versatile:
    image: versatile:latest
    ports:
      - "30001:30001"
  
  adapter-versatile:
    image: adapter-versatile-agent-java:latest
    ports:
      - "8191:8191"
    environment:
      - VERSATILE_URL=http://versatile:30001/v1/0/agent-manager/workflows/mock_workflow/conversations/{conversation_id}?type=controller&workspace_id=10
    depends_on:
      - versatile
```

### 方案3：修正resultNodeName配置

确认Versatile实际返回的结果节点名，然后配置正确的值：

```bash
# 通过环境变量覆盖
VERSATILE_RESULT_NODE=YourActualResultNodeName
```

或修改`application.yml`：
```yaml
openjiuwen:
  service:
    versatile:
      result-node-name: YourActualResultNodeName
```

**Mock服务的结果节点名：**
- Mock服务（versatile_main.py）的结果帧为`node_type=QA, node_name=GXZQAResponseNode`
- 开发环境使用Mock时保持默认值即可

### 方案4：调整超时时间

对于长耗时工作流，适当增大超时：

```bash
# 10分钟
VERSATILE_TIMEOUT=600s

# 20分钟（超长工作流）
VERSATILE_TIMEOUT=1200s
```

注意：超时设置过短会导致SSE流中断，设置过长可能导致请求堆积。建议根据实际工作流平均耗时设置为合理值（如平均耗时的2-3倍）。

### 方案5：配置Header白名单

如果需要透传额外的Header给Versatile，修改`forward-header-whitelist`配置：

```yaml
openjiuwen:
  service:
    versatile:
      forward-header-whitelist:
        - stream
        - x-invoke-mode
        - x-language
        - your-custom-header
        - authorization
```

同时可以在`headers-template`中设置默认Header：
```yaml
openjiuwen:
  service:
    versatile:
      headers-template:
        Content-Type: application/json
        accept: "*/*"
        stream: "true"
        x-invoke-mode: DEBUG
        x-language: zh-cn
        authorization: "Bearer your-token"
```

### 方案6：修复网络/Docker网络问题

**Docker网络检查：**
```bash
# 查看网络列表
docker network ls

# 检查两个容器是否在同一网络
docker network inspect your-network-name
# 确认Containers部分包含versatile和adapter-versatile

# 如果不在同一网络，连接到同一网络
docker network connect your-network-name adapter-versatile
```

**端口连通性测试：**
```bash
# 在adapter容器内测试Versatile连通性
docker exec adapter-versatile curl -v http://versatile:30001/health

# 或使用telnet/nc
docker exec adapter-versatile apt-get update && apt-get install -y telnet
docker exec adapter-versatile telnet versatile 30001
```

**防火墙/安全组：**
- 确认主机防火墙开放30001端口（Versatile）和8191端口（adapter）
- 确认云平台安全组规则允许流量
- 跨机房访问确认专线/VPN连通性

### 方案7：处理HTTP错误码

| HTTP码 | 原因 | 解决方案 |
|--------|------|----------|
| 400 Bad Request | 请求参数错误 | 检查请求体格式、必填字段是否完整 |
| 401 Unauthorized | 需要认证 | 在headers-template中添加认证Header |
| 403 Forbidden | 权限不足 | 联系Versatile管理员配置访问权限 |
| 404 Not Found | URL路径错误、workflow不存在 | 检查VERSATILE_URL路径、workflow_id是否正确 |
| 415 Unsupported Media Type | Content-Type错误 | 确认Content-Type为application/json |
| 429 Too Many Requests | 限流 | 降低请求频率，或联系Versatile扩容 |
| 500 Internal Server Error | Versatile内部错误 | 查看Versatile日志，联系Versatile开发排查 |
| 502 Bad Gateway | 网关错误 | 检查反向代理/网关配置 |
| 503 Service Unavailable | Versatile过载/维护 | 稍后重试，或联系Versatile运维 |
| 504 Gateway Timeout | 网关超时 | 检查Versatile响应时间，增大网关超时 |

### 方案8：修复SSE格式问题

如果Versatile返回的SSE格式不符合预期，需要与Versatile团队确认SSE格式规范。标准格式应为：
```
data: {"node_type":"...","node_name":"...","text":"...","...":"..."}

data: {"node_type":"QA","node_name":"GXZQAResponseNode","text":"最终答案",...}

data: [DONE]
```

**常见SSE格式问题：**
- 缺少`data: `前缀
- 每个事件后缺少空行分隔
- 返回普通JSON而非SSE流（检查stream=true是否传递）
- JSON格式不合法（缺少引号、逗号错误等）
- 缺少结果节点或End节点

## 相关配置/日志关键词

### 配置项
| 环境变量 | 配置路径 | 默认值 | 说明 |
|----------|----------|--------|------|
| `VERSATILE_URL` | `openjiuwen.service.versatile.url-template` | `http://localhost:30001/v1/0/agent-manager/workflows/mock_workflow/conversations/{conversation_id}?type=controller&workspace_id=10` | Versatile REST URL模板，必须含`{conversation_id}` |
| `VERSATILE_TIMEOUT` | `openjiuwen.service.versatile.timeout` | `600s` | 调用超时，支持ms/s/m |
| `VERSATILE_RESULT_NODE` | `openjiuwen.service.versatile.result-node-name` | `GXZQAResponseNode` | 结果节点名称，匹配后回灌LLM |
| - | `openjiuwen.service.versatile.forward-header-whitelist` | `stream, x-invoke-mode, x-language` | 允许透传的Header白名单 |
| - | `openjiuwen.service.versatile.headers-template` | 见application.yml | 默认请求头 |
| - | `openjiuwen.service.versatile.passthrough-headers` | `x-invoke-mode, x-language` | passthrough headers |
| - | `openjiuwen.service.versatile.input-metadata-keys` | `intent, wap_userName` | 从A2A metadata提取到请求体的字段 |

### 日志关键词
- `VersatileAgentHandler` - Versatile代理处理器核心日志
- `POST` - 实际HTTP请求URL
- `response status=` - 响应状态码
- `SSE node received` / `Received node` - 收到SSE节点
- `result node matched` - 结果节点匹配成功
- `passthrough to USER` - 节点透传给前端
- `interrupt triggered` - 触发中断
- `SocketTimeoutException` / `ReadTimeout` - 超时错误
- `Connection refused` - 连接被拒
- `ConnectException` - 连接异常

### 代码位置
- 应用入口：[VersatileAgentApplication.java](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/java/com/huawei/ascend/versatile/VersatileAgentApplication.java)
- 配置类：[VersatileAgentConfiguration.java](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/java/com/huawei/ascend/versatile/VersatileAgentConfiguration.java)
- 配置文件：[application.yml](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/src/main/resources/application.yml)
- Dockerfile：[Dockerfile](file:///D:/AgentTool/EDPAgent_java/agent-solution/common/agent/adapter-versatile-agent-java/deploy/Dockerfile)
- 核心Handler：`com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler`（agent-runtime依赖包中）

## 预防措施

1. **配置健康检查**：监控Versatile服务和adapter服务健康状态
2. **超时合理设置**：根据工作流实际耗时设置合理超时，默认600s
3. **日志级别生产环境调为INFO**：避免DEBUG日志过多影响性能，排错时临时开启DEBUG
4. **Docker Compose编排**：使用depends_on确保Versatile先启动
5. **网络策略**：确保adapter与Versatile在同一网络或网络可达
6. **契约测试**：维护adapter与Versatile之间的接口契约测试，版本升级前验证
7. **监控告警**：
   - 监控HTTP 5xx错误率
   - 监控请求超时率
   - 监控SSE流异常中断率
8. **多环境配置**：开发/测试/生产使用不同的Versatile地址和workflow_id

## 参考链接

- [服务启动失败排错](./startup-failure.md)
- [常见问题FAQ](../faq.md)
- [技术支持渠道](../support.md)
- [EDPAgent Versatile调用异常文档](../../../../edp-agent-java/docs/support/troubleshooting/versatile-error.md)
