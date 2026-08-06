# Preset Importer — Agent Studio 预置数据一键导入工具

在部署agent studio时，将行业精品应用（插件 / 智能体模板 / 工作流模板）自动发布到agent studio资产广场中，支持用户直接引用该模板。

---

## 目录

- [功能概览](#功能概览)
- [项目结构](#项目结构)
- [环境准备](#环境准备)
- [配置文件](#配置文件)
- [快速开始](#快速开始)
- [import-presets.py 用法](#import-presetspy-用法)
- [setup-model-auth.py 用法](#setup-model-authpy-用法)
- [models.json 格式](#modelsjson-格式)
- [预置数据结构](#预置数据结构)
- [数据流转](#数据流转)
- [常见问题](#常见问题)

---

## 功能概览

本工具完成两类工作：

| 工作 | 脚本 | 说明 |
|------|------|------|
| **数据导入** | `import-presets.py` | 把 `preset-data/` 里的插件、智能体模板、工作流模板转换为 JSONL，通过 studio-manager API 导入 |
| **市场发布** | `import-presets.py`（`--markets`） | 将导入的数据发布到三个前端页面：应用百宝箱、插件市场、模型广场 |
| **模型鉴权** | `setup-model-auth.py` | 给预置模型绑定 API Key 鉴权、设 online，并把所有智能体的 IR 模型引用改成目标模型 |

### 三个"市场"页面

| 页面 | 前端路由 | 数据来源 | 本工具做了什么 |
|------|----------|----------|----------------|
| 应用百宝箱 | `home/app-library` | `t_app` 表 | 直接写 `t_app` + 复制 IR→DSL 到 OBS + 上传 treasure metadata 到 OBS/Redis |
| 插件市场 | `home/plugin-market` | `t_tool` 表（OFFICIAL 标签） | 更新 `t_tool`: `type=inner`, `visibility=global`, `project_id=OP_SVC_PROJECT_ID` |
| 模型广场 | `home/model-square` | `t_model_service` 表 | 插入 8 个预置模型目录（DeepSeek / Qwen / GLM / Moonshot / BGE） |

---

## 项目结构

```
preset-importer/
├── import-presets.py        # 主导入脚本（数据转换 + API 导入 + 市场发布）
├── setup-model-auth.py      # 模型鉴权配置脚本（绑定 API Key + IR 模型重绑）
├── importer.env             # 配置文件（服务地址 / DB / MinIO / Redis）
├── models.json              # 模型配置（批量创建模型服务用，可选）
├── requirements.txt          # Python 依赖
├── init-venv.ps1             # venv 初始化脚本（Windows PowerShell）
└── preset-data/             # 自包含预置数据源
    ├── plugins/
    │   ├── index.json       # 插件分类索引（16 个分类，~80 个插件）
    │   ├── schema.json      # 插件 JSON schema
    │   ├── ai/              # AI & ML 服务（DeepSeek, Qwen, GLM, Moonshot...）
    │   ├── cloud/           # 云基础设施（AWS, Azure, Cloudflare...）
    │   ├── communication/   # 通讯（Slack, Discord, Twilio, SendGrid...）
    │   ├── crm/             # CRM（HubSpot, Salesforce）
    │   ├── data/            # 数据服务（和风天气, 彩云天气, Tushare...）
    │   ├── database/        # 数据库（MongoDB Atlas, Redis, Supabase）
    │   ├── developer/      # 开发工具（GitHub, GitLab, Docker Hub...）
    │   ├── ecommerce/      # 电商（Shopify, WooCommerce, eBay...）
    │   ├── entertainment/  # 娱乐（Spotify, Twitch, Eventbrite...）
    │   ├── finance/        # 金融（Alpaca, CoinGecko, Finnhub）
    │   ├── file_storage/  # 文件存储（Google Drive, Dropbox...）
    │   ├── maps/           # 地图服务（Google Maps, 高德, 百度地图...）
    │   ├── payments/      # 支付（Stripe, PayPal, Wise...）
    │   ├── productivity/  # 效率工具（Notion, Jira, Trello...）
    │   ├── social/         # 社交（Twitter, YouTube, 微博, 抖音...）
    │   └── testing/        # 测试（httpbin, Petstore...）
    └── examples/
        └── zh/
            ├── agent.finance.template.json         # 智能体模板：财务助手
            ├── agent.travel.template.json          # 智能体模板：出游助手
            ├── workflow.check_balance.template.json    # 工作流模板：查余额
            ├── workflow.check_weather.template.json    # 工作流模板：查天气
            ├── workflow.money_transfer.template.json   # 工作流模板：转账
            └── workflow.plan_route_with_amap.template.json # 工作流模板：路线规划
```

---

## 环境准备

### 前置条件

1. **Python 3.9+**（建议使用 Agent Studio 自带的 agent-runtime venv）
2. **studio-manager 已启动**（默认端口 31111）—— 数据导入通过其 REST API 完成
3. **MariaDB / MySQL 已启动** —— 市场发布需直连数据库写 `t_app` / `t_tool` / `t_model_service` 等表
4. **MinIO 已启动** —— 智能体 IR/DSL JSON 文件的存储与复制
5. **Redis 已启动**（可选） —— treasure metadata 缓存，连不上不影响 OBS 写入

### 初始化 venv

```powershell
# 方式一：用本目录的 init-venv.ps1 自动创建 venv + 装依赖
.\init-venv.ps1
# 之后用 .venv\Scripts\python.exe 运行脚本
.venv\Scripts\python.exe import-presets.py

# 方式二：复用 Agent Studio 的 agent-runtime venv（推荐，已装好 requests/pymysql/boto3）
D:\code\openjiuwen_new\agent-studio\agent-runtime\.venv\Scripts\python.exe import-presets.py
```

### 依赖

```
requests>=2.28    # 调用 studio-manager REST API
pymysql>=1.1      # 直连 MariaDB 写 t_app / t_tool / t_model_service
boto3>=1.28       # 读写 MinIO (OBS) 的 agent IR/DSL JSON
```

---

## 配置文件

编辑同目录 `importer.env`，填写目标环境信息：

```ini
# === 目标 Agent Studio 服务 ===
MANAGER_PORT=31111
BASE_URL=http://127.0.0.1:31111
AUTH_TOKEN=testUser|0          # 本地 simple 模式鉴权 token
PROJECT_ID=0                   # 工作空间项目 ID

# === 运营/官方项目（让预置插件显示在插件市场 OFFICIAL 标签）===
OP_SVC_PROJECT_ID=0            # 匹配 manager 的 opSvcProjectId
# 本地默认 0；远程环境按实际 op_svc_project_id 填写

# === 数据库（MariaDB）— 直连写表用 ===
DB_HOST=127.0.0.1
DB_PORT=3306
DB_USER=root
DB_PASSWORD=你的密码
DB_NAME=agent-builder

# === MinIO（OBS）— 改 agent IR JSON 用 ===
OBS_HOST=127.0.0.1
OBS_PORT=9000
MINIO_AK=minioadmin
MINIO_SK=minioadmin
MINIO_BUCKET=agent-builder

# === Redis — treasure metadata 缓存（可选）===
REDIS_HOST=127.0.0.1
REDIS_PORT=6379

# === 预置数据源（默认用同目录 preset-data/，可覆盖）===
# PLUGINS_DIR=D:\some\other\plugins
# EXAMPLES_ZH=D:\some\other\examples\zh
```

> 配置值支持 `%VAR%` 语法展开环境变量（如 `DB_PASSWORD=%DB_PASS%`）。

---

## 快速开始

```powershell
# 0. 编辑 importer.env 填好 DB / MinIO / 服务地址

# 1. 确保 studio-manager (31111) 已启动

# 2. 一键导入全部预置数据 + 自动发布到三个市场
python import-presets.py

# 3.（可选）配置模型鉴权，让智能体可以真正调用 LLM
python setup-model-auth.py --api-key sk-xxx --api-url https://api.deepseek.com/v1

# 4. 浏览器访问前端验证
#    应用百宝箱: http://localhost:4200/openjiuwen/home/app-library
#    插件市场:   http://localhost:4200/openjiuwen/home/plugin-market
#    模型广场:   http://localhost:4200/openjiuwen/home/model-square
```

---

## import-presets.py 用法

### 命令行参数

| 参数 | 说明 |
|------|------|
| `--sample` | 只导入少量样本验证（1 个插件 + 1 个 agent + 1 个 workflow） |
| `--only plugins` | 只导入插件 |
| `--only agents` | 只导入智能体 |
| `--only workflows` | 只导入工作流 |
| `--models <path>` | 指定模型配置文件路径（默认同目录 `models.json`） |
| `--markets` | 仅发布到全部三个市场（不执行数据导入） |
| `--app-library` | 仅发布到应用百宝箱 |
| `--plugin-market` | 仅发布到插件市场 |
| `--model-square` | 仅发布到模型广场 |

### 使用示例

```powershell
# 完整导入全部预置数据（插件 + 智能体 + 工作流 + 模型），并自动发布到三个市场
python import-presets.py

# 先少量样本验证（1 个插件 + 1 个 agent + 1 个 workflow）
python import-presets.py --sample

# 只导入某一类
python import-presets.py --only plugins
python import-presets.py --only agents
python import-presets.py --only workflows

# 数据已导入，只需重新发布到市场（如改了 DB 后刷新）
python import-presets.py --markets

# 单独刷新某个市场
python import-presets.py --plugin-market
```

### 执行流程

1. **读取配置** → `importer.env`（或回退 `agent-studio.env`）
2. **初始化工作空间** → `POST /v1/{PROJECT_ID}/agent-manager/workspace/init`
3. **创建模型服务**（如 `models.json` 有条目）→ 三步流程：建供应商 → 建鉴权 → 建模型服务
4. **转换 + 导入插件** → `preset-data/plugins/` → JSONL → `POST .../plugins/import`
5. **转换 + 导入工作流** → `preset-data/examples/zh/workflow*.json` → JSONL → `POST .../workflows/import`
6. **转换 + 导入智能体** → `preset-data/examples/zh/agent*.json` → JSONL → `POST .../agents/import`
7. **自动发布到三个市场**：
   - 应用百宝箱：直连 DB 写 `t_app` + `t_agent_version` + OBS 复制 IR→DSL + Redis treasure metadata
   - 插件市场：更新 `t_tool` 设 `type=inner`, `visibility=global`, `project_id`
   - 模型广场：插入 8 个预置模型到 `t_model_service`（SYSTEM 全局作用域）

---

## setup-model-auth.py 用法

给预置模型绑定 API Key 鉴权，使其可被真正调用。同时把所有智能体的 IR JSON 里的模型引用改成目标模型。

### 命令行参数

| 参数 | 说明 |
|------|------|
| `--api-key <key>` | 模型 API Key（必填，不传则只打印提醒） |
| `--api-url <url>` | 模型 API URL（用于匹配/设置 embedding 模型端点） |
| `--model <name>` | 指定测试的模型名（可选，默认按 API URL 匹配） |

### 使用示例

```powershell
# 配置 DeepSeek 鉴权 + 绑定到所有预置模型 + 端到端调用验证
python setup-model-auth.py --api-key sk-xxx --api-url https://api.deepseek.com/v1

# 指定测试某个模型
python setup-model-auth.py --api-key sk-xxx --api-url https://api.deepseek.com/v1 --model DeepSeek-V3

# 也可用环境变量传参
set MODEL_API_KEY=sk-xxx
set MODEL_API_URL=https://api.deepseek.com/v1
python setup-model-auth.py
```

### 执行流程

1. **创建鉴权配置** → `POST .../model-manager/provider/auths`（绑定 API Key 到 provider 100 的 metadata 1022）
2. **绑定模型鉴权** → 直连 DB：`UPDATE t_model_service SET AUTH_METADATA_ID=1022, PUBLISH_STATUS='online'`
3. **查找目标模型** → 按 `--model` 名或 `--api-url` 匹配
4. **重绑智能体 IR** → 读 OBS 上所有 agent 的 IR JSON，改 `modelConfig` → 写回 OBS + 更新 `t_agent`
5. **端到端测试** → `POST .../chat/completions` 验证模型可调用

---

## models.json 格式

`models.json` 是**可选**的模型批量创建配置。如果不填或没有条目，脚本会跳过模型创建（智能体/工作流用占位模型，需在前端 UI 手动配置）。

```json
{
  "entries": [
    {
      "provider_name": "DeepSeek",
      "provider_name_en": "DeepSeek",
      "description": "DeepSeek API 供应商",
      "provider_url": "https://api.deepseek.com/v1",
      "auth_type": "API_KEY",
      "auth_info_schema": {"API Key": ""},
      "service_name": "DeepSeek-V3",
      "model_name": "DeepSeek-V3",
      "model_type": "LLM",
      "api_url": "https://api.deepseek.com/v1",
      "interface_protocol": "openai",
      "is_support_stream": true,
      "is_support_function": true,
      "is_public": true,
      "is_network": true,
      "context_length": 64000,
      "api_key": "在此填你的 DeepSeek API key"
    }
  ]
}
```

> **注意**：模型创建走三步流程（供应商 → 鉴权 → 模型服务），部分 `auth_info` schema 校验较严，失败属正常。**推荐直接在前端 UI 的「模型管理」页面配置**，UI 会自动处理供应商鉴权。

| 字段 | 说明 |
|------|------|
| `provider_name` / `provider_name_en` | 供应商名称（中/英） |
| `auth_type` | 鉴权类型，默认 `API_KEY` |
| `auth_info_schema` | 鉴权字段定义，如 `{"API Key": ""}` |
| `model_name` | 模型名（需与 API 端一致） |
| `model_type` | `LLM` / `Text-Embedding` / `RERANK` / `IMAGE-TO-TEXT` |
| `interface_protocol` | 接口协议：`openai` / `qwen` / `zhipu` / `moonshot` / `baichuan` 等 |
| `api_key` | 你的 API Key（**不填或填占位文字则跳过此模型**） |

---

## 预置数据结构

### 插件（`preset-data/plugins/`）

每个插件是一个 JSON 文件，包含 `plugin_id`、`name`、`api_prefix`、`tools[]` 等字段。脚本会：

1. 读取 `index.json` 获取分类与插件列表
2. 对每个插件的 `tools[]`，生成 `tool_id`（格式 `{plugin_id}__{tool_name}`，非法字符替换为 `_`）
3. 转换为 JSONL 行（`{"metadata": {...}, "import_type": "tool"}`）
4. 通过 `POST .../plugins/import` 上传

```json
// 插件 JSON 示例（简化）
{
  "plugin_id": "deepseek_api",
  "name": "DeepSeek API",
  "api_prefix": "https://api.deepseek.com",
  "tools": [
    {
      "name": "Create Chat Completion",
      "path": "/chat/completions",
      "method": "POST",
      "description": "Generate conversational responses",
      "request_params": { ... }
    }
  ]
}
```

### 智能体模板（`preset-data/examples/zh/agent*.json`）

```json
{
  "agent_name": "出游助手",
  "description": "使用天气查询，路线规划等工具，对出游行程进行规划。",
  "opening_remarks": "您好！我是您的智能旅游助手...",
  "agent_type": "react",
  "configs": { "system_prompt": "..." }
}
```

### 工作流模板（`preset-data/examples/zh/workflow*.json`）

包含 `schema.nodes[]`（Start/LLM/End 三种节点类型）和 `schema.edges[]`，脚本将其转换为当前工程的 DSL + metadata 格式。

---

## 数据流转

```
                    ┌─────────────────────────────────────────────────┐
                    │              preset-data/ （自包含）             │
                    │  plugins/index.json + 16 类 ~80 个插件 JSON       │
                    │  examples/zh/ 2 agent + 4 workflow 模板 JSON       │
                    └──────────────────────┬──────────────────────────┘
                                           │ 读取 + 转换
                                           ▼
              ┌──────────────────────────────────────────┐
              │       import-presets.py 转换器             │
              │  convert_plugin()   → JSONL (tool)         │
              │  convert_workflow() → JSONL (dsl+metadata) │
              │  convert_agent()    → JSONL (metadata)      │
              └──────────────────────┬───────────────────┘
                                     │ POST .../import
                                     ▼
              ┌──────────────────────────────────────────┐
              │          studio-manager API (31111)        │
              │  /plugins/import  /workflows/import        │
              │  /agents/import                            │
              └──────────────────────┬───────────────────┘
                                     │ 落库
                                     ▼
              ┌──────────────────────────────────────────┐
              │  MariaDB: t_tool / t_agent /               │
              │          t_agent_workflow                  │
              └──────────────────────┬───────────────────┘
                                     │ 市场发布（直连 DB + OBS + Redis）
                                     ▼
    ┌───────────────┬───────────────┴───────────────┐
    ▼               ▼                               ▼
 应用百宝箱      插件市场                        模型广场
 home/app-     home/plugin-                    home/model-
 library       market                          square
 t_app 表      t_tool 表                      t_model_service
 +OBS IR→DSL  type=inner                      8 个预置模型
 +Redis        visibility=global               (DeepSeek/Qwen/
 treasure      project_id=                     GLM/Moonshot/BGE)
```

---

## 常见问题

### Q: 导入后前端看不到数据？

检查三个环节：
1. studio-manager 是否正常（`curl http://127.0.0.1:31111`）
2. 数据库是否可连（`importer.env` 的 DB 配置）
3. 市场发布是否执行了（默认导入后会自动执行，也可手动 `--markets`）

### Q: 模型创建失败？

模型创建的 `auth_info` schema 校验较严，脚本批量创建容易失败。**推荐在前端 UI「模型管理」页面手动配置**，UI 会自动处理供应商鉴权。

### Q: 智能体调用模型报错？

需要先运行 `setup-model-auth.py` 绑定 API Key，或在前端模型管理页面配置供应商鉴权。仅导入预置数据不会自动配好 API Key。

### Q: 如何只重新发布某个市场？

```powershell
python import-presets.py --app-library      # 只刷新应用百宝箱
python import-presets.py --plugin-market    # 只刷新插件市场
python import-presets.py --model-square     # 只刷新模型广场
```

### Q: 插件市场 OFFICIAL 标签看不到预置插件？

检查 `importer.env` 的 `OP_SVC_PROJECT_ID` 是否与 manager 的 `opSvcProjectId` 一致。本地环境默认 `0`，远程环境需按实际值填写。

### Q: 如何添加自定义预置数据？

- **插件**：在 `preset-data/plugins/<分类>/` 下放 JSON 文件，并在 `index.json` 对应分类的 `plugins[]` 里添加路径
- **智能体/工作流**：在 `preset-data/examples/zh/` 下放 `agent.*.json` 或 `workflow.*.json`
- 也可用 `importer.env` 的 `PLUGINS_DIR` / `EXAMPLES_ZH` 指向其他目录
