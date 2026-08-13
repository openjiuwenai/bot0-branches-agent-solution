# Excel 格式约定

原油评价报告（.xls）的 6 个 sheet 结构详解。解析逻辑内联在 `scripts/crude_assay_import.py` 中。

## 目录

1. [封面 sheet](#封面-sheet)
2. [实沸点 sheet](#实沸点-sheet)
3. [原油性质 sheet](#原油性质-sheet)
4. [渣油 sheet](#渣油-sheet)
5. [关键馏分分类 sheet](#关键馏分分类-sheet)
6. [文字描述 sheet](#文字描述-sheet)

---

## 封面 sheet

**sheet 名**：`"封面"`（找不到则回退到第一个 sheet）

**解析函数**：`parse_cover_sheet(wb)` → `{"crude_name", "sample_point", "sample_date"}`

**扫描范围**：前 80 行 × 前 15 列

**提取逻辑**：

### 原油名称 + 采样点

找含 `"原油名称"` 的行，优先读 **C 列（列索引 2）**，为空则读标签列右侧第一个非空单元格。

值格式 `"渤中25-1（连安湖）"` 用正则拆分：
```python
m = re.match(r'^(.+?)[（(](.+?)[)）]\s*$', raw)
# m.group(1) = "渤中25-1" → crude_name
# m.group(2) = "连安湖" → sample_point
```
无括号则整体作为 crude_name，sample_point 为空串。

### 采样日期

找含 `"采样日期"` 的行，同样优先读 C 列。

- xlrd `ctype == 3`（日期单元格）：用 `xlrd.xldate_as_datetime(value, wb.datemode)` 转 `YYYYMMDD`
- 否则用 `_parse_date_to_yyyymmdd(date_str)` 支持：
  - `YYYY/MM/DD` 或 `YYYY-MM-DD` 或 `YYYY年MM月DD日`
  - 纯 `YYYYMMDD`（8 位数字）

---

## 实沸点 sheet

**sheet 名**：`"实沸点"`（找不到则回退到 `"实沸点蒸馏"`）

**解析函数**：`parse_distillation_sheet(wb, crude_name)` → `list[dict]`

### 方案一：硬编码列号（标准格式）

**检测条件**：`sh.nrows > 3` 且第 3 行（行号 2... 实际是行号 3，即 `sh.cell(3, 0)`）首单元格是数字或 `<>` 开头。

> 注：代码中检测的是 `sh.cell(3, 0)`，即 0-based 第 4 行。标准格式下行 0=标题、行 1-2=表头(合并)、行 3+=数据。

**固定列映射**：

| 列索引 | 字段名 | 说明 |
|--------|--------|------|
| 0 | boiling_range | 沸点范围，如 `60～80`、`<60`、`>540` |
| 1 | yield_per_mass | 每馏分质量收率 % |
| 2 | yield_total_mass | 总质量收率 % |
| 3 | yield_per_vol | 每馏分体积收率 % |
| 4 | yield_total_vol | 总体积收率 % |
| 5 | density_20 | 20℃密度 |
| 6 | pour_point | 倾点（可选） |
| 7 | acidity | 酸度（可选） |
| 8 | acid_value | 酸值（可选） |
| 9 | sulfur | 硫含量（可选） |
| 10 | nitrogen | 氮含量（可选） |

**跳过行**：`boiling_range` 为 `""/"-"/"损失"` 的行。

### 方案二：自动检测表头（兼容非标准格式）

**检测条件**：方案一不匹配时，前 10 行搜含 `"沸点范围"/"馏分"/"温度"` 的行作 header_row。

**表头合并**：合并 header_row 和 header_row+1 的文字（兼容合并单元格），按子串动态建 col_map：

| 匹配子串 | 字段名 |
|----------|--------|
| `沸点` | boiling_range |
| `m/m` 或 `占原油`+`质量`/`每` | yield_mass / yield_total_mass（看是否含`总`） |
| `V/V` 或 `占原油`+`体积` | yield_vol / yield_total_vol（看是否含`总`） |
| `密度` | density |
| `硫` | sulfur |
| `氮` | nitrogen |
| `酸值` | acid_value |
| `粘度`/`黏度` + `20`/`50`/`80`/`100` | viscosity_20/50/80/100 |

必须找到 `boiling_range` 列，否则返回空列表。

### 通用辅助函数

```python
def _safe_float(sh, r, c):
    """安全取浮点值，空/无效返回 None"""
    if c is None:
        return None
    v = sh.cell(r, c).value
    if v == "" or v is None or str(v).strip() in ["", "-", "--"]:
        return None
    try:
        return float(v)
    except Exception:
        return None
```

---

## 原油性质 sheet

**sheet 名**：`"原油性质"`（找不到则回退到 `"一般性质"`）

**解析函数**：`parse_crude_properties_sheet(wb, crude_name)` → `(props_dict, props_list)`

**列结构**：

| 列索引 | 内容 |
|--------|------|
| 0 | 分析项目名称 |
| 1 | 单位 |
| 2 | 方法/代号（跳过不读） |
| 3 | 分析结果 |

从第 3 行开始（行号 2，0-based）。

### Section 标题检测逻辑

原油性质表有分组（如"馏程"、"粘度"等 section 标题行），需要区分标题行和数据行。判断依据：

1. **统一括号和空格**：`（ → (`、`） → )`、去全角空格 `　`
2. **前导空格检测**：`has_leading_space = (raw_name != raw_name.lstrip())` —— 原始单元格有前导空格说明是子项
3. **温度项检测**：`is_temp_item = re.match(r'^[\d.]+℃', name_str)` —— 如 `20℃` 开头的是温度子项

**判定规则**：

| 值状态 | 有前导空格/is_temp_item | 无前导空格 | 含义 |
|--------|------------------------|------------|------|
| 真正空白（`""`/`"——"`） | 保留为空值子项 | section 标题，跳过 | section 切换 |
| 有值 | 子项，display_name=`"{section} {name}"` | 非子项，清除 section | 普通数据行 |

4. **ctype==5**（Excel 错误单元格）：值视为 `"/"`（数据缺失标志），**不**触发 section 标题检测

5. **display_name 构建**：
   - section 下有前导空格/温度项的子项：`f"{current_section} {name_str}"`
   - section 下无前导空格的非子项：说明 section 结束，`display_name = name_str`，清除 section
   - 无 section：`display_name = name_str`

6. **值处理**：`""/"/"/"-"/"--"` 存为 NULL，数字存 float，否则存字符串

**返回值**：
- `props_dict`：`{"密度(20℃)": 0.8841, "API°": 27.82, "硫含量(μg/g)": 1234, ...}`
- `props_list`：`[{"name": "密度(20℃)", "unit": "g/cm³", "value": 0.8841}, ...]`

---

## 渣油 sheet

**sheet 名**：`"渣油"`

**解析函数**：`parse_residue_sheet(wb, crude_name)` → `{"residues": [...], "residue_list": [...]}`

**列结构**：

| 列索引 | 内容 |
|--------|------|
| 0 | 分析项目名称 |
| 1 | 单位 |
| 2 | 方法/代号（跳过） |
| 3 | >350℃ 值 |
| 4 | >540℃ 值 |
| 5 | 推荐方法（可选） |

### 输出格式

**旧格式 `residues`**：每个温度范围一个对象
```python
[
    {"temperature_range": ">350℃", "properties": {"密度(20℃)": 0.92, "硫含量": 3.5, ...}},
    {"temperature_range": ">540℃", "properties": {...}},
]
```

**新格式 `residue_list`**：flat list，按 name 分组
```python
[
    {"name": "密度(20℃)", "unit": "g/cm³", "val_350": 0.92, "val_540": 0.95, "recommendation": ""},
    {"name": "硫含量", "unit": "%", "val_350": 3.5, "val_540": 5.2, "recommendation": ""},
    ...
]
```

### Section 标题检测

两列值均真正为空（`""`/`"——"`）→ section 标题。但 `"/"` 视为数据缺失标志，**不**触发 section（与空串不同）。

ctype==5 视为 `"/"`。

### 兜底方案

无"渣油" sheet 时，用 `calc_residue_from_distill(distill_data, crude_name)`：
- 对 `>350℃` 和 `>540℃` 两段，从蒸馏数据中找 `boiling_range` 解析后 `start >= 350` 或 `start >= 540` 的段
- 累加 `yield_per_mass` 为"累积收率%"
- 收集 `density_20/sulfur/nitrogen/acid_value/viscosity_50/viscosity_100`

---

## 关键馏分分类 sheet

**sheet 名**：`"关键馏分分类"`

**解析函数**：`parse_fraction_class_sheet(wb, crude_name)` → `{"fractions": [...], "classification": str}`

### 分类结论提取

全表扫描，找含以下关键词的行：
- `石蜡基原油` / `中间基原油` / `环烷基原油`（带"原油"后缀）
- 若无则找 `石蜡基` / `中间基` / `环烷基`，补 `+ "原油"`

### 转置结构识别

**表头行特征**：含 ≥2 个温度范围列（正则 `\d{2,}\s*[～~]\s*\d{2,}`），温度范围在**列**（不在第一列），这是转置结构。

### 数据收集

从 header_row+1 开始按行类型分类：

| 首列含关键词 | 存入 |
|-------------|------|
| `石蜡基` | paraffinic_by_col[col_idx] = 标准值 |
| `中间基` | intermediate_by_col[col_idx] = 标准值 |
| `环烷基` | naphthenic_by_col[col_idx] = 标准值 |
| 不含 SKIP_KEYWORDS | measured_rows（实测行） |

`SKIP_KEYWORDS = ["分类", "结论", "类别", "项目", "基属"]`

### 输出

每个实测行 × 温度列 = 一条记录：
```python
{
    "temperature_range": "250~275℃",
    "property": "密度(20℃)",
    "measured": 0.852,
    "paraffinic_std": "≤0.840",    # 可选
    "intermediate_std": "0.840~0.870",  # 可选
    "naphthenic_std": "≥0.870",    # 可选
}
```

### 兜底方案

无"关键馏分分类" sheet 或无数据时，用 crude_props_dict 兜底：
```python
{
    "fractions": {
        "密度(20℃)": ...,
        "硫含量(μg/g)": ...,
        "酸值(mgKOH/g)": ...,
        "残炭(质量分数)": ...,
        "原油类别": "...",
    },
    "classification": crude_props_dict.get("原油类别", ""),
}
```

---

## 文字描述 sheet

**sheet 名**：含 `"评价"` / `"文本"` / `"文字"` 的任意 sheet

**解析函数**：`parse_text_sections(wb, crude_name)` → `list[dict]`

**扫描范围**：前 200 行 × 前 3 列

**逻辑**：
1. 含 `原油评价`/`原油性质`/`总结`/`概述` 的单元格 → 设为 `current_title`
2. 长度 >20 的单元格 → 作为 content，递增 `section_num`
3. 无任何文本 → 兜底一条 `{"section_num": "1", "section_title": "原油一般性质", "content": "{crude_name}原油评价数据已导入系统。"}`

**section_num 与章节映射**（SECTION_INDEX_MAP）：

| section_num | 章节 |
|-------------|------|
| 1 | 原油一般性质 |
| 2 | 原油实沸点性质 |
| 3 | 石脑油 |
| 4 | 喷气燃料 |
| 5 | 柴油 |
| 6 | VGO |
| 7 | 润滑油 |
| 8 | 渣油 |

**缓存**：模块级 `_text_parse_cache` 字典，key 为 `f"{crude_name}_{id(wb)}"`，避免重复解析。`process_excel_file` 结束时清空。
