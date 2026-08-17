# 流程工业智能化场景 · MCP 服务（MCP Services）

本目录沉淀 smart-process（流程工业智能化）场景的 MCP（Model Context Protocol）服务，即场景对外提供能力的统一入口。

## 已有服务

| 子目录 | 说明 | 默认端口 |
| --- | --- | --- |
| calc_service/ | 炼厂生产计划求解计算服务（Flask API + Next.js 前端 + MCP 工具） | 5081 / 5082 / 8766 |
| data_service/ | 场景数据服务 | 8765 |
| refinery-route-optimizer/ | 炼厂路径收率查询与路径对比 | 7489 |

各服务的启动与调用方式见其各自目录内的 README。

## 定位

- 上游依赖：methodology 方法论与 tools/ 原子能力
- 下游消费：skills/、Agent 编排与上层业务系统
- 职责：把可复用能力以标准 MCP 协议暴露，避免上层直接耦合底层实现

## 目录约定

- 一个服务一个子目录，命名为业务域缩写
- 子目录内包含服务定义、接口说明与本地调试脚本

## 贡献说明

新增或修改 MCP 服务请遵循仓库根目录 [CONTRIBUTING.md](../../../CONTRIBUTING.md) 中的资产评审流程。
