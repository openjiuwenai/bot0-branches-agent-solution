# workshop · Skill 资产（Skills）

本目录沉淀 workshop 方向的 Skill，即可被 Agent 直接调用的业务级操作单元。

## 定位

- 上游依赖：mcp/ 服务与 tools/ 原子能力
- 下游消费：Agent 编排与上层业务系统
- 职责：以 Skill 形式封装业务操作，供 Agent 按需调用

## 目录约定

- 一个 Skill 一个子目录，命名为动宾结构（如 `inspect-xxx`、`optimize-xxx`）
- 子目录内包含 SKILL.md、提示词与所需资源文件

## 贡献说明

新增或修改 Skill 请遵循仓库根目录 [CONTRIBUTING.md](../../../CONTRIBUTING.md) 中的资产评审流程。