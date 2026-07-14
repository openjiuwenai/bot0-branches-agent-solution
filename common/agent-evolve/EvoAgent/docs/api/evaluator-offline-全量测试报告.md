# 离线数据集评估接口 · 全量测试报告

**日期**: 2026-07-11
**接口**: `POST /evaluate/dataset`（multipart 上传 + 异步 job + SSE）
**被测版本**: `feature/metric-evaluator` 分支；ruff/mypy strict clean；973 单测全绿
**数据集**: `/home/fjf/workspace/dataset/` 下 5 个 `.xlsx`，各 148 条工单

---

## 一、测试结论

6 种评估模式全部 `completed`。多组配置、xlsx 解析、关键词命中、细粒度 `batch_metrics`
多选、**跨组 `overall` 全套指标（`_overall` + P/R/F1/Acc 跨组宏平均）**、JSON 列 pred
提取（含 markdown/散文包裹容错）、**`llm_judge`（LLM 凭 `extract_key`（需从 pred 中提取的内容）做
分类：从用户声明 labels 选规范标签，按真实 (gold, judged_label) 多类混淆算宏平均，非退化）**、异步 job/SSE、
422 配置校验均工作正常。

| 模式 | 数据集 | 组数 | accuracy | precision | recall | f1 | 跨组 overall |
|---|---|---|---|---|---|---|---|
| 1 单组精确匹配 | 数据集1 | 1 | 0.750 | 0.750 | 0.749 | 0.749 | 0.750 |
| 2 多组精确匹配 | 数据集2 | 2 | 0.750 / 0.804 | 0.750 / 0.653 | 0.749 / 0.787 | 0.749 / 0.676 | **0.777** |
| 3 关键词命中 | 数据集3 | 1 | 0.520 | 1.000 | 0.520 | 0.684 | 0.520 |
| 4 混合（精确+关键词） | 数据集4 | 2 | 0.750 / 0.520 | 0.750 / 1.000 | 0.749 / 0.520 | 0.749 / 0.684 | **0.635** |
| 5 JSON 列提取 | 数据集5-json | 2 | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 | 1.000 / 1.000 | **1.000** |
| 6 LLM 判定（桩） | 构造4条 | 1 | 0.750 | 0.833 | 0.750 | 0.733 | **0.750** |

> **跨组 `overall` 现含全套指标**（本轮新实现并验证）：
> `_overall`（各组 composite 均值的宏平均）+ `precision`/`recall`/`f1`/`accuracy`
> （各组混淆 bundle 对应项的宏平均）。后者意义：模型跨多任务的平均分类表现，
> 与各组 `batch_metrics` 勾选无关（overall 是独立"全套"汇总，混淆矩阵恒算全 4 项）。
>
> **关键区分**：跨组宏平均**不退化**——平均的是各组"已算好的、带正负结构"的真实
> 指标；而把所有组压成单一"对/不对"二分类再算 P/R/F1 会退化（correctness 单向、
> 无负例 → precision 恒=1）。本接口采用前者。
>
> - exact_match-only（模式1/2/5）：`overall.accuracy == _overall`。
> - 混 keyword 组（模式3/4）：`overall.precision` 被 keyword 的退化值 1.0 抬高
>   （模式4 precision=0.875，含一个 keyword 组的 1.0），需结合 `extraction_summary` 解读。

> **模式5 全 1.0 经独立验证为真**：148 条 `LLM输出`（markdown 围栏包裹 JSON）逐行
> 解析取键、与 gold 列逐条比对，零误判；pred 分布与 gold 分布逐位相同。该数据集
> LLM 输出系按 gold 构造的「完美输出」，用于验证 JSON 提取链路本身，非测分类精度。

---

## 二、各模式详查

### 模式 1 · 单组精确匹配（数据集1-单列模式.xlsx）

**配置**：1 组 exact_match，全选 5 指标。
```json
{"id_field":"序号","groups":[{"name":"是否属实","kind":"exact_match",
  "pred_field":"是否属实_pred","gold_field":"是否属实",
  "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**结果**
```
aggregate[是否属实]: exact_match=0.750  precision=0.750  recall=0.749  f1=0.749  accuracy=0.750
overall:            _overall=0.750  precision=0.750  recall=0.749  f1=0.749  accuracy=0.750
extraction_summary: {"raw": 148}     per_case: 148 条
```
- 148 条中 111 条 pred 与 gold 一致 → accuracy=0.75。
- gold 否77/是71 较均衡，误报20、漏报17 近对称 → P/R/F1 与 accuracy 仅差千分位。
- 单组时 overall == 该组 aggregate（只有一组，宏平均=自身）。

---

### 模式 2 · 多组精确匹配（数据集2-多列模式.xlsx）

**配置**：2 组 exact_match（是否属实 / 是否供电公司责任），全选 5 指标。
```json
{"id_field":"序号","groups":[
  {"name":"是否属实","kind":"exact_match","pred_field":"是否属实_pred","gold_field":"是否属实",
   "batch_metrics":["mean","precision","recall","f1","accuracy"]},
  {"name":"是否供电公司责任","kind":"exact_match","pred_field":"是否供电公司责任_pred","gold_field":"是否供电公司责任",
   "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
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
extraction_summary: {"raw": 296}     per_case: 148 条
```
- **跨组 precision(0.701) < recall(0.768)** → 模型跨两任务偏误报（FP 多于 FN），
  主要来自供电责任组的 25 条误报。单看 `_overall=0.777` 看不出此偏置，跨组 P/R
  一平均就显出来——这正是跨组宏平均 P/R/F1 的价值。
- 供电责任组**最能体现 macro 的价值**：gold 严重不平衡（否131/是17），pred 判了 38 条
  "是"（其中 25 条误报）。accuracy=0.804 但 precision=0.653——误报把精度拉低，
  这正是命中率看不出的信息。

---

### 模式 3 · 关键词命中（数据集3-关键词单列.xlsx）

**配置**：1 组 keyword，pred 列=`责任判定文本`，关键词 `["不属实"]`。
```json
{"id_field":"序号","groups":[{"name":"责任判定","kind":"keyword",
  "pred_field":"责任判定文本","keywords":["不属实"],
  "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**结果**
```
aggregate[责任判定]: keyword_hit=0.520  precision=1.000  recall=0.520  f1=0.684  accuracy=0.520
overall:            _overall=0.520  precision=1.000  recall=0.520  f1=0.684  accuracy=0.520
extraction_summary: {"keyword": 148}
```
- 148 条 `责任判定文本` 中 77 条含"不属实" → 命中率 0.520。
- keyword 组所有 case 视为正例（无负例）：TP=77, FN=71, FP=TN=0 →
  recall=accuracy=命中率=0.520，precision=1.0（退化），**f1=2·h/(1+h)=0.684** ✅
- 选 `["不属实"]` 而非 `["属实"]`：后者会 100% 命中（"属实"与"不属实"都含"属实"），
  无法体现 f1 计算。

---

### 模式 4 · 混合多组（数据集4-关键词+匹配多列.xlsx）

**配置**：1 组 exact_match + 1 组 keyword，各组 `batch_metrics` 独立。
```json
{"id_field":"序号","groups":[
  {"name":"是否属实","kind":"exact_match","pred_field":"是否属实_pred","gold_field":"是否属实",
   "batch_metrics":["mean","precision","recall","f1","accuracy"]},
  {"name":"责任判定","kind":"keyword","pred_field":"责任判定文本","keywords":["不属实"],
   "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**结果**
```
是否属实 : exact_match=0.750  precision=0.750  recall=0.749  f1=0.749  accuracy=0.750
责任判定 : keyword_hit=0.520  precision=1.000  recall=0.520  f1=0.684  accuracy=0.520
overall:
  _overall  = 0.635   ← (0.750 + 0.520) / 2
  precision = 0.875   ← (0.750 + 1.000) / 2  ⚠ 含 keyword 组退化值 1.0，被抬高
  recall    = 0.635   ← (0.749 + 0.520) / 2
  f1        = 0.717   ← (0.749 + 0.684) / 2
  accuracy  = 0.635   ← = _overall
extraction_summary: {"raw": 148, "keyword": 148}
```
- 两组并存：exact_match 组走 `raw` 物化、keyword 组走 `keyword` 物化，
  `extraction_summary` 同时记录两种方法计数。
- **跨组 precision=0.875 被 keyword 组的退化 1.0 抬高**——这是混 keyword 组的固有
  现象（无负例 → precision 恒=1）。解读时应看每组各自 precision，而非盲信跨组均值。

---

### 模式 5 · JSON 列提取（数据集5-json.xlsx）

**场景**：两组 pred 不在两列，而塞在**同一个 JSON 列**（`LLM输出`）里。两组都指向
该列、各设不同 `json_key` 解析自己的 pred 值。gold 仍来自各自 gold 列。

**真实数据特点——markdown 包裹的 JSON**：`LLM输出` cell 是带散文 + ` ```json ` 围栏的文本：
```
根据工单内容与处理情况，判断结果如下：
```json
{"是否属实":"否","是否供电公司责任":"否"}
```
```
148 条全部如此。`_parse_json_cell` 三层逐级兜底：① 直接 `json.loads` ② ` ```json ` 围栏
正则 ③ 首个 `{` 到末个 `}`。围栏正则在前、`{..}` 兜底在后（散文里杂散 `{杂项}` 会让
`{..}` 抓错子串）。148/148 解析成功。

**配置**
```json
{"id_field":"序号","groups":[
  {"name":"是否属实","kind":"exact_match","pred_field":"LLM输出",
   "json_key":"是否属实","gold_field":"是否属实",
   "batch_metrics":["mean","precision","recall","f1","accuracy"]},
  {"name":"是否供电公司责任","kind":"exact_match","pred_field":"LLM输出",
   "json_key":"是否供电公司责任","gold_field":"是否供电公司责任",
   "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**结果**
```
是否属实         : exact_match=1.000  precision=1.000  recall=1.000  f1=1.000  accuracy=1.000
是否供电公司责任  : exact_match=1.000  precision=1.000  recall=1.000  f1=1.000  accuracy=1.000
overall: _overall=1.000  precision=1.000  recall=1.000  f1=1.000  accuracy=1.000
extraction_summary: {"json": 296}     ← 148 条 × 2 组
```
- **全 1.0 经独立验证为真**（非解析 bug）：直接逐行解析 JSON 取键与 gold 比对，148 条
  零误判；pred 分布与 gold 分布逐位相同。数据集 LLM 输出系按 gold 构造的「完美输出」。
- 旁证：数据集2 两列 pred 合成纯 JSON 列（无围栏）跑同配置得 0.750/0.804（= 模式2 分列），
  证明提取无损、1.0 系数据使然。
- `json_key` 支持 `a.b` 点号路径取嵌套值；首记录 pred cell 不含 JSON 对象 → fail-fast
  （job FAILED）；某条 key 缺失 → extracted=None → exact_match=0（逐条宽松）。

---

### 模式 6 · LLM 分类判定（构造 4 条 + LLM 桩）

**场景**：pred 与 gold 是文本列，但「相等」不该用字符串 `==`——模型说「不是」、gold
是「否」，语义一致却字符串不等。**judge 是分类器**：LLM 凭 `extract_key`（需从 pred 中提取的内容，
如「是否属实」）的业务理解，从用户声明的 `labels` 里选一个规范标签（**不读 gold** 防泄漏
——固定 prompt，无自定义判定 prompt），pred 归不进任一声明标签 → 保留词「其他」。
scorer 用真实多类混淆矩阵 `(gold, judged_label)` 算 macro P/R/F1（只在 `labels` 上
平均，排除「其他」桶），accuracy=judged==gold 命中率。**非退化**。

**构造数据**（4 条，gold 否/否/是/是，pred 含语义一致但字符串不同的样例）：
```
case1 gold=否 pred="否"    case2 gold=否 pred="不是"   ← 语义一致字符串不同
case3 gold=是 pred="否"    case4 gold=是 pred="是"
```
**LLM 桩**：用确定性假 Model 依次返回标签文本 `否/否/否/是`（替代真实 LLM；真实跑只需
顶层 `llm_config` 填 ICBC/OpenAI 凭证，链路同）。本模式经路由 e2e + 单测验证。

**配置**
```json
{"id_field":"id","llm_config":{"api_key":"<token>","api_base":"<endpoint>","client_provider":"ICBC"},
 "groups":[{"name":"是否属实","kind":"llm_judge","pred_field":"pred","gold_field":"gold",
 "labels":["否","是"],"extract_key":"是否属实",
 "batch_metrics":["mean","precision","recall","f1","accuracy"]}]}
```

**结果**（judged_label 否/否/否/是）
```
aggregate[是否属实]: llm_judge=0.750  _overall=0.750  precision=0.833  recall=0.750  f1=0.733  accuracy=0.750  其他_count=0  其他_rate=0.0
overall:            _overall=0.750  precision=0.833  recall=0.750  f1=0.733  accuracy=0.750
extraction_summary: {"raw": 4}     ← llm_judge 物化与 exact_match 同（raw），无独立 method
```
- **accuracy=0.75 = judged==gold 命中率**（非字符串命中率 0.5）——case2「否/不是」字符串
  不等但 LLM 判成「否」==gold「否」，accuracy 计入正确。这正是 LLM 判定相对 exact_match
  的价值。
- **precision 非退化（0.833≠1.0）**：预测标签=LLM 选出的 `judged_label`（不是 verdict
  反推的 pred 文本）。否: tp2 fp1 fn0 → P=2/3 R=1.0；是: tp1 fp0 fn1 → P=1.0 R=0.5；
  macro P=(2/3+1)/2≈0.833、R=(1+0.5)/2=0.75、F1=(0.8+2/3)/2≈0.733。**与 keyword 不同，
  不退化成 precision=1.0**——判错（judged≠gold）的 case 提供了真实负例。
- **case2 关键验证**：pred「不是」字符串≠gold「否」，但 LLM 选出 `judged_label=否`
  → 计入「否」的 TP 而非「不是」标签的 FP。若用字符串混淆（exact_match 路径），case2
  会变成单独的「不是」标签、recall 偏低——LLM 分类把它正确折叠为命中。
- **「其他」诊断桶**：LLM 归不进任一声明标签的 case 计入 `judged_label="其他"`，不进
  macro 分母，桶率另出 `其他_count`/`其他_rate`（本例 0，全归进了）。
- 进度四阶段：`ingest → judge → scoring → aggregate → completed`（judge 阶段 8 并发）。

---

## 三、异步 job 与 SSE

提交即返回 `{"job_id","dataset_id","status":"queued"}`，评估在后台异步执行，通过
`GET /evaluate/dataset/jobs/{job_id}` 轮询或 `.../stream` 订阅 SSE。

四阶段进度事件：`ingest`（物化 N×M）→ `judge`（llm_judge 组并发调 LLM，仅含该 kind 组时出现）→
`scoring`（逐条打分）→ `aggregate`（按组聚合），完成推 `event: completed`。支持
`Last-Event-ID` 断线重放。本轮 6 模式均 completed，无 failed/cancelled。

---

## 四、配置校验（422）

| 用例 | 返回 detail |
|---|---|
| 未知 batch_metric `["not_a_metric"]` | `Group 'g': unknown batch metric 'not_a_metric'; valid: ['accuracy','f1','mean','precision','recall']` |
| 空 groups 列表 | `Invalid config: List should have at least 1 item ... (too_short)` |
| keyword 组 keywords 为空 | `Group 'g' (keyword) requires non-empty keywords` |
| exact_match 组缺 gold_field | `Group 'g' (exact_match) requires gold_field` |
| llm_judge 组缺 gold_field | `Group 'g' (llm_judge) requires gold_field` |
| llm_judge 组缺 labels | `Group 'g' (llm_judge) requires non-empty labels` |
| llm_judge 组声明了保留词「其他」 | `Group 'g' (llm_judge): '其他' is a reserved label, cannot be declared` |
| llm_judge 组缺 extract_key | `Group 'g' (llm_judge) requires extract_key` |
| llm_judge 组 gold 出现未声明值 | `Group 'g': gold values ['未知'] not in declared labels ['否','是']` |
| llm_judge 组但顶层无 llm_config | `LLM judge group requires top-level llm_config` |

> `batch_metrics` 合法集合 = `["mean","precision","recall","f1","accuracy"]`，
> 前端多选各勾各返回，未知名即 422。

---

## 五、覆盖项小结

- ✅ xlsx 解析（openpyxl，首行表头，跳全空行，丢弃无名尾列）
- ✅ 多组配置（exact_match / keyword / llm_judge 三种 kind）
- ✅ exact_match 裸传（绕开 dict-stringify 阻抗）
- ✅ keyword_hit 指标（命中任一词=1，否则 0）
- ✅ **llm_judge 指标**（LLM 做分类：凭 `extract_key`（需从 pred 中提取的内容）的理解从声明 labels
  选规范标签、不读 gold；固定 prompt 无自定义；judge 阶段并发调 LLM，judged_label 经
  side-channel 写回 materialized 并注入 per-case；单条 LLM 失败/不可解析降级为「其他」不阻断）
- ✅ exact_match / llm_judge 混淆矩阵按真实标签 macro 平均（precision/recall/f1
  互异，非退化；llm_judge 用真实 (gold, judged_label) 建多类矩阵，「其他」桶另出诊断键）
- ✅ **llm_judge labels + extract_key 强制声明 + 校验**（labels 非空 / extract_key 非空 /
  gold⊆labels / 「其他」保留词，均硬 422；符合「只有声明，其余报错」契约）
- ✅ 细粒度 batch_metrics 多选（mean/precision/recall/f1/accuracy 各自独立）
- ✅ **跨组 overall 全套**（`_overall` + P/R/F1/Acc 跨组宏平均，非"对/不对"二分类退化）
- ✅ JSON 列 pred 提取（`json_key`，多组共享同一 JSON 列、点号路径、fail-fast、
  markdown/散文包裹三层容错）
- ✅ 按组嵌套 aggregate + 按 case 嵌套 per_case
- ✅ 异步 job + 四阶段进度（ingest→judge→scoring→aggregate）+ SSE 断线重放
- ✅ 配置校验 422（kind / gold_field / keywords / batch_metrics 名 / llm_judge 缺 extract_key / llm_judge 缺 llm_config）

---

## 六、相关交付物

- **接口入参与前端设计**：见同目录 `evaluator-offline-接口与前端设计.md`
- **静态前端 demo**：见同目录 `evaluator-offline-demo.html`（浏览器直开；可连真实 API，
  也可用内置样例数据离线演示）
