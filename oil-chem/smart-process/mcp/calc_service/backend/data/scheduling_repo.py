# -*- coding: utf-8 -*-
"""排厂数据仓库（PostgreSQL 版）。

原 ExcelStore 继承版重写为 SQLAlchemy text() 访问 solve_db schema。
保持所有 load_*/save_* 方法名与返回类型（dataclass）不变，上层零改动。

与原 Excel 版的差异：
  - JSONB 列（arrival_plan/blend_detail/crude_stock_status）：写 json.dumps +
    CAST(:x AS jsonb)，读 ::text + json.loads（不依赖驱动 jsonb 适配，行为确定）
  - scheduling_tasks 时间列 DB 为 TIMESTAMPTZ，dataclass 仍为 str(iso)：
    读出 datetime → isoformat()；写入解析 iso 字符串
  - save_production_plan_details(merge=True) 用 ON CONFLICT(id) DO UPDATE（不删旧行）；
    merge=False 用 DELETE WHERE plan_id + INSERT（替换该计划全部明细）
  - save_scheduling_task / update_task_status / delete_scheduling_task：
    原"load 全表 → 改 → 写全表"RMW 改为单行 INSERT ON CONFLICT / UPDATE / DELETE
  - load_device_capacity 保留：查 devices 表 cjy_01 行的投影
  - 死代码不实现：save_device_capacity、save_production_plans_input（无调用方）
  - 构造接受外部 Session 以支持事务：db=None 时内部自管并自动 commit
"""
import json
from contextlib import contextmanager
from datetime import date, datetime
from typing import Dict, List, Optional

from sqlalchemy import text
from sqlalchemy.orm import Session

from ..models.scheduling import ProductionPlansInput, ProductionPlanDetail, SchedulingTask, DeviceCapacity
from ..logger import get_logger
from .db import SessionLocal

logger = get_logger()


def _f(v, default=0.0) -> float:
    """安全转 float：None/NaN/Decimal → float。"""
    if v is None:
        return default
    try:
        f = float(v)
    except (TypeError, ValueError):
        return default
    if f != f:
        return default
    return f


def _parse_jsonb(v) -> dict:
    """JSONB 列读出（::text 后）→ dict；失败返回 {}。"""
    if v is None:
        return {}
    if isinstance(v, dict):
        return v
    if isinstance(v, str):
        try:
            parsed = json.loads(v)
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}
    return {}


def _iso(v) -> str:
    """时间列读出 → iso 字符串（dataclass 保持 str 类型）。None → ''。"""
    if v is None:
        return ''
    if isinstance(v, (datetime, date)):
        return v.isoformat()
    return str(v)


class SchedulingRepository:
    """排厂数据仓库 - 管理 production_plans_input / details / scheduling_tasks（PostgreSQL）。"""

    def __init__(self, db: Session = None):
        self._db = db

    @contextmanager
    def _session(self):
        if self._db is not None:
            yield self._db
        else:
            db = SessionLocal()
            try:
                yield db
            finally:
                db.close()

    def _commit_if_owned(self, db):
        if self._db is None:
            db.commit()

    # ── production_plans_input ──

    def load_production_plans_input(self) -> List[ProductionPlansInput]:
        with self._session() as db:
            rows = db.execute(text(
                "SELECT planned_month, crude_type_id, crude_type_name, arrival_plan, "
                "monthly_processing_capacity, current_stock, max_level_stock, "
                "min_level_stock, cost FROM production_plans_input"
            )).mappings().all()
        data = []
        for r in rows:
            data.append(ProductionPlansInput(
                planned_month=str(r['planned_month'] or ''),
                crude_type_id=str(r['crude_type_id'] or ''),
                crude_type_name=str(r['crude_type_name'] or ''),
                arrival_plan=_parse_jsonb(r['arrival_plan']),
                monthly_processing_capacity=_f(r['monthly_processing_capacity']),
                current_stock=_f(r['current_stock']),
                max_level_stock=_f(r['max_level_stock']),
                min_level_stock=_f(r['min_level_stock']),
                cost=_f(r['cost'], 1000.0),
            ))
        return data

    def save_production_plans_input_costs(self, planned_month: str, items: list):
        """批量更新原油成本 cost（按 planned_month+crude_type_id 复合主键 upsert）。

        仅写 cost + crude_type_name（upsert 时 name 缺省回退 id），不动
        arrival_plan/库存/加工能力等字段，避免价格成本页误覆盖排产配置。
        若该 (month, crude) 不存在则插入最小行（cost/name + 其余列默认 0）。

        入参 items: [{crude_type_id, cost, crude_type_name?}]
        """
        if not items or not planned_month:
            return
        with self._session() as db:
            for it in items:
                cid = str(it.get('crude_type_id', '')).strip()
                if not cid:
                    continue
                cname = str(it.get('crude_type_name') or cid)
                db.execute(text(
                    "INSERT INTO production_plans_input "
                    "(planned_month, crude_type_id, crude_type_name, cost, "
                    " arrival_plan, monthly_processing_capacity, current_stock, "
                    " max_level_stock, min_level_stock) "
                    "VALUES (:pm, :cid, :cname, :cost, '{}'::jsonb, 0, 0, 0, 0) "
                    "ON CONFLICT (planned_month, crude_type_id) DO UPDATE SET "
                    "cost=EXCLUDED.cost, crude_type_name=EXCLUDED.crude_type_name"
                ), {
                    'pm': planned_month,
                    'cid': cid,
                    'cname': cname,
                    'cost': _f(it.get('cost'), 1000.0),
                })
            self._commit_if_owned(db)
        logger.info(f"已保存 {len(items)} 条原油成本到 production_plans_input ({planned_month})")

    # ── production_plan_details ──

    def load_production_plan_details(self, plan_id: str = None) -> List[ProductionPlanDetail]:
        sql = ("SELECT id, plan_id, plan_date, day_of_month, daily_input, "
               "blend_detail, crude_stock_status, device_load_rate, hours "
               "FROM production_plan_details")
        params: dict = {}
        if plan_id:
            sql += " WHERE plan_id = :plan_id"
            params['plan_id'] = plan_id
        sql += " ORDER BY plan_date, id"
        with self._session() as db:
            rows = db.execute(text(sql), params).mappings().all()

        data = []
        for r in rows:
            # 兼容原版别名 crude_stock_status / tank_status（DB 统一为 crude_stock_status）
            crude_stock_status = _parse_jsonb(r['crude_stock_status'])
            blend_detail = _parse_jsonb(r['blend_detail'])

            # 根据 blend_detail 计算 daily_input（不使用表中的值，与原版一致）
            daily_input = sum(v for v in blend_detail.values()
                              if isinstance(v, (int, float)) and v > 0)

            plan_date_raw = r['plan_date']
            if hasattr(plan_date_raw, 'isoformat'):
                plan_date = plan_date_raw if isinstance(plan_date_raw, date) and not isinstance(plan_date_raw, datetime) else plan_date_raw.date()
            else:
                ps = str(plan_date_raw or '').split(' ')[0]
                try:
                    plan_date = datetime.strptime(ps, '%Y-%m-%d').date()
                except Exception:
                    plan_date = date.today()

            detail_id = str(r['id'] or '')
            if not detail_id:
                detail_id = f"DETAIL-{r['plan_id']}-{r['day_of_month'] or plan_date.day}-{r['hours'] or 24}"

            data.append(ProductionPlanDetail(
                id=detail_id,
                plan_id=str(r['plan_id'] or ''),
                plan_date=plan_date,
                day_of_month=int(r['day_of_month'] or plan_date.day),
                daily_input=daily_input,
                blend_detail=blend_detail,
                crude_stock_status=crude_stock_status,
                device_load_rate=_f(r['device_load_rate']),
                hours=_f(r['hours'], 24.0),
            ))
        return data

    def save_production_plan_details(self, details: List[ProductionPlanDetail], merge: bool = True):
        """保存计划明细。merge=True 按 id upsert（不删旧行）；False 替换该计划全部明细。

        支持外部 Session 事务：若构造时传入 db，本方法不 commit（由调用方控制）。
        """
        with self._session() as db:
            if not merge and details:
                # 替换语义：先删该计划全部明细再插入
                plan_ids = {d.plan_id for d in details}
                db.execute(text("DELETE FROM production_plan_details WHERE plan_id = ANY(:ids)"),
                           {'ids': list(plan_ids)})

            for d in details:
                blend = d.blend_detail if isinstance(d.blend_detail, str) else json.dumps(d.blend_detail, ensure_ascii=False)
                stock = d.crude_stock_status if isinstance(d.crude_stock_status, str) else json.dumps(d.crude_stock_status, ensure_ascii=False)
                plan_date_str = d.plan_date.isoformat() if hasattr(d.plan_date, 'isoformat') else str(d.plan_date)
                db.execute(text(
                    "INSERT INTO production_plan_details "
                    "(id, plan_id, plan_date, day_of_month, daily_input, blend_detail, "
                    "crude_stock_status, device_load_rate, hours) "
                    "VALUES (:id, :plan_id, :plan_date, :day_of_month, :daily_input, "
                    "CAST(:blend_detail AS jsonb), CAST(:crude_stock_status AS jsonb), "
                    ":device_load_rate, :hours) "
                    "ON CONFLICT (id) DO UPDATE SET "
                    "plan_id=EXCLUDED.plan_id, plan_date=EXCLUDED.plan_date, "
                    "day_of_month=EXCLUDED.day_of_month, daily_input=EXCLUDED.daily_input, "
                    "blend_detail=EXCLUDED.blend_detail, "
                    "crude_stock_status=EXCLUDED.crude_stock_status, "
                    "device_load_rate=EXCLUDED.device_load_rate, hours=EXCLUDED.hours"
                ), {
                    'id': d.id,
                    'plan_id': d.plan_id,
                    'plan_date': plan_date_str,
                    'day_of_month': d.day_of_month,
                    'daily_input': d.daily_input,
                    'blend_detail': blend,
                    'crude_stock_status': stock,
                    'device_load_rate': d.device_load_rate,
                    'hours': getattr(d, 'hours', 24.0),
                })
            self._commit_if_owned(db)
        logger.info(f"{'合并' if merge else '替换'}保存 production_plan_details: {len(details)} 条")

    # ── cp_sat_plan_details（CP-SAT 排产结果独立表，不覆盖客户实际排产）──

    def load_cp_sat_plan_details(self, plan_id: str = None) -> List[ProductionPlanDetail]:
        """加载 CP-SAT 排产明细（结构与 production_plan_details 完全一致）。"""
        sql = ("SELECT id, plan_id, plan_date, day_of_month, daily_input, "
               "blend_detail, crude_stock_status, device_load_rate, hours "
               "FROM cp_sat_plan_details")
        params: dict = {}
        if plan_id:
            sql += " WHERE plan_id = :plan_id"
            params['plan_id'] = plan_id
        sql += " ORDER BY plan_date, id"
        with self._session() as db:
            rows = db.execute(text(sql), params).mappings().all()

        data = []
        for r in rows:
            crude_stock_status = _parse_jsonb(r['crude_stock_status'])
            blend_detail = _parse_jsonb(r['blend_detail'])
            daily_input = sum(v for v in blend_detail.values()
                              if isinstance(v, (int, float)) and v > 0)
            plan_date_raw = r['plan_date']
            if hasattr(plan_date_raw, 'isoformat'):
                plan_date = plan_date_raw if isinstance(plan_date_raw, date) and not isinstance(plan_date_raw, datetime) else plan_date_raw.date()
            else:
                ps = str(plan_date_raw or '').split(' ')[0]
                try:
                    plan_date = datetime.strptime(ps, '%Y-%m-%d').date()
                except Exception:
                    plan_date = date.today()
            detail_id = str(r['id'] or '')
            if not detail_id:
                detail_id = f"CPSAT-{r['plan_id']}-{r['day_of_month'] or plan_date.day}-{r['hours'] or 24}"
            data.append(ProductionPlanDetail(
                id=detail_id,
                plan_id=str(r['plan_id'] or ''),
                plan_date=plan_date,
                day_of_month=int(r['day_of_month'] or plan_date.day),
                daily_input=daily_input,
                blend_detail=blend_detail,
                crude_stock_status=crude_stock_status,
                device_load_rate=_f(r['device_load_rate']),
                hours=_f(r['hours'], 24.0),
            ))
        return data

    def save_cp_sat_plan_details(self, details: List[ProductionPlanDetail], merge: bool = False):
        """保存 CP-SAT 排产明细到独立表 cp_sat_plan_details。

        默认 merge=False（替换语义：先删该 plan_id 全部明细再插入），
        因为 CP-SAT 每次求解应整体覆盖该月的 CP-SAT 结果。
        与 production_plan_details 完全隔离，不影响客户实际排产计划。
        """
        with self._session() as db:
            if not merge and details:
                plan_ids = {d.plan_id for d in details}
                db.execute(text("DELETE FROM cp_sat_plan_details WHERE plan_id = ANY(:ids)"),
                           {'ids': list(plan_ids)})
            for d in details:
                blend = d.blend_detail if isinstance(d.blend_detail, str) else json.dumps(d.blend_detail, ensure_ascii=False)
                stock = d.crude_stock_status if isinstance(d.crude_stock_status, str) else json.dumps(d.crude_stock_status, ensure_ascii=False)
                plan_date_str = d.plan_date.isoformat() if hasattr(d.plan_date, 'isoformat') else str(d.plan_date)
                db.execute(text(
                    "INSERT INTO cp_sat_plan_details "
                    "(id, plan_id, plan_date, day_of_month, daily_input, blend_detail, "
                    "crude_stock_status, device_load_rate, hours) "
                    "VALUES (:id, :plan_id, :plan_date, :day_of_month, :daily_input, "
                    "CAST(:blend_detail AS jsonb), CAST(:crude_stock_status AS jsonb), "
                    ":device_load_rate, :hours) "
                    "ON CONFLICT (id) DO UPDATE SET "
                    "plan_id=EXCLUDED.plan_id, plan_date=EXCLUDED.plan_date, "
                    "day_of_month=EXCLUDED.day_of_month, daily_input=EXCLUDED.daily_input, "
                    "blend_detail=EXCLUDED.blend_detail, "
                    "crude_stock_status=EXCLUDED.crude_stock_status, "
                    "device_load_rate=EXCLUDED.device_load_rate, hours=EXCLUDED.hours"
                ), {
                    'id': d.id,
                    'plan_id': d.plan_id,
                    'plan_date': plan_date_str,
                    'day_of_month': d.day_of_month,
                    'daily_input': d.daily_input,
                    'blend_detail': blend,
                    'crude_stock_status': stock,
                    'device_load_rate': d.device_load_rate,
                    'hours': getattr(d, 'hours', 24.0),
                })
            self._commit_if_owned(db)
        logger.info(f"保存 cp_sat_plan_details: {len(details)} 条 (plan_id={list(plan_ids) if not merge else 'merge'})")

    # ── device_capacity（devices 表 cjy_01 行的投影，不建独立表）──

    def load_device_capacity(self) -> DeviceCapacity:
        """读取常减压装置产能约束（投影自 devices 表）。

        只投影起点装置（type='start'）一行——LP 排产只排常减压的每日进料，柴加/蜡加
        的进料由物料流推算、其容量不在 LP 层约束，而在评估层 direct_calculator
        用 Device.effective_capacity 校验。故此处无需读其它装置。

        字段映射（历史沿用）：daily_max_input ← safety_stock_thrd，
        daily_min_input ← low_safety_thrd。注意不是 DB 的 max_capacity 列。
        """
        with self._session() as db:
            # 动态查找起点装置（type='start'），调用 data_service
            from data_service.repositories import device_repo
            units = device_repo.load_units(db)
            start_unit = next((u for u in units if u['type'] == 'start'), None)
            row = start_unit or (units[0] if units else None)
        if row is None:
            return DeviceCapacity(device_id='default', device_name='默认装置',
                                  daily_max_input=200000.0, daily_min_input=0.0)
        return DeviceCapacity(
            device_id=row['device_id'],
            device_name=row['name'] or '装置',
            daily_max_input=_f(row['safety_stock_thrd'], 200000),
            daily_min_input=_f(row['low_safety_thrd'], 0),
        )

    # save_device_capacity 为死代码（无调用方），DB 版不实现。

    # ── 综合加载 ──

    def load_all_data(self) -> Dict:
        return {
            'production_plans_input': self.load_production_plans_input(),
            'device_capacity': self.load_device_capacity()
        }

    def load_history_plans(self) -> List[Dict]:
        """历史计划列表（按 plan_id 去重，取首行 plan_date 作为 create_time）。"""
        with self._session() as db:
            rows = db.execute(text(
                "SELECT plan_id, MIN(plan_date) AS plan_date "
                "FROM production_plan_details GROUP BY plan_id ORDER BY plan_id"
            )).mappings().all()
        history = []
        for r in rows:
            plan_id = str(r['plan_id'])
            history.append({
                'plan_id': plan_id,
                # 原版 plan_month = plan_id.split('_')[0]（兼容 PLAN-202601 形式，无下划线则原样）
                'plan_month': plan_id.split('_')[0] if '_' in plan_id else plan_id,
                'create_time': _iso(r['plan_date']),
            })
        return history

    # ── scheduling_tasks ──

    def load_scheduling_tasks(self) -> List[SchedulingTask]:
        with self._session() as db:
            rows = db.execute(text(
                "SELECT plan_id, planned_month, status, locked, "
                "created_at, updated_at, generated_at FROM scheduling_tasks ORDER BY plan_id"
            )).mappings().all()
        return [SchedulingTask(
            plan_id=str(r['plan_id']),
            planned_month=str(r['planned_month'] or ''),
            status=str(r['status'] or 'draft'),
            locked=bool(r['locked']),
            created_at=_iso(r['created_at']),
            updated_at=_iso(r['updated_at']),
            generated_at=_iso(r['generated_at']),
        ) for r in rows]

    def save_scheduling_task(self, task: SchedulingTask) -> bool:
        """单行 upsert（原 RMW 全表回写改为 INSERT ON CONFLICT）。"""
        try:
            with self._session() as db:
                db.execute(text(
                    "INSERT INTO scheduling_tasks "
                    "(plan_id, planned_month, status, locked, created_at, updated_at, generated_at) "
                    "VALUES (:plan_id, :planned_month, :status, :locked, "
                    "CAST(:created_at AS timestamptz), CAST(:updated_at AS timestamptz), "
                    "CAST(:generated_at AS timestamptz)) "
                    "ON CONFLICT (plan_id) DO UPDATE SET "
                    "planned_month=EXCLUDED.planned_month, status=EXCLUDED.status, "
                    "locked=EXCLUDED.locked, updated_at=EXCLUDED.updated_at, "
                    "generated_at=EXCLUDED.generated_at"
                ), {
                    'plan_id': task.plan_id,
                    'planned_month': task.planned_month,
                    'status': task.status,
                    'locked': task.locked,
                    'created_at': task.created_at or None,
                    'updated_at': task.updated_at or None,
                    'generated_at': task.generated_at or None,
                })
                self._commit_if_owned(db)
            return True
        except Exception as e:
            logger.error(f"保存排厂任务失败: {e}")
            return False

    def update_task_status(self, plan_id: str, status: str = None, locked: bool = None) -> bool:
        """单行更新（原 load 全表→改→save 全表 改为 UPDATE）。"""
        sets = ["updated_at = NOW()"]
        params: dict = {'plan_id': plan_id}
        if status is not None:
            sets.append("status = :status")
            params['status'] = status
        if locked is not None:
            sets.append("locked = :locked")
            params['locked'] = locked
        try:
            with self._session() as db:
                result = db.execute(text(
                    f"UPDATE scheduling_tasks SET {', '.join(sets)} WHERE plan_id = :plan_id"
                ), params)
                self._commit_if_owned(db)
                return result.rowcount > 0
        except Exception as e:
            logger.error(f"更新排厂任务状态失败: {e}")
            return False

    def get_task_by_month(self, planned_month: str) -> Optional[SchedulingTask]:
        with self._session() as db:
            r = db.execute(text(
                "SELECT plan_id, planned_month, status, locked, created_at, updated_at, generated_at "
                "FROM scheduling_tasks WHERE planned_month = :m LIMIT 1"
            ), {'m': planned_month}).mappings().first()
        if r is None:
            return None
        return SchedulingTask(
            plan_id=str(r['plan_id']),
            planned_month=str(r['planned_month'] or ''),
            status=str(r['status'] or 'draft'),
            locked=bool(r['locked']),
            created_at=_iso(r['created_at']),
            updated_at=_iso(r['updated_at']),
            generated_at=_iso(r['generated_at']),
        )

    def get_task_by_id(self, plan_id: str) -> Optional[SchedulingTask]:
        with self._session() as db:
            r = db.execute(text(
                "SELECT plan_id, planned_month, status, locked, created_at, updated_at, generated_at "
                "FROM scheduling_tasks WHERE plan_id = :pid LIMIT 1"
            ), {'pid': plan_id}).mappings().first()
        if r is None:
            return None
        return SchedulingTask(
            plan_id=str(r['plan_id']),
            planned_month=str(r['planned_month'] or ''),
            status=str(r['status'] or 'draft'),
            locked=bool(r['locked']),
            created_at=_iso(r['created_at']),
            updated_at=_iso(r['updated_at']),
            generated_at=_iso(r['generated_at']),
        )

    def delete_scheduling_task(self, plan_id: str) -> bool:
        """单行删除（原全表过滤回写改为 DELETE）。"""
        try:
            with self._session() as db:
                db.execute(text("DELETE FROM scheduling_tasks WHERE plan_id = :pid"),
                           {'pid': plan_id})
                self._commit_if_owned(db)
            return True
        except Exception as e:
            logger.error(f"删除排厂任务失败: {e}")
            return False

    def delete_production_plan_details(self, plan_id: str) -> bool:
        """删除指定计划的全部明细（单行 DELETE WHERE plan_id）。

        原 Excel 版按 plan_id 过滤回写整张 sheet；DB 版直接 DELETE。
        供任务删除/计划重置场景调用。
        """
        try:
            with self._session() as db:
                db.execute(text("DELETE FROM production_plan_details WHERE plan_id = :pid"),
                           {'pid': plan_id})
                self._commit_if_owned(db)
            return True
        except Exception as e:
            logger.error(f"删除计划明细失败: {e}")
            return False
