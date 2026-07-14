# Rules（项目规则）

本目录是 EvoAgent 项目规则的**唯一内容源**。

各 AI 工具的 rules 目录仅存放薄索引，通过 `@docs/rules/<file>.md` 引用此处内容。

## 文件清单

| 文件 | 说明 |
|------|------|
| `architecture.md` | 项目定位、模块边界、依赖方向 |
| `adapter.md` | 场景适配层：optimizer 子类化、文件夹结构、prompt 覆盖、callback 顺序 |
| `api.md` | API 层：FastAPI 骨架、JobManager、SSE 事件体系、双入口模型 |
| `code-style.md` | Python 代码风格、命名约定、import 顺序、数据类规则 |
| `testing.md` | 测试组织、命名、覆盖要求、Mock 策略 |
| `git-workflow.md` | Git commit 风格、分支命名、提交前检查 |

## 工具索引

| 工具 | 索引目录 | 格式 |
|------|---------|------|
| Claude Code | `.claude/rules/` | `paths` + `@docs/rules/...` |
| Cursor | `.cursor/rules/` | `globs` + `@docs/rules/...` |
| Trae | `.trae/rules/` | `globs` + `@docs/rules/...` |
