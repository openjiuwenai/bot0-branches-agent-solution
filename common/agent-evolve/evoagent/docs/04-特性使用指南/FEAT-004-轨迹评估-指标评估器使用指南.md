# 轨迹评估-指标评估器使用指南

> 本指南介绍如何通过 **轨迹评估-指标评估器** 上传数据集，执行精确匹配、关键词命中或 LLM指标信息提取，并查询逐条分数和汇总指标。  

---

## 1. 特性概览

### 1.1 这是什么

> **轨迹评估-指标评估器** 通过“上传数据集 → 创建异步任务 → 逐条评分 → 按组汇总”的方式，计算准确率、精确率、召回率、F1 等指标，适用于批量检查 Agent 或模型输出质量。

> 本特性只做数据集批量打分与汇总，不触发 Agent rollout，也不选择优化算法。

一次请求可以配置一个或多个评估组。每个组独立指定预测字段、标准答案字段、评估模式和需要返回的汇总指标。

接口采用异步任务模式：

1. 调用 `POST /evaluate/dataset` 上传数据集并取得 `job_id`；
2. 调用 `GET /evaluate/dataset/jobs/{job_id}` 查询进度和最终结果；
3. 如需实时进度，调用 `GET /evaluate/dataset/jobs/{job_id}/stream` 建立 SSE 连接。

### 1.2 本指南覆盖范围

本指南只包含以下接口：

- `POST /evaluate/dataset`：上传数据集并提交评估任务；
- `GET /evaluate/dataset/jobs/{job_id}`：查询任务状态、进度和结果；
- `GET /evaluate/dataset/jobs/{job_id}/stream`：通过 SSE 接收任务进度。

本指南不包含 SDK、类或函数的调用方法，也不包含训练、优化和数据生成接口。

---

## 2. 什么时候使用

| 使用本 API | 不使用本 API |
|---|---|
| 已有 JSON、JSONL、CSV 或 XLSX 数据集 | 只有一段对话轨迹，不需要批量统计 |
| 数据中有预测字段和标准答案字段 | 没有标准答案，也没有可检查的关键词 |
| 需要一次评估多个字段或任务 | 只需要人工查看少量结果 |
| 需要准确率、精确率、召回率或 F1 | 需要训练或修改 Agent，而不是评估结果 |

> 判断原则：需要对一批结构化记录进行逐条评分和整体统计时，使用 `/evaluate/dataset`。

---

## 3. 准备工作

### 3.1 获取必要信息

开始前，请确认以下信息：

- **数据字段**：确认数据集中的记录标识字段、预测字段和标准答案字段。它们需要分别对应配置中的 `id_field`、`pred_field` 和 `gold_field`。
- **评估方式**：确定使用 `exact_match`、`keyword` 或 `llm_judge`。只有使用 `llm_judge` 时，才需要准备模型名称、API Key、API 地址和服务商等 `llm_config` 信息。

### 3.2 检查服务

如果服务尚未启动，参考部署指南部署evoagent。

服务检查这里使用 8000 端口，以便与下文示例保持一致：
```bash
BASE_URL='http://127.0.0.1:8000'
curl -fsS "$BASE_URL/health"
```

服务正常时返回：

```json
{"status":"ok"}
```

继续检查数据集评估接口是否已经注册：

```bash
curl -fsS "$BASE_URL/openapi.json" \
  | python -c 'import json,sys; paths=json.load(sys.stdin).get("paths",{}); assert "/evaluate/dataset" in paths, "未发现 /evaluate/dataset"; print("数据集评估接口可用")'
```

### 3.3 准备数据集

接口支持 JSON、JSONL、CSV 和 XLSX 文件，单次上传大小不能超过 100 MB。数据集不能为空，每条记录应包含配置所引用的预测字段；`exact_match` 和 `llm_judge` 记录还应包含标准答案字段。建议提供不重复且非空的记录标识。

创建 `dataset.json`：

```json
[
  {
    "id": "1",
    "gold": "Paris",
    "pred": "Paris"
  },
  {
    "id": "2",
    "gold": "London",
    "pred": "Paris"
  }
]
```

创建 `config.json`：

```json
{
  "id_field": "id",
  "groups": [
    {
      "name": "城市匹配",
      "kind": "exact_match",
      "pred_field": "pred",
      "gold_field": "gold",
      "batch_metrics": [
        "mean",
        "precision",
        "recall",
        "f1",
        "accuracy"
      ]
    }
  ]
}
```

准备完成后，确认以下事项：

- `id_field`、`pred_field` 和 `gold_field` 与数据集中的字段名完全一致；
- 每个评估组使用不同且含义清晰的 `name`；
- `groups` 至少包含一个评估组，`batch_metrics` 至少包含一个合法指标；
- 当前示例使用 `exact_match`，因此不需要配置 `llm_config`。

可在提交前检查两个 JSON 文件的语法：

```bash
python -m json.tool dataset.json > /dev/null
python -m json.tool config.json > /dev/null
```

---

## 4. 快速上手

本节使用 `exact_match` 完成一个最小闭环。

### 4.1 提交评估任务

以下命令适用于Linux：

```bash
curl -sS -X POST 'http://127.0.0.1:8000/evaluate/dataset' \
  -F 'file=@dataset.json;type=application/json' \
  -F "config=$(tr -d '\r\n' < config.json)"
```

成功时返回：

```json
{
  "job_id": "0d14263cd66341e5",
  "dataset_id": "5e9e7431fe234eb4",
  "status": "queued"
}
```

请保存返回的 `job_id`。

### 4.2 查询评估结果

将 `{JOB_ID}` 替换为真实任务 ID：

```bash
curl -sS 'http://127.0.0.1:8000/evaluate/dataset/jobs/{JOB_ID}' \
  | python -m json.tool
```

任务完成后，`status` 为 `completed`：

```json
{
  "job_id": "0d14263cd66341e5",
  "status": "completed",
  "progress": {
    "phase": "aggregate",
    "done": 1,
    "total": 1
  },
  "result": {
    "per_case": [
      {
        "case_id": "1",
        "groups": {
          "城市匹配": {
            "per_metric": {
              "exact_match": 1.0
            },
            "score": 1.0
          }
        }
      },
      {
        "case_id": "2",
        "groups": {
          "城市匹配": {
            "per_metric": {
              "exact_match": 0.0
            },
            "score": 0.0
          }
        }
      }
    ],
    "aggregate": {
      "城市匹配": {
        "exact_match": 0.5,
        "_overall": 0.5,
        "precision": 0.25,
        "recall": 0.5,
        "f1": 0.3333333333333333,
        "accuracy": 0.5
      }
    },
    "overall": {
      "_overall": 0.5,
      "precision": 0.25,
      "recall": 0.5,
      "f1": 0.3333333333333333,
      "accuracy": 0.5
    },
    "extraction_summary": {
      "raw": 2
    }
  },
  "error": null
}
```

> `queued` 只代表任务已接收。必须继续查询，直到状态变为 `completed`、`failed`。

---

## 5. 接口与配置

### 5.1 接口清单

| 方法 | 路径 | 作用 | 返回方式 |
|:--:|---|---|---|
| `POST` | `/evaluate/dataset` | 上传数据集并提交评估任务 | JSON |
| `GET` | `/evaluate/dataset/jobs/{job_id}` | 查询状态、进度和最终结果 | JSON |
| `GET` | `/evaluate/dataset/jobs/{job_id}/stream` | 实时接收进度和终态事件 | SSE |

### 5.2 提交评估任务

#### 请求格式

```text
POST /evaluate/dataset
Content-Type: multipart/form-data
```

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `file` | 文件 | 是 | 无 | 待评估数据集，最大 `100 MB` |
| `config` | 字符串 | 是 | 无 | JSON 字符串形式的评估配置 |

支持的数据集格式：

| 格式 | 数据要求 |
|---|---|
| JSON | 一个对象或对象数组 |
| JSONL | 每个非空行必须是一个 JSON 对象 |
| CSV | 第一行为表头，至少包含一条数据记录 |
| XLSX | 读取第一个工作表，第一行为表头 |

> `config` 是普通表单字符串，不是配置文件上传字段。

#### `config`字段配置

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `id_field` | 字符串 | 否 | `""` | 样本 ID 字段；为空或值缺失时使用从 `0` 开始的行号 |
| `groups` | 对象数组 | 是 | 无 | 评估组，至少包含一组 |
| `llm_config` | 对象或 `null` | 条件必填 | `null` | 存在 `llm_judge` 组时必填 |

#### `groups` 组字段配置

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `name` | 字符串 | 是 | 无 | 组名，也是结果 `aggregate` 中的键；建议保持唯一 |
| `kind` | 字符串 | 是 | 无 | `exact_match`、`keyword` 或 `llm_judge` |
| `pred_field` | 字符串 | 是 | 无 | 数据集中的预测结果字段 |
| `gold_field` | 字符串 | 条件必填 | `""` | `exact_match` 和 `llm_judge` 必填 |
| `keywords` | 字符串数组 | 条件必填 | `[]` | `keyword` 必填，至少一个关键词 |
| `json_key` | 字符串 | 否 | `""` | 从预测字段的 JSON 对象中取值，支持 `a.b` 点号路径 |
| `labels` | 字符串数组 | 条件必填 | `[]` | `llm_judge` 必填，不得包含保留标签 `其他` |
| `extract_key` | 字符串 | 条件必填 | `""` | `llm_judge` 必填，用于说明需要判定的内容 |
| `batch_metrics` | 字符串数组 | 否 | 全部五项 | 需要返回的组汇总指标，至少包含一项 |

`batch_metrics` 只接受以下五个值：

| 值 | 含义 |
|---|---|
| `mean` | 返回逐条指标平均值和 `_overall` |
| `precision` | 返回宏平均精确率 |
| `recall` | 返回宏平均召回率 |
| `f1` | 返回宏平均 F1 |
| `accuracy` | 返回准确率或命中率 |

未填写 `batch_metrics` 时，默认使用全部五项。传入空数组会返回 `422`。

#### `llm_config`字段配置

仅 `llm_judge` 使用。

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|:--:|---|---|
| `model_name` | 字符串 | 否 | `""` | 模型名称 |
| `api_key` | 字符串 | 是 | 无 | 模型服务 API Key |
| `api_base` | 字符串 | 是 | 无 | 模型服务地址 |
| `client_provider` | 字符串 | 否 | `OpenAI` | 模型客户端类型；自定义 SSE 客户端可使用 `CustomSSE` |
| `temperature` | 浮点数 | 否 | `0.0` | 采样温度 |
| `max_tokens` | 整数 | 否 | `64` | 单次判定最大输出长度 |
| `verify_ssl` | 布尔值 | 否 | `false` | 是否校验 HTTPS 证书 |
| `extra_body` | 对象或 `null` | 否 | `null` | 传给 OpenAI 兼容接口的厂商扩展参数 |

#### 提交响应

| 字段 | 类型 | 说明 |
|---|---|---|
| `job_id` | 字符串 | 异步评估任务 ID |
| `dataset_id` | 字符串 | 本次上传数据集的标识 |
| `status` | 字符串 | 提交成功时通常为 `queued` |

### 5.3 查询任务

```text
GET /evaluate/dataset/jobs/{job_id}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `job_id` | 字符串 | 任务 ID |
| `status` | 字符串 | `queued`、`running`、`completed`、`failed`  |
| `progress` | 对象或 `null` | 最近一次进度，包含 `phase`、`done`、`total` |
| `result` | 对象或 `null` | 完成后返回评估结果 |
| `error` | 字符串或 `null` | 后台任务失败时返回错误信息 |

`progress.phase` 可能为：

| 阶段 | 说明 |
|---|---|
| `ingest` | 读取记录并按评估组整理数据 |
| `judge` | 使用 LLM 分类，仅 `llm_judge` 出现 |
| `scoring` | 计算每条记录的分数 |
| `aggregate` | 汇总各组和整体指标 |

#### `result` 结构

| 字段 | 类型 | 说明 |
|---|---|---|
| `per_case` | 数组 | 每条记录在各组下的 `per_metric` 和 `score` |
| `aggregate` | 对象 | 按组汇总，键为组名 |
| `overall` | 对象 | 多组之间的宏平均汇总 |
| `extraction_summary` | 对象 | `raw`、`json`、`keyword`、`json_keyword` 等取值方式的计数 |

不同模式使用不同的逐条指标名：

| `kind` | 指标名 | 得分规则 |
|---|---|---|
| `exact_match` | `exact_match` | 预测值与标准值精确相等为 `1.0`，否则为 `0.0` |
| `keyword` | `keyword_hit` | 命中任意关键词为 `1.0`，否则为 `0.0` |
| `llm_judge` | `llm_judge` | LLM 分类标签与标准标签相同为 `1.0`，否则为 `0.0` |

> `aggregate` 只返回该组在 `batch_metrics` 中选择的指标；`overall` 始终返回 `_overall`、`precision`、`recall`、`f1` 和 `accuracy`。

### 5.4 SSE 进度流

```bash
curl -N \
  -H 'Accept: text/event-stream' \
  'http://127.0.0.1:8000/evaluate/dataset/jobs/{JOB_ID}/stream'
```

返回示例：

```text
id: 1
event: progress
data: {"phase": "ingest", "done": 0, "total": 2}

id: 2
event: progress
data: {"phase": "ingest", "done": 2, "total": 2}

id: 7
event: completed
data: {"status": "completed"}
```

| `event` | `data` | 说明 |
|---|---|---|
| `progress` | `phase`、`done`、`total` | 阶段进度 |
| `completed` | `status: completed` | 任务完成 |
| `error` | `status: failed`、`error` | 任务失败 |

连接长时间没有新事件时，服务每 30 秒发送一次 `: keepalive`。断线重连时可传递整数形式的 `Last-Event-ID`：

```bash
curl -N \
  -H 'Accept: text/event-stream' \
  -H 'Last-Event-ID: 2' \
  'http://127.0.0.1:8000/evaluate/dataset/jobs/{JOB_ID}/stream'
```

> `completed` 事件不携带评估结果。收到终态事件后，仍需调用任务查询接口读取 `result` 或 `error`。

### 5.5 状态码与异常

| 状态码或现象 | 含义 | 常见原因 | 处理方式 |
|---|---|---|---|
| `200` | 请求成功 | 任务已提交、查询成功或 SSE 已连接 | 根据返回的 `status` 继续处理 |
| `404` | 任务不存在 | `job_id` 错误，或服务重启后任务已丢失 | 检查任务 ID，必要时重新提交 |
| `413` | 上传文件过大 | 文件超过 `104857600` 字节（100 MB） | 拆分为多个数据集 |
| `422` | 请求或数据校验失败 | 配置 JSON 错误、组为空、字段配置缺失、指标名错误 | 查看响应中的 `detail` |
| `500` | 提交阶段模型探测失败 | `llm_judge` 的 API Key、地址或模型服务不可用 | 检查 `llm_config` 和模型服务 |
| HTTP `200`，但 `status=failed` | 后台评估失败 | 数据列缺失、JSON 预测字段无法解析或运行阶段异常 | 查看任务响应中的 `error` |

错误响应通常为：

```json
{
  "detail": "具体错误原因"
}
```

> 任务保存在当前服务进程的内存中。服务重启后，旧 `job_id` 无法继续查询。

---

## 6. 场景用例

### 6.1 关键词命中

适用于：判断预测文本是否包含指定关键词中的任意一个。

数据集 `keyword.json`：

```json
[
  {
    "id": "1",
    "answer": "经核查，该诉求属实"
  },
  {
    "id": "2",
    "answer": "暂未发现相关情况"
  }
]
```

提交请求：

```bash
curl -sS -X POST 'http://127.0.0.1:8000/evaluate/dataset' \
  -F 'file=@keyword.json;type=application/json' \
  -F 'config={
    "id_field":"id",
    "groups":[{
      "name":"责任关键词",
      "kind":"keyword",
      "pred_field":"answer",
      "keywords":["属实","供电公司责任"],
      "batch_metrics":["mean","precision","recall","f1","accuracy"]
    }]
  }'
```

本例只有第一条命中，因此 `keyword_hit` 平均值和 `accuracy` 都是 `0.5`。

> 关键词匹配区分大小写，并采用“命中任意一个即通过”的规则。

### 6.2 多组评估

适用于：一个数据集同时评估多个预测字段。

```json
{
  "id_field": "id",
  "groups": [
    {
      "name": "事实判断",
      "kind": "exact_match",
      "pred_field": "fact_pred",
      "gold_field": "fact_gold",
      "batch_metrics": [
        "mean",
        "accuracy"
      ]
    },
    {
      "name": "责任判断",
      "kind": "exact_match",
      "pred_field": "responsibility_pred",
      "gold_field": "responsibility_gold",
      "batch_metrics": [
        "mean",
        "accuracy"
      ]
    }
  ]
}
```

结果中的 `aggregate` 会分别包含 `事实判断` 和 `责任判断`，`overall` 给出跨组宏平均结果。

### 6.3 从 JSON 字段中取值

适用于：多个预测结果保存在同一个 JSON 字符串字段中。

数据记录：

```json
{
  "id": "1",
  "fact_gold": "是",
  "result": "{\"judgement\":{\"fact\":\"是\"}}"
}
```

对应组配置：

```json
{
  "name": "事实判断",
  "kind": "exact_match",
  "pred_field": "result",
  "json_key": "judgement.fact",
  "gold_field": "fact_gold",
  "batch_metrics": [
    "mean",
    "accuracy"
  ]
}
```

`json_key` 非空时，`pred_field` 的值必须能够解析为 JSON 对象。

### 6.4 LLM 分类评估

适用于：预测内容是自然语言，需要模型先把它归类为声明的标签。

```json
{
  "id_field": "id",
  "groups": [
    {
      "name": "是否属实",
      "kind": "llm_judge",
      "pred_field": "answer",
      "gold_field": "gold",
      "labels": [
        "是",
        "否"
      ],
      "extract_key": "是否属实",
      "batch_metrics": [
        "mean",
        "precision",
        "recall",
        "f1",
        "accuracy"
      ]
    }
  ],
  "llm_config": {
    "model_name": "{MODEL_NAME}",
    "api_key": "{API_KEY}",
    "api_base": "{API_BASE}",
    "client_provider": "OpenAI",
    "temperature": 0.0,
    "max_tokens": 64,
    "verify_ssl": false
  }
}
```

注意事项：

- `gold_field` 中的每个值都必须在 `labels` 中；
- `labels` 不能主动声明保留值 `其他`；
- 模型输出无法归入声明标签，或某条模型调用失败时，该条会归入 `其他`；
- `aggregate` 会额外返回 `其他_count` 和 `其他_rate`；
- 提交接口会先调用一次模型进行连通性探测，无效凭据会直接返回 `500`。

---

## 7. 常见问题

### 7.1 故障排查表

| 现象 | 原因 | 处理方式 |
|---|---|---|
| 提交后一直看到 `queued` 或 `running` | 任务尚未完成，或 LLM 判定耗时较长 | 继续轮询，或使用 SSE 查看进度 |
| 返回 `422 Invalid config` | `config` 不是合法 JSON 字符串 | 校验 JSON，并确认它作为普通表单字段传入 |
| 提示 `requires gold_field` | `exact_match` 或 `llm_judge` 未配置标准答案字段 | 补充 `gold_field` |
| 提示 `unknown batch metric` | 使用了五种合法值之外的指标名 | 改用 `mean`、`precision`、`recall`、`f1`、`accuracy` |
| 查询返回 `status=failed` | 数据列缺失或 JSON 预测字段无法解析 | 查看 `error` 并核对数据结构 |
| SSE 收到 `completed` 但看不到分数 | 终态事件不包含结果 | 调用任务查询接口读取 `result` |
| 查询旧任务返回 `404` | 服务已重启，内存任务记录丢失 | 重新提交评估任务 |

### 7.2 常见问答

#### Q：提交接口为什么不直接返回指标？

**结论：因为数据集评估是异步任务。**

提交接口只负责校验请求、读取文件并创建任务。最终指标通过 `GET /evaluate/dataset/jobs/{job_id}` 获取。

#### Q：为什么关键词组的 `precision` 可能一直是 `1.0`？

**结论：关键词组把所有记录都视为需要命中关键词的正例。**

命中记录计为 TP，未命中记录计为 FN，没有负例和误报，因此非空数据集的关键词组精确率为 `1.0`；召回率和准确率才体现实际命中比例。

#### Q：可以只查看 SSE，不轮询任务查询接口吗？

**结论：SSE 可以显示进度和终态，但不会返回最终指标。**

收到 `completed` 或 `error` 后，仍应查询任务接口获取完整 `result` 或 `error`。
