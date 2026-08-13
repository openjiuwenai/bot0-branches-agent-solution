# -*- coding: utf-8 -*-
"""炼化数据仓库（PostgreSQL 版）。

原 ExcelStore 继承版重写为 SQLAlchemy text() 访问 solve_db schema。
保持所有 load_*/save_* 方法名与返回类型（dataclass）不变，上层零改动。

与原 Excel 版的差异：
  - 收率字段 DB 存小数 NUMERIC(6,4)，load_products* 不再 ÷100（迁移时已转）
  - save_* 的 replace 语义用 ON CONFLICT DO UPDATE + DELETE NOT IN 实现；
    CRUD 不维护的列（devices.max_capacity/device_id_2/note）在 ON CONFLICT
    DO UPDATE 中不被覆盖，等价原 Excel 的"列级保留"，且无需读旧行
  - products 复合主键 (product_id, crude_type)：save 要求传入 dict 含 crude_type
  - crude_types 回退表不再支持（DB 无此遗留表），仅从 production_plans_input 取
  - 构造接受外部 Session 以支持事务：db=None 时内部自管 session 并自动 commit

依赖方向不变：service/calculation/scheduling → data → models。
"""
from contextlib import contextmanager
from typing import Dict, List

from sqlalchemy import text
from sqlalchemy.orm import Session

from ..models.refinery import DeviceBase, ProcessingUnit, Tank, Product, RefineryScenario, EnergyConsumption, MaterialFlow
from ..logger import get_logger, log_data_load_start, log_devices_loaded, \
    log_products_loaded, log_connections_loaded, log_scenario_loaded
from .db import SessionLocal


def _f(v, default=0.0) -> float:
    """安全转 float：None/NaN/非法值 → default；Decimal/str → float。

    DB 的 NUMERIC 列经 psycopg2 返回 Decimal，统一转 float 以匹配 dataclass。
    """
    if v is None:
        return default
    try:
        f = float(v)
    except (TypeError, ValueError):
        return default
    if f != f:  # NaN
        return default
    return f


def _i(v, default=1) -> int:
    """安全转 int。"""
    if v is None:
        return default
    try:
        return int(v)
    except (TypeError, ValueError):
        return default


class RefineryRepository:
    """炼化数据仓库 - 读写 devices/products/material_flows/energy 等（PostgreSQL）。"""

    def __init__(self, db: Session = None):
        # db 由外部传入时，写方法不自动 commit（由调用方控制事务）；否则内部自管并 commit。
        self._db = db
        self.logger = get_logger()

    @contextmanager
    def _session(self):
        """获取 session：外部传入则复用，否则临时开一个并在退出时关闭。"""
        if self._db is not None:
            yield self._db
        else:
            db = SessionLocal()
            try:
                yield db
            finally:
                db.close()

    def _commit_if_owned(self, db):
        """仅当 session 是内部自管时提交；外部传入的 session 由调用方提交。"""
        if self._db is None:
            db.commit()

    # ── 读取 ──

    def load_devices(self) -> Dict[str, DeviceBase]:
        # 分别调用 data_service 读取 devices_units + devices_tanks
        from data_service.repositories import device_repo
        with self._session() as db:
            unit_rows = device_repo.load_units(db)
            tank_rows = device_repo.load_tanks(db)
        devices = {}
        for r in unit_rows:
            device = ProcessingUnit(
                id=r['device_id'],
                name=r['name'],
                max_capacity=r['max_capacity'],
                type=str(r['type'] or 'normal').strip().lower(),
                safety_stock_thrd=r['safety_stock_thrd'],
                low_safety_thrd=r['low_safety_thrd'],
                current_capacity=r['current_capacity'],
                refinery_unit_load_percent=r['refinery_unit_load_pct'],
                note=r['note'] or '',
                backend_device_id=r['backend_device_id'],
                enabled=r['enabled'],
            )
            devices[device.id] = device
        for r in tank_rows:
            device = Tank(
                id=r['device_id'],
                name=r['name'],
                max_capacity=r['max_capacity'],
                safety_stock_thrd=r['safety_stock_thrd'],
                low_safety_thrd=r['low_safety_thrd'],
                current_capacity=r['current_capacity'],
                refinery_unit_load_percent=r['refinery_unit_load_pct'],
                note=r['note'] or '',
                tank_category=r['tank_category'].strip() if r['tank_category'] else None,
                material_id=r.get('material_id'),
                material_name=r.get('material_name'),
                enabled=r['enabled'],
            )
            devices[device.id] = device
        log_devices_loaded(devices)
        return devices

    def load_energy_consumptions(self) -> List[EnergyConsumption]:
        with self._session() as db:
            rows = db.execute(text(
                "SELECT id, device_id, consumption_per_ton, price_per_unit, energy_type "
                "FROM energy ORDER BY id"
            )).mappings().all()
        result = []
        for idx, r in enumerate(rows):
            ec_id = str(r['id'] or '')
            if not ec_id:
                ec_id = f"ec_{r['device_id']}_{r['energy_type']}_{idx}"
            result.append(EnergyConsumption(
                id=ec_id,
                device_id=str(r['device_id']),
                consumption_per_ton=_f(r['consumption_per_ton']),
                price_per_unit=_f(r['price_per_unit']),
                energy_type=str(r['energy_type'] or 'electricity'),
            ))
        return result

    def load_crude_types(self) -> Dict[str, float]:
        """加载原油品种及其成本（从 production_plans_input）。

        原 Excel 版优先 production_plans_input、回退 crude_types sheet；
        DB 版无 crude_types 遗留表，仅从 production_plans_input 取。
        """
        crude_costs = {}
        with self._session() as db:
            rows = db.execute(text(
                "SELECT crude_type_id, cost FROM production_plans_input"
            )).mappings().all()
        for r in rows:
            crude_id = str(r['crude_type_id']).strip()
            crude_costs[crude_id] = _f(r['cost'], 1000.0)
        self.logger.info(f"已从 production_plans_input 加载 {len(crude_costs)} 种原油品种成本数据")
        return crude_costs

    def load_crude_type_list(self) -> list:
        """从 public.crude_types 主数据表加载油种列表。

        返回 [{crude_type_id, crude_name, crude_code, aliases, is_active, is_default}, ...]。
        读取失败时回退到 production_plans_input。
        """
        result = []
        try:
            from data_service.repositories import crude_repo
            with self._session() as db:
                result = crude_repo.load_crudes(db)
        except Exception as e:
            self.logger.warning(f"读取 public.crude_types 失败，回退到 production_plans_input: {e}")
            # 回退：从 production_plans_input 取油种列表
            with self._session() as db:
                rows = db.execute(text(
                    "SELECT DISTINCT crude_type_id, crude_type_name FROM production_plans_input ORDER BY crude_type_id"
                )).mappings().all()
            for r in rows:
                ct_id = str(r['crude_type_id'])
                result.append({
                    'crude_type_id': ct_id,
                    'crude_name': str(r['crude_type_name'] or ct_id),
                    'crude_code': '',
                    'aliases': [],
                    'is_active': True,
                    'is_default': ct_id == 'default',
                })
            # 补充 default
            if not any(r['crude_type_id'] == 'default' for r in result):
                result.insert(0, {
                    'crude_type_id': 'default', 'crude_name': '默认/通用',
                    'crude_code': 'DEF', 'aliases': [], 'is_active': True, 'is_default': True,
                })
        self.logger.info(f"已加载 {len(result)} 种油种")
        return result

    def save_crude_type(self, data: dict, is_update: bool = False):
        """新增或更新油种主数据。"""
        from data_service.writers import crude_writer
        with self._session() as db:
            if is_update:
                ct_id = str(data.get('crude_type_id', '')).strip()
                if not ct_id:
                    raise ValueError("crude_type_id 不能为空")
                # 更新时用 upsert（ON CONFLICT 语义等价）
                crude_writer.upsert_crude(db, data)
            else:
                crude_writer.upsert_crude(db, data)
            if self._db is None:
                db.commit()

    def delete_crude_type(self, crude_type_id: str):
        """删除油种主数据。保留内联 DELETE 以检查 rowcount。"""
        with self._session() as db:
            result = db.execute(text(
                "DELETE FROM public.crude_types WHERE crude_type_id=:id"
            ), {'id': crude_type_id})
            if self._db is None:
                db.commit()
            if result.rowcount == 0:
                raise ValueError(f"油种 {crude_type_id} 不存在")

    def load_products(self, crude_type: str = None) -> Dict[str, Product]:
        """加载产品（侧线+收率），按 crude_type 过滤（含 default 回退）。

        调用 data_service.side_line_repo.load_side_lines_with_yields。
        """
        from data_service.repositories import side_line_repo
        with self._session() as db:
            rows = side_line_repo.load_side_lines_with_yields(db, crude_type=crude_type)
        products = {}
        for r in rows:
            pid = r['side_line_id']
            if not pid:
                continue
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
                material_id=r.get('material_id'),
                material_name=r.get('material_name') or '',
            )
        log_products_loaded(products)
        return products

    def load_products_grouped(self) -> Dict[str, Dict[str, Product]]:
        """加载全部产品（侧线+收率），按 crude_type 分组：{crude_type: {product_id: Product}}。

        调用 data_service.side_line_repo.load_side_lines_with_yields（不传 crude_type 取全部）。
        """
        from data_service.repositories import side_line_repo
        with self._session() as db:
            rows = side_line_repo.load_side_lines_with_yields(db, crude_type=None)
        grouped: Dict[str, Dict[str, Product]] = {}
        for r in rows:
            pid = r['side_line_id']
            if not pid:
                continue
            ct = r['crude_type'] or 'BZ'
            grouped.setdefault(ct, {})[pid] = Product(
                id=pid,
                name=r['name'],
                source_device_id=r['source_device_id'] or '',
                yield_rate=r['yield_rate'],
                yield_rate_2=r['yield_rate_2'],
                yield_rate_3=r['yield_rate_3'],
                yield_rate_4=r['yield_rate_4'],
                material_type=r['material_type'] or 'product',
                is_final=r['is_final'],
                crude_type=ct,
                material_id=r.get('material_id'),
                material_name=r.get('material_name') or '',
            )
        return grouped

    def load_product_material_mapping(self) -> Dict[str, int]:
        """加载产品→物料映射 {product_id: material_id}。调用 data_service.mapping_repo。"""
        from data_service.repositories import mapping_repo
        with self._session() as db:
            mapping = mapping_repo.load_product_material_mapping(db)
        self.logger.info(f"已加载 {len(mapping)} 条产品→物料映射")
        return mapping

    def load_material_flows(self) -> Dict[str, MaterialFlow]:
        """加载物流边表 material_flows。调用 data_service.flow_repo。"""
        from data_service.repositories import flow_repo
        with self._session() as db:
            rows = flow_repo.load_flows(db)
        flows = {}
        for r in rows:
            flows[r['flow_id']] = MaterialFlow(
                id=r['flow_id'],
                source_type=r['source_type'],
                source_device_id=r['source_device_id'],
                source_product_id=r['source_product_id'],
                source_name=r['source_name'],
                tank_id=r['tank_id'],
                target_device_id=r['target_device_id'],
                target_product_id=r['target_product_id'],
                flow_type=r['flow_type'],
                special_var=r['special_var'],
                priority=r['priority'],
                is_unique_target=r['is_unique_target'],
                split_ratio=r['split_ratio'],
            )
        self.logger.info(f"已加载 {len(flows)} 条物流边")
        return flows

    def load_scenario(
        self,
        start_device_id: str = None,
        crude_type: str = None,
    ) -> RefineryScenario:
        """加载完整炼化场景（组装逻辑与原 Excel 版逐行等价）。"""
        log_data_load_start("solve_db (PostgreSQL)")
        scenario = RefineryScenario()
        scenario.crude_type = crude_type

        scenario.crude_costs = self.load_crude_types()

        devices = self.load_devices()
        for device in devices.values():
            scenario.add_device(device)
            if start_device_id is None and isinstance(device, ProcessingUnit) and device.is_start:
                start_device_id = device.id

        if start_device_id is None:
            start_device_id = 'A'

        scenario.start_device_id = start_device_id
        self.logger.info(f"设置起点装置: {start_device_id}")

        products = self.load_products(crude_type=crude_type)
        for product in products.values():
            scenario.add_product(product)

        # 物流边表（替代 connections，connections 属性从 material_flows 动态派生）
        material_flows = self.load_material_flows()
        for flow in material_flows.values():
            scenario.add_material_flow(flow)

        self.logger.info(f"使用原油品种: {crude_type if crude_type else 'BZ(默认)'}")
        log_scenario_loaded(scenario)
        return scenario

    # ── 写入（replace 语义：ON CONFLICT DO UPDATE + DELETE NOT IN）──
    # 入参 dict 字段名为 DB 列名（小写下划线），由 api 层 _xxx_payload 生成。

    def save_devices(self, devices_list: list):
        """全量替换 devices：按 type 分流到 devices_units / devices_tanks。

        调用 data_service.device_writer.replace_units + replace_tanks。
        入参 dict 含 type 字段：'normal'/'start' → devices_units，'tank' → devices_tanks。
        """
        from data_service.writers import device_writer
        unit_rows = [d for d in devices_list if str(d.get("type", "normal")) in ("normal", "start")]
        tank_rows = [d for d in devices_list if str(d.get("type", "")) == "tank"]
        with self._session() as db:
            unit_count = device_writer.replace_units(db, unit_rows)
            tank_count = device_writer.replace_tanks(db, tank_rows)
            self._commit_if_owned(db)
        self.logger.info(f"已保存 {unit_count} 个装置 + {tank_count} 个储罐数据")

    # ── 中间罐月初容量 ──────────────────────────────────────────

    def load_tank_monthly_initial(self, year_month: str) -> list:
        """加载指定月份的中间罐月初容量。"""
        with self._session() as db:
            rows = db.execute(text(
                "SELECT tank_id, year_month, initial_capacity "
                "FROM tank_monthly_initial WHERE year_month = :ym "
                "ORDER BY tank_id"
            ), {'ym': year_month}).mappings().all()
        return [{'tank_id': r['tank_id'], 'year_month': r['year_month'],
                 'initial_capacity': _f(r['initial_capacity'])} for r in rows]

    def save_tank_monthly_initial(self, rows: list):
        """批量保存中间罐月初容量（upsert）。"""
        with self._session() as db:
            for r in rows:
                db.execute(text(
                    "INSERT INTO tank_monthly_initial (tank_id, year_month, initial_capacity) "
                    "VALUES (:tank_id, :year_month, :initial_capacity) "
                    "ON CONFLICT (tank_id, year_month) DO UPDATE SET "
                    "initial_capacity=EXCLUDED.initial_capacity"
                ), {
                    'tank_id': str(r['tank_id']),
                    'year_month': str(r['year_month']),
                    'initial_capacity': _f(r.get('initial_capacity')),
                })
            self._commit_if_owned(db)
        self.logger.info(f"已保存 {len(rows)} 条月初容量数据")

    def delete_tank_monthly_initial(self, tank_id: str, year_month: str):
        """删除单条月初容量记录。"""
        with self._session() as db:
            db.execute(text(
                "DELETE FROM tank_monthly_initial "
                "WHERE tank_id = :tid AND year_month = :ym"
            ), {'tid': tank_id, 'ym': year_month})
            self._commit_if_owned(db)

    def save_products(self, products_list: list):
        """全量替换产品数据（side_lines + device_yields）。

        调用 data_service.side_line_writer 分步写入：
          1. replace_side_lines：侧线主数据（name/source_device_id/material_type/is_final），去重 upsert
          2. replace_yields：收率（yield_rate/2/3/4），按 (side_line_id, crude_type) upsert
        储罐侧线的 yield_rate 强制为 1.0。
        """
        from data_service.writers import side_line_writer
        with self._session() as db:
            if not products_list:
                side_line_writer.replace_side_lines(db, [])
                side_line_writer.replace_yields(db, [])
                self._commit_if_owned(db)
                self.logger.info("已清空 side_lines + device_yields")
                return

            # 查询储罐装置ID集合，储罐产品的 yield_rate 强制为 1.0
            tank_ids = set()
            for r in db.execute(text("SELECT device_id FROM devices_tanks")).all():
                tank_ids.add(str(r[0]))

            # 分拆数据：side_lines（去重）+ device_yields
            sl_items = []
            yield_items = []
            written_sl = set()
            for p in products_list:
                pid = str(p.get('product_id'))
                ct = str(p.get('crude_type', 'BZ'))

                if pid not in written_sl:
                    written_sl.add(pid)
                    sl_items.append({
                        'side_line_id': pid,
                        'name': str(p.get('name', '')),
                        'source_device_id': str(p.get('source_device_id', '')),
                        'material_type': str(p.get('material_type', 'product')),
                        'is_final': bool(p.get('is_final', False)),
                    })

                src_dev = str(p.get('source_device_id', ''))
                if src_dev in tank_ids:
                    yr, yr2, yr3, yr4 = 1.0, 0, 0, 0
                else:
                    yr = _f(p.get('yield_rate'))
                    yr2 = _f(p.get('yield_rate_2'))
                    yr3 = _f(p.get('yield_rate_3'))
                    yr4 = _f(p.get('yield_rate_4'))
                yield_items.append({
                    'side_line_id': pid,
                    'crude_type': ct,
                    'yield_rate': yr,
                    'yield_rate_2': yr2,
                    'yield_rate_3': yr3,
                    'yield_rate_4': yr4,
                })

            side_line_writer.replace_side_lines(db, sl_items)
            side_line_writer.replace_yields(db, yield_items)
            self._commit_if_owned(db)
        self.logger.info(f"已保存 {len(written_sl)} 条侧线 + {len(yield_items)} 条收率数据")

    def save_connections(self, connections_list: list):
        """全量替换 connections。"""
        with self._session() as db:
            if not connections_list:
                db.execute(text("TRUNCATE connections"))
                self._commit_if_owned(db)
                self.logger.info("已清空 connections")
                return
            new_ids = [str(c.get('connection_id')) for c in connections_list]
            db.execute(text("DELETE FROM connections WHERE NOT (connection_id = ANY(:ids))"),
                       {'ids': new_ids})
            for c in connections_list:
                sv = c.get('special_var')
                db.execute(text(
                    "INSERT INTO connections (connection_id, from_device_id, from_product_id, "
                    "to_device_id, priority, is_unique_target, special_var) "
                    "VALUES (:connection_id, :from_device_id, :from_product_id, "
                    ":to_device_id, :priority, :is_unique_target, :special_var) "
                    "ON CONFLICT (connection_id) DO UPDATE SET "
                    "from_device_id=EXCLUDED.from_device_id, "
                    "from_product_id=EXCLUDED.from_product_id, "
                    "to_device_id=EXCLUDED.to_device_id, priority=EXCLUDED.priority, "
                    "is_unique_target=EXCLUDED.is_unique_target, "
                    "special_var=EXCLUDED.special_var"
                ), {
                    'connection_id': str(c.get('connection_id')),
                    'from_device_id': str(c.get('from_device_id', '')),
                    'from_product_id': str(c.get('from_product_id', '')),
                    'to_device_id': str(c.get('to_device_id', '')),
                    'priority': _i(c.get('priority'), 1),
                    'is_unique_target': bool(c.get('is_unique_target', False)),
                    'special_var': None if sv in (None, '') else str(sv),
                })
            self._commit_if_owned(db)
        self.logger.info(f"已保存 {len(connections_list)} 个连接数据")

    def save_material_flows(self, flows_list: list):
        """全量替换 material_flows。"""
        with self._session() as db:
            if not flows_list:
                db.execute(text("TRUNCATE material_flows"))
                self._commit_if_owned(db)
                self.logger.info("已清空 material_flows")
                return
            new_ids = [str(f.get('flow_id')) for f in flows_list]
            db.execute(text("DELETE FROM material_flows WHERE NOT (flow_id = ANY(:ids))"),
                       {'ids': new_ids})
            for f in flows_list:
                sv = f.get('special_var')
                db.execute(text(
                    "INSERT INTO material_flows (flow_id, source_type, source_device_id, "
                    "source_product_id, source_name, tank_id, target_device_id, target_product_id, "
                    "flow_type, special_var, priority, is_unique_target, split_ratio) "
                    "VALUES (:flow_id, :source_type, :source_device_id, :source_product_id, "
                    ":source_name, :tank_id, :target_device_id, :target_product_id, "
                    ":flow_type, :special_var, :priority, :is_unique_target, :split_ratio) "
                    "ON CONFLICT (flow_id) DO UPDATE SET "
                    "source_type=EXCLUDED.source_type, "
                    "source_device_id=EXCLUDED.source_device_id, "
                    "source_product_id=EXCLUDED.source_product_id, "
                    "source_name=EXCLUDED.source_name, tank_id=EXCLUDED.tank_id, "
                    "target_device_id=EXCLUDED.target_device_id, "
                    "target_product_id=EXCLUDED.target_product_id, "
                    "flow_type=EXCLUDED.flow_type, "
                    "special_var=EXCLUDED.special_var, priority=EXCLUDED.priority, "
                    "is_unique_target=EXCLUDED.is_unique_target, "
                    "split_ratio=EXCLUDED.split_ratio"
                ), {
                    'flow_id': str(f.get('flow_id')),
                    'source_type': str(f.get('source_type', 'device')),
                    'source_device_id': f.get('source_device_id'),
                    'source_product_id': f.get('source_product_id'),
                    'source_name': f.get('source_name'),
                    'tank_id': f.get('tank_id'),
                    'target_device_id': f.get('target_device_id'),
                    'target_product_id': f.get('target_product_id'),
                    'flow_type': str(f.get('flow_type', 'source_to_tank')),
                    'special_var': None if sv in (None, '') else str(sv),
                    'priority': _i(f.get('priority'), 1),
                    'is_unique_target': bool(f.get('is_unique_target', False)),
                    'split_ratio': _f(f.get('split_ratio'), 1.0),
                })
            self._commit_if_owned(db)
        self.logger.info(f"已保存 {len(flows_list)} 条物流边数据")

    def save_energy_consumptions(self, energy_consumptions_list: List[dict]):
        """全量替换 energy（原 save_energy_consumptions，统一 replace 语义）。"""
        rows = []
        for i, e in enumerate(energy_consumptions_list):
            ec_id = str(e.get('id') or '')
            if not ec_id:
                ec_id = f"ec_{i}"
            rows.append({
                'id': ec_id,
                'device_id': str(e.get('device_id', '')),
                'consumption_per_ton': _f(e.get('consumption_per_ton')),
                'price_per_unit': _f(e.get('price_per_unit')),
                'energy_type': str(e.get('energy_type', 'electricity')),
            })
        with self._session() as db:
            if not rows:
                db.execute(text("TRUNCATE energy"))
                self._commit_if_owned(db)
                self.logger.info("已清空 energy")
                return
            new_ids = [r['id'] for r in rows]
            db.execute(text("DELETE FROM energy WHERE NOT (id = ANY(:ids))"),
                       {'ids': new_ids})
            for r in rows:
                db.execute(text(
                    "INSERT INTO energy (id, device_id, consumption_per_ton, price_per_unit, energy_type) "
                    "VALUES (:id, :device_id, :consumption_per_ton, :price_per_unit, :energy_type) "
                    "ON CONFLICT (id) DO UPDATE SET "
                    "device_id=EXCLUDED.device_id, "
                    "consumption_per_ton=EXCLUDED.consumption_per_ton, "
                    "price_per_unit=EXCLUDED.price_per_unit, "
                    "energy_type=EXCLUDED.energy_type"
                ), r)
            self._commit_if_owned(db)
        self.logger.info(f"已保存 {len(rows)} 个能耗数据")

    def preload_prices(self, month: str,
                       products: Dict[str, 'Product']) -> Dict[str, float]:
        """P1优化：批量预加载所有产品价格，返回 product_id → price 映射。

        一次 DB 查询获取所有直接价格，缺失的逐个走计算规则，
        返回 flat dict 供调用方直接使用（不再写入 scenario）。
        """
        if not month or not products:
            return {}

        # 收集所有 material_id（去重，跳过 None）
        material_ids = list(set(
            p.material_id for p in products.values() if p.material_id is not None
        ))
        if not material_ids:
            return {}

        try:
            from data_service.repositories import price_repo
            with self._session() as db:
                price_map = price_repo.resolve_prices_batch(db, month, material_ids)

            # 构建 product_id → price 映射
            prices: Dict[str, float] = {}
            count = 0
            for product_id, product in products.items():
                if product.material_id is None:
                    prices[product_id] = 0.0
                    continue
                price = price_map.get(str(product.material_id))
                if price is not None and price >= 0:
                    prices[product_id] = float(price)
                    count += 1
                else:
                    # 无价格产品也缓存（标记为 0），避免后续逐个 DB 查询
                    prices[product_id] = 0.0
            self.logger.info(
                f"[P1预加载] 月份={month}, "
                f"批量获取 {count}/{len(material_ids)} 个产品价格")
            return prices
        except Exception as e:
            self.logger.warning(f"[P1预加载] 批量价格获取失败: {e}")
            return {}
