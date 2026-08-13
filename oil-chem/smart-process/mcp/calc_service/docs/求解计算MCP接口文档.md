# MCP 接口文档

> **版本**: v2.2 | **更新**: 2026-08
> **两个 MCP 服务**: data_service（数据层，28 工具） + calc_service（计算层，17 工具）

---

## 一、服务概览

| 服务 | 端口 | 工具数 | 职责 | 启动命令 |
|------|------|--------|------|---------|
| **data_service** | 8765 | 28 | 数据 CRUD（装置/侧线/收率/价格/油种/物料） | `python -m backend.data_service.mcp.server` |
| **calc_service** | 8766 | 17 | 业务计算（求解/评估/优化/分析/可视化/规则引擎） | `python -m calc_service.backend.mcp_server.server` |

### Transport 模式

两服务均支持三种传输协议，启动参数完全对齐：

| 模式 | 参数 | 端点 | 适用场景 |
|------|------|------|---------|
| stdio（默认） | 无 `-t` 参数 | — | 本地 IDE（Trae / Claude Desktop） |
| streamable-http | `-t streamable-http --port <N>` | `http://host:<N>/mcp` | 远程 Agent 平台（推荐） |
| sse | `-t sse --port <N>` | `http://host:<N>/sse` | 长任务流式推送 |
| stateless+json | `-t streamable-http --stateless --json-response` | `http://host:<N>/mcp` | 低配平台（无 session） |

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MCP_TRANSPORT` | stdio | 传输协议 |
| `MCP_HOST` | 0.0.0.0 | HTTP/SSE 监听地址 |
| `MCP_PORT` | 8765 / 8766 | 监听端口 |
| `DATABASE_URL` | — | PostgreSQL 连接串 |
| `MCP_STATELESS` | — | 无状态模式（1/true/yes 启用） |
| `MCP_JSON_RESPONSE` | — | 纯 JSON 返回（需配合 stateless） |

---

## 二、data_service MCP（28 工具）

### 读工具（15 个，无副作用）

| # | 工具名 | 描述 | 参数 |
|---|--------|------|------|
| 1 | `list_units` | 查询全部装置 | — |
| 2 | `list_tanks` | 查询全部储罐（含关联物料） | — |
| 3 | `list_side_lines` | 查询侧线，可按装置过滤 | `device_id?` |
| 4 | `get_yields` | 查询收率，可按侧线和油种过滤 | `side_line_id?`, `crude_type?`, `limit?` |
| 5 | `get_side_lines_with_yields` | 侧线+收率联合查询（含 default 回退） | `crude_type?` |
| 6 | `list_materials` | 查询全部物料主数据 | — |
| 7 | `get_feed_ratio` | 查询装置进料配比 | `device_id`, `crude_type` |
| 8 | `get_material_mapping` | 查询侧线到物料的映射 | — |
| 9 | `get_prices` | 查询物料价格（含计算规则回退） | `month`, `material_ids?` |
| 10 | `get_side_line_prices` | 查询侧线价格（侧线→物料→价格联合） | `month` |
| 11 | `get_device_costs` | 查询装置加工成本 | `month` |
| 12 | `get_price_months` | 查询有价格数据的月份列表 | — |
| 13 | `list_flows` | 查询全部物流边 | — |
| 14 | `list_crudes` | 查询全部油种 | `active_only?` |
| 15 | `find_crude` | 按名称或别名查询油种 | `name` |

### 写工具（13 个，有副作用）

| # | 工具名 | 描述 | 参数 |
|---|--------|------|------|
| 1 | `upsert_unit` | 新增/更新装置 | `data`(JSON) |
| 2 | `upsert_tank` | 新增/更新储罐 | `data`(JSON) |
| 3 | `delete_device` | 删除装置或储罐 | `device_id`, `device_type` |
| 4 | `upsert_side_line` | 新增/更新侧线 | `data`(JSON) |
| 5 | `upsert_yields` | 批量新增/更新收率 | `data`(JSON) |
| 6 | `delete_side_line` | 删除侧线（级联收率） | `side_line_id` |
| 7 | `bind_material` | 绑定/解绑侧线与物料 | `side_line_id`, `material_id` |
| 8 | `upsert_price` | 新增/更新物料价格 | `month`, `material_id`, `price` |
| 9 | `upsert_crude` | 新增/更新油种 | `data`(JSON) |
| 10 | `delete_crude` | 删除油种（default 不可删） | `crude_type_id` |
| 11 | `toggle_crude_active` | 切换油种激活状态 | `crude_type_id`, `is_active` |
| 12 | `upsert_material` | 新增/更新物料主数据 | `data`(JSON) |
| 13 | `delete_material` | 删除物料（有依赖时需 force） | `material_id`, `force?` |

---

## 三、calc_service MCP（17 工具）

### 编排入口层（3 个）

#### MCP-01: `solve_refinery_plan`

综合求解：排产→批次→阀门枚举→选优（全链路）。支持通过 `feasibility_rules` / `selection_strategy` 定制可行性判断和选优策略。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `plan_month` | str | 是 | 计划月份（如 "2026-07"） |
| `production_plans_input` | list | 是 | 排产计划（原油品种+加工量） |
| `monthly_crude_input` | float | 是 | 月度原油加工总量（吨） |
| `blend_mode` | bool | 否 | 是否混炼（默认 false） |
| `save_data` | bool | 否 | 是否持久化（默认 true） |
| `hangmei_target` | float | 否 | 航煤目标产出（吨），None=不启用 |
| `shutdown_config` | list | 否 | 停工声明 [{unit, start_time, end_time}] |
| `simplified` | bool | 否 | 简化模式（默认 true） |
| `feasibility_rules` | dict | 否 | 可行性规则参数（见 MCP-15） |
| `selection_strategy` | dict | 否 | 选优策略参数（见 MCP-16） |

**返回**: `{success, optimal_combination, economic_summary, flow_diagram, ...}`

#### MCP-02: `optimize_valve_switches`

优化阀门切换位置（基于已存在计划，半链路）。支持通过 `feasibility_rules` / `selection_strategy` 定制规则。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `plan_id` | str | 是 | 已有排产计划 ID |
| `feasibility_rules` | dict | 否 | 可行性规则参数（见 MCP-15） |
| `selection_strategy` | dict | 否 | 选优策略参数（见 MCP-16） |

**返回**: `{success, optimal_combination, economic_summary, ...}`

#### MCP-17: `prepare_solve_data`

数据准备：加载排产计划→批次划分→阀门组合枚举。分步编排的入口工具，产出供 MCP-06/14/15/16 使用的中间数据。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `plan_month` | str | 是 | 计划月份（如 "2026-07"） |
| `shutdown_config` | list | 否 | 停工声明 [{unit, start_time, end_time}] |
| `plan_source` | str | 否 | 'lp' 读 production_plan_details（默认），'cp_sat' 读 cp_sat_plan_details |

**返回**: `{success, plan_id, batches, combinations, custom_crude_costs, batch_count, combination_count}`

> Agent 分步编排的第一步，后续链路：`prepare_solve_data → preload_reference_data → optimize_combinations → assess_feasibility → select_optimal → render_economic_summary`

---

### 独立计算层（5 个）

#### MCP-03: `calculate_batch_physical`

批次物理计算：BFS 拓扑遍历，计算装置进出料流量和利用率（不含经济计算）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scenario_id` | str | 是 | 原油品种标识（如 "BZ"） |
| `input_amount` | float | 是 | 批次输入量（吨/天） |
| `yield_mode` | str | 是 | "JIAN1_TO_WAX" / "JIAN1_TO_DIESEL" |
| `days` | int | 否 | 批次天数（默认 1） |
| `hangmei_mode` | bool | 否 | 是否航煤工况 |
| `hangmei_m_days` | float | 否 | 航煤工况天数（M 值） |
| `day_index` | float | 否 | 天数索引（月内位置） |
| `shutdown_intervals` | dict | 否 | 停工区间 {device_id: [(start_h, end_h)]} |
| `feed_ratios` | dict | 否 | 进料配比（可自动推导） |
| `custom_crude_costs` | dict | 否 | 自定义原油成本 |

**返回**: `{feasible, device_inputs, connection_flows, device_utilization, special_vars}`

#### MCP-04: `calculate_batch_full`

批次完整计算：物理计算 + 经济效益计算。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scenario_id` | str | 是 | 原油品种标识 |
| `input_amount` | float | 是 | 批次输入量（吨/天） |
| `yield_mode` | str | 是 | 收率模式 |
| `days` | int | 否 | 批次天数 |
| `hangmei_mode` | bool | 否 | 是否航煤工况 |
| `hangmei_m_days` | float | 否 | 航煤工况天数 |
| `day_index` | float | 否 | 天数索引 |
| `plan_month` | str | 否 | 计划月份（价格查表） |
| `shutdown_intervals` | dict | 否 | 停工区间 |
| `capacity_only` | bool | 否 | 仅算能力 |
| `summary_only` | bool | 否 | 简化模式 |
| `prices` | dict | 否 | 预加载价格表（MCP-14 产出） |
| `device_costs` | dict | 否 | 预加载装置成本（MCP-14 产出） |
| `feed_ratios` | dict | 否 | 进料配比（MCP-14 产出） |
| `custom_crude_costs` | dict | 否 | 自定义原油成本 |

**返回**: `{feasible, explanation, device_inputs, connection_flows, device_utilization}`

#### MCP-05: `evaluate_valve_combination`

评估单个阀门切换组合的各批次经济效益。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `combo` | dict | 是 | 阀门组合定义 |
| `batches` | list | 是 | 批次列表（含 crude_type） |
| `custom_crude_costs` | dict | 是 | 原油成本 |
| `hangmei_ctx` | dict | 否 | MCP-07 产出的航煤上下文 |
| `plan_month` | str | 否 | 计划月份 |
| `capacity_only` | bool | 否 | 仅算能力 |
| `summary_only` | bool | 否 | 简化模式 |
| `prices` | dict | 否 | 预加载价格表 |
| `device_costs` | dict | 否 | 预加载装置成本 |
| `feed_ratios` | dict | 否 | 进料配比 |

**返回**: `CombinationResult 序列化 dict（含 feasible/revenue/explanations/...）`

#### MCP-06: `optimize_combinations`

遍历所有阀门切换组合，挑出经济效益最优方案。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `batches` | list | 是 | 批次列表 |
| `combinations` | list | 是 | 阀门组合列表 |
| `custom_crude_costs` | dict | 是 | 原油成本 |
| `hangmei_ctx` | dict | 否 | MCP-07 产出的航煤上下文 |
| `select_month` | str | 否 | 选优月份（上月价） |
| `final_month` | str | 否 | 核算月份（本月价） |
| `prices` | dict | 否 | 预加载价格表 |
| `device_costs` | dict | 否 | 预加载装置成本 |
| `feed_ratios` | dict | 否 | 进料配比 |

**返回**: `{optimal_details, optimal_revenue, optimal_combination, optimal_explanations, optimal_calc_results, optimal_hangmei_summary, combination_results}`

> 字段顺序结论先行；`pass1_results` 仅内部调用时通过 `return_pass1=True` 返回，MCP 不暴露。

#### MCP-07: `init_hangmei_context`

初始化航煤工况上下文（产出 dict 供 MCP-05/06 传入）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `batches` | list | 是 | 批次列表 |
| `hangmei_target` | float | 是 | 航煤目标产出（吨） |
| `custom_crude_costs` | dict | 是 | 原油成本 |
| `plan_month` | str | 否 | 计划月份 |
| `prices` | dict | 否 | 预加载价格表 |

**返回**: `HangmeiContext 序列化 dict`

---

### 分析渲染层（4 个）

#### MCP-08: `aggregate_batch_economics`

聚合最优组合各批次经济效益（SSOT）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `optimal_explanations` | list | 是 | 各批次 explanation 列表 |
| `monthly_load` | dict | 否 | 月度负荷数据 |
| `start_device_id` | str | 否 | 起始装置ID |

**返回**: `聚合经济效益 dict`

#### MCP-09: `render_economic_summary`

从聚合数据生成经济效益说明文字。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agg` | dict | 是 | MCP-08 聚合结果 |
| `actual_profit` | float | 否 | 实际利润（覆盖计算值） |
| `near_feasible` | bool | 否 | 是否近可行方案 |

**返回**: `string（经济效益说明文字）`

#### MCP-10: `build_economic_breakdown`

从聚合数据生成结构化效益拆解。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `agg` | dict | 是 | MCP-08 聚合结果 |
| `processing_device_ids` | list | 否 | 加工装置ID列表 |
| `actual_profit` | float | 否 | 实际利润 |

**返回**: `结构化效益拆解 dict`

#### MCP-11: `analyze_jian1_switch`

减一线切换点供需分析。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `optimal_combo` | dict | 是 | 最优组合信息 |
| `batches` | list | 是 | 批次列表（含 crude_type） |
| `calc_results` | dict | 否 | 计算结果 |

**返回**: `减一线切换分析 dict（含 diesel_diff/wax_diff 顶层字段）`

---

### 可视化+数据层（3 个）

#### MCP-12: `build_flow_diagram`

构建全装置流程图数据（数字孪生视图）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scenario_id` | str | 是 | 原油品种标识 |
| `device_util` | dict | 是 | 装置利用率数据 |
| `device_inputs` | dict | 是 | 装置进料数据 |
| `connection_flows` | dict | 是 | 连接流量数据 |
| `monthly_load` | dict | 否 | 月度负荷数据 |

**返回**: `{nodes: [...], edges: [...]}`

#### MCP-13: `build_device_input_sources`

装置进料来源拆解（"为何超"计算链展示）。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `scenario_id` | str | 是 | 原油品种标识 |
| `device_id` | str | 是 | 目标装置ID |
| `connection_flows` | dict | 是 | 连接流量数据 |
| `special_vars` | dict | 是 | {jian1_to_diesel, jian1_to_wax} |
| `mode` | str | 是 | 收率模式 |

**返回**: `[{source_device, product_name, yield_rate, special_var, flow, ...}, ...]`

#### MCP-14: `preload_reference_data`

预加载引用数据（价格 + 成本 + 配比），产出供 MCP-04/05/06 使用。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `batches` | list | 是 | 批次列表（确定原油品种范围） |
| `plan_month` | str | 是 | 计划月份 |
| `custom_crude_costs` | dict | 否 | 自定义原油成本 |

**返回**: `{prices: {...}, device_costs: {...}, feed_ratios: {...}}`

---

### 参数化规则引擎层（2 个，新增）

#### MCP-15: `assess_feasibility`

参数化可行性判断 — 按自定义规则重新判定组合可行性。替代原先硬编码的罐容/负荷阈值。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `combination_results` | list | 是 | 组合结果列表（MCP-06 的 combination_results） |
| `rules` | dict | 否 | 规则参数（不传用默认规则） |

**rules 字段**:

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `max_load_rate` | float | 100.0 | 月度平均负荷率上限（百分比） |
| `tank_capacity_strict` | bool | True | 罐容违规是否硬约束 |
| `max_overload_count` | int | 0 | 允许的超容装置数上限 |
| `max_overload_ratio` | float | 0.0 | 允许的超容比例 |
| `min_hangmei_output` | float | 0.0 | 航煤最低产出（吨） |
| `require_all_feasible` | bool | False | 是否要求所有批次可行 |

**返回**: `{assessments: [{combination_id, feasible, near_feasible, infeasible_summary, details}], summary}`

#### MCP-16: `select_optimal`

参数化选优 — 按自定义策略选取最优组合。替代原先硬编码的 max(revenue) 逻辑。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `combination_results` | list | 是 | 组合结果列表（含 feasible/near_feasible 标记） |
| `strategy` | dict | 否 | 选优策略（不传用默认策略） |

**strategy 字段**:

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `objective` | str | "revenue" | 选优模式：revenue/feasibility_margin/risk_averse/multi_objective |
| `weights` | dict | `{"revenue": 1.0}` | multi_objective 模式权重 |
| `prefer_near_feasible` | bool | True | 无可行时是否接受接近可行 |
| `penalty_factor` | float | 1.0 | risk_averse 超容惩罚系数 |
| `min_revenue` | float | 0.0 | 最低收益门槛 |

**返回**: `{combination_id, description, total_revenue, score, feasible, near_feasible, is_temporary, infeasible_summary, details}`

> **详细规则字典和策略说明**：参见 `docs/炼化可行性规则字典.md` 和 `docs/选优策略指导.md`

---

## 四、典型调用链

### 链路 1: 综合求解（一键全链路）

```
Agent → MCP-01 solve_refinery_plan(plan_month, production_plans, ...
         feasibility_rules={...}, selection_strategy={...})
         └→ 内部自动编排 MCP-14→03→05→06→08→09→10 + 规则引擎
```

### 链路 2: 分步求解（Agent 自主编排）

```
Agent → MCP-17 prepare_solve_data(plan_month)
     → MCP-14 preload_reference_data(batches, plan_month)
     → MCP-06 optimize_combinations(batches, combos, ..., prices)
     → MCP-15 assess_feasibility(combination_results, rules)  [定制规则]
     → MCP-16 select_optimal(combination_results, strategy)   [定制选优]
     → MCP-08 aggregate_batch_economics(explanations, ...)
     → MCP-09 render_economic_summary(agg)        [文本说明]
     → MCP-10 build_economic_breakdown(agg)       [结构化拆解]
```

### 链路 3: 数据查询+可视化

```
Agent → data_service: list_units / list_side_lines / get_yields
     → MCP-03 calculate_batch_physical(scenario_id, ...)
     → MCP-12 build_flow_diagram(scenario_id, device_util, ...)
     → MCP-13 build_device_input_sources(scenario_id, device_id, ...)
```

### 链路 4: 数据变更+重新求解

```
Agent → data_service: upsert_side_line(data)
     → data_service: upsert_yields(data)
     → MCP-01 solve_refinery_plan(...)            [自动加载最新数据]
```

### 链路 5: 定制化求解（Agent 按用户意图组装规则）

```
用户："减一线不能超负荷，航煤保供5000吨，兼顾利润和安全"

Agent → MCP-01 solve_refinery_plan(
         ...,
         feasibility_rules={"max_load_rate": 95.0, "min_hangmei_output": 5000},
         selection_strategy={"objective": "multi_objective",
           "weights": {"revenue": 0.5, "feasibility_margin": 0.3, "hangmei_output": 0.2}}
       )
```

或分步调用：

```
Agent → MCP-06 optimize_combinations(...) → combination_results
     → MCP-15 assess_feasibility(combination_results,
         {"max_load_rate": 95.0, "min_hangmei_output": 5000})
     → MCP-16 select_optimal(combination_results,
         {"objective": "risk_averse", "penalty_factor": 2.0})
     → MCP-08/09 渲染结果
```

---

## 五、两层协作关系

```
┌─────────────────────────────────────────────────────┐
│                    Agent 决策层                       │
└──────┬──────────────────────────┬────────────────────┘
       │ 数据 CRUD                 │ 业务计算
       ▼                           ▼
┌──────────────────┐    ┌──────────────────────────────┐
│ data_service     │    │ calc_service                 │
│ (28 工具, :8765) │    │ (17 工具, :8766)             │
│                  │    │                              │
│ 装置/储罐/侧线    │    │ 编排入口: MCP-01/02/17       │
│ 收率/物料/价格    │    │ 独立计算: MCP-03/04/05/06/07 │
│ 油种/物流/成本    │    │ 分析渲染: MCP-08/09/10/11    │
│                  │    │ 可视化:   MCP-12/13/14       │
│                  │    │ 规则引擎: MCP-15/16          │
│ ↑ 共享同一 DB    │    │ ↑ scenario_id 适配层         │
└────────┬─────────┘    └──────────┬───────────────────┘
         │                         │
         └────────────┬────────────┘
                      ▼
            ┌──────────────────┐
            │  PostgreSQL DB   │
            └──────────────────┘
```

- **data_service** 管数据 CRUD，calc_service 的 `ScenarioAdapter` 也从同一 DB 加载场景
- **calc_service** 不直接调 data_service MCP，而是通过 `refinery_repo` 共享 DB 表
- **数据变更后**：calc_service 的 `ScenarioAdapter.invalidate()` 清除场景缓存，下次调用自动加载最新数据
