---
level: L2-LLD
module: agents/edpa-alpha
status: active
authority: authoritative
updated: 2026-08-15
dependency:
  - ../features/FEAT-025-edpa-cognitive-loop.md
  - ../features/FEAT-026-edpa-capability-extensions.md
  - ../L1-High-Level-Design/overview.md
  - ../L1-High-Level-Design/logical.md
---

# EDPA-alpha L2 详细设计

## 目的

本目录保存 `agents/edpa-alpha` 的 L2 详细设计。以 `common/agents/edpa-alpha` 已实现代码（origin/common）为事实来源，状态 active/authoritative——改代码须同步改本文档。

## 命名规则

`Feat-Func-[3 digits]-[short name].md`，编号对齐 version-scope FEAT-NNN。

## 功能特性清单

| 编号 | 文档 | version-scope | 特性 | 边界 |
|---|---|---|---|---|
| Feat-Func-025 | [认知增强闭环](Feat-Func-025-edpa-cognitive-loop.md) | FEAT-025 | ReAct overlay：ProactiveConvergenceRail + GroundTruthVerifier + DeterministicChecker + ExploreRail + EdpaKernel | 不改 ReAct 本体；数值/合规零 LLM |
| Feat-Func-026 | [能力扩展](Feat-Func-026-edpa-capability-extensions.md) | FEAT-026 | MCP stdio 集成 + SubAgent 派发 + Explore Tool | MCP 只 stdio；SubAgent in-process |

## 关联文档

- L1 4+1 视图：`../L1-High-Level-Design/{overview,logical,process,development,physical,scenarios}.md`
- 特性事实：`../features/FEAT-025-edpa-cognitive-loop.md` + `FEAT-026-edpa-capability-extensions.md`
- 代码：`common/agents/edpa-alpha/src/main/java/com/openjiuwen/agents/edpa/`
