# 原油评价智能体 & 工作流 — 使用说明

> 本文档介绍 openjiuwen agent-studio 平台上的一组原油评价智能体与工作流，帮助导入者快速上手。

## 资源总览

| 资源 | 类型 | 面向 | 功能 |
|------|------|------|------|
| **原油评价问答** | 智能体（Agent） | 所有人 | 自然语言查询原油评价数据（列表/侧线收率/性质/渣油/馏分分类/收率对比） |
| **原油评价V2** | 工作流（Workflow） | 被"原油评价问答"智能体挂载调用 | 代码节点直连 PostgreSQL 执行查询，返回结构化结果 |
| **原油评价数据维护** | 工作流（Workflow） | 管理员 | 上传 Excel 导入数据 / 对话式删除原油 / 查看原油列表 |

## 前置条件

导入方需准备以下环境：

### 1. PostgreSQL 数据库

```bash
# 创建数据库和用户
psql -U postgres -c "CREATE USER crude_qa WITH PASSWORD 'crude_qa2026';"
psql -U postgres -c "CREATE DATABASE crude_qa OWNER crude_qa;"
```

需创建以下 6 张表（导入工作流会写入这些表）：

| 表名 | 用途 |
|------|------|
| `crude_oil` | 原油主表（名称、采样日期、采样点、性质 JSONB） |
| `distillation` | 实沸点蒸馏数据（沸点范围、收率、密度等） |
| `sideline_yield` | 侧线收率（9 条侧线：石脑油~渣油） |
| `residue_properties` | 渣油性质（>350℃ 和 >540℃ 两段） |
| `keyfraction_class` | 关键馏分分类（基属：石蜡基/中间基/环烷基） |
| `text_description` | 文字描述（原油评价报告的文字总结） |

所有表以 `(crude_name, sample_date, sample_point)` 三元组为逻辑主键。

建表 SQL 见 [crude_qa_init.sql](crude_qa_init.sql)（如有），或参考 skill `crude-assay-import` 中的 `references/db_schema.md`。

### 2. 模型配置

- 智能体使用 **DeepSeek** 模型（`deepseek-chat`）
- 导入方需在平台「开发配置 > 模型管理」中配置好 DeepSeek 的 API Key 和 endpoint
- 如使用其他模型，需确保支持 Function Calling（OpenAI tool calling 格式）

### 3. agent-runtime 环境

工作流中的代码节点以**本地执行**方式运行，agent-runtime 的 Python 环境需安装：
- `psycopg2`（连接 PostgreSQL）
- `pandas`（解析 Excel）
- `xlrd`（读取 .xls 文件）

## 各资源详细说明

### 1. 原油评价问答（智能体）

**定位**：面向所有人的自然语言问答助手，用 ReAct 模式自动路由到查询工作流。

**模型**：deepseek-chat

**挂载的工作流**：原油评价V2（279f106d）

**支持的查询类型**：

| 用户可以问 | 示例 |
|-----------|------|
| 查原油列表 | "有哪些原油？"、"目前库里有什么原油？" |
| 查侧线收率 | "渤中25-1的侧线收率是多少？"、"锦州251的石脑油收率" |
| 查原油性质 | "渤中25-1的密度是多少？"、"锦州251是低硫还是高硫？" |
| 查渣油性质 | "渤中25-1的渣油残炭是多少？" |
| 查馏分分类 | "渤中25-1是什么基属？" |
| 收率对比 | "渤中25-1和锦州251的收率对比" |

**工作原理**：
1. 用户提问 → ReAct 引擎调用 LLM 理解意图
2. LLM 自动选择调用「原油评价V2」工作流
3. 工作流内代码节点直连 PostgreSQL 查询数据
4. 查询结果返回给 LLM → LLM 组织自然语言回复

### 2. 原油评价V2（工作流）

**定位**：查询工作流，被"原油评价问答"智能体挂载调用。不单独使用。

**架构决策**：
- 查询逻辑用**代码节点**（`jiuwen.code`，本地执行）直连 PostgreSQL
- 不依赖独立后端服务，不依赖向量库
- 文字描述走 PG `text_description` 表精确查询

**代码节点列表**：

| 节点 | 功能 |
|------|------|
| node_list_crudes | 查询原油列表 |
| node_sideline_yield | 查询侧线收率 |
| node_crude_property | 查询原油性质 + 硫/酸分类 + 文字描述 |
| node_residue | 查询渣油性质 + 加工建议 |
| node_fraction_class | 查询关键馏分分类 |
| node_compare | 多原油收率对比（表格输出） |

### 3. 原油评价数据维护（工作流）

**定位**：面向管理员的数据维护工作流，支持对话式操作。

**为什么用工作流而不是智能体**：openjiuwen 平台的 Agent 对话不支持文件上传（附件按钮会把文件转成 URL 文字塞进 query），文件上传只能走工作流 Start 节点的 `file` 入参。

**工作流拓扑**：

```
Start(query + 可选 excel_file 上传框)
  → IntentDetection 意图识别
     ├ 导入 → 解析预览 → 确认/修改 → 写入数据库 → End
     ├ 删除 → 确认 → 执行删除 → End
     └ 看列表 → 输出原油列表 → End
```

**三种操作的使用方式**：

#### 导入数据
1. 在「对话设置」面板上传 Excel 文件（.xls 格式）
2. 发送消息："导入"
3. 系统解析 Excel 并显示预览（原油名、采样日期、各项数据条数）
4. 回复"确认入库"，或修改值如"采样日期=20251011"
5. 数据写入 PostgreSQL 6 张表

#### 删除数据
1. 发送消息："删除渤中25-1"
2. 系统询问确认
3. 回复"确认"执行删除

#### 查看列表
1. 发送消息："有哪些原油"
2. 系统返回原油列表

## 导入步骤

### 方式一：通过平台 UI 导入

1. 登录 openjiuwen agent-studio 平台
2. 进入「开发中心 > 组件库」
3. 分别导入：
   - 工作流「原油评价V2」— 通过工作流导入功能
   - 工作流「原油评价数据维护」— 通过工作流导入功能
   - 智能体「原油评价问答」— 通过智能体导入功能
4. 导入后在智能体配置中确认工作流挂载关系

### 方式二：通过 API 导入

```bash
PLATFORM=http://127.0.0.1:31111
WORKSPACE=<your_workspace_id>
COOKIE="AGENT_SID=<your_user>|0"

# 导入工作流
curl -X POST "$PLATFORM/v1/0/agent-manager/workflows/import?workspace_id=$WORKSPACE" \
  -H "Cookie: $COOKIE" \
  -F "file=@原油评价V2.zip"

# 导入智能体
curl -X POST "$PLATFORM/v1/0/agent-manager/agents/import?workspace_id=$WORKSPACE" \
  -H "Cookie: $COOKIE" \
  -F "file=@原油评价问答.zip"
```

## 导入后配置

1. **配置模型**：在智能体配置页面确认模型为 deepseek-chat（或替换为其他支持 Function Calling 的模型）
2. **配置数据库连接**：确认工作流代码节点中的 PostgreSQL 连接字符串正确：
   ```
   postgresql://crude_qa:crude_qa2026@<pg_host>:5432/crude_qa
   ```
3. **发布工作流**：Agent 挂载工作流前，工作流必须先发布（在画布编辑器中点「提交版本」）
4. **清理残留插件**：如智能体上有不需要的插件（如"文件解析"插件），请删除

## 数据导入示例

如果数据库为空，可使用 `crude-assay-import` skill 快速导入示例数据：

```
# 在 agent-runtime 环境中执行
export DATABASE_URL="postgresql://crude_qa:crude_qa2026@localhost:5432/crude_qa"
python crude_assay_import.py 示例原油评价报告.xls --import-pg
```

或通过平台「原油评价数据维护」工作流上传 Excel 导入。

## 数据库表结构概要

```
crude_oil          (id, crude_name, sample_date, sample_point, properties JSONB, ...)
distillation      (id, crude_name, sample_date, sample_point, boiling_range, yield_per_mass, ...)
sideline_yield    (id, crude_name, sample_date, sample_point, sideline_name, cut_temp_start, ...)
residue_properties(id, crude_name, sample_date, sample_point, temperature_range, properties JSONB, ...)
keyfraction_class (id, crude_name, sample_date, sample_point, fractions JSONB, classification)
text_description  (id, crude_name, sample_date, sample_point, section_num, section_title, content)
```

批次键：`(crude_name, sample_date, sample_point)` — 同一批次重复导入会覆盖（幂等）。

## 侧线收率定义

工作流中的侧线收率按以下温度区间从实沸点蒸馏数据累加计算：

| 侧线 | 温度区间 |
|------|---------|
| 石脑油 | 初馏点~180℃ |
| 常一线 | 180℃~220℃ |
| 常二线 | 220℃~300℃ |
| 常三线 | 300℃~370℃ |
| 减一线 | 370℃~395℃ |
| 减二线 | 395℃~450℃ |
| 减三线 | 450℃~500℃ |
| 减四线 | 500℃~540℃ |
| 渣油 | >540℃ |

## 注意事项

1. **Excel 格式**：维护工作流支持 `.xls` 格式（基于 xlrd）。`.xlsx` 需另存为 `.xls` 后导入
2. **封面解析**：原油名和采样日期从 Excel 封面 sheet 提取。如封面格式不规范，解析可能失败，可通过对话修改值兜底
3. **权限隔离**：嵌入客户系统后，用客户自己的账号认证控制谁能访问问答（所有人）和维护工作流（管理员）
4. **代码节点执行方式**：必须选「本地执行」而非沙箱，否则无法连接 PostgreSQL
5. **模型要求**：智能体使用 ReAct 模式，模型必须支持 Function Calling。当前使用 deepseek-chat

## 相关资源

| 资源 | 说明 |
|------|------|
| `crude-assay-import` skill | 原油评价报告解析脚本，可独立用于数据导入和格式校验 |
| PostgreSQL `crude_qa` 数据库 | 14 张表（6 张实写 + 8 张预留），存储全部原油评价数据 |
