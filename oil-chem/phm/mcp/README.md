# 设备智能化运维场景 · MCP 服务（MCP Services）

本目录沉淀 phm（设备智能化运维）场景的 MCP（Model Context Protocol）服务，即场景对外提供能力的统一入口。

## 定位

- 上游依赖：methodology 方法论与 tools/ 原子能力
- 下游消费：skills/、Agent 编排与上层业务系统
- 职责：把可复用能力以标准 MCP 协议暴露，避免上层直接耦合底层实现

## 目录约定

- 一个服务一个子目录，命名为业务域缩写（如 `asset-xxx`、`operation-xxx`）
- 子目录内包含服务定义、接口说明与本地调试脚本

## 贡献说明

新增或修改 MCP 服务请遵循仓库根目录 [CONTRIBUTING.md](../../../CONTRIBUTING.md) 中的资产评审流程。
