---
name: scenario-skill-builder
description: 从上传材料提取业务 facts/conflicts，由平台规范化为 ScenarioContract、稳定标识和统一 HITL；不负责写最终包或执行验证。
---

# Skill 场景事实抽取

本 Skill 只负责材料理解和单向交接。它不写 `generated-skill/`，不提交验证 DSL，不执行 fixture，也不决定最终状态。

## 输入边界

- 业务事实只来自材料包聚合工具返回的 `inputs/`、材料索引和 digest。
- 长材料使用聚合结果中的摘要，录屏使用覆盖完整流程的 `recordingDigest`。Scenario 不逐文件补读；页面结构、字段样例等实现细节由 Author 后续按 `evidenceRefs` 查证。
- `validation/` 与历史摘要只能恢复流程，不能成为新业务事实。
- Office/PDF 有解析 Markdown 时优先使用解析副本；不可读内容保持未验证。

## 唯一输出

聚合读取完成后不得输出材料分析正文，直接调用一次 `write_scenario_draft` 提交完整 JSON；平台在同一工具调用内持久化、规范化并原子提交。只有首次返回 `next_action=repair_and_resubmit` 时，才根据返回的 `issues` 原地修正并最多再提交一次；不要重新读取材料或开启第三次提交。`finish_scenario_draft` 只用于恢复已经持久化但尚未提交的旧草稿。草稿只包含：

- `facts`：材料已经明确的事实。每项必须有 `kind`、无损 `value`、字符串数组 `evidenceRefs`（如 `inputs/source.md`）。`rule` 必须提供可核对的 `sourceQuote`；`requirement` 应提供 quote，缺失时平台只保留材料路径并强制人工审核，不得伪造原文。可补 `label`。
- `conflicts`：材料缺失或互相冲突、且答案会改变 Skill 行为的用户选择。字段使用 `title`、`description`、`type`、`defaultValue`、`options`，可选 `evidenceRefs`/`sourceQuote` 说明冲突来源；不要写 `default`。每个 option 必须是 `{"value": ..., "label": ..., "description": ...}` 对象，不要写裸字符串。
- 可选 `skillName`、`displayName`。

`facts` 必须是原生 JSON 数组，每条 fact 都是数组中的独立对象。禁止把其他 fact 的 JSON 文本拼接、转义或序列化进某条 `value` 字符串。

CSV/Excel/XLS/XLSX 表格输入必须在对应 `kind=input` 的 `value` 中同时保留 `format` 和材料列出的 `fields`；多份表格按名称分别声明，不能只把字段名压进 `description`。

`facts.kind` 使用以下固定类型：

- `purpose`、`trigger`、`non_trigger`
- `input`、`output`、`step`
- `dependency`、`script_requirement`、`acceptance`
- `requirement`、`rule`

不要使用 `external_system` 等未列出的 kind。外部系统名称、URL、登录、验证码、网络和权限要求统一写入 `kind=dependency`；具体自动化步骤写入 `kind=step`。

材料明确要求 CLI、Python/其他语言脚本、命令行入口或离线可执行工具时，必须另外提交一条 `kind=script_requirement` fact，并保留原始要求和证据。声明 API/browser 等运行机制时还必须提供材料逐句 `sourceQuote`；只有材料路径而没有原文时，平台会把机制降为待确认选择而不是硬能力。不能只把“通过 CLI 运行”写进 trigger/input/step，也不能因同一业务流程可用文字描述而省略可执行交付要求。

URL、官网链接、网页导出文件或人工打开说明本身不产生浏览器能力。只有材料明确要求 Playwright/Selenium、浏览器自动化，或由 Skill 自动点击、填写、下载、截图等页面交互时才编译 browser；明确 HTTP method、API endpoint、requests/httpx 时编译 API。未指定机制的外部 URL 采集进入 HITL，默认由人工或文件提供外部结果，不能静默升级为 API/browser。

Agent 不提交 `factId`、`requirementId`、`ruleId`、`decisionId`、`semanticConcept`、capability、obligation 或 `runtimeRequirements`。平台根据规范化事实生成稳定 ID、概览字段和业务规则，避免模型生成的机器标识在重试间漂移。

同一事实只写一次。阈值、公式、权重、分类、精度和异常规则使用 `kind=rule`；非规则业务口径使用 `kind=requirement`。材料已经明确的口径不得再次放入 `conflicts`。

## HITL

只有答案会改变输入、计算、输出、报告字段、运行能力或业务结论时才创建 conflict。

- `select` 提供 2 至 6 个可读选项；`boolean`/`text` 只用于真实业务选择。
- 采集方式的业务值使用 `api|browser|hybrid|file|manual|fixture`，输出格式使用 `markdown|json|json_and_markdown|pdf`。
- 不询问 `validation_input_mode`、`validation_transport`、fixture/live 组合或验收 JSON 通道。
- Scenario 一次提交全部已知冲突；平台统一生成一张 HITL 表单，回答持久化后才启动 Author。

## 完成条件

- 材料包已聚合读取，长材料和录屏已使用控制器摘要。
- facts 保留了可执行业务语义和材料证据，conflicts 只含真正的业务选择。
- `write_scenario_draft` 返回成功，平台已生成带 `semanticHash` 的 ScenarioContract。

提交成功后立即结束 Scenario worker。控制器随后生成结构化输入 fixture，Author 只负责计划并物化最终包；最终状态由控制器验收决定。
