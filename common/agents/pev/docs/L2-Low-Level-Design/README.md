---
level: L2-LLD
module: agents/pev
status: active
authority: authoritative
updated: 2026-08-06
dependency:
  - ../features/FEAT-023-pev-selfheal-loop.md
  - ../L1-High-Level-Design/overview.md
  - ../L1-High-Level-Design/logical.md
  - ../L1-High-Level-Design/process.md
---

# PEV L2 详细设计

## 目的

本目录保存 `agents/pev` 模块的 L2 详细设计。它在 L1 高阶设计指导下，按特性颗粒度展开实现级设计，回答"某个特性由哪些类型/SPI 协作承接、走怎样的运行流程、有哪些状态与错误语义、当前边界在哪里"，不重新定义 L1 已确定的模块边界与依赖方向。

## 成熟度声明

与 `agent-client` L2（proposed/non-authoritative，skeleton）不同，`agents/pev` 在 `common/agents/pev` 已是**已实现代码**（origin/common，承重测试全绿 + 真 LLM e2e 软观察）。因此本目录 Feat-Func 文档：

- 状态为 **`active` / `authoritative`**——是"与代码严格对应的当前事实"，不是待评审设计。
- 类型/方法签名以 `common/agents/pev/src/main/` 实际代码为准；改代码须同步改本文档。
- 每份 Feat-Func 对应一条 version-scope 事实要求（`FEAT-023`，status: active），本目录是其**实现级细化，必须服从 FEAT-023 事实要求**。

## 命名规则

沿用 4+1 文档体系 L2 惯例：`Feat-Func-[3 digits]-[short name].md`。编号与短名**对齐对应 version-scope `FEAT-NNN`**（本模块 `Feat-Func-023` ↔ `features/FEAT-023-pev-selfheal-loop.md`），便于按编号双向追溯。

## 功能特性清单

| 编号 | 文档 | version-scope 事实来源 | 特性 | 当前实现边界 |
|---|---|---|---|---|
| Feat-Func-023 | [PEV 自愈执行闭环](Feat-Func-023-pev-selfheal-loop.md) | `FEAT-023-pev-selfheal-loop.md` | 单 kernel 自包含 Plan→Execute→Verify→Diagnose→Dispatch 闭环：三阶段 SPI、kernel 决策核心（sealed + 纯函数 IFF）、terminalGuard、verifier 容错、kernel-native trace、rail 缝合点。 | 已实现（origin/common）；真流式/多 agent/HTTP 接入/增量 trace 显式 OUT。 |

## 关联文档

- L1 4+1 视图：`../L1-High-Level-Design/{overview,logical,process,development,physical,scenarios}.md`
- 特性事实：`../features/FEAT-023-pev-selfheal-loop.md`
- 开发指南：`common/agents/pev/README.md`
- 代码：`common/agents/pev/src/main/java/com/openjiuwen/agents/pev/`
