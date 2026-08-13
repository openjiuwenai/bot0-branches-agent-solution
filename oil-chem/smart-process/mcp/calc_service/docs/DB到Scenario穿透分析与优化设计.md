# DB → Scenario 穿透分析与优化设计

> **版本**: v1.0 · 2026-08
> **分析范围**: DB 表结构 → data_service → refinery_repo → RefineryScenario → 计算层消费
> **目标**: 识别数据组装链路中的冗余、妥协、死代码，达成 Scenario 最佳状态

---

## 一、数据流全景图

```
DB Tables                              data_service                  refinery_repo              RefineryScenario         计算层消费
──────────                             ────────────                  ─────────────              ────────────────         ────────────

solve_db.devices_units     ──┐
solve_db.devices_tanks     ──┼──> device_repo.load_units/tanks ──> load_devices() ────────> devices: Dict          ✓ 直接消费
                              │                                    (type=tank/normal/start)    ↓ @property
                              │                                                       cdu_device_id           ✓ 直接消费
                              │                                                       processing_device_ids   ✓ 直接消费
                              │                                                       tank_device_ids         ✓ 直接消费
                              │                                                       intermediate_tank_ids   ✓ 直接消费

solve_db.side_lines        ──┐
solve_db.device_yields     ──┼──> side_line_repo ──────────────> load_products(crude_type) > products: Dict          ✓ 直接消费
public.md_material         ──┘    (JOIN for material_name)                                  ↓ @property
                              │                                                       hangmei_active_ids      ✓ 直接消费
                              │                                                       _main_feeds_cache       ✓ get_main_feeds()

solve_db.material_flows    ─────> flow_repo.load_flows ────────> load_material_flows() ──> material_flows: Dict     ✗ 仅 evaluate_combination 直接用
                              │                                                           ↓ _compute_connections()
                              │                                                    connections: Dict         ✓ 计算层大量消费
                              │                                                           ↓ @property
                              │                                                    _upstream_map_cache      ✓ get_upstream()
                              │                                                    _downstream_map_cache    ✓ get_downstream()
                              │                                                    yield_switch_device_ids  ✓ 直接消费
                              │                                                    _split_ratio_cache       ✓ get_split_ratio()

solve_db.energy             ─────> inline SQL ────────────────> load_energy_consumptions()> energy_consumptions     ✗ 死加载！无人消费

solve_db.production_plans   ─────> inline SQL ────────────────> load_crude_types() ──────> crude_costs: Dict        ✓ 间接（crude_oil_price 取值）

solve_db.side_lines        ──┐
public.md_material         ──┼──> mapping_repo ───────────────> load_*_map() ────────────> product_material_map    ✓ 间接（price 查表）
                              │                                                       material_name_map       ✓ 间接（get_products_by_material）

public.material_price      ──┐
public.material_price_default─┤
public.price_calc_rule     ──┼──> price_repo.resolve_prices_batch ──────────────────────> price_costs (lazy)       ✗ 已在第二轮移除，prices 独立传入
public.ref_price_benchmark ──┘

public.device_cost         ──┐
public.device_cost_default─┤
public.md_device           ──┴──> price_repo.load_device_costs ──────────────────────────> (不在 Scenario 上)       ✓ 独立传入 device_costs
```

---

## 二、发现的问题（6 项）

### 问题 1: `Connection` — 冗余的中间适配对象

**现状**:
- DB 存储的是 `material_flows`（归一化物流表，4 种 flow_type）
- 计算层消费的是 `connections`（简化 3 元组：from_device, from_product, to_device）
- `_compute_connections()` 做了一层有损转换

**有损点**:
```python
# tank_to_target 类型：from_product_id 需要"猜"
tank_candidates = [p for p in self.products.values()
                   if p.source_device_id == flow.tank_id]
# 按优先级选 main_feed > product > 合成 f"{tank_id}_out"
```
当一个储罐有多个产品时，`from_product_id` 是猜的——这不可靠。

**计算层实际使用方式**:
- `get_upstream_connections(device_id)` — 遍历 connections 找 `to_device_id == device_id`
- `get_downstream_connections(device_id)` — 遍历 connections 找 `from_device_id == device_id`
- 直接访问 `connections.values()` 遍历

**这些操作完全可以直接在 `material_flows` 上做** — flow_type 已经包含了方向信息：
- `source_to_tank`: from=source_device_id, to=tank_id
- `tank_to_target`: from=tank_id, to=target_device_id
- `direct`: from=source_device_id, to=target_device_id
- `final`: from=source_device_id, to=tank_id

### 问题 2: `start_device_id` 与 `cdu_device_id` 重复

**现状**:
```python
# 字段（DB 加载时设置）
start_device_id = next(d.id for d in devices.values() if d.type == 'start')

# 属性（惰性计算）
@property
def cdu_device_id(self):
    return next((d.id for d in self.devices.values() if d.type == 'start'), None) \
           or self.start_device_id  # fallback 到同一个值
```

两者指向**同一个装置**。计算层混用：`direct_calculator` 用 `start_device_id`，`economics/switch_analysis/hangmei_optimizer` 用 `cdu_device_id`。

### 问题 3: `price_costs` 残留在 Scenario 上

**现状**:
- `price_costs: Dict[str, Dict[str, float]]` — month → product_id → price 的嵌套缓存
- 第二轮重构已将 `prices: Dict[str, float]` 改为显式参数传入计算层
- 但 `scenario.price_costs` 仍然作为 `refinery_repo.preload_prices()` 的写入目标存在
- 计算层不再读 `scenario.price_costs`（已改为读 `prices` 参数），但 Service 层仍通过 `preload_prices(month, scenario)` 写入 scenario

**这是第二轮重构的遗留** — `preload_prices` 方法仍然写 scenario，然后 Service 层再从 scenario 读出来传给计算层，中间多了一次"写入 scenario → 读出 scenario → 传入计算层"的无意义中转。

### 问题 4: `crude_oil_price` / `crude_type` / `crude_costs` 层级混淆

**现状**:
- `crude_costs: Dict[str, float]` — **场景级配置**：所有原油品种的成本字典
- `crude_oil_price: float` — **批次级参数**：当前批次的原油价格
- `crude_type: str` — **批次级参数**：当前批次的原油品种

Scenario 被设计为"场景配置 + 批次上下文"的混合体。多批次求解时，同一个 scenario 对象的 `crude_type` 和 `crude_oil_price` 在不同批次间被反复改写。

### 问题 5: `energy_consumptions` 死加载

**现状**:
- `load_scenario()` 调用 `load_energy_consumptions()` 从 `solve_db.energy` 表加载数据
- **计算层零函数引用 `scenario.energy_consumptions`**
- 能耗成本实际来自 `device_costs`（从 `public.device_cost` 表加载，独立传入计算层）
- `energy` 表的详细能耗数据（氢气/蒸汽/电/水）仅供配置 UI 的 CRUD 路由使用

**每次场景加载浪费一次 DB 查询**。

### 问题 6: `connections` 旧表残留

**现状**:
- `solve_db.connections` 表仍然存在于 DB 中
- `refinery_repo.load_connections()` 方法仍然存在
- 但 `load_scenario()` **不调用** `load_connections()` — 已改用 `material_flows` 派生
- `Connection` 类和 `_compute_connections()` 是纯内存派生

旧表和方法是历史遗留，增加维护混乱。

---

## 三、优化方案

### 3.1 方案总览

| 优化项 | 类型 | 收益 | 风险 | 优先级 |
|--------|------|------|------|--------|
| A. 消除 Connection 中间层 | 架构 | 去掉有损转换 + 1 层派生 | 中（计算层大量改） | 高 |
| B. 合并 start_device_id / cdu_device_id | 简化 | 消除重复语义 | 低 | 高 |
| C. 移除 price_costs 残留 | 清理 | 消除无意义中转 | 低 | 高 |
| D. 移除 crude_oil_price 批次级字段 | 架构 | Scenario 回归纯场景配置 | 低 | 中 |
| E. 停止加载 energy_consumptions | 清理 | 省一次 DB 查询 | 低 | 高 |
| F. 删除 connections 旧表和方法 | 清理 | 消除维护混乱 | 低 | 低 |
| G. 拆分 Device 类为 ProcessingUnit + Tank | 架构 | 类型安全，消除 None 互斥字段 | 中（计算层 7+ 处 type 判断改 isinstance） | 高 |

### 3.2 优化 A: 消除 Connection 中间层

**目标**: 计算层直接消费 `material_flows`，不再通过 `Connection` 中间对象。

**设计**: 在 `RefineryScenario` 上提供基于 `material_flows` 的拓扑查询方法，替代 `connections` 属性：

```python
class RefineryScenario:
    # 移除: connections 属性、_connections_cache、_compute_connections()
    # 移除: Connection 类（移到 models/legacy.py 或直接删除）

    def get_upstream_flows(self, device_id: str) -> List[MaterialFlow]:
        """获取流向指定装置的所有物流边。

        等价于旧 get_upstream_connections(device_id)，但直接遍历 material_flows。
        """
        result = []
        for flow in self.material_flows.values():
            if flow.flow_type in ('source_to_tank', 'final'):
                to = flow.tank_id  # 这些类型 to=tank_id
            elif flow.flow_type in ('tank_to_target', 'direct'):
                to = flow.target_device_id
            else:
                continue
            if to == device_id:
                result.append(flow)
        return result

    def get_downstream_flows(self, device_id: str) -> List[MaterialFlow]:
        """获取从指定装置流出的所有物流边。"""
        result = []
        for flow in self.material_flows.values():
            if flow.flow_type in ('source_to_tank', 'direct', 'final'):
                frm = flow.source_device_id
            elif flow.flow_type == 'tank_to_target':
                frm = flow.tank_id
            else:
                continue
            if frm == device_id:
                result.append(flow)
        return result
```

**计算层变更**:
- `scenario.connections.values()` → `scenario.material_flows.values()`
- `scenario.get_upstream_connections(dev)` → `scenario.get_upstream_flows(dev)`
- `scenario.get_downstream_connections(dev)` → `scenario.get_downstream_flows(dev)`
- `conn.from_device_id` / `conn.to_device_id` / `conn.from_product_id` → 按 `flow.flow_type` 判断取哪个字段

**关键收益**: 消除 `tank_to_target` 的 `from_product_id` 猜测问题。`MaterialFlow` 本身有 `source_product_id`，虽然 `tank_to_target` 类型的 flow 不直接携带"罐里装的是什么产品"，但计算层使用 `tank_to_target` 时实际只需要知道 `tank_id` 和 `target_device_id`，不需要 `from_product_id`（产品信息在 `target_product_id` 或通过 `products` 查找）。

### 3.3 优化 B: 合并 `start_device_id` / `cdu_device_id`

**方案**: 保留 `start_device_id` 作为唯一字段（DB 加载时设置），删除 `cdu_device_id` 属性。计算层统一使用 `start_device_id`。

```python
# 删除:
@property
def cdu_device_id(self): ...

# 计算层全文替换:
# cdu_device_id → start_device_id
```

**影响文件**: `economics.py`、`hangmei_optimizer.py`、`switch_analysis.py`、`economic_reporter.py`、`solve_service.py`

### 3.4 优化 C: 移除 `price_costs` 残留

**方案**: `preload_prices()` 不再写入 scenario，直接返回 `Dict[str, float]`：

```python
# refinery_repo.py
def preload_prices(self, month: str, product_material_map: Dict[str, int]) -> Dict[str, float]:
    """预加载产品价格，返回 product_id → price 字典。"""
    # 不再接收 scenario 参数，不再写 scenario.price_costs
    # 直接返回 prices dict
    ...
    return prices  # Dict[str, float]

# Service 层
prices = self._refinery_repo().preload_prices(month, scenario.product_material_map)
# 直接传入计算层，不经过 scenario
```

**移除**:
- `RefineryScenario.price_costs` 字段
- `RefineryScenario.get_price_from_api()` 方法（已由 `revenue_calculator.get_product_price()` 替代）
- `refinery_repo.preload_prices(month, scenario)` 的 scenario 参数

### 3.5 优化 D: 移除 `crude_oil_price` 批次级字段

**方案**: `crude_oil_price` 是批次级参数，不应在 Scenario 上。从 `crude_costs` 字典按 `crude_type` 查即可。

```python
# 移除:
crude_oil_price: float = 0.0  # 删除

# 需要原油价格时:
crude_price = scenario.crude_costs.get(crude_type, 0.0)
```

**`load_scenario()` 变更**:
```python
# 重构前
def load_scenario(self, start_device_id=None, crude_oil_price=0.0, crude_type=None):
    scenario.crude_oil_price = crude_oil_price
    scenario.crude_type = crude_type

# 重构后
def load_scenario(self, crude_type=None):
    scenario.crude_type = crude_type
    # crude_oil_price 从 crude_costs 查
```

### 3.6 优化 E: 停止加载 `energy_consumptions`

**方案**: `load_scenario()` 不再调用 `load_energy_consumptions()`。

```python
# load_scenario() 中删除:
# scenario.energy_consumptions.extend(self.load_energy_consumptions())

# RefineryScenario 保留 energy_consumptions 字段（CRUD 路由仍可用）
# 但 load_scenario 不自动填充
```

**影响**: 零计算层影响（无人消费）。CRUD 路由如需 energy 数据，自行调用 `load_energy_consumptions()`。

### 3.7 优化 F: 删除 connections 旧表和方法

**方案**:
- 删除 `refinery_repo.load_connections()` 方法
- 删除 `solve_db.connections` 表（需 DB 迁移脚本）
- `Connection` 类随优化 A 一起删除

### 3.7 优化 G: 拆分 Device 类为 ProcessingUnit + Tank

**现状**:
- DB 已分表: `devices_units`(装置) + `devices_tanks`(储罐)
- `data_service.device_repo` 已分离: `load_units()` + `load_tanks()`
- 但 `refinery_repo.load_devices()` 将两者合并到同一个 `Dict[str, Device]`
- `Device` 类有 5 个互斥字段（装置和罐各用一部分，另一部分永远 None）
- 计算层通过 `device.type == 'tank'` 字符串判断区分（7 处）

**Device 类互斥字段矩阵**:

| 字段 | ProcessingUnit | Tank |
|------|:---:|:---:|
| `type` | ✓ normal/start | ✓ tank (硬编码) |
| `backend_device_id` | ✓ 有值 | ✗ None |
| `tank_category` | ✗ None | ✓ 有值 |
| `material_id` | ✗ None | ✓ 有值 |
| `material_name` | ✗ None | ✓ 有值 |

**设计**: 拆为基类 + 2 个子类，保持 `devices` 合并字典（拓扑遍历需要统一节点集）:

```python
@dataclass
class DeviceBase:
    """设备基类：装置和储罐的公共字段。"""
    id: str
    name: str
    max_capacity: float = 0.0
    safety_stock_thrd: float = 0.0
    low_safety_thrd: float = 0.0
    current_capacity: float = 0.0
    refinery_unit_load_percent: float = 100.0
    note: str = ""
    enabled: bool = True

    @property
    def effective_capacity(self) -> float: ...  # 公共属性

    @property
    def is_tank(self) -> bool:
        return isinstance(self, Tank)

    @property
    def is_processing_unit(self) -> bool:
        return isinstance(self, ProcessingUnit)


@dataclass
class ProcessingUnit(DeviceBase):
    """装置（devices_units 表）"""
    type: str = "normal"  # normal / start
    backend_device_id: Optional[int] = None  # 对应 md_device 主键

    @property
    def is_start(self) -> bool:
        return self.type == "start"


@dataclass
class Tank(DeviceBase):
    """储罐（devices_tanks 表）"""
    tank_category: Optional[str] = None  # intermediate / product / crude
    material_id: Optional[int] = None
    material_name: Optional[str] = None

    @property
    def is_intermediate(self) -> bool:
        return self.tank_category == "intermediate"
```

**refinery_repo.load_devices() 变更**:

```python
def load_devices(self) -> Dict[str, DeviceBase]:
    from data_service.repositories import device_repo
    with self._session() as db:
        unit_rows = device_repo.load_units(db)
        tank_rows = device_repo.load_tanks(db)
    devices = {}
    for r in unit_rows:
        devices[r['device_id']] = ProcessingUnit(
            id=r['device_id'], name=r['name'],
            type=str(r['type'] or 'normal').strip().lower(),
            backend_device_id=r['backend_device_id'],
            ...
        )
    for r in tank_rows:
        devices[r['device_id']] = Tank(
            id=r['device_id'], name=r['name'],
            tank_category=r['tank_category'],
            material_id=r.get('material_id'),
            material_name=r.get('material_name'),
            ...
        )
    return devices
```

**Scenario 派生属性简化**:

```python
# 优化前（基于 type 字符串过滤）
@property
def tank_device_ids(self):
    return {d.id for d in self.devices.values() if d.type == 'tank' and d.enabled}

@property
def processing_device_ids(self):
    return {d.id for d in self.devices.values() if d.type not in ('tank', 'start') and d.enabled}

@property
def intermediate_tank_ids(self):
    return {d.id for d in self.devices.values()
            if d.type == 'tank' and d.tank_category == 'intermediate' and d.enabled}

# 优化后（基于 isinstance）
@property
def tank_device_ids(self):
    return {d.id for d in self.devices.values() if isinstance(d, Tank) and d.enabled}

@property
def processing_device_ids(self):
    return {d.id for d in self.devices.values()
            if isinstance(d, ProcessingUnit) and not d.is_start and d.enabled}

@property
def intermediate_tank_ids(self):
    return {d.id for d in self.devices.values()
            if isinstance(d, Tank) and d.is_intermediate and d.enabled}
```

**计算层迁移**:

| 旧代码 | 新代码 | 出现位置 |
|--------|--------|---------|
| `device.type == 'tank'` | `isinstance(device, Tank)` 或 `device.is_tank` | cost_calculator, direct_calculator, hangmei_optimizer, flow_diagram_builder, revenue_calculator (7 处) |
| `device.type == 'start'` | `isinstance(device, ProcessingUnit) and device.is_start` | refinery.py |
| `device.tank_category` (可能 None) | `device.tank_category` (Tank 上必然有值) | direct_calculator, tank_capacity_checker |
| `device.backend_device_id` (可能 None) | `device.backend_device_id` (ProcessingUnit 上必然有值) | cost_calculator |
| `getattr(device, 'tank_category', None)` | `device.tank_category if isinstance(device, Tank) else None` 或直接 `device.is_tank` 判断后访问 | direct_calculator:279 |

**兼容性**: `DeviceBase` 保留 `is_tank`/`is_processing_unit` 属性作为语义糖，使迁移后代码可读性不降。

---

## 四、优化后的 Scenario 精简结构

```
RefineryScenario（优化后）
│
├── DB 原始数据（3 个核心字段）
│   ├── devices: Dict[str, DeviceBase]    ← ProcessingUnit + Tank（类型安全）
│   ├── products: Dict[str, Product]      ← side_lines + device_yields
│   └── material_flows: Dict[str, MaterialFlow]  ← material_flows 表
│
├── 配置/映射（1 个辅助字段）
│   └── crude_costs: Dict[str, float]     ← production_plans_input
│
├── 标识（1 个字段）
│   └── crude_type: str                   ← 当前原油品种
│
├── 拓扑查询方法（直接遍历 material_flows，无中间对象）
│   ├── get_upstream_flows(device_id)     ← 替代 get_upstream_connections
│   ├── get_downstream_flows(device_id)   ← 替代 get_downstream_connections
│   └── get_split_ratio(flow_id)          ← 遍历 material_flows
│
├── 装置分类方法（从 devices 过滤）
│   ├── start_device_id                   ← 唯一的 start 装置 ID
│   ├── processing_device_ids             ← devices.filter(type != tank/start)
│   ├── tank_device_ids                   ← devices.filter(type == tank)
│   └── intermediate_tank_ids             ← devices.filter(tank_category == intermediate)
│
├── 产品查询方法（从 products 过滤，零 DB 查询）
│   ├── get_main_feeds(device_id)         ← products.filter(material_type == main_feed)
│   ├── get_products_by_material(name)    ← products.filter(material_name == name)
│   ├── get_feed_ratio(device_id, crude)  ← products.filter(main_feed + crude_type)
│   ├── hangmei_active_device_ids         ← products.filter(yield_rate_3/4 > 0)
│   └── yield_switch_device_ids           ← material_flows.filter(special_var != None)
│
└── 已移除
    ✗ Connection 类 + _compute_connections()
    ✗ connections 属性 + _connections_cache
    ✗ _upstream_map_cache / _downstream_map_cache（改为直接遍历）
    ✗ cdu_device_id（合并到 start_device_id）
    ✗ price_costs（移到 Service 层 prices 参数）
    ✗ crude_oil_price（从 crude_costs 查）
    ✗ energy_consumptions（死加载，停止 load_scenario 中调用）
    ✗ product_material_map（Product.material_id 直接替代）
    ✗ material_name_map（Product.material_name 直接替代）
    ✗ get_feed_ratio 模块级函数（改为 Scenario 内存方法）
```

**字段数**: 14 → **6**（减少 57%）
**派生层**: 3 层（flows→connections→adjacency）→ **1 层**（flows 直接遍历）
**冗余 DB 查询**: 每次 solve ~25 次 → **0 次**

---

## 五、实施路线

### Phase A: 低风险清理（优化 B + C + D + E + F）

| 步骤 | 内容 | 影响范围 |
|------|------|---------|
| A1 | 合并 `cdu_device_id` → `start_device_id` | 5 个计算文件 + 1 服务文件 |
| A2 | `preload_prices` 返回 dict，不写 scenario | refinery_repo + solve_service |
| A3 | 移除 `crude_oil_price` 字段 | refinery_repo + solve_service |
| A4 | `load_scenario` 停止加载 `energy_consumptions` | refinery_repo |
| A5 | 删除 `refinery_repo.load_connections()` 方法 | refinery_repo |

### Phase B: 消除 Connection 中间层（优化 A）

| 步骤 | 内容 | 影响范围 |
|------|------|---------|
| B1 | 在 `RefineryScenario` 上添加 `get_upstream_flows` / `get_downstream_flows` | refinery.py |
| B2 | 计算层迁移: `connections.values()` → `material_flows.values()` | direct_calculator + flow_diagram_builder + switch_analysis |
| B3 | 计算层迁移: `get_upstream_connections` → `get_upstream_flows` | direct_calculator + flow_diagram_builder |
| B4 | 计算层迁移: `get_downstream_connections` → `get_downstream_flows` | direct_calculator |
| B5 | 计算层迁移: `conn.from_device_id` 等 → 按 `flow.flow_type` 判断字段 | 全部使用 Connection 的函数 |
| B6 | 删除 `Connection` 类 + `_compute_connections()` + `connections` 属性 | refinery.py |
| B7 | 删除 `_upstream_map_cache` / `_downstream_map_cache` | refinery.py |

### Phase C: 拆分 Device 类（优化 G）

| 步骤 | 内容 | 影响范围 |
|------|------|---------|
| C1 | 创建 `DeviceBase` 基类 + `ProcessingUnit` + `Tank` 子类 | refinery.py |
| C2 | `refinery_repo.load_devices()` 改为构造子类实例 | refinery_repo.py |
| C3 | Scenario `@property` 改为 `isinstance` 判断 | refinery.py |
| C4 | 计算层迁移: `device.type == 'tank'` → `device.is_tank` | 5 个计算文件 (7 处) |
| C5 | 计算层迁移: `getattr(device, 'tank_category', None)` → 类型安全访问 | direct_calculator |
| C6 | 删除旧 `Device` 类 + `type` 字符串字段 | refinery.py |

**执行顺序**: Phase A → Phase B → Phase C → Phase D → 验证

---

### Phase D: Product 补全 material_id/material_name，消除冗余映射（优化 H）

**背景**: DB 已将 `side_lines` 和 `device_yields` 分表，`side_lines.material_id` 直接关联 `md_material`。`data_service.side_line_repo.load_side_lines_with_yields` 一次 JOIN 已返回 `material_id` + `material_name`。但 `refinery_repo.load_products` 构造 `Product` 时**丢弃了这两个字段**，导致 Scenario 不得不维护两个冗余 dict 和一次冗余 DB 查询来补偿。

**问题链路**:

```
side_lines.material_id ──> data_service JOIN 返回 ──> refinery_repo 丢弃 ──> Product 缺字段
                                                                    ↓
                        product_material_map ←── 冗余 DB 查询（取回同样的 material_id）
                        material_name_map   ←── 冗余 DB 查询（取回同样的 material_name）
                        get_feed_ratio()    ←── 冗余 DB 查询（products 已含 main_feed 数据）
```

**冗余量化**: 每次 solve 加载 5 个 scenario → `load_product_material_mapping` × 5 + `load_material_name_map` × 5 + `get_feed_ratio` × 15 = **~25 次冗余 DB 查询**

**字段消费分析**（25 处消费点的字段共现矩阵）:

| 消费类型 | 消费方 | 需要的字段 |
|---|---|---|
| 纯收率 | direct_calculator, combination_evaluator, device_input_calc | yield_rate / yield_rate_2/3/4 |
| 纯类型/拓扑 | switch_analysis, economics:70, get_main_feeds | material_type + source_device_id |
| 纯名称 | flow_diagram_builder:47, crud_routes | name |
| 收率+类型+拓扑 | cost_calculator, hangmei_optimizer:28 | material_type + yield_rate + source_device_id |
| 全字段 | revenue_calculator, hangmei_optimizer:252, economics:163 | id + name + source_device_id + material_type + 4收率 |
| 物料过滤 | get_products_by_material, preload_prices | **需要 material_id/material_name**（当前从冗余 dict 取） |

**结论**: Product 保持合并体（6+ 处需联合访问拓扑+收率），但需补全 `material_id` 和 `material_name` 字段，消除 3 处冗余。

**设计**:

1. **Product 加字段**:

```python
@dataclass
class Product:
    id: str
    name: str
    source_device_id: str
    yield_rate: float = 0.0
    yield_rate_2: float = 0.0
    yield_rate_3: float = 0.0
    yield_rate_4: float = 0.0
    material_type: str = 'product'       # product / main_feed / auxiliary
    is_final: bool = False
    crude_type: str = 'BZ'
    material_id: Optional[int] = None    # ← 新增：直接关联 md_material
    material_name: str = ''              # ← 新增：物料名称（如 '航煤'、'柴油'）
```

2. **load_products 填充新字段**（数据已在 JOIN 结果中）:

```python
# refinery_repo.load_products()
products[pid] = Product(
    id=pid,
    name=r['name'],
    source_device_id=r['source_device_id'] or '',
    yield_rate=r['yield_rate'],
    yield_rate_2=r['yield_rate_2'],
    yield_rate_3=r['yield_rate_3'],
    yield_rate_4=r['yield_rate_4'],
    material_type=r['material_type'] or 'product',
    is_final=r['is_final'],
    crude_type=r['crude_type'] or 'BZ',
    material_id=r.get('material_id'),       # ← 直接取
    material_name=r.get('material_name') or '',  # ← 直接取
)
```

3. **删除冗余 dict 和加载方法**:

```python
# refinery_repo.py — 删除以下方法:
def load_product_material_mapping(self) -> Dict[str, int]: ...  # 删除
def load_material_name_map(self) -> Dict[str, int]: ...         # 删除

# refinery.py — RefineryScenario 删除:
product_material_map: Dict[str, int] = field(default_factory=dict)  # 删除
material_name_map: Dict[str, int] = field(default_factory=dict)     # 删除
```

4. **get_products_by_material 简化**:

```python
# 优化前（两次 dict 查找 + 全量遍历）:
def get_products_by_material(self, material_name: str) -> List[Product]:
    mid = self.material_name_map.get(material_name)
    if mid is None:
        return []
    return [p for pid, p in self.products.items()
            if self.product_material_map.get(pid) == mid]

# 优化后（直接字段过滤）:
def get_products_by_material(self, material_name: str) -> List[Product]:
    return [p for p in self.products.values() if p.material_name == material_name]
```

5. **get_feed_ratio 改为 Scenario 内存方法**:

```python
# 优化前（模块级函数，每次调用开 DB session + 查询）:
def get_feed_ratio(device_id: str, crude_type: str) -> float:
    # SELECT yield_rate FROM side_lines JOIN device_yields WHERE material_type='main_feed' ...
    ...

# 优化后（Scenario 内存方法，零 DB 查询）:
def get_feed_ratio(self, device_id: str, crude_type: str) -> float:
    """获取装置在指定油种下的进料配比。

    场景依赖:
        - products: main_feed 类型产品的 yield_rate
    """
    for p in self.get_main_feeds(device_id):
        if p.crude_type == crude_type and p.yield_rate > 0:
            return p.yield_rate
    return 1.0
```

6. **preload_prices 改用 Product.material_id**:

```python
# 优化前（传入 product_material_map）:
prices = repo.preload_prices(month, scenario.product_material_map)
# preload_prices 内部: for product_id, material_id in product_material_map.items():

# 优化后（直接从 products 取 material_id）:
prices = repo.preload_prices(month, scenario.products)
# preload_prices 内部: for product_id, product in products.items():
#     if product.material_id: price = price_map.get(str(product.material_id))
```

7. **消费侧迁移**:

| 旧代码 | 新代码 | 位置 |
|--------|--------|------|
| `scenario.material_name_map.get('航煤')` | 不需要 mid，直接 `get_products_by_material('航煤')` | hangmei_optimizer |
| `scenario.product_material_map.get(pid)` | `scenario.products[pid].material_id` | hangmei_optimizer, preload_prices |
| `scenario.material_name_map.get('燃料油DMX')` | `scenario.products[pid].material_name == '燃料油DMX'` | hangmei_optimizer |
| `get_feed_ratio(proc_id, crude_type)` | `scenario.get_feed_ratio(proc_id, crude_type)` | solve_service |
| `import: from data.refinery_repo import get_feed_ratio` | 删除 | solve_service |

| 步骤 | 内容 | 影响范围 |
|------|------|---------|
| D1 | Product 加 `material_id` + `material_name` 字段 | refinery.py |
| D2 | `load_products` 填充 material_id/material_name | refinery_repo.py |
| D3 | 删除 `product_material_map` / `material_name_map` 字段 + `load_product_material_mapping` / `load_material_name_map` 方法 | refinery.py + refinery_repo.py |
| D4 | `get_products_by_material` 简化为直接字段过滤 | refinery.py |
| D5 | `get_feed_ratio` 改为 Scenario 内存方法 | refinery.py + refinery_repo.py（删除模块级函数） |
| D6 | `preload_prices` 改用 `Product.material_id` | refinery_repo.py |
| D7 | 消费侧迁移：hangmei_optimizer / solve_service / economics | hangmei_optimizer.py + solve_service.py |

---

## 六、验证标准

```bash
# 1. Scenario 字段数 ≤ 6
grep "field(default" models/refinery.py  # 应 ≤ 6

# 2. 零 Connection 引用
grep -r "Connection" calculation/  # 应 0 结果（不含 import 注释）

# 3. 零 cdu_device_id 引用
grep -r "cdu_device_id" calculation/  # 应 0 结果

# 4. 零 price_costs 引用
grep -r "price_costs" calculation/  # 应 0 结果

# 5. 零 energy_consumptions 在 load_scenario
grep "load_energy_consumptions" data/refinery_repo.py  # 应 0 结果（在 load_scenario 中）

# 6. 零 product_material_map 引用
grep -r "product_material_map" .  # 应 0 结果

# 7. 零 material_name_map 引用
grep -r "material_name_map" .  # 应 0 结果

# 8. 零模块级 get_feed_ratio 引用
grep -r "from data.refinery_repo import get_feed_ratio" .  # 应 0 结果

# 9. 性能测试
cd backend; python -m pytest tests/test_perf_solve.py -v
```
