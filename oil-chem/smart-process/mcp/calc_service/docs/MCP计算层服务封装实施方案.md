# MCP 计算层服务封装实施方案

> **基准文档**: `MCP服务封装架构设计.md` v2.0
> **前置条件**: Phase 5（场景依赖声明）✅、Phase 6（scenario_id 适配层）✅ 已完成
> **目标**: 将 14 个计算层函数封装为标准 MCP 服务，Agent 可通过 `scenario_id` + 业务参数独立调用

---

## 一、封装总则

### 1.1 封装模式

每个 MCP 服务 = **适配函数**（`handle_*`）+ **源函数**（原有计算逻辑），适配函数负责：
1. 从 `scenario_id` 加载 `RefineryScenario`（通过 `ScenarioAdapter`）
2. 将 JSON 参数转换为 Python 类型
3. 调用源函数
4. 将结果序列化为 JSON 兼容结构（dataclass → dict，set → list）

### 1.2 目录结构

```
solve_v1/backend/mcp_server/
├── __init__.py          # 包入口
├── adapters.py          # ScenarioAdapter + MultiScenarioAdapter（已有）
├── handlers_analysis.py # MCP-08/09/10/11 适配函数（Phase 1）
├── handlers_data.py     # MCP-14/12/13 适配函数（Phase 2）
├── handlers_calc.py     # MCP-03/04/05/06/07 适配函数（Phase 3）
├── handlers_orchestrate.py # MCP-01/02 适配函数（Phase 4）
└── serializer.py        # dataclass → dict / set → list 序列化工具
```

### 1.3 序列化约定

| Python 类型 | JSON 输出 | 处理方式 |
|------------|-----------|---------|
| `RefineryScenario` | 不输出 | 适配层内部使用，不暴露 |
| `HangmeiContext` | `{...}` dict | `asdict()` 或手动提取字段 |
| `CombinationResult` | `{...}` dict | `asdict()` |
| `CostBreakdown` | `{...}` dict | `asdict()` |
| `set` | `[...]` list | `list(set)` |
| `Decimal` | `float` | `float(value)` |
| `datetime` | `str` | `isoformat()` |

---

## 二、分阶段实施

### Phase 1: 分析渲染层（4 MCP，S 级，零依赖）

**特点**: 源函数全是纯数据变换，无 scenario 依赖（MCP-11 除外），封装成本最低。

#### MCP-08: `aggregate_batch_economics`

| 维度 | 内容 |
|------|------|
| 源函数 | `aggregate_economics()` in `economic_reporter.py` |
| 签名 | `(optimal_explanations: list, monthly_load: dict = None, start_device_id: str = None) -> dict` |
| scenario 依赖 | 无 |
| 适配工作 | 直接暴露，无转换 |

**适配函数**:
```python
# handlers_analysis.py
def handle_aggregate_batch_economics(optimal_explanations: list,
                                     monthly_load: dict = None,
                                     start_device_id: str = None) -> dict:
    """MCP-08: 聚合最优组合各批次经济效益。"""
    from ..calculation.economic_reporter import aggregate_economics
    return aggregate_economics(optimal_explanations, monthly_load, start_device_id)
```

#### MCP-09: `render_economic_summary`

| 维度 | 内容 |
|------|------|
| 源函数 | `build_economic_explanation()` in `economic_reporter.py` |
| 签名 | `(agg: dict, actual_profit: float = None, near_feasible: bool = False) -> str` |
| scenario 依赖 | 无 |

**适配函数**:
```python
def handle_render_economic_summary(agg: dict,
                                   actual_profit: float = None,
                                   near_feasible: bool = False) -> str:
    """MCP-09: 从聚合数据生成经济效益说明文字。"""
    from ..calculation.economic_reporter import build_economic_explanation
    return build_economic_explanation(agg, actual_profit, near_feasible)
```

#### MCP-10: `build_economic_breakdown`

| 维度 | 内容 |
|------|------|
| 源函数 | `build_economic_breakdown()` in `economic_reporter.py` |
| 签名 | `(agg: dict, processing_device_ids: set = None, actual_profit: float = None) -> dict` |
| scenario 依赖 | 无 |
| 注意 | `processing_device_ids: set` 不可 JSON 序列化，适配层接受 list 并转 set |

**适配函数**:
```python
def handle_build_economic_breakdown(agg: dict,
                                    processing_device_ids: list = None,
                                    actual_profit: float = None) -> dict:
    """MCP-10: 从聚合数据生成结构化效益拆解。"""
    from ..calculation.economic_reporter import build_economic_breakdown
    pids = set(processing_device_ids) if processing_device_ids else None
    return build_economic_breakdown(agg, pids, actual_profit)
```

#### MCP-11: `analyze_jian1_switch`

| 维度 | 内容 |
|------|------|
| 源函数 | `build_jian1_switch_analysis()` in `switch_analysis.py` |
| 签名 | `(optimal_combo, batches, scenarios: Dict[str, RefineryScenario], calc_results=None) -> dict` |
| scenario 依赖 | `scenarios: Dict[str, RefineryScenario]`（多场景） |
| 适配工作 | 需 `MultiScenarioAdapter` 按 batches 加载场景 |

**注意**: 此函数已在 `adapters.py` 中有 `handle_analyze_jian1_switch`，但签名不完整（缺少 `optimal_combo`/`batches`/`calc_results` 参数）。需修正为完整签名。

**修正后适配函数**:
```python
def handle_analyze_jian1_switch(optimal_combo: dict,
                                batches: list,
                                scenario_ids: list = None,
                                calc_results: dict = None,
                                adapter: ScenarioAdapter = None) -> dict:
    """MCP-11: 减一线切换点供需分析。

    scenario_ids: 需加载的原油品种列表；若 None 则从 batches 提取 crude_type。
    """
    from ..calculation.switch_analysis import build_jian1_switch_analysis
    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches)
    return build_jian1_switch_analysis(optimal_combo, batches, scenarios, calc_results)
```

**Phase 1 产出文件**: `handlers_analysis.py`、`serializer.py`

---

### Phase 2: 数据预加载 + 可视化（3 MCP）

#### MCP-14: `preload_reference_data`

| 维度 | 内容 |
|------|------|
| 源函数 | `SolveService._preload_reference_data()` |
| 签名 | `(self, batches, scenarios, plan_month, custom_crude_costs=None) -> (prices, device_costs, feed_ratios)` |
| scenario 依赖 | `scenarios`（多场景，函数内会就地填充） |
| 适配工作 | 需将 SolveService 实例方法提取为独立函数，或通过适配层持有 SolveService 实例 |

**设计决策**: 适配层持有 `SolveService` 实例（复用其 DB 连接和 repo），调用 `_preload_reference_data` 后返回三元组。

**适配函数**:
```python
# handlers_data.py
def handle_preload_reference_data(batches: list,
                                  plan_month: str,
                                  custom_crude_costs: dict = None,
                                  adapter: ScenarioAdapter = None) -> dict:
    """MCP-14: 预加载价格/成本/配比。

    Returns:
        {prices: {...}, device_costs: {...}, feed_ratios: {...}}
    """
    from ..service.solve_service import SolveService
    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches, custom_crude_costs)

    service = SolveService()
    prices, device_costs, feed_ratios = service._preload_reference_data(
        batches, scenarios, plan_month, custom_crude_costs)

    return {
        "prices": prices,
        "device_costs": device_costs,
        "feed_ratios": feed_ratios,
    }
```

#### MCP-12: `build_flow_diagram`

已在 `adapters.py` 中实现 `handle_build_flow_diagram`，迁移到 `handlers_data.py`。

#### MCP-13: `build_device_input_sources`

已在 `adapters.py` 中实现 `handle_build_device_input_sources`，迁移到 `handlers_data.py`。

**Phase 2 产出文件**: `handlers_data.py`（含 MCP-14 新增 + MCP-12/13 迁移）

---

### Phase 3: 独立计算层（5 MCP）

#### MCP-03: `calculate_batch_physical`

已在 `adapters.py` 中实现 `handle_calculate_physical`，迁移到 `handlers_calc.py`。

#### MCP-04: `calculate_batch_full`

已在 `adapters.py` 中实现 `handle_calculate_direct`，迁移到 `handlers_calc.py`。

#### MCP-05: `evaluate_valve_combination`

| 维度 | 内容 |
|------|------|
| 源函数 | `evaluate_combination()` in `combination_evaluator.py` |
| 签名 | 14 个参数（含 `scenarios`, `hangmei_ctx`, `logger`, `_physical_cache`, `_hangmei_precompute_cache`） |
| scenario 依赖 | `scenarios: Dict[str, RefineryScenario]` |
| 适配工作 | scenarios 由 MultiScenarioAdapter 加载；hangmei_ctx 由 MCP-07 产出或 None；logger/cache 适配层内部创建 |

**适配函数**:
```python
# handlers_calc.py
def handle_evaluate_valve_combination(
    combo: dict,
    batches: list,
    custom_crude_costs: dict,
    hangmei_ctx: dict = None,    # MCP-07 产出的 dict（需反序列化）
    plan_month: str = None,
    capacity_only: bool = False,
    summary_only: bool = False,
    prices: dict = None,
    device_costs: dict = None,
    feed_ratios: dict = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-05: 评估单个阀门切换组合的各批次经济效益。"""
    from ..calculation.combination_evaluator import evaluate_combination
    from ..calculation.hangmei_optimizer import HangmeiContext

    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches, custom_crude_costs)

    # hangmei_ctx 反序列化（dict → HangmeiContext，若需要）
    hm_ctx = None  # 简化：MCP-05 直接透传 None 或由调用方构造

    result = evaluate_combination(
        combo=combo, batches=batches, scenarios=scenarios,
        custom_crude_costs=custom_crude_costs,
        hangmei_ctx=hm_ctx, plan_month=plan_month,
        logger=sa._repo._get_logger() if hasattr(sa._repo, '_get_logger') else None,
        capacity_only=capacity_only, summary_only=summary_only,
        prices=prices, device_costs=device_costs, feed_ratios=feed_ratios,
    )

    if result is None:
        return {"feasible": False, "reason": "评估失败"}
    # CombinationResult → dict
    return result.to_dict() if hasattr(result, 'to_dict') else {"feasible": True, "data": str(result)}
```

**注意**: `HangmeiContext` 的序列化/反序列化是 Phase 3 的重点难点。需检查 HangmeiContext 的字段结构，设计 `serialize_hangmei_context()` / `deserialize_hangmei_context()` 工具函数。

#### MCP-06: `optimize_combinations`

| 维度 | 内容 |
|------|------|
| 源函数 | `optimize_combinations()` in `combination_optimizer.py` |
| 签名 | 11 个参数 |
| scenario 依赖 | `scenarios: Dict[str, RefineryScenario]` |

**适配函数**:
```python
def handle_optimize_combinations(
    batches: list,
    combinations: list,
    custom_crude_costs: dict,
    hangmei_ctx: dict = None,
    select_month: str = None,
    final_month: str = None,
    prices: dict = None,
    device_costs: dict = None,
    feed_ratios: dict = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-06: 遍历所有组合，挑出经济效益最优方案。"""
    # 类似 MCP-05，scenarios 由 MultiScenarioAdapter 加载
```

#### MCP-07: `init_hangmei_context`

| 维度 | 内容 |
|------|------|
| 源函数 | `build_hangmei_context()` in `hangmei_optimizer.py` |
| 签名 | 7 个参数 |
| 返回 | `HangmeiContext`（非 JSON 可序列化） |
| 适配工作 | 返回值需序列化为 dict |

**适配函数**:
```python
def handle_init_hangmei_context(
    batches: list,
    hangmei_target: float,
    custom_crude_costs: dict,
    plan_month: str = None,
    prices: dict = None,
    adapter: ScenarioAdapter = None,
) -> dict:
    """MCP-07: 初始化航煤工况上下文。

    Returns:
        HangmeiContext 序列化后的 dict（供 MCP-05/06 传入）
    """
    from ..calculation.hangmei_optimizer import build_hangmei_context

    sa = adapter or ScenarioAdapter()
    msa = MultiScenarioAdapter(sa)
    scenarios = msa.load_by_batches(batches, custom_crude_costs)

    ctx = build_hangmei_context(
        batches=batches, scenarios=scenarios,
        hangmei_target=hangmei_target,
        custom_crude_costs=custom_crude_costs,
        plan_month=plan_month, prices=prices,
    )

    # 序列化 HangmeiContext → dict
    from .serializer import serialize_hangmei_context
    return serialize_hangmei_context(ctx)
```

**Phase 3 产出文件**: `handlers_calc.py`（含 MCP-03/04 迁移 + MCP-05/06/07 新增）、`serializer.py` 补充 HangmeiContext 序列化

---

### Phase 4: 编排入口层（2 MCP）

#### MCP-01: `solve_refinery_plan`

| 维度 | 内容 |
|------|------|
| 源函数 | `SolveService.comprehensive_solve()` |
| 签名 | `(self, plan_month, production_plans_input, monthly_crude_input, blend_mode, save_data, hangmei_target, shutdown_config=None, plan_source='lp', simplified=True) -> dict` |
| scenario 依赖 | 内部自行加载（不暴露给 Agent） |
| 适配工作 | 最低 — 直接委托 SolveService 实例 |

**适配函数**:
```python
# handlers_orchestrate.py
def handle_solve_refinery_plan(
    plan_month: str,
    production_plans_input: list,
    monthly_crude_input: float,
    blend_mode: bool = False,
    save_data: bool = True,
    hangmei_target: float = None,
    shutdown_config: list = None,
    simplified: bool = True,
) -> dict:
    """MCP-01: 综合求解 — 排产→批次→阀门枚举→选优。"""
    from ..service.solve_service import SolveService
    service = SolveService()
    return service.comprehensive_solve(
        plan_month=plan_month,
        production_plans_input=production_plans_input,
        monthly_crude_input=monthly_crude_input,
        blend_mode=blend_mode,
        save_data=save_data,
        hangmei_target=hangmei_target,
        shutdown_config=shutdown_config,
        simplified=simplified,
    )
```

#### MCP-02: `optimize_valve_switches`

| 维度 | 内容 |
|------|------|
| 源函数 | `SolveService.optimize_valve()` |
| 签名 | `(self, plan_id: str) -> dict` |
| scenario 依赖 | 内部自行加载 |

**适配函数**:
```python
def handle_optimize_valve_switches(plan_id: str) -> dict:
    """MCP-02: 优化阀门切换（基于已存在计划）。"""
    from ..service.solve_service import SolveService
    service = SolveService()
    return service.optimize_valve(plan_id=plan_id)
```

**Phase 4 产出文件**: `handlers_orchestrate.py`

---

## 三、序列化工具设计

### 3.1 `serializer.py` 核心函数

```python
# serializer.py
from dataclasses import asdict, is_dataclass
from decimal import Decimal
from typing import Dict, Any

def to_jsonable(obj: Any) -> Any:
    """递归将 Python 对象转为 JSON 可序列化结构。"""
    if obj is None:
        return None
    if isinstance(obj, (str, int, float, bool)):
        return obj
    if isinstance(obj, Decimal):
        return float(obj)
    if isinstance(obj, set):
        return list(obj)
    if is_dataclass(obj) and not isinstance(obj, type):
        return {k: to_jsonable(v) for k, v in asdict(obj).items()}
    if isinstance(obj, dict):
        return {k: to_jsonable(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [to_jsonable(v) for v in obj]
    return str(obj)  # 兜底


def serialize_hangmei_context(ctx) -> dict:
    """HangmeiContext → JSON dict（供 MCP-07 输出、MCP-05/06 输入）。"""
    if ctx is None:
        return None
    return {
        'hangmei_target': ctx.hangmei_target,
        'hangmei_batches': ctx.hangmei_batches,
        'm_days': ctx.m_days,
        'device_inputs': to_jsonable(ctx.device_inputs),
        'hangmei_active_device_ids': list(ctx.hangmei_active_device_ids),
        # ... 其他字段按实际 HangmeiContext 定义补齐
    }
```

### 3.2 HangmeiContext 字段检查

需读取 `hangmei_optimizer.py` 中 `HangmeiContext` dataclass 定义，确认全部字段后补齐序列化逻辑。

---

## 四、实施顺序与验证

### 4.1 实施顺序

| 步骤 | 内容 | 依赖 |
|------|------|------|
| 1 | 创建 `serializer.py` | 无 |
| 2 | 创建 `handlers_analysis.py`（MCP-08/09/10/11） | serializer.py |
| 3 | 创建 `handlers_data.py`（MCP-14/12/13） | adapters.py, serializer.py |
| 4 | 创建 `handlers_calc.py`（MCP-03/04/05/06/07） | adapters.py, serializer.py |
| 5 | 创建 `handlers_orchestrate.py`（MCP-01/02） | 无额外依赖 |
| 6 | 迁移 `adapters.py` 中的 handle_* 到对应 handlers_*.py | — |
| 7 | 更新 `__init__.py` 统一导出 | 全部 handlers |

### 4.2 验证标准

每个 Phase 完成后验证：
1. **导入测试**: `python -c "from calc_service.backend.mcp_server import *"`
2. **单元调用**: 用真实 scenario_id 调用每个 handle_* 函数，确认返回 JSON 兼容结构
3. **序列化验证**: 确认返回值中无 `RefineryScenario`/`HangmeiContext`/`set` 等不可序列化类型

### 4.3 不做的事

- 不创建 MCP Server 进程（FastMCP/HTTP 服务）— 本次只做适配函数层
- 不修改原有计算函数签名 — 适配层在外部包装
- 不实现缓存失效通知 — `ScenarioAdapter.invalidate()` 已提供手动接口
