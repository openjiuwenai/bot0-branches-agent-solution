# 离线数据集评估 · 接口入参与前端设计

配套：[全量测试报告](evaluator-offline-全量测试报告.md) · [静态前端 demo](evaluator-offline-demo.html)

---

## 一、接口入参

### 提交：`POST /evaluate/dataset`（multipart）

| 部分 | 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| file | `file` | 文件 | ✅ | 数据集文件，支持 `.xlsx/.csv/.json/.jsonl`，上限 100MB |
| form | `config` | JSON blob 字符串 | ✅ | 评估配置，见下 |

### `config` 结构

```jsonc
{
  "id_field": "序号",          // 可选；留空则用行号作 case_id
  "llm_config": {              // 可选；含 llm_judge 组时必填
    "model_name": "",
    "api_key": "<token>",
    "api_base": "<endpoint>",
    "client_provider": "OpenAI",  // "OpenAI" | "ICBC"（内网端点）
    "temperature": 0.0,
    "max_tokens": 64,
    "verify_ssl": false
  },
  "groups": [                  // 必填，至少 1 组
    {
      "name": "是否属实",                // 必填；结果标签，aggregate 的键
      "kind": "exact_match",             // 必填；"exact_match" | "keyword" | "llm_judge"
      "pred_field": "是否属实_pred",     // 必填；预测列名
      "gold_field": "是否属实",          // exact_match/llm_judge 必填；keyword 留空
      "keywords": ["不属实"],            // keyword 必填至少1个；其它留空
      "json_key": "",                   // 可选；非空时 pred_field 列当 JSON 解析、按此键取值（支持 a.b 点号路径）
      "labels": ["否","是"],             // 仅 llm_judge 必填；用户声明的规范标签集合（"其他"为保留词，禁用）
      "extract_key": "是否属实",         // 仅 llm_judge 必填；需从 pred 中提取的内容，LLM 凭此把 pred 归进 labels（无自定义 prompt）
      "batch_metrics": ["mean","precision","recall","f1","accuracy"]  // 必填至少1个；合法集合见下
    }
  ]
}
```

**字段条件显隐**（前端据此切换 UI；注意 kind 与提取方式是**正交两维度**，非三选一）：

| 字段 | exact_match | keyword | llm_judge |
|---|---|---|---|
| `gold_field` | ✅ 必填 | ❌ 不用 | ✅ 必填 |
| `keywords` | ❌ 不用 | ✅ 必填≥1 | ❌ 不用 |
| `pred_field` | ✅ 必填 | ✅ 必填 | ✅ 必填 |
| `json_key`（提取方式=JSON 时） | 可选 | 可选 | 可选 |
| `labels` | ❌ 不用 | ❌ 不用 | ✅ 必填≥1 |
| `extract_key` | ❌ 不用 | ❌ 不用 | ✅ 必填 |

> **kind 三态**：`exact_match`（字符串相等）、`keyword`（命中任一关键词）、
> `llm_judge`（LLM 做分类：凭 `extract_key`（需从 pred 中提取的内容）的理解，从用户声明的
> `labels` 里选一个规范标签，**不读 gold** 防泄漏）——judge 是「分类器」而非
> 「判相等」。llm_judge 的 per-case 指标 `llm_judge`∈{0,1}（= `judged_label==gold`
> 的 correctness）；聚合用真实多类混淆矩阵 `(gold, judged_label)` 算 macro P/R/F1
> （**非退化**，只在 `labels` 上平均，排除 fallback 桶「其他」），accuracy=judged==gold
> 命中率。pred 归不进任一声明标签 → `judged_label="其他"`（诊断桶，桶率另出
> `其他_count`/`其他_rate` 键）。含 llm_judge 组时顶层必带 `llm_config`（构建 LLM
> client；ICBC 内网端点用 `client_provider:"ICBC"`）。LLM 判定在独立 judge 阶段并发
> 执行（默认 8 并发），单条 LLM 失败/不可解析降级为 `judged_label="其他"`。

> **`labels` + `extract_key` 契约（llm_judge 强制声明）**：只有「用户声明」一条路
> ——不声明 → 422；gold 列的 distinct 值必须 ⊆ `labels`（否则 422，清数据）；
> `"其他"` 是保留词，不得出现在 `labels`（422）；`extract_key`（需从 pred 中提取的内容，如
> 「是否属实」）必填（422），LLM 凭对该内容的理解把 pred 归进 `labels`。这样
> P/R/F1 对任意类数、任意 pred 形态（标签型 / 自然语言 / JSON 取值）都有意义，
> 不再靠 verdict 反推预测类。

> **两个正交维度**（都应是一等选择器，不要把 json 提取塞进"高级"）：
> - **kind**（如何评分）：exact_match / keyword / llm_judge
> - **提取方式**（pred 从哪来）：直接取列原值（`json_key=""`）/ 从 JSON 列按键提取（`json_key` 非空）
>
> 二者可组合：exact_match+raw（模式1/2）、exact_match+json（模式5）、llm_judge+raw
> （模式6）。后端也支持 keyword+json（method=`json_keyword`，如 LLM 把自由文本塞
> JSON 里再扫关键词），但实际场景 keyword 的 pred 都是裸文本列，**前端对 keyword
> 隐藏"提取方式"、强制 raw**，UI 更干净；真遇到 keyword+json 场景再补开关。故 json
> 提取**不是 kind 的第四个取值**，而是独立维度——并进 kind 会丢失组合能力。

**`batch_metrics` 合法集合**：`["mean","precision","recall","f1","accuracy"]`
（多选各勾各返回；未知名 → 422）。

### 四种典型入参模板

**模式1 · 单组精确匹配**
```json
{"id_field":"序号","groups":[{"name":"是否属实","kind":"exact_match",
  "pred_field":"是否属实_pred","gold_field":"是否属实",
  "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**模式2 · 多组精确匹配**（多组各自 (pred列, gold列)）
```json
{"id_field":"序号","groups":[
  {"name":"是否属实","kind":"exact_match","pred_field":"是否属实_pred","gold_field":"是否属实","batch_metrics":["mean","precision","recall","f1","accuracy"]},
  {"name":"是否供电公司责任","kind":"exact_match","pred_field":"是否供电公司责任_pred","gold_field":"是否供电公司责任","batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**模式3 · 关键词命中**（pred 文本是否含关键词）
```json
{"id_field":"序号","groups":[{"name":"责任判定","kind":"keyword",
  "pred_field":"责任判定文本","keywords":["不属实"],
  "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**模式5 · JSON 列提取**（多组 pred 共存于同一 JSON 列，各设 json_key）
```json
{"id_field":"序号","groups":[
  {"name":"是否属实","kind":"exact_match","pred_field":"LLM输出","json_key":"是否属实","gold_field":"是否属实","batch_metrics":["mean","precision","recall","f1","accuracy"]},
  {"name":"是否供电公司责任","kind":"exact_match","pred_field":"LLM输出","json_key":"是否供电公司责任","gold_field":"是否供电公司责任","batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**模式6 · LLM 分类判定**（LLM 凭 `extract_key` 业务理解从声明 labels 里选规范标签；含 llm_judge 组时顶层带 llm_config）
```json
{"id_field":"序号","llm_config":{"model_name":"","api_key":"<token>","api_base":"<endpoint>","client_provider":"ICBC","temperature":0.0,"max_tokens":512,"verify_ssl":false},"groups":[
  {"name":"是否属实","kind":"llm_judge","pred_field":"是否属实_pred","gold_field":"是否属实","labels":["否","是"],"extract_key":"是否属实","batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```
> 固定判定 prompt（无自定义）：「依据业务理解，从预测中提取「{extract_key}」的取值，
> 只能从下列标签中选一个：{labels}\n预测：{pred}\n只输出标签本身，不要解释。」
> `{extract_key}` 是需从 pred 中提取的内容（如「是否属实」），LLM 凭此把 pred 归进 `labels`；
> `{labels}` 渲染为逗号分隔（如「否, 是」）。**不暴露 `{gold}` 占位符**——judge
> 是分类器，不读 gold 防泄漏。`max_tokens` 默认 64 对推理模型（如 qwen3.7-max 会
> 先花 reasoning_tokens）偏紧，建议 512。

### 响应与查询

- **提交响应** `200`：`{"job_id","dataset_id","status":"queued"}`
- **轮询** `GET /evaluate/dataset/jobs/{job_id}` → `JobResponse{job_id,status,progress,result,error}`
- **SSE** `GET /evaluate/dataset/jobs/{job_id}/stream`（支持 `Last-Event-ID` 重放）
- **`status`**：`queued → running → completed | failed | cancelled`

### `result` 结构（completed 时）

```jsonc
{
  "overall": {              // 跨组综合（恒算全套，与各组 batch_metrics 勾选无关）
    "_overall": 0.777,       // 各组 composite 均值的宏平均
    "precision": 0.701,     // 各组 precision 宏平均
    "recall": 0.768,
    "f1": 0.713,
    "accuracy": 0.777       // exact_match-only 时 == _overall
  },
  "aggregate": {            // 按组名嵌套；只含该组勾选的 batch_metrics
    "是否属实": {"exact_match":0.75,"_overall":0.75,"precision":0.75,"recall":0.749,"f1":0.749,"accuracy":0.75}
    // llm_judge 组额外无条件输出 "其他_count"/"其他_rate"（LLM 归不进任一声明标签的比例）
  },
  "per_case": [             // 按 case 嵌套
    {"case_id":"270","groups":{"是否属实":{"per_metric":{"exact_match":1.0},"score":1.0}}}
  ],
  "extraction_summary": {"raw":296}   // 物化方式计数：raw/keyword/json/json_keyword
}
```

### 422 配置校验

| 用例 | detail |
|---|---|
| 未知 batch_metric | `Group 'X': unknown batch metric '...'; valid: [...]` |
| 空 groups | `Invalid config: List should have at least 1 item (too_short)` |
| keyword 组空 keywords | `Group 'X' (keyword) requires non-empty keywords` |
| exact_match/llm_judge 缺 gold_field | `Group 'X' (exact_match) requires gold_field` |
| llm_judge 组缺 labels | `Group 'X' (llm_judge) requires non-empty labels` |
| llm_judge 组声明了保留词「其他」 | `Group 'X' (llm_judge): '其他' is a reserved label, cannot be declared` |
| llm_judge 组缺 extract_key | `Group 'X' (llm_judge) requires extract_key` |
| llm_judge 组 gold 出现未声明值 | `Group 'X': gold values [...] not in declared labels [...]` |
| llm_judge 组但顶层无 llm_config | `LLM judge group requires top-level llm_config` |
| json_key 设了但首记录非 JSON | job `FAILED`：`pred cell is not valid JSON: ...` |

---

## 二、前端设计

### 页面结构：两步向导 + 结果页

```
[配置页]  ──提交──▶  [进度页]  ──completed──▶  [结果页]
 Step1 文件         (三阶段进度条)
 Step2 组配置
 Step3 提交
```

### Step 1 · 数据集上传

- 文件选择器（accept `.xlsx,.csv,.json,.jsonl`）
- `id_field` 文本框（可选，placeholder「留空用行号」）
- **上传后解析表头**：把 `pred_field`/`gold_field` 做成下拉（消除拼写 422）。
  xlsx 前端可借 SheetJS 读首行；或后端补一个轻量「读表头」接口。

### Step 2 · 评估组配置（可增删的组卡片列表）

每组一张卡片，字段按 `kind` 条件显隐：

```
┌─ 组 1 ───────────────────────────────────── ✕ ┐
│ 组名  [是否属实           ]                     │
│ 类型 kind（如何评分）                            │
│   ◉ 精确匹配 exact_match  ○ 关键词 keyword       │
│   ○ LLM判定 llm_judge                            │
│ 提取方式（pred 从哪来）                          │
│   ◉ 直接取列原值  ○ 从JSON列按键提取             │
│   [json_key         ]   ← 仅"从JSON列"时显示      │
│ 预测列 [是否属实_pred     ▾]  ← pred_field 下拉  │
│ 真值列 [是否属实           ▾]  ← gold_field(em/lj) │
│ 关键词 [不属实       ] +      ← keywords(仅kw)    │
│ 标签集 [否  ] [是  ] +       ← labels(仅lj，≥1；禁止"其他")│
│ 提取内容 [是否属实       ]   ← extract_key(仅lj，必填)    │
│ 聚合指标 ☑mean ☑precision ☑recall ☑f1 ☑accuracy │
└────────────────────────────────────────────────┘
                          [+ 添加组]
```
> 顶层（任一组为 llm_judge 时显示）：`llm_config` 表单段
> （model_name / api_key / api_base / client_provider / temperature / max_tokens / verify_ssl）。

**条件显隐规则**（对应后端 422 校验）：
- `kind=exact_match` → 显示 `gold_field`（必填）、显示"提取方式"，隐藏 `keywords`/`labels`/`extract_key`
- `kind=keyword` → 显示 `keywords`（多 tag 输入，≥1）、隐藏"提取方式"（强制 raw，
  pred 必为裸文本列），隐藏 `gold_field`/`labels`/`extract_key`
- `kind=llm_judge` → 显示 `gold_field`（必填）、显示"提取方式"、显示 `labels`
  （多 tag 输入，≥1，禁止「其他」）、显示 `extract_key`（提取内容，必填）、隐藏 `keywords`；
  **顶层 `llm_config` 表单段显示**（必填）
- `提取方式=从JSON列按键提取`（exact_match/llm_judge）→ 显示 `json_key`（必填，支持
  `a.b` 路径）；`直接取列原值` 时隐藏

> **labels tag 输入**：llm_judge 组的「标签集合」是该指标的分类空间，gold 列的
> distinct 值必须 ⊆ 它（前端上传后可读 gold 列 distinct 值预填，提示用户补齐）。
> 后端会校验 gold 出现未声明值 → 422，UI 应把 tag 输入做成醒目必填项。

### Step 3 · 提交

点"开始评估"→ 构造 multipart：`file` + `config`(JSON blob)。422 时按 `detail`
文本映射到具体组的字段红框。

### 进度页

推荐 **SSE**（`EventSource('/evaluate/dataset/jobs/{id}/stream')`），监听
`progress`/`completed`/`error`；轮询作兜底。三段进度条对应三阶段
（`data.phase` 决定高亮、`done/total` 决定填充）。`completed` 触发跳结果页。

### 结果页

1. **综合分卡片**：`overall._overall` 大字 + P/R/F1/Acc 四个小数（跨组宏平均）。
   precision<recall 时给个⚠「偏误报」提示。
2. **按组聚合表**：行=组名，列=该组勾选的指标。precision 远低于 accuracy 的组
   加⚠，点开看混淆矩阵 tooltip。
3. **提取方式分布**：`extraction_summary` 小饼图（raw/keyword/json/json_keyword）。
4. **逐条明细表**：`per_case` 分页，case_id × 各组 score，score=0 标红，点开看 per_metric。

### 设计要点

- **列名用下拉**：`pred_field`/`gold_field` 后端按精确字符串匹配，手填易拼错 → 422。
  上传后读表头做下拉能消一大半 422。
- **batch_metrics 多选**：5 个独立勾选框，对应前端"多选项"需求。
- **跨组 overall 解读**：混 keyword 组时 precision 会被退化值 1.0 抬高，UI 应提示
  "含关键词组时跨组 precision 偏高，看每组各自 precision 更准"。
