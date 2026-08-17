# 流程工业智能化场景（smart-process）

油气化工矿山钢铁行业 Agent 解决方案的业务场景之一，面向炼化生产优化、过程控制、生产调度、能效管理等流程工业智能化业务。

## 典型子场景

| 子场景 | 说明 |
| --- | --- |
| 生产计划与调度 | 月度生产计划编制、原油加工排产、装置开停工安排（本目录 calc_service、preSchedule 即属此类） |
| 工艺操作优化 | 加工路线对比、收率预测、操作参数寻优（如 refinery-route-optimizer） |
| 过程控制 | 先进过程控制（APC）、实时优化（RTO）、软测量 |
| 物料与收率分析 | 物料平衡核算、收率趋势分析与异常归因（如 dataAnalysis） |
| 能效与碳管理 | 能耗结构分析、蒸汽与燃料系统优化、碳排放核算 |
| 质量与安全管理 | 产品质量预测、报警管理、异常工况诊断 |

## 资产形态

| 子目录 | 资产类型 | 说明 |
| --- | --- | --- |
| mcp/ | MCP 服务 | 场景对外提供能力的标准协议入口 |
| skills/ | Skill | 可被 Agent 直接调用的业务级操作单元 |
| tools/ | 工具 / 业务系统 | 可独立运行、解决具体业务问题的能力单元 |

## 与其他目录的关系

| 目录 | 定位 |
| --- | --- |
| methodology/ | 跨场景方法论，是本场景资产的方法论输入 |
| workshop/ | 跨场景最佳实践，是本场景资产的实践参照 |

## 目录结构说明

```
smart-process/
├── mcp/                    # 场景级 MCP 服务
│   ├── calc_service/               # 炼厂生产计划求解计算服务
│   ├── data_service/               # 场景数据服务
│   └── refinery-route-optimizer/   # 炼厂路径收率查询与路径对比
├── skills/                 # 场景级 Skill
│   └── crude-assay-import/         # 原油评价数据导入 crude_qa 库
└── tools/                  # 场景级工具与业务系统
    ├── crudeAgent/         # 原油评价智能体与工作流（agent-studio 导入配置）
    ├── dataAnalysis/       # 化工炼化物料平衡数据分析系统
    ├── knowledgeAgent/     # 制度问答智能体部署指南
    └── preSchedule/        # 原油加工月计划排产系统
```

各子目录的详细说明见其各自的 README。

## 贡献说明

新增或修改本场景资产请遵循仓库根目录 [CONTRIBUTING.md](../../CONTRIBUTING.md) 中的资产评审流程。
