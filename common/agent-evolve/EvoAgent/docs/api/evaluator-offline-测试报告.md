# 离线数据集评估接口 · 测试报告

**日期**: 2026-07-11
**接口**: `POST /evaluate/dataset`（multipart 上传 + 异步 job + SSE）
**被测版本**: `feature/metric-evaluator` 分支，971 单测全绿，ruff/mypy strict clean
**数据集**: `/home/fjf/workspace/dataset/` 下 5 个数据集（前 4 个 `.xlsx` 各 148 条
工单；数据集5 为 markdown 包裹 JSON 的 LLM 输出列，148 条）

---

## 一、测试结论

5 种评估模式全部 `completed`，结果结构与数值均符合预期。接口的多组配置、xlsx 解析、
关键词命中指标、细粒度 `batch_metrics` 多选、跨组 `overall` 综合分、**JSON 列 pred 提取
（含 markdown/散文包裹容错）**、异步 job/SSE、422 配置校验均工作正常。

| 模式 | 数据集 | 组数 | accuracy | precision | recall | f1 | 跨组 overall（_overall）|
|---|---|---|---|---|---|---|---|
| 1 单组精确匹配 | 数据集1 | 1 | 0.750 | 0.750 | 0.749 | 0.749 | 0.750 |
| 2 多组精确匹配 | 数据集2 | 2 | 0.750 / 0.804 | 0.750 / 0.653 | 0.749 / 0.787 | 0.749 / 0.676 | **0.777** |
| 3 关键词命中 | 数据集3 | 1 | 0.520 | 1.000 | 0.520 | 0.684 | 0.520 |
| 4 混合（精确+关键词） | 数据集4 | 2 | 0.750 / 0.520 | 0.750 / 1.000 | 0.749 / 0.520 | 0.749 / 0.684 | **0.635** |
| 5 JSON 列提取 | 数据集5-json | 2 | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 | **1.000** |

> **跨组 `overall` 现含全套指标**：`_overall`（各组 composite 均值的宏平均）+
> `precision`/`recall`/`f1`/`accuracy`（各组混淆 bundle 对应项的宏平均）。后者
> 意义：模型跨多任务的平均分类表现，与各组 `batch_metrics` 勾选无关（overall 是
> 独立"全套"汇总，混淆矩阵恒算全 4 项）。例（模式2）：
> `_overall=0.777`、`precision=0.701`、`recall=0.768`、`f1=0.713`、`accuracy=0.777`。
> precision < recall → 模型跨组偏误报（FP 多于 FN），单看 `_overall` 看不出此偏置。
>
> **为什么不是"聚合成对/不对二分类"算 P/R/F1**：把所有组压成单一"对/不对"二分类
> 再算 P/R/F1 会退化——correctness 是单向的（无"应答错"的负例）→ FP=TN=0 →
> precision 恒=1、recall=accuracy=正确率=`_overall`，无信息。跨组宏平均各组**已算好的、
> 带正负结构**的真实指标则不退化（每组仍是多分类，有正负类）。exact_match-only 时
> `overall.accuracy == _overall`（因每组 `_overall == accuracy`）；混 keyword 组时
> precision 被 keyword 的退化值 1.0 抬高，需结合 `extraction_summary` 解读。
>
> 模式5 全 1.0 经独立验证为真：直接从 xlsx 逐行解析 `LLM输出` 的 JSON 与 gold 列
> 逐条比对，148 条零误判；pred 分布与 gold 分布逐位相同。该数据集的 LLM 输出系按
> gold 构造的「完美输出」，用于验证 JSON 提取链路（含 markdown 围栏容错），非测精度。
> 旁证：数据集2 的两列 pred 合成纯 JSON 列跑同一配置得 0.750/0.804（= 模式2 分列结果）。

> **为什么各指标不再全等**：exact_match 组按真实 (gold, pred) 标签值建混淆矩阵、
> 对每个标签算 per-class precision/recall/f1 再 **macro 平均**（标签等权）。
> accuracy 恒 = 命中率；precision/recall/f1 反映误报(FP)/漏报(FN)的不对称与类不平衡，
> 一般互异。例：是否供电公司责任（gold 是17/否131）accuracy=0.804 但 precision=0.653
> ——25 条"否"被误判成"是"拉低了 precision。关键词组仍用全正例二元混淆，
> precision 恒=1.0、f1=2h/(1+h)。
>
> 跨组 `overall._overall` = 各组 composite 均值的宏平均；因每组都覆盖全部 148 条，
> 它等价于各组 `_overall` 的宏平均。例：模式2 = (0.750 + 0.804) / 2 = 0.777。

---

## 二、各模式详查

### 模式 1 · 单组精确匹配（数据集1-单列模式.xlsx）

**配置**
```json
{
  "id_field": "序号",
  "groups": [{
    "name": "是否属实",
    "kind": "exact_match",
    "pred_field": "是否属实_pred",
    "gold_field": "是否属实",
    "batch_metrics": ["mean", "f1", "accuracy"]
  }]
}
```

**结果**
```json
{
  "overall": {"_overall": 0.75},
  "aggregate": {
    "是否属实": {
      "exact_match": 0.75, "_overall": 0.75,
      "precision": 0.750, "recall": 0.749, "f1": 0.749, "accuracy": 0.750
    }
  },
  "extraction_summary": {"raw": 148},
  "per_case": [148 条]
}
```

- 148 条中 111 条 pred 与 gold 完全一致 → accuracy=命中率=0.75。
- precision/recall/f1 由真实标签（是/否）混淆矩阵 macro 平均得出，因该列两类较
  均衡（gold 否77/是71，误报20、漏报17 对称），故与 accuracy 仅差千分位——这是
  数据本身的对称性使然，非指标退化。
- gold 分布：否 77 / 是 71；pred 分布：否 80 / 是 68。混淆：
  ```
        pred是  pred否
  gold是   51     20
  gold否   17     60
  ```

---

### 模式 2 · 多组精确匹配（数据集2-多列模式.xlsx）

**配置**：两组 exact_match，`batch_metrics` 全选 5 项。
```json
{
  "id_field": "序号",
  "groups": [
    {"name": "是否属实",       "kind": "exact_match", "pred_field": "是否属实_pred",       "gold_field": "是否属实",       "batch_metrics": ["mean","precision","recall","f1","accuracy"]},
    {"name": "是否供电公司责任","kind":"exact_match","pred_field":"是否供电公司责任_pred","gold_field":"是否供电公司责任","batch_metrics":["mean","precision","recall","f1","accuracy"]}
  ]
}
```

**结果**
```
是否属实         : exact_match=0.750  precision=0.750  recall=0.749  f1=0.749  accuracy=0.750
是否供电公司责任  : exact_match=0.804  precision=0.653  recall=0.787  f1=0.676  accuracy=0.804
overall:
  _overall  = 0.777   ← (0.750 + 0.804) / 2
  precision = 0.701   ← (0.750 + 0.653) / 2
  recall    = 0.768   ← (0.749 + 0.787) / 2
  f1        = 0.713   ← (0.749 + 0.676) / 2
  accuracy  = 0.777   ← = _overall（exact_match-only 恒等）
extraction_summary: {"raw": 296}   ← 148 条 × 2 组 = 296 次物化
```

- 两组独立聚合、按组名嵌套，互不干扰。`overall` 是跨组宏平均：`_overall` +
  四项混淆指标各取各组对应项的均值（overall 恒算全套，与各组 `batch_metrics`
  勾选无关）。
- **跨组 precision(0.701) < recall(0.768)** → 模型跨两任务整体偏误报（FP 多于
  FN），主要来自供电责任组的 25 条误报。单看 `_overall=0.777` 看不出此偏置，
  跨组 P/R 一平均就显出来——这正是跨组宏平均 P/R/F1 的价值。

- 两组独立聚合、按组名嵌套，互不干扰。
- 是否供电公司责任**最能体现 macro 的价值**：gold 严重不平衡（否131/是17），pred
  判了 38 条"是"（其中 25 条误报）。混淆：
  ```
          pred是  pred否
  gold是    13      4    (17)
  gold否    25    106  (131)
  ```
  - accuracy=119/148=0.804（命中率）
  - precision_是=13/38=0.342、precision_否=106/110=0.964 → macro=0.653
  - recall_是=13/17=0.765、recall_否=106/131=0.809 → macro=0.787
  - f1 macro=0.676
  - precision(0.653) ≪ accuracy(0.804)：误报把精度拉低，这正是命中率看不出的信息。

---

### 模式 3 · 关键词命中（数据集3-关键词单列.xlsx）

**配置**：pred 列 = `责任判定文本`（模型真实输出的责任判定长文本），关键词 `["不属实"]`。
```json
{
  "id_field": "序号",
  "groups": [{
    "name": "责任判定",
    "kind": "keyword",
    "pred_field": "责任判定文本",
    "keywords": ["不属实"],
    "batch_metrics": ["mean", "f1"]
  }]
}
```

**结果**
```json
{
  "overall": {"_overall": 0.5203},
  "aggregate": {
    "责任判定": {"keyword_hit": 0.5203, "_overall": 0.5203, "f1": 0.6844}
  },
  "extraction_summary": {"keyword": 148}
}
```

- 148 条 `责任判定文本` 中 77 条含"不属实" → 命中率 0.5203。
- 关键词模式所有 case 视为正例（无负例）：TP=77, FN=71, FP=TN=0。
  - recall = 77/148 = 0.5203（= 命中率）
  - precision = 1.0（无负例，恒为 1，未勾选故不返回）
  - accuracy = 0.5203
  - **f1 = 2·h/(1+h) = 2×0.5203 / 1.5203 = 0.6844** ✅ 公式验证通过
- 选 `["不属实"]` 而非 `["属实"]` 是为得到非平凡结果——`["属实"]` 会命中 100%
  （"属实"与"不属实"都含"属实"），无法体现 f1 计算。

---

### 模式 4 · 混合多组（数据集4-关键词+匹配多列.xlsx）

**配置**：一组精确匹配 + 一组关键词，各组 `batch_metrics` 独立勾选。
```json
{
  "id_field": "序号",
  "groups": [
    {"name": "是否属实", "kind": "exact_match", "pred_field": "是否属实_pred", "gold_field": "是否属实", "batch_metrics": ["mean","f1","accuracy"]},
    {"name": "责任判定","kind": "keyword",    "pred_field": "责任判定文本", "keywords": ["不属实"],         "batch_metrics": ["mean","f1"]}
  ]
}
```

**结果**
```
是否属实 : exact_match=0.750  precision=0.750  recall=0.749  f1=0.749  accuracy=0.750
责任判定 : keyword_hit=0.5203  precision=1.0  recall=0.5203  f1=0.6844  accuracy=0.5203
overall._overall : 0.635   ← (0.750 + 0.5203) / 2
extraction_summary: {"raw": 148, "keyword": 148}
```

- 两组在同一数据集上并存，exact_match 组走 `raw` 物化、keyword 组走 `keyword`
  物化，`extraction_summary` 同时记录两种方法计数。
- `per_case` 按 case 嵌套两组，样例（case_id=270）：
  ```json
  {
    "case_id": "270",
    "groups": {
      "是否属实": {"per_metric": {"exact_match": 1.0}, "score": 1.0},
      "责任判定": {"per_metric": {"keyword_hit": 1.0}, "score": 1.0}
    }
  }
  ```
  该工单 pred="否" 与 gold="否" 一致（exact_match=1），且 `责任判定文本` 含
  "不属实"（keyword_hit=1）。

---

### 模式 5 · JSON 列提取（数据集5-json.xlsx）

**场景**：两组 pred 不在两列，而塞在**同一个 JSON 列**（`LLM输出`）里。两组都指向
该列、各设不同 `json_key` 解析自己的 pred 值。gold 仍来自各自 gold 列（`是否属实` /
`是否供电公司责任`）。

**真实数据特点——markdown 包裹的 JSON**：`LLM输出` cell 不是纯 JSON，而是带散文 +
` ```json ` 围栏的文本：
```
根据工单内容与处理情况，判断结果如下：
```json
{"是否属实":"否","是否供电公司责任":"否"}
```
```
148 条全部如此。**首轮实测直接 `json.loads` 在首记录 fail-fast**（job FAILED，
"pred cell is not valid JSON"）。遂改进 `_parse_json_cell` 为三层逐级兜底：
1. 直接 `json.loads`（纯 JSON 快路径）；
2. **` ```json ... ``` ` 围栏正则提取**（精确，不受散文里杂散 `{`/`}` 干扰）；
3. 首个 `{` 到末个 `}` 子串（无围栏时的最后兜底）。

顺序刻意「围栏正则在前、`{..}` 兜底在后」：散文里若含杂散 `{杂项}` 而 JSON 在围栏内，
`{..}` 会从首个 `{` 抓到末个 `}` 取到错误子串（解析失败）；围栏正则只提围栏内内容，
正确。改进后 148/148 解析成功。

**配置**
```json
{
  "id_field": "序号",
  "groups": [
    {"name": "是否属实", "kind": "exact_match", "pred_field": "LLM输出",
     "json_key": "是否属实", "gold_field": "是否属实",
     "batch_metrics": ["mean","precision","recall","f1","accuracy"]},
    {"name": "是否供电公司责任", "kind": "exact_match", "pred_field": "LLM输出",
     "json_key": "是否供电公司责任", "gold_field": "是否供电公司责任",
     "batch_metrics": ["mean","precision","recall","f1","accuracy"]}
  ]
}
```

**结果**
```
是否属实         : exact_match=1.000  precision=1.000  recall=1.000  f1=1.000  accuracy=1.000
是否供电公司责任  : exact_match=1.000  precision=1.000  recall=1.000  f1=1.000  accuracy=1.000
overall._overall  : 1.000
extraction_summary: {"json": 296}   ← 148 条 × 2 组
per_case[0] = {"case_id":"270","groups":{"是否属实":{...1.0},"是否供电公司责任":{...1.0}}}
```

- **全 1.0 经独立验证为真**（非解析 bug）：直接从 xlsx 逐行解析 `LLM输出` 的 JSON、
  取 `是否属实`/`是否供电公司责任` 与 gold 列逐条比对，148 条**零误判**；pred 分布
  （否77/是71、否131/是17）与 gold 分布逐位相同。该数据集的 LLM 输出系按 gold 构造的
  「完美输出」，用于验证 JSON 提取链路本身，而非测分类精度。
- 旁证：用数据集2 的两列 pred 合并成纯 JSON 列（无围栏）跑同一配置，得 0.750/0.804
  （与分列模式2 逐位一致），证明提取无损、1.0 系数据使然。
- `json_key` 支持 `a.b` 点号路径取嵌套值；首记录 pred cell 完全不含 JSON 对象 →
  fail-fast（job FAILED）；某条里 key 不存在 → extracted=None → exact_match=0（逐条宽松）。
- extraction_method：`json`（exact_match+json_key）/ `json_keyword`（keyword+json_key），
  `extraction_summary` 据此统计。

---

## 三、异步 job 与 SSE

提交即返回 `{"job_id","dataset_id","status":"queued"}`，评估在后台异步执行，
通过 `GET /evaluate/dataset/jobs/{job_id}` 轮询或 `.../stream` 订阅 SSE。

**SSE 事件流样例**（模式 1，job_id=`5dfd92940438`）：
```
id: 1   event: progress   data: {"phase": "ingest", "done": 0, "total": 148}
id: 2   event: progress   data: {"phase": "ingest", "done": 10, "total": 148}
...
id: 8   event: progress   data: {"phase": "ingest", "done": 70, "total": 148}
```
三阶段：`ingest`（物化）→ `scoring`（逐条打分）→ `aggregate`（按组聚合），
完成推 `event: completed`。支持 `Last-Event-ID` 断线重放历史事件。

---

## 四、配置校验（422）

接口对配置严格校验，非法配置返回 422 并指明原因：

| 用例 | 返回 detail |
|---|---|
| 未知 batch_metric `["not_a_metric"]` | `Group 'g': unknown batch metric 'not_a_metric'; valid: ['accuracy','f1','mean','precision','recall']` |
| 空 groups 列表 | `Invalid config: List should have at least 1 item ... (too_short)` |
| keyword 组 keywords 为空 | `Group 'g' (keyword) requires non-empty keywords` |
| exact_match 组缺 gold_field | `Group 'g' (exact_match) requires gold_field` |

> `batch_metrics` 合法集合 = `["mean","precision","recall","f1","accuracy"]`，
> 前端多选各勾各返回，未知名即 422。

---

## 五、测试方法

1. 起服务：`uv run uvicorn evo_agent.api.app:create_app --factory --port 8765`
2. 用 httpx 构造 multipart（`file` + `config` JSON）提交 4 种配置。
3. 轮询 `GET /jobs/{job_id}` 到 `completed`，取 `result`。
4. 补充：SSE 流读取、4 组 422 校验用例。
5. 服务停止。

全部请求体与返回可在仓库内复现；数据集不随仓库分发（本地路径）。

---

## 六、覆盖项小结

- ✅ xlsx 解析（openpyxl，首行表头，跳全空行，丢弃无名尾列）
- ✅ 多组配置（exact_match / keyword 两种 kind）
- ✅ exact_match 裸传（绕开 dict-stringify 阻抗）
- ✅ keyword_hit 指标（命中任一词=1，否则 0）
- ✅ 细粒度 batch_metrics 多选（mean/precision/recall/f1/accuracy 各自独立）
- ✅ exact_match 混淆矩阵按真实标签 macro 平均（precision/recall/f1 互异，非退化）
- ✅ 跨组 overall 综合分（`_overall` + P/R/F1/Acc 跨组宏平均；非"对/不对"二分类退化）
- ✅ **JSON 列 pred 提取**（`json_key`，多组共享同一 JSON 列、点号路径、fail-fast、
  markdown/散文包裹容错）
- ✅ 按组嵌套 aggregate + 按 case 嵌套 per_case
- ✅ 异步 job + 三阶段进度 + SSE 断线重放
- ✅ 配置校验 422（kind / gold_field / keywords / batch_metrics 名）
