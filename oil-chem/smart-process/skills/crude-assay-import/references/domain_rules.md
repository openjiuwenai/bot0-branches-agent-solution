# 领域规则与阈值表

原油评价数据的业务规则：侧线切割、沸点解析、原油分类、加工工艺建议。逻辑内联在 `scripts/crude_assay_import.py` 中。

## 目录

1. [侧线切割定义（SIDELINE_CUTS）](#侧线切割定义sideline_cuts)
2. [沸点范围解析（parse_temp_range）](#沸点范围解析parse_temp_range)
3. [侧线收率累加算法](#侧线收率累加算法)
4. [侧线别名映射（SIDELINE_ALIASES）](#侧线别名映射sideline_aliases)
5. [硫/酸分类阈值表](#硫酸分类阈值表)
6. [加工工艺建议规则](#加工工艺建议规则)
7. [意图识别正则库（INTENT_PATTERNS）](#意图识别正则库intent_patterns)

---

## 侧线切割定义（SIDELINE_CUTS）

9 条侧线的温度区间定义，是侧线收率计算的唯一依据。

| 侧线名 | start (℃) | end (℃) | label |
|--------|-----------|---------|-------|
| 石脑油 | 0 | 180 | 初馏点~180℃ |
| 常一线 | 180 | 220 | 180℃~220℃ |
| 常二线 | 220 | 300 | 220℃~300℃ |
| 常三线 | 300 | 370 | 300℃~370℃ |
| 减一线 | 370 | 395 | 370℃~395℃ |
| 减二线 | 395 | 450 | 395℃~450℃ |
| 减三线 | 450 | 500 | 450℃~500℃ |
| 减四线 | 500 | 540 | 500℃~540℃ |
| 渣油 | 540 | 9999 | >540℃ |

源码：
```python
SIDELINE_CUTS = {
    "石脑油": {"start": 0,    "end": 180,  "label": "初馏点~180℃"},
    "常一线": {"start": 180,  "end": 220,  "label": "180℃~220℃"},
    "常二线": {"start": 220,  "end": 300,  "label": "220℃~300℃"},
    "常三线": {"start": 300,  "end": 370,  "label": "300℃~370℃"},
    "减一线": {"start": 370,  "end": 395,  "label": "370℃~395℃"},
    "减二线": {"start": 395,  "end": 450,  "label": "395℃~450℃"},
    "减三线": {"start": 450,  "end": 500,  "label": "450℃~500℃"},
    "减四线": {"start": 500,  "end": 540,  "label": "500℃~540℃"},
    "渣油":   {"start": 540,  "end": 9999, "label": ">540℃"},
}
```

---

## 沸点范围解析（parse_temp_range）

将 boiling_range 字符串解析为 `(start, end)` 整数元组。

```python
def parse_temp_range(boiling_range):
    if not boiling_range:
        return None
    # 优先匹配区间格式
    m = re.match(r'<?\s*(\d+)\s*[～~]\s*(\d+)', boiling_range)
    if m:
        return int(m.group(1)), int(m.group(2))
    # <60
    m = re.match(r'<\s*(\d+)', boiling_range)
    if m:
        return 0, int(m.group(1))
    # >540
    m = re.match(r'>\s*(\d+)', boiling_range)
    if m:
        return int(m.group(1)), 9999
    return None
```

**支持的格式**：

| 格式 | 示例 | 解析结果 |
|------|------|----------|
| 区间（全角～） | `60～80` | (60, 80) |
| 区间（半角~） | `60~80` | (60, 80) |
| 带前缀 < 的区间 | `<60～80` | (60, 80) |
| 小于 | `<60` | (0, 60) |
| 大于 | `>540` | (540, 9999) |

---

## 侧线收率累加算法

侧线收率不是直接从 Excel 读取，而是从实沸点蒸馏数据中按温度区间累加计算。

```python
for sname, cuts in SIDELINE_CUTS.items():
    cum_yield = 0
    for d in distill_data:
        parsed = parse_temp_range(d.get("boiling_range", ""))
        if parsed:
            s, e = parsed
            # 沸点区间完全落在侧线区间内才累加
            if s >= cuts["start"] and e <= cuts["end"]:
                ym = d.get("yield_per_mass") or 0
                cum_yield += ym
    sideline_yields.append({
        "sideline_name": sname,
        "cut_temp_start": cuts["start"],
        "cut_temp_end": cuts["end"],
        "cut_label": cuts["label"],
        "yield_mass_pct": round(cum_yield, 2),
    })
```

**关键点**：`s >= cuts["start"] and e <= cuts["end"]` —— 沸点区间必须**完全落在**侧线区间内才累加。跨区间的馏分不计入任何侧线。

---

## 侧线别名映射（SIDELINE_ALIASES）

问答系统中用户可能用别名指代侧线，需归一到标准名。

```python
SIDELINE_ALIASES = {
    "石脑油": ["石脑油", "轻石脑油", "重石脑油", "naphtha"],
    "常一线": ["常一线", "常一", "常压一线", "航煤", "喷气燃料"],
    "常二线": ["常二线", "常二", "常压二线", "煤油"],
    "常三线": ["常三线", "常三", "常压三线", "柴油"],
    "减一线": ["减一线", "减一", "减压一线", "vgo1"],
    "减二线": ["减二线", "减二", "减压二线", "vgo2"],
    "减三线": ["减三线", "减三", "减压三线", "vgo3"],
    "减四线": ["减四线", "减四", "减压四线"],
    "渣油":   ["渣油", "常压渣油", "减压渣油", "残渣", "residue"],
}
```

**标准侧线顺序**（展示用 `SIDELINE_ORDER`）：
`石脑油, 常一线, 常二线, 常三线, 减一线, 减二线, 减三线, 减四线, 渣油`

---

## 硫/酸分类阈值表

函数：`classify_crude_by_sulfur_acid(properties)` → `(sulfur_cat, acid_cat)`

从 properties dict 中提取硫含量和酸值进行分类。

### 硫含量分类

**匹配规则**：key **包含子串** `"硫含量"`（会匹配到 `硫含量(μg/g)`）

| 硫含量 (μg/g) | 分类 |
|---------------|------|
| < 3000 | 低硫 |
| 3000 ≤ val < 10000 | 含硫 |
| ≥ 10000 | 高硫 |

### 酸值分类

**匹配规则**：key **包含子串** `"酸值"`（会匹配到 `酸值(mgKOH/g)`）

| 酸值 (mgKOH/g) | 分类 |
|----------------|------|
| > 1.0 | 高酸 |
| 0.5 < val ≤ 1.0 | 含酸 |
| ≤ 0.5 | 低酸 |

### 综合分类

拼接为 `f"{sulfur_cat}{acid_cat}原油"`，如 `"低硫高酸原油"`。两者都为空则返回空串。

### 温度关键词映射

问答系统提取温度时用：

```python
TEMP_KEYWORDS = {
    180: ["180", "石脑油"],
    240: ["240", "航煤", "喷气燃料"],
    350: ["350", "柴油", "常压"],
    500: ["500", "VGO", "蜡油"],
    540: ["540", "减压"],
}
```

---

## 加工工艺建议规则

基于原油基属分类（石蜡基/中间基/环烷基）和硫/酸分类生成加工建议。

### 基属建议

| 基属 | 加工建议 |
|------|----------|
| 含"石蜡基" | 适合生产优质润滑油、石蜡产品，催化裂化轻油收率高 |
| 含"中间基" | 适合催化裂化和加氢裂化，兼顾燃料油和化工原料 |
| 含"环烷基" | 适合生产优质沥青、橡胶填充油、变压器油等特种产品 |

### 硫/酸修正

| 条件 | 附加建议 |
|------|----------|
| 含硫/高硫 | 需加氢脱硫，适合加氢裂化-催化裂化联合流程 |
| 高酸 | 需考虑设备防腐，适合加氢处理后再二次加工 |
| 低硫 且 低酸 | 可直接常减压蒸馏，适合直馏产品生产 |

### 渣油加工建议

| 温度范围 | 建议 |
|----------|------|
| >350℃ 常压渣油 | 掺入减压馏分油作催化裂化原料，或作渣油加氢原料 |
| >540℃ 减压渣油 | 同上 |

---

## 意图识别正则库（INTENT_PATTERNS）

问答系统 7 类意图的正则匹配库。规则优先 + LLM 兜底。

```python
INTENT_PATTERNS = {
    "self_introduce": [
        r"你是谁", r"你叫什么", r"介绍.*自己", r"自我介绍", r"你是什么",
        r"你能做什么", r"你有什么功能", r"使用说明", r"怎么用", r"帮助",
    ],
    "sideline_yield": [
        r"收率", r"产率", r"石脑油", r"常一[线]?", r"常二[线]?", r"常三[线]?",
        r"减一[线]?", r"减二[线]?", r"减三[线]?", r"减四[线]?", r"渣油",
        r"侧线", r"馏分", r"切割", r"各线", r"产品收率"
    ],
    "crude_property": [
        r"性质", r"密度", r"粘度", r"黏度", r"酸值", r"凝点", r"硫含量",
        r"氮含量", r"残炭", r"蜡含量", r"胶质", r"沥青质", r"API",
        r"原油.*特点", r"原油.*指标", r"原油.*属性"
    ],
    "crude_compare": [
        r"对比", r"比较", r"区别", r"差异", r"差别", r"哪个好",
        r"优于", r"差于", r"高于", r"低于",
        r"(?:对比|比较).*(?:收率|性质|数据)",
        r"(?:收率|性质).*(?:对比|比较|区别|差异|哪个好|相比)",
    ],
    "residue_property": [
        r"渣油", r"残炭", r"四组分", r"饱和分", r"芳香分", r"胶质",
        r"沥青质", r"减压渣油", r"常压渣油", r">350", r">540",
    ],
    "fraction_class": [
        r"分类", r"关键馏分", r"加工", r"工艺", r"基属", r"石蜡基",
        r"中间基", r"环烷基", r"路线", r"适合", r"建议"
    ],
    "list_crudes": [
        r"(?:有哪些|有什么|列出|所有|哪些).*原油",
        r"原油.*(?:有哪些|有哪些|列表|列出)",
        r"已上传.*原油",
        r"目前.*原油",
        r"现有.*原油",
    ],
}
```

### 意图评分与优先级

- 每个意图统计命中正则数作为得分
- 全 0 分 → 返回 `"general"`（走 LLM 总结兜底）
- 并列最高分时按优先级顺序选择：
  `PRIORITY = [list_crudes, residue_property, fraction_class, crude_property, crude_compare, sideline_yield]`
  （`self_introduce` 和 `general` 不在 PRIORITY 中）

### LLM 兜底意图识别

`llm_detect_intent(question, crude_names=None)` 函数：
- 读 `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL`
- base_url 为空直接返回 None，回退到正则
- system prompt 要求 LLM 严格返回 JSON `{"intent": "...", "crude_names": [...], "detail": "..."}`
- `intent == "other"` 或不在 INTENT_DESCRIPTIONS 中则视为未命中
