# 流程工业智能化场景 · 工具与业务系统（Tools）

本目录沉淀 smart-process（流程工业智能化）场景的工具与业务系统，即可独立运行、解决具体业务问题的单元。

## 已有工具

| 子目录 | 说明 |
| --- | --- |
| crudeAgent/ | 原油评价智能体与工作流（agent-studio 平台的导入配置及使用说明） |
| dataAnalysis/ | 化工炼化物料平衡数据分析系统（Excel 导入、收率趋势分析、罐表分析） |
| knowledgeAgent/ | 制度问答智能体部署指南（RagFlow + openGauss 知识库对接，不含代码） |
| preSchedule/ | 原油加工月计划排产系统（CP-SAT + Benders 分解，含 Web 前后端） |

各工具的使用方式见其各自目录内的 README。

## 定位

- 上游依赖：methodology 方法论与场景数据
- 下游消费：skills/、mcp/ 与 Agent 编排
- 职责：承载场景业务逻辑，为上层资产提供能力底座

## 目录约定

- 一个工具一个子目录，命名体现业务动作
- 子目录内包含可运行代码、使用说明与依赖清单

## 贡献说明

新增或修改工具请遵循仓库根目录 [CONTRIBUTING.md](../../../CONTRIBUTING.md) 中的资产评审流程。
