# DFW-RAG 知识处理平台

面向 RAG 场景的知识萃取、合成与质检一体化工作台。

- **知识萃取**：从 BadCase Excel/CSV 迭代生成相似问，写入 Chroma 向量库，直到目标答案类在 top-k 检索中全部召回。
- **知识合成**：逐行 LLM 泛化相似问，按余弦相似度阈值筛选，导出 CSV。
- **知识质检**：复用 `dfw-ragwheel` 质检能力，支持相似问质检、意图描述质检、规则/违禁词库/API 环境配置。

同时提供 **Flask Web 界面** 和 **统一 CLI 入口**，并内置 `bge-small-zh-v1.5` 本地 Embedding 模型。

- **部署指导**：doc/DEPLOY_GUIDE.md
- **LLM 配置模板编写指南**：doc/LLM_TEMPLATE_GUIDE.md

---

## 目录

- [环境要求](#环境要求)
- [安装](#安装)
- [快速开始](#快速开始)
  - [启动 Web 服务](#启动-web-服务)
  - [CLI 入口](#cli-入口)
- [配置说明](#配置说明)
- [CLI 使用指南](#cli-使用指南)
- [Web 界面](#web-界面)
- [Docker 部署](#docker-部署)
- [项目结构](#项目结构)
- [常见问题](#常见问题)

---

## 环境要求

- **Python**：`>=3.13,<3.14`
- **操作系统**：Windows（当前主要开发和运行环境）
- **依赖管理**：`pip` 或 `uv`

---

## 安装

### 1. 克隆或复制项目

```powershell
git clone <REPO_URL>
cd dfw-ragwheel
```

### 2. 创建虚拟环境

```powershell
python -m venv .venv
.\.venv\Scripts\activate
```

如果使用 uv：

```powershell
uv python install 3.13
uv venv --python 3.13 .venv
.\.venv\Scripts\activate
```

### 3. 安装依赖

```powershell
pip install -r requirements.txt
```

或使用 uv：

```powershell
uv pip install -r requirements.txt
```

如需开发依赖（pytest、black、ruff）：

```powershell
pip install -e ".[dev]"
```

### 4. 验证安装

```powershell
python -c "import chromadb, torch, onnxruntime, sentence_transformers; print('ok')"
dfw-rag --help
```

---

## 快速开始

### 启动 Web 服务

```powershell
python web/app.py
```

或：

```powershell
python -m web.app
```

默认监听 **`0.0.0.0:4398`**，浏览器访问：

```text
http://127.0.0.1:4398
```

端口可通过环境变量覆盖：

```powershell
$env:DFW_RAG_PORT="5000"
python web/app.py
```

### CLI 入口

项目提供统一命令 `dfw-rag`：

```powershell
# 查看帮助
dfw-rag --help

# 运行知识萃取
dfw-rag extract --excel .\data\badcase.xlsx --target-kb default

# 纯聚类模式（不调用 LLM）
dfw-rag extract --excel .\data\badcase.xlsx --target-kb demo_kb --completion-mode cluster

# 知识合成 / 相似问泛化
dfw-rag single --single-input .\data\qa.xlsx --single-output .\outputs\single_generated.csv
```

兼容旧入口：

```powershell
python -m rag_extract_split --help
python -m rag_extract_split.single_mode --help
```

---

## 配置说明

### 1. 全局 CONFIG

核心配置位于 `rag_extract_split/config/settings.py` 中的 `CONFIG` 字典：

| 配置段 | 用途 |
|--------|------|
| `rag_extract` | 萃取流程参数：最大轮次、目标召回率、top-k、补全模式、trace 开关 |
| `allocation` | 续轮额度分配策略：`soft_inverse` / `gap_power` / `softmax` / `piecewise` |
| `rag_llm` | 旧版 LLM 服务配置（OpenAI SDK 或 HTTP POST） |
| `rag_embedding` | 旧版 Embedding 配置：本地模型或远程 API |
| `chroma` | ChromaDB 连接、持久化目录、`hnsw_space`、批大小 |
| `logging` | 日志目录与 trace 文件名 |

默认使用内置本地模型：

```python
CONFIG["rag_embedding"]["mode"] = "local"
CONFIG["rag_embedding"]["local_model_dir"] = "models/bge-small-zh-v1.5"
```

### 2. Web 端 LLM / Embedding 配置管理

Web 界面提供 llmkit 风格的配置管理：

- **LLM 配置页面**：`/llm-configs`
- **Embedding 配置页面**：`/embedding-configs`

配置持久化位置：

| 文件 | 说明 |
|------|------|
| `data/llmkit/llm_profiles.yaml` | LLM 配置列表 |
| `data/llmkit/embedding_profiles.yaml` | Embedding 配置列表 |
| `data/llmkit/embedding_active.txt` | 当前激活的 Embedding 配置名 |
| `data/llmkit/templates/*.yaml` | 用户自定义模板 |
| `rag_extract_split/llmkit/templates/*.yaml` | 内置模板（openai_compatible、anthropic_claude、embedding_openai_compatible） |

支持：
- 基于模板快速新增 LLM / Embedding 配置
- 测试连接
- 新增自定义模板（粘贴 YAML）
- 旧版 `data/llm_configs.json` 自动迁移到 `data/llmkit/llm_profiles.yaml`

### 3. 知识质检专用环境变量

质检模块读取 **`web/.env`**，复制示例文件后按需修改：

```powershell
copy web\.env.example web\.env
```

关键配置项：

| 环境变量 | 说明 |
|----------|------|
| `EMBEDDING_MODE` | `local` / `openai` / `http` / `fallback` |
| `EMBEDDING_MODEL` | local 模式下填模型路径，如 `models/bge-small-zh-v1.5` |
| `LLM_MODE` | `openai` / `http` |
| `LLM_MODEL` / `LLM_API_KEY` / `LLM_BASE_URL` | OpenAI 兼容接口配置 |
| `CHROMA_PERSIST_DIR` | 质检专用 Chroma 持久化目录 |
| `API_TIMEOUT` | 接口超时时间 |

---

## CLI 使用指南

`dfw-rag` 提供以下子命令：`extract`（默认）、`import`、`query`、`get`、`list`、`update`、`delete`、`single`。

不带子命令时，默认进入 `extract` 子命令（兼容旧用法）。

### `extract`：运行知识萃取

```powershell
dfw-rag extract --excel <PATH> [OPTIONS]
```

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `--excel` | 是 | - | BadCase 文件路径（`.xlsx/.xls/.csv`） |
| `--sheet` | 否 | 第一个 sheet | Excel sheet 名或索引 |
| `--out` | 否 | 自动生成 | 输出 QA Excel 路径 |
| `--json-out` | 否 | - | 输出任务元信息 JSON |
| `--target-kb` | 否 | `default` | 目标 Chroma collection 名 |
| `--completion-mode` | 否 | `llm` | `llm` 或 `cluster` |
| `--clear-collection` | 否 | - | 启动前清空目标 collection |
| `--chroma-space` | 否 | 取 CONFIG | `l2` / `cosine` / `ip` |
| `--import-excel` | 否 | - | 先导入指定 Excel/CSV 到向量库 |
| `--import-replace` | 否 | - | 导入前清空重建 |
| `--import-only` | 否 | - | 仅导入，不运行萃取 |
| `--write-batch-size` / `--query-batch-size` / `--delete-batch-size` | 否 | 取 CONFIG | 覆盖向量库批大小 |

示例：

```powershell
dfw-rag extract `
  --excel .\data\badcase.xlsx `
  --target-kb demo_kb `
  --out .\outputs\qa_result.xlsx `
  --completion-mode llm
```

### `single`：相似问泛化

```powershell
dfw-rag single --single-input <PATH> --single-output <PATH> [OPTIONS]
```

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `--single-input` | 是 | - | 输入 CSV/Excel |
| `--single-output` | 是 | - | 输出 CSV 路径 |
| `--single-q-column` | 否 | `query` | 问题列名 |
| `--single-a-column` | 否 | `answer` | 答案列名 |
| `--single-k` | 否 | `5` | 每次生成相似问数量 |
| `--single-m` | 否 | `2` | 满足阈值的目标条数 |
| `--single-threshold` | 否 | `0.80` | 余弦相似度阈值 |
| `--single-max-attempts` | 否 | `5` | 单行最多重试次数 |

### `import`：批量导入 QA 到向量库

```powershell
dfw-rag import --input <PATH> --collection <NAME> [OPTIONS]
```

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `--input` | 是 | - | 输入 Excel/CSV |
| `--collection` | 否 | `qa_excel_kb` | 目标 collection |
| `--replace` | 否 | - | 导入前清空重建 |
| `--batch-size` | 否 | `128` | 写入批大小 |
| `--export-ids` | 否 | - | 导出导入的 id 列表 |

### `query`：批量检索 / 单条测试

```powershell
# 批量文件模式
dfw-rag query --input <PATH> --collection <NAME> --top-k 5 --output <PATH>

# 单条测试
dfw-rag query --input-single "如何查询我的订单" --collection intent_kb --top-k 3
```

### 其他子命令

```powershell
# 列出 collections
dfw-rag list [--with-count]

# 按 id 获取记录
dfw-rag get --collection <NAME> --ids "id1,id2,id3"

# 按 id 批量更新
dfw-rag update --input <PATH> --collection <NAME>

# 按 id 删除
dfw-rag delete --collection <NAME> --ids "id1,id2"
dfw-rag delete --collection <NAME> --input <PATH> --id-column id
```

---

## Web 界面

### 页面路由

| 路由 | 说明 |
|------|------|
| `/` | 首页，三个模块入口 |
| `/extract` | 知识萃取：基础语料导入、执行萃取、结果入库、召回验证、Collection 管理、语料清理 |
| `/synthesize` | 知识合成：Single 模式相似问泛化 |
| `/qc` | 知识质检：质检、知识库、规则、违禁词库、API 环境 |
| `/llm-configs` | LLM 配置管理 + 配置模板管理 |
| `/embedding-configs` | Embedding 配置管理 + 配置模板管理 |

### 主要 API 接口

| 接口 | 说明 |
|------|------|
| `POST /api/upload` | 通用文件上传 |
| `POST /api/extract/import_base` | 基础语料导入 |
| `POST /api/extract/run` | 执行知识萃取 |
| `POST /api/extract/import_result` | 萃取结果入库 |
| `POST /api/extract/cleanup` | 按新增数据 ID 清理语料 |
| `POST /api/synthesize/run` | 执行知识合成 |
| `POST /api/verify/query` | 单条/批量召回验证 |
| `GET /api/collections` | 列出 collections 及数量 |
| `POST /api/qc/run` | 运行质检 |
| `GET /api/qc/job` | 查询质检任务状态 |
| `GET /api/qc/download` | 下载质检结果 |
| `GET/POST /api/llm/templates`、`/api/llm/profiles` | LLM 模板/配置管理 |
| `GET/POST /api/embedding/templates`、`/api/embedding/profiles` | Embedding 模板/配置管理 |

### 执行后下载输出文件

知识萃取、知识合成、知识质检执行完成后，页面会显示“下载生成文件”按钮，可直接下载对应的输出文件。

---

## Docker 部署

项目已提供 `Dockerfile` 与 `gunicorn.conf.py`，支持一键构建并部署到 Linux 服务器。

### 镜像构建

```bash
docker build -t dfw-ragwheel:<TAG> .
```

### 本地运行验证

```bash
# 前台运行（调试用）
docker run -p 4398:4398 --rm dfw-ragwheel:<TAG>

# 后台运行（建议挂载数据/日志/上传目录）
docker run -d \
  -p 4398:4398 \
  -v $(pwd)/data:/app/data \
  -v $(pwd)/logs:/app/logs \
  -v $(pwd)/web/uploads:/app/web/uploads \
  --name dfw-ragwheel \
  dfw-ragwheel:<TAG>

# 验证接口
curl http://127.0.0.1:4398/
```

### 导出并迁移到服务器

请将下列占位符替换为实际环境中的值：`<SSH_KEY_PATH>`、`<SSH_USER>`、`<SERVER_IP>`、`<DEPLOY_DIR>`、`<TAG>`。

```bash
# 1. 导出镜像
docker save dfw-ragwheel:<TAG> | gzip > dfw-ragwheel.tar.gz

# 2. 上传到服务器
scp -i "<SSH_KEY_PATH>" \
  dfw-ragwheel.tar.gz \
  <SSH_USER>@<SERVER_IP>:<DEPLOY_DIR>/

# 3. 在服务器加载并启动
ssh -i "<SSH_KEY_PATH>" <SSH_USER>@<SERVER_IP> '
  cd <DEPLOY_DIR>
  docker load -i dfw-ragwheel.tar.gz
  docker rm -f dfw-ragwheel 2>/dev/null
  docker run -d \
    -p 4398:4398 \
    -v <DEPLOY_DIR>/data:/app/data \
    -v <DEPLOY_DIR>/logs:/app/logs \
    -v <DEPLOY_DIR>/uploads:/app/web/uploads \
    --name dfw-ragwheel \
    dfw-ragwheel:<TAG>
'

# 4. 服务器验证
curl http://<SERVER_IP>:4398/
```

### 环境变量

| 环境变量 | 说明 | 默认值 |
|----------|------|--------|
| `DFW_RAG_PORT` | Flask 开发模式端口（Docker 内 gunicorn 固定 4398） | `4398` |
| `DFW_RAG_DEBUG` | 是否开启 Flask 调试模式 | `false` |
| `DFW_RAG_HOME` | 项目根目录 / 数据目录 | `/app` |
| `GUNICORN_WORKERS` | gunicorn worker 数量 | `CPU * 2 + 1` |

---

## 项目结构

```
rag_extract_split/
├── __init__.py              # 包声明
├── __main__.py              # 包级 CLI 入口
├── cli_entry.py             # dfw-rag 入口：设置 DFW_RAG_HOME 并调用 CLI
├── main.py                  # 兼容旧入口
├── single_mode.py           # 兼容旧入口
├── config/                  # 配置与数据模型
│   ├── settings.py          # 全局 CONFIG
│   ├── models.py            # RAGCase 等数据模型
│   ├── llmkit_manager.py    # LLM 配置管理器
│   └── embedding_manager.py # Embedding 配置管理器
├── llmkit/                  # llmkit 风格模板与配置
│   ├── template.py          # Template / TemplateManager
│   ├── profile.py           # Profile / ProfileManager
│   └── templates/           # 内置 LLM/Embedding 模板
├── common/                  # 通用工具
├── io/                      # Excel/CSV 读写
├── infrastructure/          # Embedding、Chroma、HTTP 适配器
├── generation/              # QA 生成策略（llm / cluster）
├── extraction/              # 萃取核心流程
├── cli/                     # CLI 实现
├── web/                     # Flask Web APP
│   ├── app.py               # Flask 入口，默认端口 4398
│   ├── config.py            # Web 全局配置
│   ├── api/                 # API 路由（extract / synthesize / qc / outputs）
│   ├── llmkit_routes.py     # LLM/Embedding 配置管理路由
│   ├── backend/knowledge_qc/# 知识质检后端逻辑
│   ├── templates/           # HTML 模板
│   ├── static/              # CSS/JS
│   ├── uploads/             # 上传文件临时目录
│   └── .env.example         # 质检模块环境变量示例
├── data/                    # 项目数据
│   ├── llmkit/              # LLM/Embedding profile、用户模板、激活文件
│   └── chromadb/            # 萃取/合成默认 Chroma 目录
├── models/                  # 本地模型目录
│   └── bge-small-zh-v1.5/   # 内置本地 Embedding 模型
├── outputs/                 # CLI/Web 输出目录（不入库）
├── logs/                    # 日志目录
├── pyproject.toml           # 项目元数据与依赖
├── requirements.txt         # 完整依赖锁定
└── README.md                # 本文件
```

---

## 常见问题

### 1. Web 启动报错“以一种访问权限不允许的方式做了一个访问套接字的尝试”

说明当前端口（默认 4398）被占用或无权限。更换端口：

```powershell
$env:DFW_RAG_PORT="5000"
python web/app.py
```

### 2. 复制项目到新目录后切换界面崩溃（退出码 `-1073741819`）

通常是 `.venv` 被直接复制导致原生库路径错乱，或 Chroma 索引文件损坏。建议：

```powershell
# 1. 在新目录重新创建虚拟环境并安装依赖
Remove-Item -Recurse -Force .venv
python -m venv .venv
.\.venv\Scripts\activate
pip install -r requirements.txt

# 2. 若仍崩溃，备份后删除 Chroma 数据目录，让程序重建
Move-Item data\chromadb data\chromadb.bak
```

### 3. 质检报 `未配置 EMBEDDING_API_KEY` 或连接错误

请检查 `web/.env` 中的 `EMBEDDING_*` 和 `LLM_*` 配置；也可在“知识质检 → API 环境”页签中选择已保存的 LLM / Embedding 配置。

### 4. Embedding 测试报 `Expected embeddings to be a list of floats or ints...`

通常是本地模型输出 `np.float32` 类型未转换导致。请确认 `sentence-transformers` 和项目代码版本匹配，或改用 OpenAI 兼容 Embedding 接口。

### 5. 如何安装开发依赖？

```powershell
pip install -e ".[dev]"
```

---

## 许可证

请按实际使用场景自行约定许可证。
