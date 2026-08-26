---
name: skill-package-author
description: 基于当前材料生成或修订可编辑、可打包的 Skill 草稿。
---

# Skill 包写作

本 Skill 只负责把当前材料和已确认结论物化到 `generated-skill/`。它不编写平台契约，不执行 Gate，不声明业务正确性已经被证明。

默认工作流的 `author_build` 只完成生产包，随后由 Core 直接运行确定性 CLI 启动与 smoke。`author_validate` 保留为显式高级验证能力，不进入默认生成闭环。知识/SOP 包继续由普通 `author` 一次完成。

## 输入

默认工作流的当前消息包含与 ScenarioContract 匹配的 `scenario_author_handoff` 时，它是本阶段权威的方向和决策输入。不得读取 `validation/` 投影或遍历 `inputs/`。实现页面解析、字段映射或复杂规则时，可以用 `read_workspace_file` 各读取一次 handoff `evidenceRefs` 明确引用的文本材料；读取只用于查证实现细节，不能覆盖 Scenario/HITL 结论。

只有显式 `run_phase=author` 且没有有效 handoff 时，才按需读取：

- `inputs/` 中与当前能力直接相关的材料。
- `validation/scenario_contract.json`（如果存在）：这是 Scenario 到 Author 的业务事实交接，优先于模型自行推断；其中的 facts、已确认规则、输入输出和 pending decisions 不能被 Author 重新改写。
- `validation/artifact_manifest.json.resolvedCapabilityContract`（如果存在）：这是 Scenario 硬要求与 HITL 已确认能力合并后的唯一能力契约。`requiredCapabilities` 中为 `true` 的浏览器/API/通用外部采集能力必须有对应真实入口，不能通过把正文改成“人工执行”“外部系统边界”或“不包含该能力”来降级绕过。
- 如果当前消息包含“平台生成的有效 Scenario 决策交接”，同一 `decisionId` 已经是 HITL 的最终选择；它覆盖原契约中的对应 `pendingDecisions`，最终 Skill、摘要和“未确认事项”中不得重复标记为待确认。
- 已存在的 `generated-skill/` 草稿，用于增量修订。

默认抽取由 `scenario-skill-builder` 先完成材料理解，再进入本 Skill。Author 不重复执行场景冲突识别，也不创建第二份业务契约。显式 `run_phase=author` 没有 ScenarioContract 时，才允许直接基于材料生成或修订草稿；遇到无法确认的行为口径必须在正文标记未验证，不得伪造确定结论。
- `generated-skill/` 中已有草稿，用于增量修订。

材料事实优先。多个来源真正冲突且没有用户结论时，不猜默认值；在 Skill 正文中明确相关边界。

## 包结构

- `SKILL.md` 是唯一必需文件，frontmatter 必须包含非空 `name` 和 `description`；`name` 使用 kebab-case。
- `references/`、`scripts/`、`fixtures/`、`assets/` 只在能力确实需要时生成。
- `agents/openai.yaml` 完全由宿主适配层管理；Author 不生成、不修改。
- 包内只能有一个根 `SKILL.md`，不得创建嵌套 Skill 根、符号链接或平台保留目录。
- 导出包不得依赖 `inputs/`、`validation/`、`workspace/`、`playwright/` 或 `.skill-builder/`。
- 导出文档中也不得保留这些平台路径作为“来源说明”或证据链接；需要保留的事实应归纳到 `references/`，正文只引用最终包内相对路径。

## 流程

1. 有有效 handoff 时据此确定方向和文件清单；只在实现需要时读取 handoff 引用的具体原文，不遍历材料、不重新做 Scenario 判断。没有 handoff 的显式 Author 才按需读取当前材料。
2. Core 根据最终包文件、Scenario 能力契约和 `requirements.txt` 生成 ImplementationPlan；Author 不提交或改写 `packageKind/scriptsRequired`。控制器要求脚本时必须生成非自检生产脚本，不要求脚本时不生成 `scripts/`。
3. 写入完整文件。`SKILL.md`、较长生产脚本使用 `write_skill_file` 单独生成；2 至 4 个独立小型 reference 或文本 fixture 可用 `write_skill_files` 批量提交。大型实现按职责拆分，不得写占位骨架。
   - 控制器生成的 `sample-input/invalid` 只用于 schema 和非法输入检查，不自行替换或删除。`author_build` 不再生成 `invalid/error/empty` 变体，只为结构化入口生成一份最小业务 happy fixture。外部 HTML/JSON 响应 fixture 只有在生产入口真实支持本地响应注入时才生成，禁止创建未被代码消费的展示样例；受控外部重放和更深边界 case 属于 Validate。
   - XLSX 业务 fixture 使用 `write_tabular_fixture` 提交列和行，由控制器生成真实工作簿；不得用文本写入 `.xlsx`，也不得生成一次性 fixture 生成器脚本。
4. 纯知识或 SOP Skill 不为凑结构生成脚本、fixture 或 UI 元数据。
5. 需要脚本时只生成真实生产入口，文档、依赖和样例与入口保持一致。fixture 仅用于离线样例和测试，生产入口必须从实际输入或外部结果提取。
   - Python 脚本和顶层包名不得与标准库模块同名，例如 `inspect.py`、`json.py`、`email/`；使用 `inspect_cli.py`、`json_report.py` 等业务名称，并同步更新正文、自检命令和包内导入。
6. `author_build` 在生产包和最小 happy fixture 物化后直接调用 `finish_authoring`，不生成或运行自检。Core 随后执行 CLI 启动和可确定推导的业务 smoke；无法确定验证的多输入或外部核心能力进入 `needs_review`。显式 `author_validate` 才生成受控外部重放计划和必要的边界 case，并在该阶段强制响应 fixture 被生产命令真实消费。
   - 非法记录不得进入 count、金额、评分或推荐等业务聚合；Build 在生产逻辑中实现该边界，但不为每个失败条件扩写 fixture。Validate 可复用平台 invalid，并仅在确定性重放确有需要时增加最小定向 case。
7. 材料中出现外部系统不自动等于本包能力。只实现 `resolvedCapabilityContract` 和 ImplementationPlan 中确认的能力；真实存在入口但未在线执行时如实标记未验证。
   - `happy_path` 只接受退出码 0，必须使用本地成功 fixture 产生至少一条有效业务结果并断言关键字段或数量；`blocked` 降级只能由独立的 `external_offline` 用例验证。退出码、文件存在和报告标题不能单独证明业务成功。
   - 多输入关联、对账或文件交接场景中，新增一组成套且键值可关联的业务 happy fixtures，并让 happy path 同时消费这些文件；平台 `sample-input` 不能证明业务成功。
   - 真实 API 端点没有材料/HITL 证据时，入口必须通过 CLI、环境变量或配置要求用户提供；禁止把 `example.com`、`example.org`、`example.net` 等保留示例域名写成生产默认端点。
   - 携带数值的枚举确认值（如 `first_50` / 前 50 条）同样是固定值，不得转换成带默认值的可配置参数。
8. 每个当前职责完成后调用 `finish_authoring`，提交 `summary` 和 `agent_self_check`。Build 只报告静态包检查，Validate 才报告离线重放；`implementation_evidence` 可选，只作诊断，不影响候选提交。
9. 控制器只会把单一机械根因族交给一次有界 Repair。业务重放、能力缺失、fixture、HITL、evidence 和外部环境问题不进入自动 Repair，也不会自动重启 Author/Scenario。

## 完成边界

控制器预检成功表示当前包可编辑、可归档并形成 PackageRevision；worker 退出后仍由独立交付验收生成最终 ValidationRevision 和 `ready / needs_review / failed` 结论。外部权限、未实际执行的在线能力和深入业务审查不会因草稿提交而被伪造为通过。
