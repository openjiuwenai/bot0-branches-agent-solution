# 结构化抽取清单

场景阶段在内部用本清单检查覆盖面，但只提交一份有预算的 `ScenarioDraft`。平台从 canonical `ScenarioContract` 生成 `validation/scenario_summary.md`；Agent 不把清单逐行改写成第二套抽取数据。

## 内部核对维度

| 核对项 | ScenarioDraft 中的唯一落点 |
| --- | --- |
| 已有材料证据的具体业务事实 | 一条 `facts`，包含固定 `kind`、无损 `value` 和类型化 `evidenceRefs` |
| 会改变 Skill 行为的材料缺失或冲突 | 一条合并后的 `conflicts`，不填写机器 ID |
| 触发、流程或实现边界 | 对应 kind 的一条 fact，平台生成概览字段 |
| 不改变产物行为的未知信息 | 不创建 HITL；在相关事实或验收边界中标记未验证 |

## 必填抽取项

| 抽取项 | 要抽取什么 | 目的地 |
| --- | --- | --- |
| 目标 | 业务目标、用户可见产出、成功标准 | `generated-skill/SKILL.md` 核心 SOP；细节进入 `references/workflow.md` |
| 触发边界 | 什么时候应该触发这个 Skill | `SKILL.md` frontmatter `description` 和正文 `When to use` |
| 不触发边界 | 相似但不应触发的任务 | `SKILL.md` 正文 `When not to use` |
| 输入 | 文件、字段、目录、格式、必填/可选值 | `references/input-schema.md`；确定性校验进入 `scripts/` |
| 输出 | 报告、CSV、JSON、摘要、渲染产物、命名和 schema | `references/output-template.md`；确定性渲染进入 `scripts/` |
| 流程 | 人工步骤、自动步骤、外部系统步骤、分支 | `SKILL.md` 概览；详细 SOP 进入 `references/workflow.md` |
| 业务规则 | 阈值、评分、计算、分类、例外 | `references/business-rules.md`；脆弱或重复逻辑进入 `scripts/` |
| 外部系统 | URL、API、数据库、权限、登录、验证码、内网、写操作 | `references/external-sources.md`；未确认边界写入 Skill 正文 |
| HITL | 会实质影响 Skill 行为或输出的决策 | `validation/scenario_summary.md`；确认后的稳定规则可同步到 Skill |
| 风险 | 数据缺失、系统阻断、破坏性操作、低证据覆盖 | `validation/scenario_summary.md`；运行期降级规则进入 `SKILL.md` |
| 验收标准 | 需要证明的业务结果、边界 fixture、外部能力和未验证范围 | `kind=acceptance`/`script_requirement` facts；不创建额外完成态字段 |

没有证据支撑的字段必须标为 `unknown` 或 `unverified`。不要把推断事实静默写成 Skill 指令。会改变数值结果、报告字段、评分/等级/风险、合规判断或验收结论的 open question 必须视为行为级待确认项。

## 仅录屏输入

屏幕录制和网页录制只能作为流程证据和初稿来源，不能证明可复用自动化已经可用。

如果唯一实质材料是录屏：

- 可以生成 Skill 初稿，但必须把抽取依据标记为 `recording-only`。
- 材料包包含 `recordingDigest` 且 `coverageComplete=true` 时，使用该完整流程摘要，不再请求 offset/next_offset；重点核对业务目标、交互首尾、查询/提交/下载等关键动作及中后段 `observedStates`。
- `web-recording.md` 中出现的 `playwright/recordings/...`、截图或 trace 路径只是历史录制引用；路径未作为 `inputs/` 材料提供时不要尝试读取。直接使用已持久化的录屏摘要作为证据并提交 ScenarioDraft。
- 除非录屏或 HITL 明确确认，否则业务目标、变量输入、输出 schema、成功标准、权限、登录态、写操作安全性、异常分支都必须标记为未确认。
- 除非在本 Session 中完成回放或独立验证，否则不要声明外部系统可达性、登录后状态、下载行为、写操作、数据采集或报告生成为已验证。
- 对任何会改变生成行为、数值结果、报告字段或验收结论的缺失答案，必须在首次 `conflicts` 中声明用户可读选择。平台生成稳定 decisionId 并展示统一 HITL；用户跳过时保留 pending/not_verified。
