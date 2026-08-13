---
name: crude-assay-import
description: 解析炼厂原油评价报告 Excel(.xls)，提取封面/实沸点蒸馏/原油性质/渣油/关键馏分分类/文字描述 6 类数据，计算侧线收率，导入 PostgreSQL 6 张表 + ChromaDB 向量库。当需要导入原油评价数据、解析实沸点蒸馏表、计算侧线收率、原油分类、新增原油批次、修复导入错误、适配新 Excel 格式时触发。自带可执行脚本和脱敏示例 Excel，零外部代码依赖，开箱即用。
---

# 原油评价报告导入

## 概览

将炼厂原油评价报告（.xls）解析为结构化数据，可导入 PostgreSQL（6 张表）+ ChromaDB（文字描述向量化），供原油评价问答系统检索。**自带可执行脚本** `scripts/crude_assay_import.py`，零外部代码依赖，开箱即用。

## 立即执行：当用户请求解析/导入原油评价报告时

收到用户请求后，**立即执行以下步骤**，无需等待用户确认：

### 步骤 1：确定文件路径

- 用户提供了自己的 .xls 文件 → 使用该路径
- 用户未提供文件 → 使用自带示例 `assets/示例原油评价报告.xls`（先说"我用自带示例演示"）
- 文件是 .xlsx 而非 .xls → 提醒用户 xlrd 不支持 .xlsx，需另存为 .xls

### 步骤 2：执行解析脚本

直接运行以下命令（确保已安装 xlrd：`pip install xlrd`）：

```bash
python scripts/crude_assay_import.py <文件路径> --json /tmp/crude_result.json
```

将 `<文件路径>` 替换为实际的 .xls 文件路径。

### 步骤 3：读取结果并向用户展示

读取 `/tmp/crude_result.json`，向用户展示以下关键信息：

1. **基本信息**：原油名、采样日期、采样点
2. **侧线收率表**：9 条侧线的名称、切割温度区间、收率百分比
3. **原油分类**：硫分类（低硫/含硫/高硫）、酸分类（低酸/含酸/高酸）、综合分类
4. **关键馏分分类**：基属（石蜡基/中间基/环烷基原油）
5. **蒸馏段数**、**性质项数**、**文字描述段数**

### 步骤 4：如用户需要入库

- 导入 PostgreSQL：用户需先设置 `DATABASE_URL` 环境变量，再加 `--import-pg` 参数
- 导入 ChromaDB：用户需先 `pip install chromadb`，再加 `--import-chroma` 参数
- 仅查看结果不需要入库 → 跳过此步

## 用户可能的不同请求及对应操作

| 用户说的 | 执行操作 |
|---------|---------|
| "解析/导入这个原油评价 Excel" | 执行步骤 1-3，展示全部结果 |
| "看看侧线收率" | 执行步骤 2-3，重点展示侧线收率表 |
| "这个原油是低硫还是高硫" | 执行步骤 2-3，重点展示硫/酸分类 |
| "先用示例跑一下" | 用 `assets/示例原油评价报告.xls` 执行步骤 2-3 |
| "导入到数据库" | 步骤 2 命令加 `--import-pg`，需用户先配 DATABASE_URL |
| "导入报错了" | 检查下方"常见陷阱"小节，逐项排查 |

## 脚本输出结构

`process_excel_file()` 返回的结构化 dict（`--json` 导出同名）：

```json
{
  "crude_name": "渤中25-1",
  "sample_date": "20250210",
  "sample_point": "连安湖",
  "sheet_names": ["封面", "原油性质", "实沸点", ...],
  "distillation": [{"boiling_range": "60～80", "yield_per_mass": 1.23, ...}],
  "crude_properties": {"dict": {"密度(20℃)": 0.8841, ...}, "list": [{"name", "unit", "value"}, ...]},
  "sideline_yields": [{"sideline_name": "石脑油", "cut_temp_start": 0, "cut_temp_end": 180, "yield_mass_pct": 12.5}, ...],
  "residue": {"residues": [...], "residue_list": [...]},
  "fraction_class": {"fractions": [...], "classification": "中间基原油"},
  "text_sections": [{"section_num": "1", "section_title": "...", "content": "..."}, ...],
  "classification": {"sulfur_cat": "低硫", "acid_cat": "低酸", "combined": "低硫低酸原油"}
}
```

## 两种报告格式（勿混淆）

| 格式 | sheet 结构 | 处理方式 |
|------|-----------|----------|
| **全评价报告**（本 skill 范围） | 封面/原油性质/实沸点/渣油/关键馏分分类/文字 | 本 skill 的脚本 |
| 快评报告（不在范围） | 数据汇总/VGO 两 sheet | 需另写 openpyxl 解析，走 crude_fingerprint 表 |

## 工作流决策树

```
用户请求
├── "导入这个原油评价 Excel" → 快速使用（上方命令）
├── "解析实沸点蒸馏表" → 脚本 --json 输出，详见 references/excel_format.md
├── "侧线收率怎么算的" → 脚内联 SIDELINE_CUTS 累加，详见 references/domain_rules.md
├── "原油是低硫还是高硫" → 脚内联 classify_crude_by_sulfur_acid，详见 references/domain_rules.md
├── "导入报错了" → 常见陷阱（下方）+ 检查 Excel 格式
└── "新报告格式不一样" → 脚本方案二自动检测表头，详见 references/excel_format.md
```

## 脚本内嵌的 10 步解析流程

`scripts/crude_assay_import.py` 的 `process_excel_file()` 按以下步骤执行：

1. **xlrd 打开 .xls**（仅支持 .xls，不支持 .xlsx）
2. **解析封面** → 提取原油名/采样点/日期（详见 references/excel_format.md §封面）
3. **解析实沸点** → 蒸馏数据（两种格式方案，详见 references/excel_format.md §实沸点）
4. **解析原油性质** → props_dict + props_list（详见 references/excel_format.md §原油性质）
5. **计算侧线收率** → 按 SIDELINE_CUTS 切割温度累加 yield_per_mass（详见 references/domain_rules.md）
6. **解析渣油** → 有 sheet 用 sheet，无则从蒸馏兜底（详见 references/excel_format.md §渣油）
7. **解析关键馏分分类** → 有 sheet 用 sheet，无则从性质兜底（详见 references/excel_format.md §关键馏分分类）
8. **解析文字描述** → 供 ChromaDB 向量检索（详见 references/excel_format.md §文字描述）
9. **入库 PostgreSQL**（可选 `--import-pg`，详见 references/db_schema.md）
10. **入库 ChromaDB**（可选 `--import-chroma`，详见 references/db_schema.md）

## 常见陷阱

### 1. 酸值匹配

性质表中的酸值字段名带括号 `酸值(mgKOH/g)`，脚本用 `"酸值" in key` 子串匹配，与硫含量的 `"硫含量" in key` 一致，确保带括号的字段名也能正确命中。

### 2. xlrd ctype==5 错误单元格

Excel 引用失效等错误单元格 ctype=5，值无法直接转 float。脚本在原油性质和渣油 sheet 中将 ctype==5 视为 `"/"`（数据缺失标志），不触发 section 标题检测。

### 3. 全角/半角括号统一

性质名和单位统一 `（ → (`、`） → )`，去全角空格 `　`。沸点范围用全角 `～` 或半角 `~` 均可。

### 4. sample_date 兜底

封面解析失败时 `sample_date` 兜底为 `"00000000"`（8 位字符串），不是空串也不是 None。

### 5. .xlsx 不支持

xlrd 2.0+ 已移除 .xlsx 支持。若收到 .xlsx，`xlrd.open_workbook` 会报错。需用 openpyxl 替代或要求存为 .xls。

## 脚本 CLI 参数

| 参数 | 说明 |
|------|------|
| `file` | Excel 文件路径（必填） |
| `--crude-name NAME` | 指定原油名（默认从封面提取） |
| `--sample-date YYYYMMDD` | 指定采样日期（默认从封面提取） |
| `--sample-point POINT` | 指定采样点（默认从封面提取） |
| `--json OUTPUT.json` | 输出结构化 JSON 到文件 |
| `--import-pg` | 导入 PostgreSQL（需 DATABASE_URL + psycopg2） |
| `--import-chroma` | 导入 ChromaDB（需 chromadb） |
| `--quiet` | 静默模式 |

## 参考资料

| 文件 | 内容 | 何时读取 |
|------|------|----------|
| [references/excel_format.md](references/excel_format.md) | 6 个 sheet 格式约定、列映射表、section 检测逻辑 | 需要理解/修改 Excel 解析逻辑、适配新格式时 |
| [references/domain_rules.md](references/domain_rules.md) | SIDELINE_CUTS 定义、parse_temp_range 正则、硫/酸分类阈值、侧线别名、加工工艺建议 | 计算侧线收率、原油分类、修改分类规则时 |
| [references/db_schema.md](references/db_schema.md) | 6 张表 CREATE TABLE、批次三元组、入库策略、ChromaDB 配置、ONNX 模型路径 | 修改入库逻辑、调试数据库问题时 |
