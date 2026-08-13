# MCP 输出优化问题分析与实施方案

> **基准文档**: `MCP服务封装架构设计.md` v2.0 / `求解计算MCP接口文档.md` v2.0
> **排查范围**: calc_service（14 工具）+ data_service（28 工具），共 42 个 MCP 工具
> **核心原则**: 优先更改后端源实现，MCP 适配层保持简单封装

---

## 一、问题总览

全量排查发现 **5 个问题**，按优先级排列：

| 编号 | 优先级 | 服务 | 工具 | 问题类型 | 影响 |
|------|--------|------|------|----------|------|
| P0 | 最高 | calc_service | `optimize_combinations`（MCP-06） | 内部缓存泄漏 + 字段顺序倒置 | 返回体积极大，Agent 无法获取最优结论 |
| P1 | 高 | calc_service | `evaluate_valve_combination`（MCP-05） | 字段顺序倒置 | 截断时丢失可行性/瓶颈信息 |
| P2 | 中 | calc_service | `analyze_jian1_switch`（MCP-11） | 结论埋在嵌套末尾 | Agent 需深度解析才能提取关键结论 |
| P3 | 中 | data_service | `get_prices` | 文档与实现不符 | 空参时返回 `{}` 而非全量价格 |
| P4 | 低 | data_service | 7 个列表工具 | 全量返回无分页 | 随数据增长有膨胀风险 |

**已完成的优化**（本次排查前已修复）：
- `solve_refinery_plan`（MCP-01）和 `optimize_valve_switches`（MCP-02）：已实现 `simplified` 摘要模式 + 结论先行字段顺序。本次不重复。

---

## 二、问题详细分析与解决方案

### P0: `optimize_combinations`（MCP-06）— 最高风险

#### 问题描述

`optimize_combinations` 是组合寻优的核心函数，返回结果存在双重问题：

**问题 A — 内部缓存泄漏**：

返回 dict 中包含 `pass1_results` 字段，这是 R2 优化的物理计算复用缓存，存储全部组合的完整 `CombinationResult` 对象（含 `calc_results`/`explanations`/`batch_details`/`monthly_load`），体积占整体返回的 60%+。该字段仅供 `comprehensive_solve` 的 PASS 4 阶段内部复用，对 Agent / MCP 调用方完全无业务意义。

**问题 B — 字段顺序倒置**：

`combination_results`（全量明细列表，最大字段）排在第 1 位，`optimal_revenue`/`optimal_details`（最优结论）排在第 3-4 位。截断时 Agent 只能看到一堆组合明细，看不到"哪个是最优方案"。

#### 源码定位

| 文件 | 行号 | 代码 |
|------|------|------|
| `solve_v1/backend/calculation/combination_optimizer.py` | L162-L171 | 返回 dict 构造，`pass1_results` 在 L170 |
| `solve_v1/backend/mcp_server/handlers_calc.py` | L283 | `to_jsonable(result)` 直接透传，未裁剪 |

当前返回结构：

```python
# combination_optimizer.py L162-L171
return {
    'combination_results': combination_results,    # ← 全量明细，排第1（最大）
    'optimal_combination': optimal_combination,    # ← 排第2
    'optimal_details': optimal_details,            # ← 最优结论，排第3
    'optimal_revenue': optimal_revenue,            # ← 核心数字，排第4
    'optimal_explanations': optimal_explanations,
    'optimal_calc_results': optimal_calc_results,
    'optimal_hangmei_summary': optimal_hangmei_summary,
    'pass1_results': pass1_results,                # ← 内部缓存泄漏！
}
```

#### 解决方案

**在源函数 `optimize_combinations` 中修复**（不是在 MCP 适配层）：

1. **移除 `pass1_results`**：改为通过函数参数回传（`pass1_results` 已由调用方 `SolveService` 通过 `SolutionStage` 对象持有，不需要在返回 dict 中重复）
2. **调整字段顺序**：结论先行，明细后行

改动后的返回结构：

```python
# combination_optimizer.py — 改动后
return {
    # ── ① 结论型（Agent 最关心）──
    'optimal_details': optimal_details,            # 最优组合标识
    'optimal_revenue': optimal_revenue,            # 最优效益
    'optimal_combination': optimal_combination,    # 最优组合完整对象
    # ── ② 最优组合明细 ──
    'optimal_explanations': optimal_explanations,
    'optimal_calc_results': optimal_calc_results,
    'optimal_hangmei_summary': optimal_hangmei_summary,
    # ── ③ 全量组合列表（排最后）──
    'combination_results': combination_results,
    # pass1_results 移除：调用方通过 SolutionStage.pass1_results 持有
}
```

#### 调用方适配

`SolveService._evaluate_and_pick`（[solve_service.py L519-L532](file:///d:/coding/huilian_tag_meixiao/solve_v1/backend/service/solve_service.py#L519-L532)）当前从返回 dict 中取 `pass1_results`：

```python
# 当前代码（L524-L532）
opt = optimize_combinations(...)
return SolutionStage(
    ...
    pass1_results=opt.get('pass1_results'),  # ← 从返回 dict 取
)
```

改动后需确认 `pass1_results` 的传递路径不被破坏。`optimize_combinations` 内部已将 `pass1_results` 作为局部变量持有，只需在返回 dict 中移除该字段，调用方通过其他途径获取即可。

**具体方案**：`optimize_combinations` 增加一个 `return_pass1: bool = False` 参数，仅在 `SolveService` 调用时传 `True`，返回 dict 中才包含 `pass1_results`；MCP 适配层默认 `False`，不返回该字段。

---

### P1: `evaluate_valve_combination`（MCP-05）— 高风险

#### 问题描述

`serialize_combination_result` 中 `feasible`（可行性）排在第 12 位，前面有 `calc_results`/`explanations` 两个大体积 list（每个含多批次深层嵌套 dict）。若返回被截断，可行性与瓶颈信息（`bottleneck_devices`/`infeasible_summary`）会丢失，Agent 无法判断组合是否可行。

#### 源码定位

| 文件 | 行号 | 代码 |
|------|------|------|
| `solve_v1/backend/mcp_server/serializer.py` | L63-L85 | `serialize_combination_result` 函数 |

当前字段顺序：

```python
# serializer.py L67-L85 — 当前顺序
return {
    'combination_id': ...,        # 1
    'description': ...,           # 2
    'switch_position': ...,       # 3
    'initial_mode': ...,          # 4
    'switches': ...,              # 5
    'total_revenue': ...,         # 6
    'total_cost': ...,            # 7
    'batch_results': ...,         # 8  ← 大列表
    'explanations': ...,          # 9  ← 大列表
    'calc_results': ...,          # 10 ← 最大列表
    'hangmei_summary': ...,       # 11
    'feasible': ...,              # 12 ← 结论埋在这里！
    'bottleneck_devices': ...,    # 13
    'infeasible_summary': ...,    # 14
    'tank_check_result': ...,     # 15
    'batch_details': ...,         # 16
    'monthly_load': ...,          # 17
}
```

#### 解决方案

**在 `serialize_combination_result` 中调整字段顺序**：结论先行，明细后行。

```python
# serializer.py — 改动后
def serialize_combination_result(result) -> Dict:
    """CombinationResult → JSON dict。

    字段顺序：结论先行（feasible/revenue），明细后行（calc_results/explanations）。
    """
    if result is None:
        return None
    return {
        # ── ① 结论型（Agent 最关心）──
        'feasible': result.feasible,
        'total_revenue': result.total_revenue,
        'combination_id': result.combination_id,
        'description': result.description,
        'infeasible_summary': result.infeasible_summary,
        'bottleneck_devices': to_jsonable(result.bottleneck_devices),
        # ── ② 配置型 ──
        'switch_position': result.switch_position,
        'initial_mode': result.initial_mode,
        'switches': to_jsonable(result.switches),
        'total_cost': result.total_cost,
        # ── ③ 明细型（大体积，排后面）──
        'batch_results': to_jsonable(result.batch_results),
        'hangmei_summary': to_jsonable(result.hangmei_summary),
        'tank_check_result': to_jsonable(result.tank_check_result),
        'monthly_load': to_jsonable(result.monthly_load),
        'explanations': to_jsonable(result.explanations),
        'batch_details': to_jsonable(result.batch_details),
        'calc_results': to_jsonable(result.calc_results),
    }
```

---

### P2: `analyze_jian1_switch`（MCP-11）— 中风险

#### 问题描述

核心结论 `diff`（供需缺口）被嵌套在 `diesel`/`wax` 两个子 dict 的第三个字段中，前 8 个顶层字段全是过程参数（天数、模式、日均量）。Agent 需读到末尾嵌套才能获得结论。

#### 源码定位

| 文件 | 行号 | 代码 |
|------|------|------|
| `solve_v1/backend/calculation/switch_analysis.py` | L156-L175 | `build_jian1_switch_analysis` 返回结构 |

当前返回结构：

```python
# switch_analysis.py L156-L175 — 当前
return {
    'switch_day': ...,               # 1  过程参数
    'switch_batch_id': ...,          # 2  过程参数
    'initial_mode': ...,             # 3  过程参数
    'initial_mode_cn': ...,          # 4  过程参数
    'diesel_avg_daily': ...,         # 5  过程参数
    'wax_avg_daily': ...,            # 6  过程参数
    'diesel_processing_days': ...,   # 7  过程参数
    'wax_processing_days': ...,      # 8  过程参数
    'diesel': {                      # 9  ← diff 埋在这里
        'cdu_output': ...,
        'device_demand': ...,
        'diff': ...,                 # ← 核心结论
    },
    'wax': {                         # 10
        'cdu_output': ...,
        'device_demand': ...,
        'diff': ...,                 # ← 核心结论
    },
}
```

#### 解决方案

**在源函数 `build_jian1_switch_analysis` 中调整返回结构**：

将 `diff` 提升到顶层，让 Agent 一眼看到供需缺口结论；保留 `diesel`/`wax` 子 dict 供需要明细的场景使用。

```python
# switch_analysis.py — 改动后
return {
    # ── ① 结论型（供需缺口，Agent 最关心）──
    'diesel_diff': round(diesel_cdu_output - diesel_device_demand, 1),
    'wax_diff': round(wax_cdu_output - wax_device_demand, 1),
    # ── ② 切换信息 ──
    'switch_day': round(switch_day, 2),
    'switch_batch_id': switch_batch_id,
    'initial_mode': initial_mode,
    'initial_mode_cn': mode_cn,
    # ── ③ 过程参数 ──
    'diesel_avg_daily': round(diesel_avg_daily, 1),
    'wax_avg_daily': round(wax_avg_daily, 1),
    'diesel_processing_days': round(diesel_processing_days, 2),
    'wax_processing_days': round(wax_processing_days, 2),
    # ── ④ 明细（供详细分析）──
    'diesel': {
        'cdu_output': round(diesel_cdu_output, 1),
        'device_demand': round(diesel_device_demand, 1),
        'diff': round(diesel_cdu_output - diesel_device_demand, 1),
    },
    'wax': {
        'cdu_output': round(wax_cdu_output, 1),
        'device_demand': round(wax_device_demand, 1),
        'diff': round(wax_cdu_output - wax_device_demand, 1),
    },
}
```

---

### P3: `get_prices`（data_service）— 文档与实现不符

#### 问题描述

MCP 工具 `get_prices` 的 docstring 写"material_ids 空字符串取全部"，但实际实现中 `resolve_prices_batch` 在 `material_ids` 为空时直接 `return {}`。即不传 material_ids 时返回空对象，而非全量价格。同时，完整价格明细函数 `load_material_prices`（含 `price/default_price/is_overridden` 字段）未通过任何 MCP 工具暴露。

#### 源码定位

| 文件 | 行号 | 代码 |
|------|------|------|
| `backend/data_service/mcp/read_tools.py` | L79-L90 | `get_prices` 工具定义 + docstring |
| `backend/data_service/repositories/price_repo.py` | L199-L207 | `resolve_prices_batch` 空参时返回 `{}` |
| `backend/data_service/repositories/price_repo.py` | L32-L66 | `load_material_prices` 未被 MCP 暴露 |

#### 解决方案

**方案 A（推荐）**：修正 docstring + 空参时调用 `load_material_prices` 返回全量明细

```python
# read_tools.py — 改动后
@mcp.tool()
def get_prices(month: str = "", material_ids: str = "") -> str:
    """查询物料价格（含计算规则回退）。

    Args:
        month: 月份 YYYY-MM，空字符串取最新
        material_ids: 逗号分隔的物料 ID（如 "1,2,3"）。
                      空字符串时返回全部物料价格明细（含 default_price/is_overridden）。
    """
    ids = None
    if material_ids:
        ids = [int(x.strip()) for x in material_ids.split(",") if x.strip()]
    with get_session() as db:
        if ids:
            # 指定物料：返回 {material_id_str: price} 扁平 dict
            return _json(price_repo.resolve_prices_batch(db, month or None, ids))
        else:
            # 全量：返回明细列表 [{material_id, material_name, price, ...}]
            return _json(price_repo.load_material_prices(db, month or None))
```

---

### P4: data_service 大表全量返回无分页

#### 问题描述

以下 7 个工具无 `limit/offset` 参数，全量返回整张表数据，随数据增长有膨胀风险：

| 工具 | 风险 | 原因 |
|------|------|------|
| `get_yields`（不传参） | 最高 | 侧线×油种笛卡尔积，可达 4500 行 |
| `list_flows` | 高 | 无任何过滤参数，单条 13 字段 |
| `list_materials` | 中 | 全量返回物料主数据 |
| `list_tanks` | 中 | 储罐数量可能上百条 |
| `list_side_lines`（不传参） | 中 | 侧线 50-200 条 |
| `get_side_line_prices` | 中 | 全侧线价格联合查询 |
| `list_crudes` | 低 | 油种通常几十条 |

#### 解决方案

**为高风险工具增加可选 `limit` 参数**（不改必填，向后兼容）：

优先修改 `get_yields` 和 `list_flows`（风险最高的两个），其余视数据增长情况后续跟进。

```python
# read_tools.py — get_yields 改动后
@mcp.tool()
def get_yields(side_line_id: str = "", crude_type: str = "", limit: int = 0) -> str:
    """查询收率，可按侧线和油种过滤。

    Args:
        side_line_id: 侧线ID，空字符串取全部
        crude_type: 油种标识，空字符串取全部
        limit: 返回条数上限（0=不限）。建议传入避免全量返回。
    """
    with get_session() as db:
        rows = price_repo.load_yields(db, side_line_id or None, crude_type or None)
        if limit > 0:
            rows = rows[:limit]
        return _json(rows)
```

```python
# read_tools.py — list_flows 改动后
@mcp.tool()
def list_flows(limit: int = 0) -> str:
    """查询全部物流边。

    Args:
        limit: 返回条数上限（0=不限）。
    """
    with get_session() as db:
        rows = flow_repo.load_flows(db)
        if limit > 0:
            rows = rows[:limit]
        return _json(rows)
```

---

## 三、实施计划

### 3.1 实施顺序

| 步骤 | 内容 | 涉及文件 | 优先级 |
|------|------|----------|--------|
| 1 | P0: `optimize_combinations` 移除 pass1_results + 调整字段顺序 | `combination_optimizer.py`、`handlers_calc.py` | 最高 |
| 2 | P1: `serialize_combination_result` 调整字段顺序 | `serializer.py` | 高 |
| 3 | P2: `build_jian1_switch_analysis` 提升 diff 到顶层 | `switch_analysis.py` | 中 |
| 4 | P3: `get_prices` 修正空参行为 | `read_tools.py` | 中 |
| 5 | P4: `get_yields`/`list_flows` 增加 limit 参数 | `read_tools.py` | 低 |

### 3.2 验证标准

每个步骤完成后验证：

1. **语法检查**：`python -c "import ast; ast.parse(open(...).read())"`
2. **E2E 测试**：运行 `e2e_test.py` 确认现有测试不回归
3. **MCP 重启**：重启 calc_service / data_service MCP 进程，确认工具调用正常
4. **字段顺序验证**：用 MCP 工具调用一次，确认返回 JSON 中结论字段在前

### 3.3 风险评估

| 步骤 | 风险 | 缓解措施 |
|------|------|----------|
| 步骤1 | `pass1_results` 移除后 `SolveService` 可能丢失 PASS 4 复用数据 | 增加 `return_pass1` 参数，调用方按需获取 |
| 步骤2 | 前端解析 JSON 时字段顺序变化不影响（JSON 无序），但需确认前端无硬编码位置索引 | JSON 字段是按 key 取值，不依赖顺序 |
| 步骤3 | 前端若直接读 `diesel.diff` 需同步改为也支持 `diesel_diff` | 保留 `diesel`/`wax` 子 dict 不删除，只是额外提升到顶层 |
| 步骤4 | 空参时返回格式从 `{}` 变为 `[{...}]`，可能影响调用方 | MCP docstring 明确标注两种返回格式 |
| 步骤5 | `limit` 参数为可选，默认 0 不限，完全向后兼容 | 无风险 |

### 3.4 不做的事

- 不拆分 MCP 工具为"摘要+详情"两个工具（源实现优化已足够，不需要增加工具数量）
- 不修改 MCP 适配层的封装模式（保持"适配层只做参数转换+序列化"原则）
- 不修改 data_service 写工具（返回体量小，无截断风险）
- 不对低风险工具（`list_units`/`find_crude`/`get_feed_ratio` 等）做改动

---

## 四、附录：全量排查结果汇总

### calc_service（14 工具）

| # | 工具 | 体积 | 结论先行 | 风险 | 状态 |
|---|------|------|----------|------|------|
| MCP-01 | `solve_refinery_plan` | 大→小 | 是（已优化） | 低 | ✅ 已修复 |
| MCP-02 | `optimize_valve_switches` | 大→小 | 是（已优化） | 低 | ✅ 已修复 |
| MCP-03 | `calculate_batch_physical` | 中 | 是 | 低 | 无需改动 |
| MCP-04 | `calculate_batch_full` | 中偏大 | 是 | 低 | 无需改动 |
| MCP-05 | `evaluate_valve_combination` | 大 | **否** | **高** | ⬜ 待修复 P1 |
| MCP-06 | `optimize_combinations` | 极大 | **否** | **最高** | ⬜ 待修复 P0 |
| MCP-07 | `init_hangmei_context` | 中 | 是 | 低 | 无需改动 |
| MCP-08 | `aggregate_batch_economics` | 大 | 是 | 中 | 无需改动 |
| MCP-09 | `render_economic_summary` | 大 | 是 | 中 | 无需改动 |
| MCP-10 | `build_economic_breakdown` | 大 | 是 | 中 | 无需改动 |
| MCP-11 | `analyze_jian1_switch` | 中小 | **否** | 中 | ⬜ 待修复 P2 |
| MCP-12 | `build_flow_diagram` | 大 | N/A | 中 | 无需改动 |
| MCP-13 | `build_device_input_sources` | 中小 | 隐式是 | 低 | 无需改动 |
| MCP-14 | `preload_reference_data` | 中 | N/A | 低 | 无需改动 |

### data_service（15 读工具）

| # | 工具 | 体积 | 分页 | 风险 | 状态 |
|---|------|------|------|------|------|
| 1 | `list_units` | 小～中 | 无 | 低 | 无需改动 |
| 2 | `list_tanks` | 中 | 无 | 中 | 后续跟进 |
| 3 | `list_side_lines` | 中 | 仅过滤 | 中 | 后续跟进 |
| 4 | `get_yields` | 中～大 | 无 | **最高** | ⬜ 待修复 P4 |
| 5 | `get_side_lines_with_yields` | 中 | 无 | 中 | 后续跟进 |
| 6 | `list_materials` | 中 | 无 | 中 | 后续跟进 |
| 7 | `get_feed_ratio` | 极小 | LIMIT 1 | 低 | 无需改动 |
| 8 | `get_material_mapping` | 中 | 无 | 低 | 无需改动 |
| 9 | `get_prices` | 取决于入参 | 无 | **中** | ⬜ 待修复 P3 |
| 10 | `get_side_line_prices` | 中 | 无 | 中 | 后续跟进 |
| 11 | `get_device_costs` | 小 | 无 | 低 | 无需改动 |
| 12 | `get_price_months` | 小 | 无 | 低 | 无需改动 |
| 13 | `list_flows` | 中～大 | 无 | **高** | ⬜ 待修复 P4 |
| 14 | `list_crudes` | 小～中 | 仅过滤 | 低 | 无需改动 |
| 15 | `find_crude` | 极小 | LIMIT 1 | 低 | 无需改动 |
