# -*- coding: utf-8 -*-
"""
炼化场景数据模型（搬迁自 solve/refinery_model.py）。

修正点：RefineryScenario 字段与逻辑保持一致，确保数据兼容。
"""
from dataclasses import dataclass, field
from typing import Dict, List, Optional


@dataclass
class Product:
    """产品类 - 侧线属性 + 收率（对应 DB side_lines + device_yields 两表 JOIN 结果）"""
    id: str
    name: str
    source_device_id: str
    yield_rate: float = 0.0
    yield_rate_2: float = 0.0  # 备用收率，用于XY分流场景
    yield_rate_3: float = 0.0  # 航煤工况：X=0时使用
    yield_rate_4: float = 0.0  # 航煤工况：Y=0或X>0,Y>0时使用
    material_type: str = 'product'  # product / main_feed / auxiliary
    is_final: bool = False
    crude_type: str = 'BZ'  # 原油品种
    material_id: Optional[int] = None  # 关联 md_material.id（替代 product_material_map）
    material_name: str = ''  # 物料名称（替代 material_name_map）

    @property
    def full_id(self) -> str:
        return f"{self.source_device_id}_{self.name}"


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
    # note 记录装置配置的人工调整说明（如"6000提升到8000"），仅展示用，不参与计算
    note: str = ""
    # 启用/停用：停用时该装置从物流拓扑和计算中完全移除，等同于不存在
    enabled: bool = True

    @property
    def effective_capacity(self) -> float:
        """评估层（direct_calculator）用的"剩余可吃量"。

        = safety_stock_thrd × refinery_unit_load_percent% − current_capacity
        即安全库存阈值 × 负荷率 − 已占用量，反映当前还能处理多少吨/天。

        与 LP 排产层 DeviceCapacity.daily_max_input（=safety_stock_thrd，未扣
        负荷率与已占用）口径不同——LP 只保证月度总量可排，单批次是否超容由
        评估层用本属性校验。两层口径不同是有意设计。

        注：本属性名是 API 返回的 dict key（bottleneck_devices / CRUD 接口），
        改名会破坏前端契约，故保留；语义见上方说明。
        """
        # NaN 值检查（NaN != NaN）
        if self.safety_stock_thrd != self.safety_stock_thrd:
            return 0
        if self.refinery_unit_load_percent != self.refinery_unit_load_percent:
            return 0
        if self.current_capacity != self.current_capacity:
            return 0

        try:
            return max(0, self.safety_stock_thrd * self.refinery_unit_load_percent / 100.0 - self.current_capacity)
        except Exception:
            return 0

    @property
    def is_tank(self) -> bool:
        return isinstance(self, Tank)

    @property
    def is_processing_unit(self) -> bool:
        return isinstance(self, ProcessingUnit)


@dataclass
class ProcessingUnit(DeviceBase):
    """装置（devices_units 表）。"""
    type: str = "normal"  # normal / start
    # 对应慧炼 md_device 主键（用于从价格API获取加工成本），NULL 表示无映射
    backend_device_id: Optional[int] = None

    @property
    def is_start(self) -> bool:
        return self.type == "start"


@dataclass
class Tank(DeviceBase):
    """储罐（devices_tanks 表）。"""
    # 罐分类: intermediate(中间罐) / product(成品罐) / crude(原油罐)
    tank_category: Optional[str] = None
    # 储罐关联的唯一物料，关联 public.md_material.id
    material_id: Optional[int] = None
    # 物料名称（只读展示用，来自 JOIN md_material.name）
    material_name: Optional[str] = None

    @property
    def is_intermediate(self) -> bool:
        return self.tank_category == "intermediate"


@dataclass
class EnergyConsumption:
    """能耗类"""
    device_id: str = ""
    consumption_per_ton: float = 0.0
    price_per_unit: float = 0.0
    energy_type: str = "electricity"  # electricity / natural_gas / steam / water
    id: str = ""

    @property
    def cost_per_ton(self) -> float:
        return self.consumption_per_ton * self.price_per_unit


@dataclass
class MaterialFlow:
    """物流边 — 归一化模型，一行 = 一条有向边。

    source_type 三种来源：
      - device:   源自装置侧线产出（source_device_id + source_product_id）
      - external: 源自外购/原油（source_name，如"曹妃甸原油"）
      - tank:     源自储罐直接供料（tank_id 作为来源）

    flow_type 五种边类型（归一化后每行只表示一段边）：
      - source_to_tank: 装置侧线→中间罐（source_device_id→tank_id，含 special_var）
      - tank_to_target: 中间罐→加工装置（tank_id→target_device_id，含 split_ratio）
      - direct:         装置→装置直供（不经罐）
      - final:          装置→成品罐（不再加工）
      - input:          外购/原油→装置（系统入口）

    归一化规则：
      - 旧 intermediate 链路拆为 source_to_tank + tank_to_target 两行
      - source_to_tank 行：source_device_id/source_product_id/tank_id/special_var 有效，
        target_device_id/target_product_id/split_ratio 为 NULL
      - tank_to_target 行：tank_id/target_device_id/target_product_id/split_ratio 有效，
        source_device_id/source_product_id/special_var 为 NULL
      - direct 行：source_device_id/source_product_id/target_device_id/target_product_id 有效
    """
    id: str
    source_type: str = 'device'           # device | external | tank
    source_device_id: str = None          # source_type=device 时填
    source_product_id: str = None         # source_type=device 时填（源装置产品）
    source_name: str = None               # source_type=external 时填
    tank_id: str = None                   # source_to_tank: 目标罐; tank_to_target: 来源罐; final: 目标成品罐
    target_device_id: str = None          # tank_to_target/direct/input: 目标装置
    target_product_id: str = None         # tank_to_target/direct: 目标装置的进料产品(main_feed/auxiliary的product.id)
    flow_type: str = 'source_to_tank'     # source_to_tank | tank_to_target | direct | final | input
    special_var: str = None               # X / Y 分流标记（仅 source_to_tank 有意义）
    priority: int = 1
    is_unique_target: bool = False
    split_ratio: float = 1.0              # 罐→多装置的分配比例（仅 tank_to_target 有意义）

    @property
    def from_device_id(self) -> Optional[str]:
        """派生：按 flow_type 判断来源装置（替代 Connection.from_device_id）。"""
        if self.flow_type in ('source_to_tank', 'direct', 'final'):
            return self.source_device_id
        elif self.flow_type == 'tank_to_target':
            return self.tank_id
        return None

    @property
    def to_device_id(self) -> Optional[str]:
        """派生：按 flow_type 判断目的装置（替代 Connection.to_device_id）。"""
        if self.flow_type in ('source_to_tank', 'final'):
            return self.tank_id
        elif self.flow_type in ('tank_to_target', 'direct'):
            return self.target_device_id
        return None

    @property
    def from_product_id(self) -> Optional[str]:
        """派生：按 flow_type 判断来源产品（替代 Connection.from_product_id）。

        source_to_tank/direct/final: source_product_id
        tank_to_target: target_product_id（目的装置的进料产品）
        """
        if self.flow_type in ('source_to_tank', 'direct', 'final'):
            return self.source_product_id
        elif self.flow_type == 'tank_to_target':
            return self.target_product_id
        return None


@dataclass
class RefineryScenario:
    """炼化场景聚合根"""
    devices: Dict[str, DeviceBase] = field(default_factory=dict)
    products: Dict[str, Product] = field(default_factory=dict)
    material_flows: Dict[str, 'MaterialFlow'] = field(default_factory=dict)
    energy_consumptions: List[EnergyConsumption] = field(default_factory=list)
    start_device_id: Optional[str] = None
    crude_type: str = 'BZ'  # 当前使用的原油品种
    crude_costs: Dict[str, float] = field(default_factory=dict)  # 原油品种 -> 成本（元/吨）
    # 主料缓存：{device_id: [Product, ...]}，避免每次遍历全部 products
    _main_feeds_cache: Optional[Dict[str, list]] = field(default=None, init=False, repr=False)

    @property
    def start_device(self) -> Optional[DeviceBase]:
        return self.devices.get(self.start_device_id)

    def add_device(self, device: DeviceBase):
        self.devices[device.id] = device

    def add_product(self, product: Product):
        self.products[product.id] = product
        self._main_feeds_cache = None  # 失效主料缓存

    def add_material_flow(self, flow: 'MaterialFlow'):
        self.material_flows[flow.id] = flow
        if hasattr(self, '_upstream_flows_cache'):
            del self._upstream_flows_cache
            del self._downstream_flows_cache

    def _build_flow_adjacency_cache(self):
        """构建 upstream/downstream 邻接表缓存（首次调用时懒构建）。

        停用的装置（enabled=False）的物流边会被跳过，使其从拓扑中完全移除。
        """
        self._upstream_flows_cache: Dict[str, list] = {}
        self._downstream_flows_cache: Dict[str, list] = {}
        for flow in self.material_flows.values():
            frm = flow.from_device_id
            to = flow.to_device_id
            if not frm or not to:
                continue
            from_dev = self.devices.get(frm)
            if from_dev and not from_dev.enabled and not (isinstance(from_dev, ProcessingUnit) and from_dev.is_start):
                continue
            to_dev = self.devices.get(to)
            if to_dev and not to_dev.enabled and not (isinstance(to_dev, ProcessingUnit) and to_dev.is_start):
                continue
            self._upstream_flows_cache.setdefault(to, []).append(flow)
            self._downstream_flows_cache.setdefault(frm, []).append(flow)

    def get_upstream_flows(self, device_id: str) -> List['MaterialFlow']:
        """获取流向指定装置的所有物流边。

        按 flow_type 判断 to_device:
        - source_to_tank / final: to = flow.tank_id
        - tank_to_target / direct: to = flow.target_device_id

        停用的装置（enabled=False）的物流边会被跳过，使其从拓扑中完全移除。
        """
        if not hasattr(self, '_upstream_flows_cache'):
            self._build_flow_adjacency_cache()
        return self._upstream_flows_cache.get(device_id, [])

    def get_downstream_flows(self, device_id: str) -> List['MaterialFlow']:
        """获取从指定装置流出的所有物流边。

        按 flow_type 判断 from_device:
        - source_to_tank / direct / final: from = flow.source_device_id
        - tank_to_target: from = flow.tank_id

        停用的装置（enabled=False）的物流边会被跳过，使其从拓扑中完全移除。
        """
        if not hasattr(self, '_downstream_flows_cache'):
            self._build_flow_adjacency_cache()
        return self._downstream_flows_cache.get(device_id, [])

    def get_product(self, source_device_id: str, product_name: str) -> Optional[Product]:
        product_id = f"{source_device_id}_{product_name}"
        return self.products.get(product_id)

    # ── 动态装置推断（替代 config.py 硬编码装置 ID）──────────────────────────
    # 所有属性按需从数据拓扑推断，首次调用后缓存。

    @property
    def hangmei_active_device_ids(self) -> set:
        """受航煤工况影响的装置ID集合（航煤产品有 yield_rate_3/4 > 0 的装置）。

        仅检查航煤产品（通过 material_name 过滤），避免 CDU 等装置的
        非航煤产品因 yield_rate_3 有值而被误判为航煤主动装置。
        """
        if not hasattr(self, '_hangmei_active_ids'):
            hangmei_products = self.get_products_by_material('航煤')
            self._hangmei_active_ids = {
                p.source_device_id for p in hangmei_products
                if p.yield_rate_3 > 0 or p.yield_rate_4 > 0
            }
        return self._hangmei_active_ids

    @property
    def yield_switch_device_ids(self) -> set:
        """XY 分流影响的装置ID集合（有 special_var 的 source_to_tank 边的罐下游加工装置）。"""
        if not hasattr(self, '_yield_switch_ids'):
            # 找有 special_var 的 source_to_tank 边指向的罐
            switch_tanks = {
                f.tank_id for f in self.material_flows.values()
                if f.special_var and f.flow_type == 'source_to_tank'
            }
            # 找这些罐的 tank_to_target 边指向的加工装置
            self._yield_switch_ids = {
                f.target_device_id for f in self.material_flows.values()
                if f.flow_type == 'tank_to_target' and f.tank_id in switch_tanks
            }
        return self._yield_switch_ids

    @property
    def tank_device_ids(self) -> set:
        """储罐装置ID集合（isinstance Tank 且 enabled）。"""
        if not hasattr(self, '_tank_device_ids'):
            self._tank_device_ids = {
                d.id for d in self.devices.values()
                if isinstance(d, Tank) and d.enabled
            }
        return self._tank_device_ids

    @property
    def intermediate_tank_ids(self) -> set:
        """中间罐ID集合（Tank 且 tank_category=='intermediate' 且 enabled）。"""
        if not hasattr(self, '_intermediate_tank_ids'):
            self._intermediate_tank_ids = {
                d.id for d in self.devices.values()
                if isinstance(d, Tank) and d.is_intermediate and d.enabled
            }
        return self._intermediate_tank_ids

    @property
    def processing_device_ids(self) -> set:
        """加工装置ID集合（ProcessingUnit 且非 start 且 enabled）。"""
        if not hasattr(self, '_processing_ids'):
            self._processing_ids = {
                d.id for d in self.devices.values()
                if isinstance(d, ProcessingUnit) and not d.is_start and d.enabled
            }
        return self._processing_ids

    def get_products_by_material(self, material_name: str) -> List[Product]:
        """按物料名称查找所有产品（跨装置），直接过滤 Product.material_name。"""
        return [p for p in self.products.values() if p.material_name == material_name]

    def get_main_feeds(self, device_id: str) -> List[Product]:
        """获取某装置的主料产品列表。"""
        if self._main_feeds_cache is None:
            self._main_feeds_cache = {}
            for p in self.products.values():
                if p.material_type == 'main_feed':
                    self._main_feeds_cache.setdefault(p.source_device_id, []).append(p)
        return self._main_feeds_cache.get(device_id, [])

    def get_feed_ratio(self, device_id: str, crude_type: str) -> float:
        """获取装置在指定油种下的进料配比（main_feed 类型的 yield_rate）。

        场景依赖:
            - products: main_feed 类型产品的 yield_rate + crude_type
        """
        for p in self.get_main_feeds(device_id):
            if p.crude_type == crude_type and p.yield_rate > 0:
                return p.yield_rate
        return 1.0

    def get_downstream_tank(self, device_id: str) -> Optional[str]:
        """获取装置的下游储罐ID（通过 material_flows 拓扑查找）。
        用于替代硬编码的 gyrly_tank_01 / hc_tank_01。
        """
        for flow in self.get_downstream_flows(device_id):
            to_dev = self.devices.get(flow.to_device_id)
            if to_dev and isinstance(to_dev, Tank):
                return flow.to_device_id
        return None
