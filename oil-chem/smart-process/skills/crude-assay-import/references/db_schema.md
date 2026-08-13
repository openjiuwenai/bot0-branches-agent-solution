# 数据库表结构与入库策略

原油评价数据的持久化方案：PostgreSQL 6 张表 + ChromaDB 向量库。建表和入库逻辑内联在 `scripts/crude_assay_import.py` 的 `import_to_postgres()` 和 `import_to_chromadb()` 中。

## 目录

1. [批次三元组](#批次三元组)
2. [6 张表结构](#6-张表结构)
3. [入库策略](#入库策略)
4. [ChromaDB 配置](#chromadb-配置)
5. [ONNX 模型路径重定向](#onnx-模型路径重定向)

---

## 批次三元组

所有表都使用 `(crude_name, sample_date, sample_point)` 三元组作为逻辑主键：

| 字段 | 类型 | 格式 | 示例 |
|------|------|------|------|
| crude_name | VARCHAR(200) | 中文原油名 | 渤中25-1 |
| sample_date | VARCHAR(8) | YYYYMMDD | 20250210 |
| sample_point | VARCHAR(200) | 采样点 | 连安湖 |

- `sample_date` 解析失败时兜底为 `"00000000"`
- `sample_point` 无括号时为空串 `""`
- 批次展示标签：`f"{sample_date}({sample_point})"`，无采样点则为 `sample_date`
- `crude_oil` 和 `keyfraction_class` 有 UNIQUE 约束在此三元组上

---

## 6 张表结构

### 1. crude_oil（原油主表）

```sql
CREATE TABLE crude_oil (
    id          SERIAL PRIMARY KEY,
    crude_name     VARCHAR(200) NOT NULL,
    sample_date    VARCHAR(8) DEFAULT '' NOT NULL,
    sample_point   VARCHAR(200) DEFAULT '' NOT NULL,
    properties     JSONB,          -- {"密度(20℃)": 0.8841, "API°": 27.82, ...}
    properties_list JSONB,         -- [{"name", "unit", "value"}, ...]
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(crude_name, sample_date, sample_point)
);
```

**properties JSONB 常见字段**：密度(20℃)、API°、酸值(mgKOH/g)、残炭(质量分数)、蜡含量(质量分数)、胶质(质量分数)、沥青质(质量分数)、硫含量(μg/g)、氮含量(μg/g)、镍(μg/g)、钒(μg/g)、凝点、原油类别。

### 2. distillation（实沸点蒸馏）

```sql
CREATE TABLE distillation (
    id          SERIAL PRIMARY KEY,
    crude_name     VARCHAR(200) NOT NULL,
    sample_date    VARCHAR(8) DEFAULT '' NOT NULL,
    sample_point   VARCHAR(200) DEFAULT '' NOT NULL,
    boiling_range      VARCHAR(50),    -- "60～80" / "<60" / ">540"
    yield_per_mass     DOUBLE PRECISION,
    yield_total_mass   DOUBLE PRECISION,
    yield_per_vol      DOUBLE PRECISION,
    yield_total_vol    DOUBLE PRECISION,
    density_20         DOUBLE PRECISION,
    pour_point         DOUBLE PRECISION,
    acidity            DOUBLE PRECISION,
    acid_value         DOUBLE PRECISION,
    sulfur             DOUBLE PRECISION,
    nitrogen           DOUBLE PRECISION,
    aniline_point      DOUBLE PRECISION,
    refractive_index_20 DOUBLE PRECISION,
    refractive_index_70 DOUBLE PRECISION,
    viscosity_20       DOUBLE PRECISION,
    viscosity_50       DOUBLE PRECISION,
    viscosity_80       DOUBLE PRECISION,
    viscosity_100      DOUBLE PRECISION,
    characterization_factor DOUBLE PRECISION,
    correlation_index   DOUBLE PRECISION
);
```

### 3. sideline_yield（侧线收率）

```sql
CREATE TABLE sideline_yield (
    id          SERIAL PRIMARY KEY,
    crude_name     VARCHAR(200) NOT NULL,
    sample_date    VARCHAR(8) DEFAULT '' NOT NULL,
    sample_point   VARCHAR(200) DEFAULT '' NOT NULL,
    sideline_name   VARCHAR(50) NOT NULL,   -- 石脑油/常一线/.../渣油
    cut_temp_start  DOUBLE PRECISION,       -- 切割起始温度
    cut_temp_end    DOUBLE PRECISION,        -- 切割结束温度
    cut_label       VARCHAR(50),             -- "初馏点~180℃"
    yield_mass_pct  DOUBLE PRECISION         -- 质量收率 %
);
```

### 4. residue_properties（渣油性质）

```sql
CREATE TABLE residue_properties (
    id          SERIAL PRIMARY KEY,
    crude_name     VARCHAR(200) NOT NULL,
    sample_date    VARCHAR(8) DEFAULT '' NOT NULL,
    sample_point   VARCHAR(200) DEFAULT '' NOT NULL,
    temperature_range  VARCHAR(20),    -- ">350℃" / ">540℃"
    properties         JSONB,          -- {"密度(20℃)": 0.92, ...}
    residue_list       JSONB           -- [{"name","unit","val_350","val_540","recommendation"}, ...]
);
```

### 5. keyfraction_class（关键馏分分类）

```sql
CREATE TABLE keyfraction_class (
    id          SERIAL PRIMARY KEY,
    crude_name     VARCHAR(200) NOT NULL,
    sample_date    VARCHAR(8) DEFAULT '' NOT NULL,
    sample_point   VARCHAR(200) DEFAULT '' NOT NULL,
    fractions       JSONB,     -- [{"temperature_range","property","measured","paraffinic_std?",...}]
    classification  TEXT,       -- "石蜡基原油" / "中间基原油" / "环烷基原油"
    UNIQUE(crude_name, sample_date, sample_point)
);
```

### 6. text_description（文字描述）

```sql
CREATE TABLE text_description (
    id          SERIAL PRIMARY KEY,
    crude_name     VARCHAR(200) NOT NULL,
    sample_date    VARCHAR(8) DEFAULT '' NOT NULL,
    sample_point   VARCHAR(200) DEFAULT '' NOT NULL,
    section_num    VARCHAR(10),     -- "1"~"8"
    section_title  VARCHAR(100),
    content        TEXT
);
```

---

## 入库策略

函数：`import_to_postgres(crude_name, sample_date, sample_point, distill_data, crude_props_dict, crude_props_list, sideline_yields, residue_data, fraction_class, text_sections)`

| 表 | 策略 | 说明 |
|----|------|------|
| crude_oil | **UPSERT** | `INSERT ... ON CONFLICT (crude_name, sample_date, sample_point) DO UPDATE SET properties=..., properties_list=...` |
| distillation | **DELETE + INSERT** | 先删同批次，再逐行 INSERT |
| sideline_yield | **DELETE + INSERT** | 先删同批次，再逐行 INSERT |
| residue_properties | **DELETE + INSERT** | 先删同批次，再逐行 INSERT（兼容新旧格式） |
| keyfraction_class | **UPSERT** | `INSERT ... ON CONFLICT DO UPDATE SET fractions=..., classification=...` |
| text_description | **DELETE + INSERT** | 先删同批次，再逐行 INSERT |

### residue_properties 新旧格式兼容

```python
# residue_data 可能是 dict 或 list
if isinstance(residue_data, dict) and "residues" in residue_data:
    residues_old = residue_data["residues"]      # 旧格式 list of {"temperature_range", ...}
    residue_list_new = residue_data.get("residue_list", [])  # 新格式 flat list
else:
    residues_old = residue_data if isinstance(residue_data, list) else []
    residue_list_new = []

# 入库时每条 residue 去掉 temperature_range 后入 properties jsonb
for res in residues_old:
    props_copy = {k: v for k, v in res.items() if k != "temperature_range"}
    # INSERT INTO residue_properties (..., temperature_range, properties, residue_list)
    # VALUES (..., res["temperature_range"], json.dumps(props_copy), json.dumps(residue_list_new))
```

---

## ChromaDB 配置

函数：`import_to_chromadb(crude_name, sample_date, sample_point, text_sections)`

### Collection 配置

```python
client = chromadb.PersistentClient(path=CHROMA_PATH)
collection = client.get_or_create_collection(
    name="crude_oil_knowledge",
    metadata={"hnsw:space": "cosine"}  # 余弦相似度
)
```

### CHROMA_PATH

```python
CHROMA_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "..", "data", "chroma_db")
# 即 <repo_root>/data/chroma_db/
```

### 文档入库

每条 text_section 生成一条向量记录：

```python
doc_id = f"{crude_name}_{sample_date}_{sample_point}_{section_num}_{i}"
collection.add(
    documents=[content],
    ids=[doc_id],
    metadatas=[{
        "crude_name": crude_name,
        "sample_date": sample_date,
        "sample_point": sample_point,
        "section_num": section_num,
        "section_title": section_title,
        "type": "text_description",   # 必须有，get_text_from_rag 用此字段过滤
    }]
)
```

### metadata 必填字段

| 字段 | 用途 |
|------|------|
| `type` | 必须为 `"text_description"`，`get_text_from_rag` 的 `$and` 过滤用 |
| `crude_name` | 按原油名过滤 |
| `sample_date` | 按批次过滤 |
| `sample_point` | 按批次过滤 |
| `section_num` | 按章节精确取（`"1"`~`"8"`） |
| `section_title` | 展示用 |

> 缺少 `type="text_description"` 会导致 `get_text_from_rag` 查不到记录。

---

## ONNX 模型路径重定向

ChromaDB 默认会从 S3 下载 `all-MiniLM-L6-v2` ONNX 模型，离线环境会超时。通过 `_embedding.py` 重定向到内置模型。

### 模型位置

```
data/model/all-MiniLM-L6-v2/onnx/
├── config.json
├── model.onnx
├── special_tokens_map.json
├── tokenizer.json
├── tokenizer_config.json
└── vocab.txt
```

### 重定向机制

```python
# 参考：ChromaDB 的 ONNXMiniLM_L6_V2 类
MODEL_DIR = Path(__file__).resolve().parent.parent.parent.parent.parent / "data" / "model" / "all-MiniLM-L6-v2"

def use_built_in_model() -> bool:
    onnx_dir = MODEL_DIR / "onnx"
    if not onnx_dir.is_dir():
        return False
    from chromadb.utils.embedding_functions.onnx_mini_lm_l6_v2 import ONNXMiniLM_L6_V2
    ONNXMiniLM_L6_V2.DOWNLOAD_PATH = MODEL_DIR
    return True

# 导入即执行
use_built_in_model()
```

chromadb 的 `_download_model_if_not_exists` 检测到 `onnx/` 下 6 个文件齐全就跳过下载。

### 部署路径

| 环境 | 路径 |
|------|------|
| 开发态 | `repo/data/model/all-MiniLM-L6-v2/onnx/` |
| Docker 容器 | `/data/model/all-MiniLM-L6-v2/onnx/`（Dockerfile `COPY data /data`） |
| 源码部署 | `huilian_deploy/data/model/all-MiniLM-L6-v2/onnx/` |
