# -*- coding: utf-8 -*-
"""FastAPI server for crude_run_planner — exposes pipeline results as JSON."""
import os, sys, math, traceback, time, threading
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))

from fastapi import FastAPI, Body
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel

import crude_run_planner as crp

app = FastAPI(title="原油加工计划预排产 API")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)

SRC = os.path.join(os.path.dirname(__file__), "原油加工月计划模板.xlsx")
_selected_src = SRC  # 用户选择的模板文件（默认）

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def _safe_float(v, default=0.0):
    try:
        return float(v) if v is not None else default
    except (TypeError, ValueError):
        return default

def _seq_to_json(seq):
    out = []
    for b in seq:
        u = b["u"]
        comps = [{"crude": c["crude"], "load_hr": c.get("load_hr", 0), "is_main": c.get("is_main", True)}
                 for c in u["comps"]]
        out.append({
            "start_h": b["start_h"],
            "dur_h": b["dur_h"],
            "end_h": b["start_h"] + b["dur_h"],
            "si": b.get("si", 0),
            "is_blend": u.get("is_blend", False),
            "uid": u["uid"],
            "comps": comps,
            "rate_hr": u.get("rate_hr", 0),
            "tons": b["dur_h"] * u.get("rate_hr", 0),
            "comp_str": "+".join(f"{c['crude']}({c.get('load_hr',0)})" for c in u["comps"]),
            "total_load": sum(c.get("load_hr", 0) for c in u["comps"]),
        })
    return out

def _grid_to_json(grid, tanks, days):
    cells = {}
    for t in tanks:
        cells[t] = {}
        for d in range(1, days + 1):
            c = grid.get(t, {}).get(d, {})
            cells[t][str(d)] = {
                "inv": _safe_float(c.get("inv")),
                "crude": c.get("crude"),
                "load": c.get("load"),
                "time": _safe_float(c.get("time")) if c.get("time") is not None else None,
                "proc": _safe_float(c.get("proc")) if c.get("proc") is not None else None,
                "recv": _safe_float(c.get("recv")) if c.get("recv") is not None else None,
                "unload_recv": _safe_float(c.get("unload_recv")) if c.get("unload_recv") is not None else None,
                "transfer_out": _safe_float(c.get("transfer_out")) if c.get("transfer_out") is not None else None,
                "no_feed_h": _safe_float(c.get("no_feed_h")) if c.get("no_feed_h") is not None else None,
            }
    return cells

def _daily_cdu_summary(seq, grid, days, tanks):
    """每天 CDU 实际运行小时 + 实际加工总量 + 期望总量(按批次负荷) + 分油种缺口."""
    day_start_h = lambda d: (d - 1) * 24
    summary = []
    for d in range(1, days + 1):
        ds, de = day_start_h(d), day_start_h(d + 1)
        planned_h = 0
        planned_proc = 0
        # 分油种计划量
        planned_by_crude = defaultdict(float)
        for b in seq:
            ov = min(de, b["start_h"] + b["dur_h"]) - max(ds, b["start_h"])
            if ov > 0:
                planned_h += ov
                rate = b["u"].get("rate_hr", 0)
                planned_proc += ov * rate
                for comp in b["u"]["comps"]:
                    lh = comp.get("load_hr", 0)
                    if lh > 0:
                        planned_by_crude[comp["crude"]] += ov * lh
        actual_proc = 0
        actual_time = 0
        actual_by_crude = defaultdict(float)
        for t in tanks:
            c = grid.get(t, {}).get(d, {})
            p = _safe_float(c.get("proc"))
            if p > 0:
                actual_proc += p
                tm = _safe_float(c.get("time"))
                if tm > actual_time:
                    actual_time = tm
                crude = c.get("crude")
                if crude:
                    actual_by_crude[crude] += p
        # 分油种缺口
        gap_crudes = []
        for crude, planned in planned_by_crude.items():
            actual = actual_by_crude.get(crude, 0.0)
            gap = planned - actual
            if gap > 1:
                gap_crudes.append({"crude": crude, "gap": round(gap)})
        summary.append({
            "day": d,
            "planned_h": round(planned_h, 1),
            "planned_proc": round(planned_proc),
            "actual_proc": round(actual_proc),
            "actual_time": round(actual_time, 1),
            "gap_proc": round(planned_proc - actual_proc),
            "gap_h": round((planned_proc - actual_proc) / 715, 1) if planned_proc > actual_proc else 0,
            "gap_crudes": gap_crudes,
        })
    return summary

def _compliance_detail(P, seq, grid):
    from collections import defaultdict
    plan = defaultdict(float)
    for b in seq:
        for c in b["u"]["comps"]:
            plan[c["crude"]] += b["dur_h"] * c.get("load_hr", 0)
    real = defaultdict(float)
    for t, days in grid.items():
        for d, cell in days.items():
            if cell.get("proc") and cell.get("crude"):
                real[cell["crude"]] += cell["proc"]
    origin = P.get("ORIGIN", {})
    proc_total = P.get("PROC_TOTAL", 0)
    extra = P.get("EXTRA_DOMESTIC", set())
    details = []
    for c, req in P["PROC"].items():
        is_imp = origin.get(c) == "imp"
        kind = "进口·各自" if is_imp else ("非计划库存" if c in extra else "国内·计总量")
        ok = plan[c] >= req - 1 and real[c] >= req - 1
        if not is_imp and c not in extra and proc_total:
            ok = sum(plan.values()) >= proc_total - 1 and sum(real.values()) >= proc_total - 1
        details.append({
            "crude": c,
            "plan": round(plan[c]),
            "real": round(real[c]),
            "req": round(req),
            "ok": ok,
            "kind": kind,
            "origin": origin.get(c, "dom"),
        })
    n_judged = len(P["PROC"]) - len(extra)
    compliant = sum(1 for d in details if d["ok"] and d["kind"] != "非计划库存")
    return {"n_judged": n_judged, "compliant": compliant, "details": details,
            "proc_total": round(proc_total),
            "plan_total": round(sum(plan.values())),
            "real_total": round(sum(real.values()))}

# ---------------------------------------------------------------------------
# run result cache
# ---------------------------------------------------------------------------
_cache = {}

def _run_pipeline():
    if _cache:
        return _cache
    t0 = time.perf_counter()

    P = crp.read_all(_selected_src)

    # Phase 0
    P["_BATCH_WEIGHTS"], P["_BATCH_ORDER"] = crp.greedy_batch_priority(P)
    ship_assign, cp_constraints, no_proc_windows = crp.preprocess_arrivals(P)
    batch_est, batch_bd = crp.estimate_batches(P)
    P["_G_INIT_AVAIL"] = crp.g_init_availability(P)
    P["_ARRIVAL_CUM_SEGS"] = crp.arrival_cum_segments(P)

    n_preferred = P.get("N_PREFERRED", 3)   # 目标优选解数量：凑够即停止放宽阶梯（默认3）
    extra = P.get("EXTRA_DOMESTIC", set())
    n_judged = len(P["PROC"]) - len(extra)
    max_benders_iter = P.get("MAX_BENDERS_ITER", 3)

    all_tanks = P["GTANKS"] + P["TTANKS"]
    days = P["DAYS"]

    rounds_data = []
    tank_grids_data = []
    results = []

    # 自适应批次阶梯
    # 档1(下限): 达标即停快速试探（大概率不可行）
    # 档2-4(下限+1~+3): 充分搜索，不同种子产生多样解
    MAX_OFF = 3
    LADDER_TIMES = [30, 90, 240, 240]
    LADDER_SEEDS = [2, 2, 3, 3]
    LADDER_STOP = [True, True, True, True]
    hit_n = False; round_counter = 0

    for lvl in range(MAX_OFF + 1):
        cap = batch_est + lvl
        tl = LADDER_TIMES[min(lvl, len(LADDER_TIMES) - 1)]
        ns = LADDER_SEEDS[min(lvl, len(LADDER_SEEDS) - 1)]
        stop_comp = LADDER_STOP[min(lvl, len(LADDER_STOP) - 1)]
        lvl_seeds = crp.make_seeds(ns + 2)
        print(f"\n[阶梯] 档{lvl+1}: cap<={cap} 时限{tl}s x {ns}种子 stop={stop_comp}")

        lvl_feasible = 0; lvl_preferred = 0; si = 0
        for si in range(ns):
            seed = lvl_seeds[si]
            print(f"\n=== Round {round_counter+1} Benders (cap<={cap}, seed={seed}) ===")
            try:
                seq, nb, obj, grid, warn, clog, sc, n_iter = crp.benders_solve(
                    P, ship_assignments=ship_assign, cp_constraints=cp_constraints,
                    no_process_windows=no_proc_windows, batch_cap=cap,
                    max_iter=max_benders_iter, time_limit=tl,
                    warm_start=(round_counter == 0), random_seed=seed,
                    stop_on_compliant=stop_comp)
            except Exception as e:
                print(f"  Failed: {e}"); round_counter += 1; continue
            round_counter += 1
            if not seq: continue
            lvl_feasible += 1
            sc.update({"seed": seed, "round": round_counter, "nb": nb, "obj": obj})
            feasible, gaps, _, _, _ = crp.check_feasibility(P, seq, ship_assign)
            total_gap = sum(sum(d.values()) for d in gaps.values())
            sc["total_gap"] = total_gap; sc["benders_iters"] = n_iter
            sc["benders_converged"] = feasible
            sc["is_preferred"] = (sc["compliant_crudes"] == n_judged and total_gap <= 1)
            results.append((sc, seq, grid, warn, clog))
            rounds_data.append({
                "round": sc["round"], "seed": sc["seed"], "n_batches": sc["nb"],
                "objective": sc["obj"], "status": "converged" if feasible else "gap_remain",
                "score": sc, "seq": _seq_to_json(seq), "warm_start": (round_counter == 1),
                "total_gap": total_gap, "benders_iters": n_iter, "benders_converged": feasible,
            })
            tank_grids_data.append({
                "round": sc["round"], "grid": _grid_to_json(grid, all_tanks, days),
                "warnings": warn, "clog": [x for x in clog if isinstance(x, str)],
                "unload_to_t_count": sc.get("unload_to_t_count", 0),
                "unload_commingle_count": sc.get("unload_commingle_count", 0),
                "daily_summary": _daily_cdu_summary(seq, grid, days, all_tanks),
            })
            print(f"  Result: nb={nb} compliant={sc['compliant_crudes']}/{n_judged} gap={total_gap:.0f}t")
            if sc["is_preferred"]:
                lvl_preferred += 1
                if lvl_preferred >= n_preferred:
                    break

        # 自适应：有优选解但不够 → 追加2种子
        if 0 < lvl_preferred < n_preferred and si == ns - 1:
            print(f"  档{lvl+1} 有{lvl_preferred}个优选解(目标{n_preferred})，追加2种子...")
            for si2 in range(ns, min(ns + 2, len(lvl_seeds))):
                seed = lvl_seeds[si2]
                print(f"\n=== Round {round_counter+1} Benders (cap<={cap}, seed={seed}, 追加) ===")
                try:
                    seq, nb, obj, grid, warn, clog, sc, n_iter = crp.benders_solve(
                        P, ship_assignments=ship_assign, cp_constraints=cp_constraints,
                        no_process_windows=no_proc_windows, batch_cap=cap,
                        max_iter=max_benders_iter, time_limit=tl,
                        warm_start=False, random_seed=seed,
                        stop_on_compliant=stop_comp)
                except Exception as e:
                    print(f"  Failed: {e}"); round_counter += 1; continue
                round_counter += 1
                if not seq: continue
                lvl_feasible += 1
                sc.update({"seed": seed, "round": round_counter, "nb": nb, "obj": obj})
                feasible, gaps, _, _, _ = crp.check_feasibility(P, seq, ship_assign)
                total_gap = sum(sum(d.values()) for d in gaps.values())
                sc["total_gap"] = total_gap; sc["benders_iters"] = n_iter
                sc["benders_converged"] = feasible
                sc["is_preferred"] = (sc["compliant_crudes"] == n_judged and total_gap <= 1)
                results.append((sc, seq, grid, warn, clog))
                rounds_data.append({
                    "round": sc["round"], "seed": sc["seed"], "n_batches": sc["nb"],
                    "objective": sc["obj"], "status": "converged" if feasible else "gap_remain",
                    "score": sc, "seq": _seq_to_json(seq), "warm_start": False,
                    "total_gap": total_gap, "benders_iters": n_iter, "benders_converged": feasible,
                })
                tank_grids_data.append({
                    "round": sc["round"], "grid": _grid_to_json(grid, all_tanks, days),
                    "warnings": warn, "clog": [x for x in clog if isinstance(x, str)],
                    "unload_to_t_count": sc.get("unload_to_t_count", 0),
                    "unload_commingle_count": sc.get("unload_commingle_count", 0),
                    "daily_summary": _daily_cdu_summary(seq, grid, days, all_tanks),
                })
                print(f"  Result: nb={nb} compliant={sc['compliant_crudes']}/{n_judged} gap={total_gap:.0f}t")
                if sc["is_preferred"]:
                    lvl_preferred += 1
                    if lvl_preferred >= n_preferred:
                        break

        if lvl_feasible == 0:
            print(f"  档{lvl+1} 全不可行")
        if lvl_preferred >= n_preferred:
            print(f"  档{lvl+1} 凑够 {n_preferred} 个优选解 → 停止放宽")
            hit_n = True
            break

    selected = crp.select_solutions(results, n_preferred)
    selected_idx = [i for i, r in enumerate(results) if r in selected]

    # 生成 xlsx 结果文件（文件名加时间戳避免与旧文件冲突）
    import datetime
    ts = datetime.datetime.now().strftime("%m%d%H%M")
    BASE = f"原油加工月计划_预排产结果_benders_{ts}"
    xlsx_paths = []
    xlsx_error = ""
    try:
        xlsx_paths = crp.write_solutions(_selected_src, BASE, selected, P)
        print(f"  xlsx generated: {xlsx_paths}")
    except Exception as e:
        xlsx_error = str(e)
        print(f"  xlsx ERROR: {e}")
        traceback.print_exc()

    comp = _compliance_detail(P, results[0][1] if results else [], results[0][2] if results else {})
    comp_by_round = [_compliance_detail(P, seq, grid) for sc, seq, grid, w, c in results]

    # parameters
    params = {
        "year": P["YEAR"], "month": P["MONTH"], "days": P["DAYS"],
        "proc": {k: round(v) for k, v in P["PROC"].items()},
        "proc_total": round(P.get("PROC_TOTAL", 0)),
        "gtanks": P["GTANKS"], "ttanks": P["TTANKS"],
        "tanks": {t: {"cap": P["CAP"].get(t, 0), "heel": P.get("HEEL", {}).get(t, 0),
                       "allow": P.get("ALLOW", {}).get(t, ""), "avail_cap": P.get("AVAIL_CAP", {}).get(t, 0),
                       "is_g": t in P["GTANKS"], "farness": P.get("FARNESS", {}).get(t, 1),
                       "crude": P.get("INIT", {}).get(t, {}).get("crude"),
                       "ton": P.get("INIT", {}).get(t, {}).get("ton", 0)}
                  for t in all_tanks},
        "main_tanks": sorted(P.get("MAIN_TANKS", set())),
        "blend_tanks": sorted(P.get("BLEND_TANKS", set())),
        "arrivals": [{"crude": a["crude"], "ton": round(a["ton"]), "berth_day": a["berth_day"]}
                     for a in P["ARRIVALS"]],
        "recipes": {rid: {"main": rc["main_crude"], "cap_hr": rc["total_cap_hr"],
                          "blends": [{"crude": b["crude"], "cands": b["cands"]} for b in rc["blends"]]}
                    for rid, rc in P.get("RECIPES", {}).items()},
        "rate_hr": P.get("RATE_HR", {}),
        "origin": P.get("ORIGIN", {}),
        "can_single": P.get("CAN_SINGLE", {}),
        "global_params": {
            "UNLOAD_TPH": P.get("UNLOAD_TPH", 1800),
            "TRANSFER_TPH": P.get("TRANSFER_TPH", 700),
            "GG_TPH": P.get("GG_TPH", 1000),
            "MIN_BATCH_H": P.get("MIN_BATCH_H", 48),
            "MAX_BATCH_H": P.get("MAX_BATCH_H", 156),
            "EDGE_MIN_BATCH_H": P.get("EDGE_MIN_BATCH_H", 24),
            "MAX_UNLOAD_TANKS": P.get("MAX_UNLOAD_TANKS", 2),
            "SWITCH_RESID_TON": P.get("SWITCH_RESID_TON", 1000),
            "CLEAR_MARGIN": P.get("CLEAR_MARGIN", 2000),
            "TIME_LIMIT": P.get("TIME_LIMIT", 240),
            "N_ROUNDS": P.get("N_ROUNDS", 8),
            "N_PREFERRED": P.get("N_PREFERRED", 3),
        },
    }

    phase0 = {
        "ship_assignments": {str(k): v for k, v in ship_assign.items()},
        "cp_constraints": [{"crude": c, "need_tons": round(n), "deadline_hour": t}
                           for c, n, t in cp_constraints],
        "no_process_windows": [{"crude": c, "start_h": s, "end_h": e}
                               for c, s, e in no_proc_windows],
    }

    batch_estimate = {
        "lower_bound": batch_est,
        "cap": cap,
        "breakdown": {c: {"V": round(v[0]), "C_charge": round(v[1]) if v[1] else None,
                          "n": v[2], "tag": v[3]}
                      for c, v in batch_bd.items()},
    }

    _cache.update({
        "parameters": params,
        "phase0": phase0,
        "batch_estimate": batch_estimate,
        "rounds": rounds_data,
        "tank_grids": tank_grids_data,
        "selected_idx": selected_idx,
        "compliance": comp,
        "compliance_by_round": comp_by_round,
        "elapsed": round(time.perf_counter() - t0, 1),
        "n_rounds_run": len(results),
        "xlsx_files": [os.path.basename(p) for p in xlsx_paths],
        "xlsx_error": xlsx_error,
    })
    return _cache

# ---------------------------------------------------------------------------
# endpoints
# ---------------------------------------------------------------------------
_run_status = {"status": "idle"}  # idle | running | done | error
_run_lock = threading.Lock()
_run_logs = []  # 运行日志行列表
_run_logs_lock = threading.Lock()

class _LogCapture:
    """捕获 print 输出到 _run_logs，同时保留原 stdout。"""
    def __init__(self):
        self._stdout = sys.stdout
    def write(self, text):
        self._stdout.write(text)
        if text.strip():
            with _run_logs_lock:
                _run_logs.append(text.rstrip("\n"))
                if len(_run_logs) > 2000:
                    _run_logs.pop(0)
    def flush(self):
        self._stdout.flush()

def _run_in_background():
    global _run_status
    old_stdout = sys.stdout
    sys.stdout = _LogCapture()
    try:
        _run_pipeline()
        with _run_lock:
            _run_status = {"status": "done"}
    except Exception as e:
        traceback.print_exc()
        with _run_lock:
            _run_status = {"status": "error", "error": str(e),
                           "traceback": traceback.format_exc()}
    finally:
        sys.stdout = old_stdout

@app.get("/api/data")
async def get_data():
    if _cache:
        return _cache
    return {"error": "no data yet, POST /api/run first"}

@app.get("/api/templates")
async def list_templates():
    """列出项目目录下'原油加工月计划模板'开头的 xlsx 文件。"""
    d = os.path.dirname(__file__)
    files = []
    for f in os.listdir(d):
        if f.startswith("原油加工月计划模板") and f.endswith(".xlsx") and not f.startswith("~$"):
            files.append({"name": f, "size": os.path.getsize(os.path.join(d, f))})
    files.sort(key=lambda x: x["name"])
    return {"templates": files, "selected": os.path.basename(_selected_src)}

@app.post("/api/run")
async def run(template: str = Body("", embed=True)):
    global _run_status, _selected_src
    with _run_lock:
        if _run_status.get("status") == "running":
            return {"status": "already_running"}
        _run_status = {"status": "running"}
    # 设置用户选择的模板
    if template:
        candidate = os.path.join(os.path.dirname(__file__), template)
        if os.path.exists(candidate):
            _selected_src = candidate
            print(f"  使用模板: {template}")
        else:
            print(f"  模板不存在: {template}，使用默认")
    _cache.clear()
    with _run_logs_lock:
        _run_logs.clear()
    t = threading.Thread(target=_run_in_background, daemon=True)
    t.start()
    return {"status": "running"}

@app.get("/api/status")
async def status():
    try:
        with _run_lock:
            s = dict(_run_status)
        with _run_logs_lock:
            logs = list(_run_logs)
        if s.get("status") == "done" and _cache:
            return {"status": "done", "data": _cache, "logs": logs}
        s["logs"] = logs
        return s
    except Exception as e:
        return {"status": "error", "error": str(e), "traceback": traceback.format_exc()}

@app.get("/api/health")
async def health():
    return {"status": "ok"}

@app.get("/api/download/{idx}")
async def download(idx: int):
    files = _cache.get("xlsx_files", [])
    if not files:
        return {"error": "尚无结果文件，请先运行排产"}
    if idx < 1 or idx > len(files):
        return {"error": f"结果文件序号 {idx} 越界（当前共 {len(files)} 个）"}
    fname = files[idx - 1]                       # 用缓存里的真实文件名（含 _benders_时间戳）
    fpath = os.path.join(os.path.dirname(__file__), fname)
    if not os.path.exists(fpath):
        return {"error": f"文件 {fname} 不存在，请重新运行排产"}
    return FileResponse(fpath, filename=fname,
                        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
