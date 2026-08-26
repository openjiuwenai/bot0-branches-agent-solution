# 通用 CSV 数据质量校验 Skill

## 目标

生成一个可离线运行的 Python Skill。读取 CSV 记录，执行确定性字段校验，输出 JSON 质量报告。不得访问网络，不得依赖宿主数据库。

## CLI

必须提供统一生产入口：

```bash
python scripts/validate_records.py --input <input.csv> --output <report.json>
```

退出码：

- 输入文件及全部记录合法：0
- 输入可读取但存在非法记录：2
- 文件缺失、表头错误或无法解析：3

## 输入契约

CSV 使用 UTF-8 编码并包含以下字段：

| 字段 | 类型 | 规则 |
|---|---|---|
| `record_id` | string | 必填，匹配 `^[A-Z0-9_-]{3,32}$`，全文件唯一 |
| `category` | enum | 必填，只允许 `A`、`B`、`C` |
| `amount` | decimal string | 必填，使用 Decimal 从字符串解析，必须大于 0 且不超过 100000.00，最多两位小数 |
| `status` | enum | 必填，只允许 `active`、`inactive` |

允许额外列，但报告中不得泄露额外列内容。

## 输出契约

输出 UTF-8 JSON：

```json
{
  "status": "pass|fail",
  "summary": {"total": 0, "valid": 0, "invalid": 0},
  "errors": [
    {
      "row": 2,
      "record_id": "REC-001",
      "field": "amount",
      "code": "amount_out_of_range",
      "message": "可读错误说明"
    }
  ]
}
```

要求：

- `summary.total = summary.valid + summary.invalid`
- 同一行可以产生多个 error
- error 按行号、字段名、code 稳定排序
- 无错误时 `status=pass` 且 `errors=[]`
- 有错误时 `status=fail`

## 业务规则

1. 金额必须使用 `Decimal(raw_text)`，禁止先转 float。
2. 缺少必填列是文件级错误，退出码 3，不生成伪造 pass 报告。
3. 空值、枚举错误、金额格式/范围错误和重复 `record_id` 都有稳定 code。
4. 输入为空但表头合法时输出 pass，total/valid/invalid 均为 0。
5. 输出写入临时文件后原子替换目标文件。
6. 报告不得硬编码 fixture 业务结果。

## 生成包要求

- 必须生成 `SKILL.md`
- 必须生成 `scripts/validate_records.py`
- 必须生成至少一个合法业务 fixture 和一个非法 fixture
- 自检覆盖 happy path、重复 ID、金额 `1`、`1.0`、`"1.00"`、缺列和枚举错误
- 只使用 Python 标准库
