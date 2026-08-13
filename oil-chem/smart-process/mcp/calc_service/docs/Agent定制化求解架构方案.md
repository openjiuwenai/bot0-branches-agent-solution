# Agent 定制化求解架构方案

> **版本**: v1.0 | **日期**: 2026-08
> **目标**: 将现有 solve_v1 求解应用从"硬编码不可定制"改造为"Agent 可定制化求解"
> **核心原则**: Agent 在业务方案层面做决策，引擎在计算层面做确定性计算

---

## 一、问题诊断

### 1.1 当前系统的核心短板

| 短板 | 表现 | 根因 |
|------|------|------|
| 规则不可定制 | 可行性判断阈值硬编码（负荷上限 1.0、罐容硬约束） | 规则未参数化 |
| 选优不可定制 | 固定 `max(revenue)` 单目标 | 选优策略未参数化 |
| 切换方案不可定制 | 只支持批次间切换、一个月一次 | `generate_switch_combinations` 硬编码枚举 |
| Agent 无法编排 | 中间数据体积大，经 MCP 传输截断 | MCP 工具粒度设计错误 |

### 1.2 MCP 工具粒度问题（根因分析）

**根根因不是传输限制，而是 MCP 工具粒度设计错误**——把求解管线的内部步骤暴露为独立 MCP 工具，导致大体积中间数据被迫经过 Agent 上下文传输。

求解管线 8 步：

```
排产加载 → 批次划分 → 组合枚举 → 物理计算 → 经济计算 → 可行性判断 → 选优 → 渲染
   ①          ②          ③          ④          ⑤          ⑥          ⑦      ⑧
```

步骤间传递的是大体积数据结构（batches、combinations、combination_results），这些数据：
- 是确定性计算的输入输出，不需要 Agent 决策
- 体积大（几十 KB ~ 几百 KB），不适合经 MCP stdio 传输
- 有严格的前后依赖，不能跳步

**错误做法**：把 ①~⑧ 每步都暴露为 MCP 工具，Agent 手动编排。

**正确做法**：只暴露业务能力（MCP-01/02），管线步骤 ①~⑧ 全部在服务进程内自动完成。

### 1.3 定制化的三个层次

| 层次 | 定制内容 | 举例 | 实现方式 |
|------|----------|------|---------|
| **L1 参数级** | 调阈值、换策略 | 负荷上限 95%、多目标选优 | `feasibility_rules` / `selection_strategy` 参数 |
| **L2 模式级** | 选管线分支 | 只算物理不算经济、只重判定不重算 | `mode` 参数 |
| **L3 编排级** | 改管线结构 | 算完看结果再决定下一步、自定义切换方案 | `switch_mode` + `switch_plan` 参数 + Agent 闭环迭代 |

---

## 二、整体架构

### 2.1 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                           Agent                                   │
│                                                                   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐         │
│  │ 理解意图  │→│ 组装参数  │→│ 调用能力  │→│ 解读结果  │         │
│  │          │  │          │  │          │  │          │         │
│  │ Skill    │  │ rules    │  │ MCP-01   │  │ 摘要指标  │         │
│  │ 指导     │  │ strategy │  │          │  │ 自评估   │         │
│  │          │  │ switch   │  │ data_svc │  │ 调优决策  │         │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘         │
│       ↑                                           │               │
│       │              闭环迭代 ←────────────────────┘               │
└───────┼───────────────────────────────────────────────────────────┘
        │
        │ 小数据传入（rules/strategy/switch_plan）
        │ 小数据返回（摘要/指标/结论）
        │
   ┌────┴────────────────────────────────────────────────┐
   │                    MCP 工具层                         │
   │                                                      │
   │  calc_service (8 个业务能力工具)                       │
   │  ├── solve_refinery_plan(plan_month, ...,            │
   │  │     mode, switch_mode, switch_plan,               │
   │  │     feasibility_rules, selection_strategy)        │
   │  ├── optimize_valve_switches(plan_id, ...,           │
   │  │     feasibility_rules, selection_strategy)        │
   │  ├── calculate_batch_physical(scenario_id, ...)      │
   │  ├── calculate_batch_full(scenario_id, ...)          │
   │  ├── init_hangmei_context(batches, target)           │
   │  ├── build_flow_diagram(...)                         │
   │  ├── build_device_input_sources(...)                 │
   │  └── analyze_jian1_switch(...)                       │
   │                                                      │
   │  data_service (28 个数据 CRUD 工具)                   │
   │  ├── 读: list_units / get_yields / get_prices / ...  │
   │  └── 写: upsert_yields / upsert_side_line / ...      │
   │                                                      │
   └────┬─────────────────────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────────────────────┐
│              SolveService（内部管线）               │
│                                                   │
│  ①→②→③→④→⑤→⑥→⑦→⑧                               │
│  排产 → 批次 → 切换方案 → 物理 → 经济 → 可行 → 选优 → 渲染 │
│                                                   │
│  中间数据全在进程内，不外传                         │
│  规则引擎 + 选优引擎 在管线内部自动执行             │
│  PASS 1 物理结果缓存复用                           │
└───────────────────────────────────────────────────┘
```

### 2.2 Agent 的职责边界

| Agent 应该做的 | Agent 不应该做的 |
|---------------|-----------------|
| 理解用户意图 | 编排计算管线步骤 |
| 翻译为规则参数 | 传递中间数据结构 |
| 调用业务能力 | 手动构造 batches/combinations |
| 在摘要上自评估约束 | 理解 CombinationResult 结构 |
| 设计切换方案 | 控制管线内部循环 |
| 解读结果给用户 | |

### 2.3 引擎的职责边界

| 引擎应该做的 | 引擎不应该做的 |
|-------------|---------------|
| 给定方案 → 算物理指标 + 经济效益 | 理解用户意图 |
| 给定规则参数 → 判定可行性 | 决定选哪个组合 |
| 给定策略参数 → 选最优 | 做不确定性决策 |
| 返回轻量摘要供 Agent 评估 | 承载所有可能的约束规则 |
| 支持自定义切换方案输入 | 限制切换方式（硬编码） |

---

## 三、MCP 工具设计

### 3.1 工具清单（精简后）

从当前 17 个精简为 8 个，砍掉的 9 个回归为内部函数：

| MCP 工具 | 类型 | 保留/回退 | 说明 |
|----------|------|----------|------|
| `solve_refinery_plan` | 业务能力 | ✅ 保留（增强） | 全链路求解，支持 mode/switch_plan/rules/strategy |
| `optimize_valve_switches` | 业务能力 | ✅ 保留 | 半链路优化，支持 rules/strategy |
| `calculate_batch_physical` | 原子计算 | ✅ 保留 | 单批次物理计算 |
| `calculate_batch_full` | 原子计算 | ✅ 保留 | 单批次完整计算 |
| `init_hangmei_context` | 原子计算 | ✅ 保留 | 航煤上下文初始化 |
| `build_flow_diagram` | 可视化 | ✅ 保留 | 流程图数据 |
| `build_device_input_sources` | 可视化 | ✅ 保留 | 进料来源拆解 |
| `analyze_jian1_switch` | 分析 | ✅ 保留 | 切换点分析 |
| `prepare_solve_data` | 管线步骤 | ❌ 回退 | 内部步骤 ①② |
| `preload_reference_data` | 管线步骤 | ❌ 回退 | 内部数据预加载 |
| `optimize_combinations` | 管线步骤 | ❌ 回退 | 内部步骤 ③④⑤ |
| `evaluate_valve_combination` | 管线步骤 | ❌ 回退 | 内部步骤（单组合评估） |
| `aggregate_batch_economics` | 管线步骤 | ❌ 回退 | 内部步骤 ⑧ |
| `render_economic_summary` | 管线步骤 | ❌ 回退 | 内部步骤 ⑧ |
| `build_economic_breakdown` | 管线步骤 | ❌ 回退 | 内部步骤 ⑧ |
| `assess_feasibility` | 规则引擎 | ❌ 回退为内部引擎 | 通过 MCP-01 的 rules 参数驱动 |
| `select_optimal` | 规则引擎 | ❌ 回退为内部引擎 | 通过 MCP-01 的 strategy 参数驱动 |

### 3.2 核心工具：solve_refinery_plan 参数设计

```
solve_refinery_plan(
    # ── 基础参数 ──
    plan_month: str,                    # 计划月份
    production_plans_input: list,       # 排产计划输入
    monthly_crude_input: float,         # 月度加工总量
    blend_mode: bool = False,           # 是否混炼
    save_data: bool = True,             # 是否持久化
    hangmei_target: float = None,       # 航煤目标
    shutdown_config: list = None,       # 停工声明
    simplified: bool = True,            # 简化模式

    # ── L1 定制：参数级 ──
    feasibility_rules: dict = None,     # 可行性规则（6 个内置字段）
    selection_strategy: dict = None,    # 选优策略（4 种模式）

    # ── L2 定制：模式级（待实现） ──
    mode: str = "full",                 # full / reassess_only / physical_only

    # ── L3 定制：编排级（待实现） ──
    switch_mode: str = "enumerate",     # enumerate / custom
    switch_plan: dict = None,           # 自定义切换方案（switch_mode=custom 时）
)
```

### 3.3 返回结构设计

```json
{
    "success": true,
    "optimal_combination": {
        "combination_id": 3,
        "description": "第12天切换",
        "switches": {"batch_1": "WAX", "batch_2": "DIESEL"}
    },
    "optimal_revenue": 5280000,
    "economic_summary": "最优方案利润528万元...",

    "combination_metrics": [
        {
            "combination_id": 1,
            "feasible": true,
            "total_revenue": 5100000,
            "monthly_load": {"CDU": 87.5, "FCC": 102.3},
            "tank_violations": 0,
            "hangmei_output": 3200,
            "shutdown_hours": {"FCC": 0, "CDU": 24},
            "key_outputs": {"lpg": 480, "diesel": 12000, "wax": 8000}
        },
        ...
    ]
}
```

`combination_metrics` 是轻量摘要（8~16 个组合 × 每个约 20 个数字），供 Agent 自评估任意约束。

---

## 四、三层约束体系

### 4.1 第一层：应用内置规则（已实现）

通过 `feasibility_rules` 参数传入，在计算过程中生效：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `max_load_rate` | float | 100.0 | 月度平均负荷率上限（百分比） |
| `tank_capacity_strict` | bool | True | 罐容违规是否硬约束 |
| `max_overload_count` | int | 0 | 允许的超容装置数上限 |
| `max_overload_ratio` | float | 0.0 | 允许的超容比例 |
| `min_hangmei_output` | float | 0.0 | 航煤最低产出（吨） |
| `require_all_feasible` | bool | False | 是否要求所有批次可行 |

### 4.2 第二层：Agent 自评估规则（Skill 指导）

应用未实现的约束，Agent 在 `combination_metrics` 摘要上自己判断：

| 用户约束 | 取哪个字段 | 判断逻辑 |
|----------|-----------|---------|
| "液化气不超500" | `key_outputs.lpg` | lpg > 500 → 淘汰 |
| "停工不超3天" | `shutdown_hours` | max(shutdown_hours) > 72 → 淘汰 |
| "柴油产出至少1万吨" | `key_outputs.diesel` | diesel < 10000 → 淘汰 |
| "蜡油和柴油比例2:1" | `key_outputs.wax / diesel` | 比值偏离2 → 降权 |
| "FCC负荷越低越好" | `monthly_load.FCC` | 按 FCC 负荷升序排 |

**不需要改代码**，不需要新 MCP 工具，不需要新规则字段。

### 4.3 第三层：摘要字段扩展（按需）

如果用户要的指标不在摘要里，给 `combination_metrics` 加字段——小改动，不是加 MCP 工具。

---

## 五、Agent 闭环迭代

### 5.1 调优手段（按成本从低到高）

| 层次 | 调优方式 | 是否重算 | 耗时 |
|------|---------|---------|------|
| 第一层 | 调选优参数（strategy/rules） | 否（reassess_only） | 秒级 |
| 第二层 | 调排产配比（production_plans） | 是（full） | 分钟级 |
| 第三层 | 调底层数据（data_service 改 DB） | 是（full） | 分钟级 |
| 第四层 | 自定义切换方案（switch_plan） | 是（full） | 分钟级 |

### 5.2 闭环示例

```
用户："液化气不超500，FCC负荷不超95%，利润尽量高"

Agent 第一轮（探索）:
  solve_refinery_plan(plan_month="2026-07",
    production_plans=[{BZ: 40000}, {LH: 40000}],
    mode="full",
    feasibility_rules={"max_load_rate": 95.0},
    selection_strategy={"objective": "revenue"})

  → 返回: 组合3最优, revenue=528万, 但 lpg=520(超限), FCC=97%(超限)

Agent 评估: 液化气和FCC都超 → 先试 reassess_only（秒级）

Agent 第二轮（调策略）:
  solve_refinery_plan(mode="reassess_only",
    feasibility_rules={"max_load_rate": 95.0},
    selection_strategy={"objective": "risk_averse", "penalty_factor": 3.0})

  → 返回: 组合5, revenue=510万, FCC=93%(达标), 但 lpg=510(仍超)

Agent 评估: reassess_only 无解 → 需调排产配比
  Skill指导: LH液化气收率高，减LH加BZ

Agent 第三轮（调排产）:
  solve_refinery_plan(mode="full",
    production_plans=[{BZ: 50000}, {LH: 30000}],
    feasibility_rules={"max_load_rate": 95.0},
    selection_strategy={"objective": "revenue"})

  → 返回: 组合2, revenue=515万, lpg=460(达标), FCC=91%(达标)

Agent: 约束全部满足，推荐组合2，利润515万
```

### 5.3 自定义切换方案迭代

```
Agent 第一轮（枚举探索）:
  solve_refinery_plan(switch_mode="enumerate")
  → 返回 batch_info + combination_metrics
  → Agent 看到 8 种组合的指标

Agent 第二轮（自定义方案）:
  推理: "组合3在批次2结束后切换利润最高，但FCC超负荷。
         批次2是10天，如果第5天就切换，可能降低FCC负荷。"

  solve_refinery_plan(switch_mode="custom",
    switch_plan={
      "batch_1": {"mode": "WAX"},
      "batch_2": {"mode": "WAX", "switch_at_day": 5, "after": "DIESEL"},
      "batch_3": {"mode": "DIESEL"}
    },
    feasibility_rules={"max_load_rate": 95.0})
  → 返回这个特定方案的指标

Agent 第三轮（微调）:
  "第5天切换FCC降到92%了，但利润降了30万。
   试试第7天切换？"
  solve_refinery_plan(switch_mode="custom",
    switch_plan={..., "batch_2": {"switch_at_day": 7, ...}})
```

---

## 六、Skill 设计

### 6.1 三个全局 Skill

| Skill | 职责 | 内容 |
|-------|------|------|
| `refinery-solve-orchestration` | 编排流程指导 | 工具清单、调用路径、mode/switch_mode 选择、双价格月口径 |
| `refinery-feasibility-rules` | 规则字典 + 自评估指南 | 6 个内置规则字段、用户意图映射、Agent 自评估规则指南 |
| `refinery-optimal-selection` | 选优策略 + 调优指南 | 4 种选优模式、调优路径、原油属性速查、决策流程 |

### 6.2 Skill 需要补充的内容

当前 Skill 已覆盖 L1 定制，需要补充：

| 补充项 | 所属 Skill | 内容 |
|--------|-----------|------|
| Agent 自评估规则指南 | feasibility-rules | 指标→约束映射、判断逻辑 |
| 调优策略指南 | optimal-selection | 调优路径（4 层）、成本排序 |
| 切换策略指导 | solve-orchestration | 枚举 vs 自定义、批次内切换构造方法 |
| 原油属性速查 | optimal-selection | 各原油收率特征、调优参考 |
| mode 选择指南 | solve-orchestration | full / reassess_only / physical_only 适用场景 |

---

## 七、对计算引擎的要求

### 7.1 已完成

| 要求 | 状态 | 说明 |
|------|------|------|
| 规则参数化 | ✅ 已完成 | `feasibility_rules` 6 个字段 |
| 选优参数化 | ✅ 已完成 | `selection_strategy` 4 种模式 |
| rules/strategy 传入 MCP-01 | ✅ 已完成 | MCP-01/02 支持 |
| PASS 1 物理结果缓存复用 | ✅ 已有 | 管线内部优化 |

### 7.2 待实现（P0：核心能力）

| 要求 | 优先级 | 说明 |
|------|--------|------|
| `mode` 参数 | P0 | `full` / `reassess_only` / `physical_only` 三种模式 |
| `reassess_only` 实现 | P0 | 复用已有 plan_id 的计算结果，只重新判定+选优，不重算 |
| `combination_metrics` 返回 | P0 | 所有组合的轻量摘要，供 Agent 自评估 |

### 7.3 待实现（P1：定制化能力）

| 要求 | 优先级 | 说明 |
|------|--------|------|
| `switch_mode` 参数 | P1 | `enumerate`（当前行为）/ `custom`（接受自定义方案） |
| `switch_plan` 参数 | P1 | Agent 传入自定义切换方案（批次内切换、多次切换） |
| 引擎支持批次内切换 | P1 | `evaluate_switch_plan(batches, switch_plan)` 替代固定枚举 |
| 引擎支持多次切换 | P1 | 放开"一个月一次"限制 |

### 7.4 待实现（P2：优化项）

| 要求 | 优先级 | 说明 |
|------|--------|------|
| MCP 工具精简 | P2 | 17 个 → 8 个，管线步骤回归内部函数 |
| Skill 内容补充 | P2 | 自评估指南、调优策略、切换策略 |
| `combination_metrics` 字段扩展 | P2 | 按用户需求逐步加字段 |
| 原油属性速查表 | P2 | 各原油收率特征，供 Agent 调优参考 |

### 7.5 引擎不需要做的

| 不需要 | 原因 |
|--------|------|
| 为每种约束加规则字段 | Agent 在摘要上自评估 |
| 暴露管线中间步骤为 MCP 工具 | 管线在进程内自动完成 |
| 实现内存缓存跨工具传数据 | 大数据不离开服务进程 |
| 理解用户自然语言意图 | Agent 的职责 |

### 7.6 引擎定型标准

引擎做完以下三项即定型，后续所有新约束不需要改引擎：

1. **放开切换方案输入**：支持 `switch_plan` 自定义方案
2. **返回轻量摘要**：`combination_metrics` 供 Agent 自评估
3. **支持 mode 参数**：`reassess_only` 避免重复计算

---

## 八、实施路径

### 阶段一：验证当前能力（已完成）

- [x] 规则参数化（`feasibility_rules`）
- [x] 选优参数化（`selection_strategy`）
- [x] MCP-01/02 支持 rules/strategy 参数
- [x] 3 个 Skill 创建
- [x] MCP-17 `prepare_solve_data`（后续回退，不需要）

### 阶段二：补核心能力（待实施）

- [ ] MCP-01 加 `mode` 参数（full / reassess_only / physical_only）
- [ ] `reassess_only` 内部实现（复用已有计算结果）
- [ ] MCP-01 返回 `combination_metrics` 轻量摘要
- [ ] 验证 Agent 闭环迭代（探索→评估→调优→再求解）

### 阶段三：切换方案定制化（待实施）

- [ ] 引擎支持 `switch_mode="custom"` + `switch_plan` 参数
- [ ] `evaluate_switch_plan(batches, switch_plan)` 实现（批次内切换、多次切换）
- [ ] Skill 补充切换策略指导
- [ ] 验证 Agent 自定义切换方案闭环

### 阶段四：精简 MCP 工具（待实施）

- [ ] 回退 9 个管线步骤工具为内部函数
- [ ] 保留 8 个业务能力工具
- [ ] 更新 MCP 接口文档
- [ ] 更新 Skill 内容

---

## 九、通用改造方法论

对于已有 App → Agent 定制化的通用路径：

```
步骤 1: 识别业务能力边界
  → 区分"业务能力"和"管线步骤"
  → 只暴露业务能力为 MCP 工具

步骤 2: 参数化决策点
  → 硬编码规则 → 可配置参数
  → 通过 MCP 工具参数暴露给 Agent

步骤 3: 返回轻量摘要
  → 核心业务能力返回所有候选的轻量指标
  → 供 Agent 自评估任意约束

步骤 4: 放开方案输入
  → 从"只接受枚举"变为"也接受自定义方案"
  → 一次性改动，后续不需要为每种新需求改代码

步骤 5: Skill 承载领域知识
  → 规则字典 + 用户意图映射
  → 调优策略 + 决策流程
  → 指标→约束映射

步骤 6: 保持管线内部封装
  → 确定性计算在服务进程内自动完成
  → 中间数据不外传
  → Agent 在业务方案层面做决策
```

---

## 十、风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Agent 多轮迭代耗时 | 用户等待时间长 | `reassess_only` 秒级返回；限制最大迭代轮数 |
| Agent 设计的切换方案不合理 | 计算失败或结果异常 | 引擎做方案校验，返回明确的错误提示 |
| `combination_metrics` 字段不够 | Agent 无法评估某些约束 | 按需扩展字段（小改动） |
| 第三层调优改 DB 数据 | 影响全局计算 | 提供试算模式（不持久化）；或事务回滚 |
| MCP 工具精简影响已有调用 | 外部平台已配置的工具失效 | 保留旧工具注册但标记 deprecated；分阶段回退 |
