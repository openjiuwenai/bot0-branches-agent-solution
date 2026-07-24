# 模型API调用失败排错指南

## 问题描述

EDPAgent-Java在调用大语言模型（LLM）API时失败，导致Agent无法进行推理、规划或生成回复。EDPAgent默认使用DeepSeek模型，也支持OpenAI兼容接口（如GPT、vLLM、Ollama等私有部署）。

**重要提示**：API Key无效不会导致服务启动失败，但会导致所有用户请求失败。

## 常见症状

1. **Agent无响应**：发送消息后长时间无回复，最终超时
2. **回复错误信息**：Agent返回类似"抱歉，服务暂时不可用"、"模型调用失败"等错误话术
3. **日志报错**：
   - HTTP 401 Unauthorized（认证失败）
   - HTTP 403 Forbidden（禁止访问）
   - HTTP 429 Too Many Requests（限流）
   - HTTP 500/502/503（服务端错误）
   - `timeout`/`SocketTimeoutException`（网络超时）
   - `Model API error` 相关错误日志
4. **SSE流中断**：回复到一半突然停止

## 可能原因

| 分类 | 可能原因 | 对应HTTP码 |
|------|----------|------------|
| 认证问题 | API Key未设置 | 401 |
| 认证问题 | API Key无效/过期 | 401 |
| 认证问题 | API Key格式错误（缺少前缀等） | 401/403 |
| 账户问题 | 账户余额不足 | 401/402/403 |
| 限流问题 | 请求频率超限（QPM/TPM限制） | 429 |
| 限流问题 | 并发请求数过多 | 429 |
| 配置问题 | 模型名称错误（MODEL_NAME） | 404/400 |
| 配置问题 | Base URL错误 | Connection refused/404 |
| 配置问题 | API版本不兼容 | 400 |
| 网络问题 | 网络超时/延迟过高 | timeout |
| 网络问题 | DNS解析失败 | UnknownHostException |
| 网络问题 | 代理配置错误 | 连接失败 |
| 服务端问题 | 模型服务过载/宕机 | 500/502/503 |
| 服务端问题 | 模型维护中 | 503 |

## 排查步骤

### 步骤1：检查环境变量是否正确设置

首先确认必填的环境变量是否已设置：

| 环境变量 | 是否必填 | 默认值 | 检查要点 |
|----------|----------|--------|----------|
| `EDP_AGENT_MODEL_API_KEY` | **是** | `sk-067b754046374d83888c52657ab6bbc7`（仅开发用） | 必须设置为有效的API Key，不要使用默认值 |
| `EDP_AGENT_MODEL_PROVIDER` | 否 | `OpenAI` | 当前仅支持OpenAI兼容接口，填`OpenAI` |
| `EDP_AGENT_MODEL_NAME` | 否 | `deepseek-v4-pro` | 确认模型名称正确（如`deepseek-chat`、`gpt-4o`等） |
| `EDP_AGENT_MODEL_BASE_URL` | 否 | `https://api.deepseek.com/v1` | 确认Base URL末尾包含`/v1` |

验证配置：
```bash
# 查看容器环境变量
docker exec edp-agent env | grep EDP_AGENT_MODEL
```

### 步骤2：使用curl手动测试API Key有效性

绕过EDPAgent，直接用curl调用模型API测试Key是否有效：

**测试DeepSeek API：**
```bash
curl -X POST https://api.deepseek.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d '{
    "model": "deepseek-chat",
    "messages": [{"role": "user", "content": "Hello!"}],
    "max_tokens": 10
  }'
```

**测试OpenAI API：**
```bash
curl -X POST https://api.openai.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d '{
    "model": "gpt-3.5-turbo",
    "messages": [{"role": "user", "content": "Hello!"}],
    "max_tokens": 10
  }'
```

**判断结果：**
- 返回200 + JSON结果：Key有效，继续排查其他问题
- 返回401：Key无效/过期/未设置
- 返回403：Key无权限或被禁用
- 返回429：触发限流
- 返回404：模型名或Base URL错误
- 超时/连接失败：网络问题

### 步骤3：检查账户余额和配额

**DeepSeek：**
1. 登录 https://platform.deepseek.com/
2. 查看"费用中心" → "余额"
3. 查看"API Keys"确认Key状态正常
4. 查看"用量统计"确认是否超量

**OpenAI：**
1. 登录 https://platform.openai.com/
2. 查看"Settings" → "Billing"确认余额
3. 查看"Usage"确认用量限制

**私有部署：**
- 联系模型服务管理员确认账户状态和配额

### 步骤4：查看日志中的具体HTTP状态码

开启DEBUG日志获取详细错误信息：
```bash
# 设置日志级别为DEBUG
EDP_AGENT_LOG_LEVEL=DEBUG

# 查看错误日志
docker logs edp-agent 2>&1 | grep -i "model\|api\|error\|401\|429\|500\|timeout"
```

根据状态码定位问题：
- **401**：认证失败 → 回到步骤2检查Key
- **403**：禁止访问 → 检查Key权限、IP白名单、账户状态
- **404**：Not Found → 检查MODEL_NAME和BASE_URL
- **429**：限流 → 降低请求频率或升级配额
- **500/502/503**：服务端错误 → 稍后重试，查看模型服务商状态页
- **timeout**：超时 → 检查网络或增大超时时间

### 步骤5：验证BASE_URL和MODEL_NAME

常见的BASE_URL配置错误：
- 缺少`/v1`后缀：`https://api.deepseek.com` ❌ → 应为`https://api.deepseek.com/v1` ✅
- 多了尾部斜杠：`https://api.deepseek.com/v1/` ❌ → 最好去掉`/`
- 错误的域名：注意拼写（如`deepseek`不是`deepseak`）

常见的MODEL_NAME错误：
- DeepSeek：`deepseek-chat`（对话）、`deepseek-coder`（代码）、`deepseek-v4-pro`
- OpenAI：`gpt-4o`、`gpt-4o-mini`、`gpt-3.5-turbo`
- 私有部署：使用模型服务实际部署的模型名

**测试模型列表API验证：**
```bash
curl https://api.deepseek.com/v1/models \
  -H "Authorization: Bearer YOUR_API_KEY"
```

### 步骤6：检查网络连通性和DNS

```bash
# 测试DNS解析
nslookup api.deepseek.com

# 测试端口连通性
telnet api.deepseek.com 443
# 或
nc -zv api.deepseek.com 443

# 在容器内测试（Docker部署时必须）
docker exec edp-agent curl -v https://api.deepseek.com/v1/models \
  -H "Authorization: Bearer YOUR_API_KEY"

# 检查代理设置
docker exec edp-agent env | grep -i proxy
```

如果容器内无法访问外网：
- 检查Docker网络配置（是否使用了--network=none）
- 检查是否需要配置HTTP_PROXY/HTTPS_PROXY环境变量
- 检查宿主机防火墙和公司网络策略

### 步骤7：检查是否配置了错误的重试策略

当前EDPAgent通过OpenJiuwen SDK调用模型，检查是否需要配置重试：
- 如果频繁出现5xx错误，可以增加重试次数
- 如果频繁出现429，需要实现退避重试（backoff）

### 步骤8：测试私有部署模型（如适用）

如果使用私有部署模型（vLLM/Ollama/本地模型）：
```bash
# vLLM示例
curl http://your-vllm-server:8000/v1/models

# Ollama示例
curl http://localhost:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama2",
    "messages": [{"role": "user", "content": "Hello"}]
  }'
```

确保：
1. 私有模型服务已启动
2. 防火墙开放对应端口
3. BASE_URL正确指向模型服务
4. 模型名称与实际部署一致

## 解决方案

### 方案1：设置正确的API Key

**本地开发（.env文件）：**
```bash
EDP_AGENT_MODEL_API_KEY=sk-your-valid-api-key-here
EDP_AGENT_MODEL_PROVIDER=OpenAI
EDP_AGENT_MODEL_NAME=deepseek-chat
EDP_AGENT_MODEL_BASE_URL=https://api.deepseek.com/v1
```

**Docker运行：**
```bash
docker run -d --name edp-agent \
  -e EDP_AGENT_MODEL_API_KEY=sk-your-valid-api-key \
  -p 8190:8190 \
  edp-agent:latest
```

**Docker Compose：**
```yaml
services:
  edp-agent:
    image: edp-agent:latest
    environment:
      - EDP_AGENT_MODEL_API_KEY=${EDP_AGENT_MODEL_API_KEY}
      - EDP_AGENT_MODEL_NAME=deepseek-chat
      - EDP_AGENT_MODEL_BASE_URL=https://api.deepseek.com/v1
    ports:
      - "8190:8190"
```

### 方案2：充值或更换API Key

如果余额不足：
1. 登录模型服务商平台充值
2. 或创建新的API Key替换
3. 检查是否有Key被禁用/删除

### 方案3：修正模型名称和Base URL

**常用配置参考：**

| 服务商 | MODEL_NAME | BASE_URL |
|--------|------------|----------|
| DeepSeek | `deepseek-chat` | `https://api.deepseek.com/v1` |
| DeepSeek（旧模型） | `deepseek-v4-pro` | `https://api.deepseek.com/v1` |
| OpenAI GPT-4o | `gpt-4o` | `https://api.openai.com/v1` |
| OpenAI GPT-3.5 | `gpt-3.5-turbo` | `https://api.openai.com/v1` |
| Azure OpenAI | 部署名 | `https://{resource}.openai.azure.com/openai/deployments/{deployment}` |
| vLLM本地 | 模型名 | `http://your-vllm:8000/v1` |
| Ollama | `llama2`/`qwen`等 | `http://localhost:11434/v1` |

### 方案4：处理限流（429错误）

1. **降低并发**：减少同时发送的请求数
2. **实现请求排队**：在上层增加请求队列
3. **升级配额**：联系服务商提升QPM/TPM限制
4. **配置重试退避**：遇到429时等待一段时间后重试
5. **多Key轮询**：使用多个API Key轮询（需要二次开发）

### 方案5：网络问题处理

**配置代理（如需要）：**
```bash
docker run -d --name edp-agent \
  -e HTTP_PROXY=http://your-proxy:port \
  -e HTTPS_PROXY=http://your-proxy:port \
  -e NO_PROXY=localhost,redis,versatile \
  -e EDP_AGENT_MODEL_API_KEY=sk-your-key \
  -p 8190:8190 \
  edp-agent:latest
```

**增大超时（网络较差时）：**
当前超时配置由OpenJiuwen SDK管理，如遇超时：
1. 检查网络带宽和延迟
2. 联系网络管理员确认外网访问策略
3. 考虑使用国内中转或私有部署模型

### 方案6：切换到备用模型

如果主模型服务不稳定，可以切换到其他模型：
```bash
# 切换到OpenAI GPT-4o
EDP_AGENT_MODEL_NAME=gpt-4o
EDP_AGENT_MODEL_BASE_URL=https://api.openai.com/v1
EDP_AGENT_MODEL_API_KEY=sk-openai-key
```

或切换到私有部署模型以获得更好的稳定性。

### 方案7：配置重试和熔断

对于生产环境，建议：
1. 在EDPAgent上层增加API网关，实现重试、熔断、限流
2. 配置健康检查，模型失败时快速降级
3. 准备备用模型，主模型失败时自动切换

## 常见错误码详解

| HTTP状态码 | 含义 | 原因 | 解决方案 |
|-----------|------|------|----------|
| **400 Bad Request** | 请求格式错误 | 请求体格式错、参数非法、模型不支持该功能 | 检查请求格式、模型参数（temperature/max_tokens等） |
| **401 Unauthorized** | 认证失败 | Key无效/过期/未设置、Key格式错 | 重新生成API Key、检查Bearer前缀 |
| **403 Forbidden** | 禁止访问 | Key无权限、IP白名单、账户被封禁、区域不支持 | 检查Key权限、添加IP白名单、联系客服 |
| **404 Not Found** | 资源不存在 | 模型名错误、Base URL错误、端点不存在 | 检查MODEL_NAME和BASE_URL、调用/models接口确认 |
| **429 Too Many Requests** | 限流 | QPM/TPM超限、并发数过多、免费额度用完 | 降低频率、升级配额、等待重试 |
| **500 Internal Server Error** | 服务端错误 | 模型服务内部错误 | 稍后重试、查看服务商状态页 |
| **502 Bad Gateway** | 网关错误 | 服务过载、部署更新中 | 稍后重试、使用备用模型 |
| **503 Service Unavailable** | 服务不可用 | 维护中、过载 | 查看服务商公告、稍后重试 |
| **timeout/SocketTimeout** | 超时 | 网络延迟高、服务响应慢、丢包 | 检查网络、增大超时、换模型 |
| **Connection refused** | 连接被拒 | Base URL错、服务未启动、防火墙拦 | 检查URL、确认服务运行、检查网络 |
| **UnknownHost** | DNS失败 | 域名错误、DNS无法解析 | 检查域名拼写、检查DNS配置 |

## 相关配置/日志关键词

### 配置项
- `edpa.agent.model.provider` - 模型提供者（固定为OpenAI）
- `edpa.agent.model.name` - 模型名称
- `edpa.agent.model.base-url` - API Base URL
- `edpa.agent.model.api-key` - API Key

### 日志关键词
- `Model API` - 模型API相关日志
- `401 Unauthorized` - 认证失败
- `429 Too Many Requests` - 限流
- `timeout` - 超时
- `SocketTimeoutException` - Socket超时
- `UnknownHostException` - DNS解析失败
- `Connection refused` - 连接失败
- `chat/completions` - 聊天补全接口

### 代码位置
- 模型配置：`engine/src/main/resources/application.yml:31-35`
- 环境变量参考：`docs/reference/环境变量参考.md:94-125`

## 预防措施

1. **多Key备份**：准备多个API Key，避免单个Key失效导致服务不可用
2. **密钥轮换**：定期轮换API Key，降低泄露风险
3. **监控告警**：
   - 监控模型API错误率（4xx/5xx比例）
   - 监控平均响应时间
   - 余额不足告警
   - 限流次数告警
4. **降级预案**：
   - 主模型失败时自动切换到备用模型
   - 配置友好的错误话术
   - 必要时支持人工接管
5. **本地/私有模型**：核心业务场景考虑使用私有部署模型，避免公网依赖
6. **定期验证**：定期用curl脚本测试API Key有效性
7. **用量监控**：监控Token使用量，避免超支或触发限流

## 参考链接

- [环境变量完整参考](../../reference/环境变量参考.md#大模型配置)
- [DeepSeek API文档](https://platform.deepseek.com/docs)
- [OpenAI API文档](https://platform.openai.com/docs)
- [application.yml配置](file:///D:/AgentTool/EDPAgent_java/branch_edpa_java/agent-store/edp-agent-java/engine/src/main/resources/application.yml)
