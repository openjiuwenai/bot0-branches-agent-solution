# EDPAgent 配置驱动样例（零代码）

> 本样例演示如何**仅通过 YAML 配置文件**创建一个完整的智能客服 Agent，无需编写任何 Java 代码。

## 项目结构

```
edp-agent-config-driven-sample/
├── README.md                                    # 本文件
├── application.yml                              # 基础设施配置（模型/Redis/沙箱/端口）
├── start.bat                                    # Windows 启动脚本
├── start.sh                                     # Linux/Mac 启动脚本
├── test-call.bat                                # 测试调用脚本（5 个测试用例）
├── scenarios/
│   └── smart-customer-service/                  # 智能客服场景
│       ├── governance/
│       │   ├── actrule.yaml                     # 执行约束（任务模板/工具限制）
│       │   ├── planrule.yaml                    # 角色规划（角色/范围/提示词/技能路由）
│       │   └── scriptconfig.yaml                # 话术配置（通用话术/思维链/确认话术）
│       └── skills/
│           └── faq_skill/                      # FAQ 技能
│               ├── SKILL.yaml                   # 技能声明
│               └── SKILL.md                     # 技能文档（LLM 读取的知识库）
└── .todo/                                       # 运行时自动生成（会话待办持久化，无需手动管理）
```

## 快速开始

### 前置条件

1. 已构建 edp-agent-engine JAR
2. Redis >= 6.2 运行在 localhost:6379
3. DeepSeek API Key

### 3 步启动

```bat
REM 1. 设置 API Key
set EDP_AGENT_MODEL_API_KEY=sk-xxx

REM 2. 启动
cd edp-agent-config-driven-sample
start.bat

REM 3. 测试（新开终端）
test-call.bat
```

### Linux/Mac

```bash
export EDP_AGENT_MODEL_API_KEY=sk-xxx
cd edp-agent-config-driven-sample
chmod +x start.sh && ./start.sh
```

## 配置文件详解

### 1. application.yml — 基础设施配置

配置 Spring Boot 基础设施，所有参数支持环境变量覆盖。

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `server.port` | 服务端口 | 8190 |
| `edpa.agent.scenario-home` | 场景目录路径 | ./scenarios/smart-customer-service |
| `edpa.agent.model.provider` | 模型提供商 | OpenAI |
| `edpa.agent.model.name` | 模型名称 | deepseek-v4-pro |
| `edpa.agent.model.base-url` | 模型 API 地址 | https://api.deepseek.com/v1 |
| `edpa.agent.model.api-key` | API Key | 环境变量 EDP_AGENT_MODEL_API_KEY |
| `edpa.agent.redis.mode` | Redis 部署模式 | single |
| `edpa.agent.redis.host` | Redis 主机 | localhost |
| `edpa.agent.redis.port` | Redis 端口 | 6379 |
| `edpa.agent.redis.todo.key-prefix` | Key 前缀（多实例隔离） | edpa |
| `edpa.agent.redis.todo.ttl-seconds` | Todo TTL（秒） | 3600 |
| `edpa.agent.sandbox.enabled` | 沙箱开关 | false |

### 2. governance/planrule.yaml — 角色规划配置

定义 Agent 的角色身份、业务范围和系统提示词。

| 配置块 | 字段 | 说明 |
|--------|------|------|
| **role** | role | 角色名称（如"智能客服助手"） |
| | description | 角色描述 |
| **scope** | allowed | 允许的业务范围列表 |
| | denied | 禁止的业务范围列表 |
| **supplementary_prompt** | additional_prompt | 注入 LLM 系统提示词的业务规则 |
| **skill_routing** | trigger | 触发关键词（正则） |
| | skill | 对应的技能名称 |
| | priority | 优先级（数字越小越优先） |

### 3. governance/actrule.yaml — 执行约束配置

定义 Agent 的执行规则、任务模板和工具限制。

| 配置块 | 字段 | 说明 |
|--------|------|------|
| **todolist_entries** | catalog_id | 任务唯一标识 |
| | content | 任务描述 |
| | depends_on | 依赖的前置任务 ID 列表（DAG） |
| | skill | 关联的技能名称 |
| **todolist_dynamic_paths** | condition | 跳转条件 |
| | target_catalog_id | 目标任务 ID |
| **执行限制** | max_subtasks | 单层最大子任务数 |
| | max_steps | 最大执行步数 |
| | enable_task_loop | 是否启用任务循环 |
| | skill_mode | Skill 加载模式（all/auto_list/none） |
| **allowed_tools** | — | 允许使用的工具白名单 |
| **tool_limits** | — | 各工具调用次数上限 |

### 4. governance/scriptconfig.yaml — 话术配置

定义 Agent 回复用户时的固定话术模板。

| 配置块 | 说明 |
|--------|------|
| **general_scripts** | 通用话术（工具开始/结束、任务开始/结束、规划、取消等） |
| **think_chunk_scripts** | 思维链话术模式（real_stream 真实流式 / fixed_script 固定帧） |
| **ask_user_confirm** | 确认话术模板（身份确认、业务确认、取消确认） |

### 5. skills/faq_skill/ — 技能目录

技能是 Agent 的领域知识包，由 LLM 在运行时按需加载。

| 文件 | 说明 |
|------|------|
| `SKILL.yaml` | 技能声明（名称、描述、版本） |
| `SKILL.md` | 技能文档（LLM 读取的知识库，Markdown 格式） |

## 配置覆盖优先级

```
高优先级 ────────────────────────────── 低优先级
  场景级 governance/*.yaml  →  框架级 governance/*.yaml  →  application.yml
  (scenarios/xxx/governance/)     (JAR 内 governance/)          (外部配置)
```

**覆盖规则**：场景级增量覆盖框架级，字段级合并（非整体替换）。

## 如何创建新场景

只需 3 步，零 Java 代码：

### 步骤 1：复制场景目录

```bash
cp -r scenarios/smart-customer-service scenarios/my-new-scenario
```

### 步骤 2：修改 YAML 配置

修改 `scenarios/my-new-scenario/governance/` 下的 3 个 YAML 文件：

- `planrule.yaml`：修改 role、scope、supplementary_prompt、skill_routing
- `actrule.yaml`：修改 todolist_entries、allowed_tools
- `scriptconfig.yaml`：修改 general_scripts、ask_user_confirm（可选）

### 步骤 3：修改 application.yml 指向新场景

```yaml
edpa:
  agent:
    scenario-home: ./scenarios/my-new-scenario
```

启动即可，**Java 代码零修改**。

## 测试用例

| 测试 | 输入 | 预期 |
|------|------|------|
| 基本问候 | "你好" | 客服角色自我介绍 |
| 产品咨询 | "你们有哪些产品套餐？" | 列出产品A/B/C |
| 业务查询 | "如何查询我的账单？" | 提供 3 种查询方式 |
| 超出范围 | "帮我推荐一只股票" | 告知不在服务范围 |
| 投诉建议 | "我想投诉服务态度问题" | 触发投诉处理流程 |

## 调用方式

### REST API（简单入口）

```bash
curl -X POST http://localhost:8190/v1/query \
  -H "Content-Type: application/json" \
  -d '{"conversation_id":"c1","message":"你好"}'
```

### A2A JSON-RPC（标准入口）

```bash
# 阻塞调用
curl -X POST http://localhost:8190/a2a \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"SendMessage","id":"r1","params":{"message":{"role":"user","parts":[{"text":"你好"}],"contextId":"c1"}}}'

# 流式调用
curl -N -X POST http://localhost:8190/a2a \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"jsonrpc":"2.0","method":"SendStreamingMessage","id":"r2","params":{"message":{"role":"user","parts":[{"text":"你好"}],"contextId":"c2"}}}'
```

### Agent Card 发现

```bash
curl http://localhost:8190/.well-known/agent-card.json
```

## 环境变量

### 模型配置

| 变量名 | 说明 | 示例 |
|--------|------|------|
| EDP_AGENT_MODEL_API_KEY | 模型 API Key | sk-xxx |
| EDP_AGENT_MODEL_NAME | 模型名称 | deepseek-v4-pro |
| EDP_AGENT_MODEL_BASE_URL | 模型 API 地址 | https://api.deepseek.com/v1 |
| EDP_AGENT_MODEL_PROVIDER | 模型提供商 | OpenAI |

### Redis 配置

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| EDPA_REDIS_MODE | Redis 部署模式（single/sentinel/cluster） | single |
| EDPA_REDIS_HOST | Redis 主机 | localhost |
| EDPA_REDIS_PORT | Redis 端口 | 6379 |
| EDPA_REDIS_PASSWORD | Redis 密码 | （空） |
| EDPA_REDIS_DB | Redis 数据库索引 | 0 |
| EDPA_REDIS_CONNECT_TIMEOUT | 连接超时（毫秒） | 5000 |
| EDPA_REDIS_SOCKET_TIMEOUT | 读写超时（毫秒） | 10000 |
| EDPA_REDIS_CHECKPOINTER_TTL | Checkpoint TTL（分钟） | 60 |
| EDPA_REDIS_KEY_PREFIX | Todo Key 前缀（多实例隔离） | edpa |
| EDPA_REDIS_TODO_TTL | Todo TTL（秒） | 3600 |
| EDPA_REDIS_REFRESH_ON_READ | 读取时是否续期 TTL | true |

### Versatile 配置

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| EDP_AGENT_VERSATILE_URL | Versatile 业务系统地址（含占位符） | http://localhost:30001/... |
| EDP_AGENT_VERSATILE_A2A_URL | Versatile A2A 适配器地址 | http://localhost:8191/a2a |
| EDP_AGENT_VERSATILE_TIMEOUT | 调用超时 | 30s |

### MCP SSE 配置

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| EDP_MCP_MASTER_URL | MCP SSE 主服务地址 | http://localhost:8090 |
| EDP_MCP_STANDBY_URL | MCP SSE 备用服务地址 | （空） |
| EDP_MCP_ACCESS_TOKEN | MCP 访问令牌 | （空） |
| EDP_MCP_APP_NAME | MCP 应用名称 | test_app |

### 沙箱配置

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| EDPA_SANDBOX_ENABLED | 沙箱开关 | false |
| EDPA_SANDBOX_SERVICE_URL | 沙箱服务地址 | http://127.0.0.1:8321 |
| EDPA_SANDBOX_EXEC_TIMEOUT | 脚本执行超时（秒） | 60 |
| EDPA_SANDBOX_SKILL_DEPLOY_PATH | 技能部署路径 | /app/skills |

## FAQ

**Q: 不配置 Redis 会怎样？**
A: Redis 是 EDPAgent 的核心依赖，用于 Todo 持久化和会话中断恢复（Checkpoint）。不配置 Redis 时应用无法启动。本地开发可安装 Redis 或使用 Docker：`docker run -d -p 6379:6379 redis`

**Q: 如何切换模型？**
A: 修改 `application.yml` 的 `edpa.agent.model.name`，或设置环境变量 `EDP_AGENT_MODEL_NAME`。

**Q: 如何添加新技能？**
A: 在 `skills/` 目录下创建新文件夹，添加 `SKILL.yaml` 和 `SKILL.md`，然后在 `planrule.yaml` 的 `skill_routing` 中添加路由规则。

**Q: 如何接入外部业务系统？**
A: 配置 `edpa.agent.versatile.url` 指向业务系统 API，URL 支持 `{workflow_id}` 和 `{conversation_id}` 占位符。也可配置 `adapter-a2a-url` 使用 A2A 协议接入。

**Q: 场景级配置和框架级配置什么关系？**
A: 框架级配置在 JAR 内（`governance/*.yaml`），提供通用默认值。场景级配置在外部目录，仅写差异字段，自动与框架级合并。

**Q: 如何启用沙箱执行 MCP 脚本？**
A: 设置 `EDPA_SANDBOX_ENABLED=true` 并配置 `EDPA_SANDBOX_SERVICE_URL`。启用后 MCP 脚本在沙箱中安全执行，未启用时在本地直接执行。
