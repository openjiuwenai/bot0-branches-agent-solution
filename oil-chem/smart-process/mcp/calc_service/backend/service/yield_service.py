# -*- coding: utf-8 -*-
"""收率与排厂基础数据编排服务层。

承接原 api/yield_routes.py 中的业务编排（_build_product_yields /
_fallback_plans_from_details / get_scheduling_data 整形），让路由回归薄封装。
依赖方向：service → {data} → models。

收率数据统一走 RefineryRepository.load_products_grouped()（小数），消除原
products sheet 双读 + 单位不一致问题。
"""
import json
from typing import Dict, List

from ..logger import get_logger
from ..data.refinery_repo import RefineryRepository
from ..data.scheduling_repo import SchedulingRepository
from ..models.scheduling import ProductionPlansInput


# 装置类型判断关键词（迁移自 get_product_yields_core）
_DIESEL_KEYWORDS = ['柴油加氢', '柴油', 'diesel', 'cyjq']
_WAX_KEYWORDS = ['蜡油加氢', '蜡油', 'wax', 'lyjq']


class YieldService:
    """收率与排厂基础数据编排。"""

    def __init__(self, logger=None):
        self.logger = logger or get_logger()

    def _repo(self) -> SchedulingRepository:
        return SchedulingRepository()

    def _refinery_repo(self) -> RefineryRepository:
        return RefineryRepository()

    def build_product_yields(self) -> dict:
        """构建 crude_type→device→operation_mode→products 层级。

        柴油加氢(cyjq_01)/蜡油加氢(lyjq_01) 装置返回两种工况
        (含减一线/不含减一线)，其余装置返回单一常规工况。
        收率来源统一走 load_products_grouped()（小数），不再各自 read_sheet。
        """
        refinery_repo = self._refinery_repo()
        products_by_crude = refinery_repo.load_products_grouped()
        # 动态获取罐区设备ID集合，排除非加工装置
        devices = refinery_repo.load_devices()
        tank_device_ids = {did for did, dev in devices.items() if dev.is_tank}
        crude_types_dict: Dict[str, dict] = {}

        for crude_type, products in products_by_crude.items():
            for product_id, prod in products.items():
                device_id = prod.source_device_id
                if device_id in tank_device_ids:
                    continue
                # 跳过主料/辅料（前端收率表只展示产出物）
                if prod.material_type != 'product':
                    continue

                if crude_type not in crude_types_dict:
                    crude_types_dict[crude_type] = {
                        'crude_type_id': crude_type,
                        'crude_type_name': crude_type,
                        'devices': {}
                    }

                is_diesel = any(kw in device_id.lower() or kw in prod.name for kw in _DIESEL_KEYWORDS)
                is_wax = any(kw in device_id.lower() or kw in prod.name for kw in _WAX_KEYWORDS)
                is_special = is_diesel or is_wax

                if device_id not in crude_types_dict[crude_type]['devices']:
                    if is_special:
                        device_name = '柴油加氢装置' if is_diesel else '蜡油加氢装置'
                        crude_types_dict[crude_type]['devices'][device_id] = {
                            'device_id': device_id,
                            'device_name': device_name,
                            'operation_modes': [
                                {
                                    'mode_id': 'with_jian1',
                                    'mode_name': '含减一线工况',
                                    'description': '减一线作为原料进入该装置' if is_diesel else '减一线去该装置',
                                    'has_jian1_feed': True,
                                    'products': []
                                },
                                {
                                    'mode_id': 'without_jian1',
                                    'mode_name': '不含减一线工况',
                                    'description': '减一线不进入该装置' if is_diesel else '减一线去柴油加氢，蜡油去该装置',
                                    'has_jian1_feed': False,
                                    'products': []
                                }
                            ]
                        }
                    else:
                        crude_types_dict[crude_type]['devices'][device_id] = {
                            'device_id': device_id,
                            'operation_modes': [
                                {
                                    'mode_id': 'normal',
                                    'mode_name': '常规工况',
                                    'description': '标准操作模式',
                                    'has_jian1_feed': None,
                                    'products': []
                                }
                            ]
                        }

                device_data = crude_types_dict[crude_type]['devices'][device_id]

                if is_special:
                    for mode in device_data['operation_modes']:
                        if mode['has_jian1_feed']:
                            # 含减一线工况：使用 yield_rate_2
                            mode['products'].append({
                                'product_id': product_id,
                                'product_name': prod.name,
                                'yield_rate': prod.yield_rate_2,
                                'yield_rate_source': 'yield_rate_2',
                                'is_final': prod.is_final
                            })
                        else:
                            # 不含减一线工况：使用 yield_rate
                            mode['products'].append({
                                'product_id': product_id,
                                'product_name': prod.name,
                                'yield_rate': prod.yield_rate,
                                'yield_rate_source': 'yield_rate',
                                'is_final': prod.is_final
                            })
                else:
                    device_data['operation_modes'][0]['products'].append({
                        'product_id': product_id,
                        'product_name': prod.name,
                        'yield_rate': prod.yield_rate,
                        'yield_rate_source': 'yield_rate',
                        'is_final': prod.is_final
                    })

        result_data = {'crude_types': []}
        for crude_data in crude_types_dict.values():
            crude_data['devices'] = list(crude_data['devices'].values())
            result_data['crude_types'].append(crude_data)
        return result_data

    def _fallback_plans_from_details(self, repo: SchedulingRepository) -> List[ProductionPlansInput]:
        """production_plans_input 为空时，从 production_plan_details 反推原油品种。

        原版逻辑（web_app.py L2486-2540）：扫描 blend_detail 与 crude_stock_status，
        收集出现过的原油 id，生成默认 input 行。

        原直读 sheet（repo.read_sheet）改为走 repo.load_production_plan_details()，
        消除 service 层直读存储的特例（DB 化后 Repository 不再暴露 read_sheet）。
        """
        crude_types_found = set()
        details = repo.load_production_plan_details()  # 全量读，无 plan_id 过滤
        if not details:
            return []

        for detail in details:
            for field in ('blend_detail', 'crude_stock_status'):
                parsed = getattr(detail, field, {})
                if isinstance(parsed, str):
                    try:
                        parsed = json.loads(parsed)
                    except Exception:
                        parsed = {}
                if isinstance(parsed, dict):
                    for crude_id, amount in parsed.items():
                        if isinstance(amount, (int, float)) and amount > 0:
                            crude_types_found.add(crude_id)

        self.logger.info(f"从 production_plan_details 提取到原油类型: {crude_types_found}")
        return [
            ProductionPlansInput(
                planned_month='2026-06',
                crude_type_id=crude_id,
                crude_type_name=crude_id,
                arrival_plan={},
                monthly_processing_capacity=30000,
                current_stock=5000,
                max_level_stock=20000,
                min_level_stock=3000,
                cost=3500,
            )
            for crude_id in crude_types_found
        ]

    def get_scheduling_data(self) -> dict:
        """获取排厂基础数据（兼容旧前端形状）。

        返回结构兼容旧前端：crude_types / storage_tanks / arrival_plans /
        blend_schemes / device_capacity / production_plans_input（统一模型）。
        storage_tanks 仍以 virtual_tank_<crude> 命名以兼容现有前端展示。
        """
        repo = self._repo()
        all_data = repo.load_all_data()
        input_data: List[ProductionPlansInput] = all_data['production_plans_input']
        device_capacity = all_data['device_capacity']

        if not input_data:
            self.logger.info("production_plans_input 为空，从 production_plan_details 提取原油数据")
            input_data = self._fallback_plans_from_details(repo)

        # 1. crude_types
        crude_types = {}
        for item in input_data:
            crude_types[item.crude_type_id] = {
                'id': item.crude_type_id,
                'name': item.crude_type_name,
                'cost': item.cost
            }

        # 2. storage_tanks（虚拟罐，按原油品种，兼容旧前端）
        storage_tanks = {}
        for item in input_data:
            tank_id = f"virtual_tank_{item.crude_type_id}"
            storage_tanks[tank_id] = {
                'tank_id': tank_id,
                'tank_name': f"{item.crude_type_name}虚拟罐",
                'crude_type_id': item.crude_type_id,
                'current_stock': item.current_stock,
                'capacity': item.max_level_stock,
                'min_level': item.min_level_stock,
                'max_level': item.max_level_stock
            }

        # 3. arrival_plans
        arrival_plans = []
        for item in input_data:
            arrival_plan = item.arrival_plan
            if isinstance(arrival_plan, str):
                try:
                    arrival_plan = json.loads(arrival_plan)
                except Exception:
                    arrival_plan = {}
            if not isinstance(arrival_plan, dict):
                arrival_plan = {}

            for arrival_date, quantity in arrival_plan.items():
                plan_id = f"arrival_{item.crude_type_id}_{str(arrival_date).split(' ')[0].replace('-', '')}"
                arrival_plans.append({
                    'plan_id': plan_id,
                    'crude_type_id': item.crude_type_id,
                    'arrival_date': arrival_date,
                    'quantity': quantity,
                    'target_tank_id': f"virtual_tank_{item.crude_type_id}",
                    'status': '待卸货'
                })

        return {
            'crude_types': crude_types,
            'storage_tanks': storage_tanks,
            'arrival_plans': arrival_plans,
            'blend_schemes': {},  # 新结构不含，保留空对象兼容前端
            'device_capacity': device_capacity.to_dict(),
            'production_plans_input': [item.to_dict() for item in input_data],
        }
