# 离线重放计划

只要包内文档公开 Python CLI，就在生产脚本和 CLI 文档完成后调用
`write_self_check_plan`。只提交结构化的 `checks`；控制器会先按当前包和
ScenarioContract 校验覆盖、命令、fixture 与断言，再确定性生成唯一入口
`scripts/self_check.py`。不要用 `write_skill_file`、`replace_skill_file_text` 或
`delete_skill_file` 直接创建、修改、替换或删除任何自检入口。

`write_self_check_plan` 接收的每个用例包含 `id`、`kind`、`covers`、`commands` 和
`assertions`。控制器生成的入口支持
`python scripts/self_check.py --output-dir PATH`，并在
`PATH/self_check_summary.json` 写入 `skill-builder-self-check/v1` 的
`status=planned` 计划。平台随后删除临时结果、独立重放全部命令并计算最终状态，
声明本身不能让失败命令变成通过。

使用以下规范对象形状；`source` 是输出目录内的相对路径，不需要添加
`${outputDir}/` 前缀：

```json
{
  "checks": [{
    "id": "happy-path",
    "kind": "happy_path",
    "covers": ["rule-example"],
    "commands": [{
      "command": [
        "python", "scripts/run.py",
        "--input", "fixtures/sample.json",
        "--output", "${outputDir}/result.json"
      ],
      "expectedExitCodes": [0]
    }],
    "assertions": [{
      "source": "result.json",
      "path": "status",
      "operator": "equals",
      "expected": "success"
    }]
  }]
}
```

计划校验失败时只修正工具返回的具体 `issues` 后再次调用
`write_self_check_plan`。不要编写 `subprocess` 执行器、断言解释器、结果计数、
`pass/fail` 判定或另一份旁路自检脚本。

## 用例要求

- `happy_path`：覆盖每个文档化 CLI，只接受退出码 0；使用本地成功 fixture 产生至少一条有效业务结果，并断言真实输出状态和至少一个非空关键业务字段或数量，不能接受 `blocked`。
- `invalid_input`：有结构化输入时，覆盖缺失必填、错误类型或错列之一。
- `file_handoff`：多个公开 CLI 构成流水线时，用 `${outputDir}` 把前一步真实输出交给后一步。
- `business_rule`：用 `covers` 覆盖可执行 `ruleId`，并断言对应阈值、分类、精度、计数或状态。
- `external_offline`：浏览器/API CLI 使用本地页面或响应 fixture，独立验证成功解析或结构化 `blocked` 降级分支，不访问公网；如果依赖初始化失败发生在输出文件创建前，断言稳定的 stdout/stderr 业务错误原因。

## 命令和断言

- 命令是参数数组，首项只能是 `python` 或 `python3`，脚本位于 `scripts/`。
- fixture 只从 `fixtures/` 读取；运行输出只写 `${outputDir}/...`。
- 含非 ASCII 文本的 HTML fixture 必须以 UTF-8 保存，并在 `<meta charset="utf-8">`
  或等价 Content-Type meta 中显式声明 UTF-8；纯 ASCII HTML 不强制声明。
- 不使用 shell、绝对路径、`/tmp`、平台目录或 `..`。
- 每个命令填写 `expectedExitCodes`；非法输入可以预期非零退出码。
- `source` 是输出目录内相对文件，或 `$command` 的 `exitCode`、`stdout`、`stderr`。
- Markdown、文本和 CSV 的全文路径使用 `$`、`.` 或 `content`；平台会统一按文件全文判定。
  JSON/JSONL 才使用字段路径。文件存在断言可使用全文路径或 `path=exists`。
- 支持 `exists`、`not_empty`、`equals`、`not_equals`、`in`、`contains`、
  `minimum`、`maximum`、`min_items`、`max_items`、`length_equals`、
  `keys_equal`、`sum_equals`、`type`。

成功用例不能只检查退出码或文件存在；要检查机器状态和真实业务字段。外部离线边界至少检查结构化 blocked 状态，无法产出结构化文件时检查稳定的 stdout/stderr 业务错误原因。
ScenarioContract 声明的输出字段需要字段断言，有明确类型时增加 `type`；Markdown 输出
章节使用实际报告上的 `contains` 断言。若章节声明为复合语义组（例如
`Header (date, operator, count)`），可以断言语义组标题本身，也可以分别断言括号内的全部
真实输出字段；不要为了逐字匹配抽象组名而向业务输出增加虚假标题。已有会真实执行并输出
`pass/fail` 的历史 v1 入口仍可由验收读取，但 Author/Repair 只能通过
`write_self_check_plan` 生成新的 `planned` 入口，避免生成包和平台重复实现同一执行逻辑。
