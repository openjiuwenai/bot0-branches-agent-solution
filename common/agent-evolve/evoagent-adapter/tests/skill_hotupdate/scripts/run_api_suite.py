"""Skill hot-update API integration suite (TC-01 ~ TC-12).

Requires a running EvoAgentAdapter instance and a reachable business agent
configured with agent_url (e.g. EDPAgent) sharing the same skills directory.

Usage:
    python run_api_suite.py
    python run_api_suite.py --base-url http://host:8900 --agent-name edp_agent
    ADAPTER_URL=http://host:8900 python run_api_suite.py
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# Allow running as ``python run_api_suite.py`` from this directory.
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from config import HotUpdateConfig, parse_hotupdate_args
else:
    from .config import HotUpdateConfig, parse_hotupdate_args


@dataclass
class Result:
    case_id: str
    name: str
    passed: bool
    detail: str = ""


def api(
    cfg: HotUpdateConfig,
    method: str,
    path: str,
    body: dict | None = None,
    timeout: int = 120,
) -> tuple[int, Any]:
    url = f"{cfg.base_url_normalized}{path}"
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"} if data else {},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"raw": raw}
        return e.code, payload


def skill_action(cfg: HotUpdateConfig, action: str, **extra: Any) -> tuple[int, Any]:
    return api(
        cfg,
        "POST",
        "/api/v1/skills",
        {"agent_name": cfg.agent_name, "action": action, **extra},
    )


def run_all(cfg: HotUpdateConfig) -> list[Result]:
    results: list[Result] = []
    skill = cfg.skill_name
    agent = cfg.agent_name

    code, data = skill_action(cfg, "skill_list")
    names = [s.get("name") for s in data.get("skills", [])]
    ok = code == 200 and skill in names
    results.append(Result("TC-01", "skill_list 返回目标 skill", ok, f"skills={names}"))

    code, data = skill_action(cfg, "skill_content", skill_name=skill)
    content = data.get("content", "")
    ok = code == 200 and len(content) > 100 and content.lstrip().startswith("---")
    results.append(Result("TC-02", "skill_content 返回完整 Markdown", ok, f"len={len(content)}"))

    marker = f"<!-- HOTUPDATE_TC03_{int(time.time())} -->"
    patched = content.replace("\n\n", f"\n\n{marker}\n\n", 1) if content else content + marker
    code, upd = skill_action(cfg, "update_skill", skill_name=skill, skill_content=patched)
    code2, rb = skill_action(cfg, "skill_content", skill_name=skill)
    ok = code == 200 and upd.get("success") is True and marker in rb.get("content", "")
    results.append(Result("TC-03", "update_skill 写盘且 skill_content 可读回", ok, f"success={upd.get('success')}"))

    conv = f"tc04-{int(time.time())}"
    code, call = api(
        cfg,
        "POST",
        f"/api/v1/agents/{agent}/conversations/{conv}",
        {"query": "推荐一款低风险理财产品"},
        timeout=300,
    )
    ok = code == 200 and call.get("success") is True
    results.append(Result("TC-04", "热更后新会话对话成功", ok, f"success={call.get('success')}"))

    code, traces = api(cfg, "GET", f"/api/v1/agents/{agent}/traces/{conv}", timeout=60)
    skill_recs = [c for c in traces.get("calls", []) if c.get("type") == "SKILL"]
    ok = code == 200 and len(skill_recs) >= 1
    results.append(Result("TC-05", "traces 含 SKILL 执行记录", ok, f"count={len(skill_recs)}"))

    code, restored = skill_action(cfg, "restore_skill", skill_names=[skill])
    item = (restored.get("restored") or [{}])[0]
    code2, after = skill_action(cfg, "skill_content", skill_name=skill)
    ok = code == 200 and item.get("success") is True and after.get("content") == content
    results.append(
        Result(
            "TC-06",
            "restore_skill 恢复到快照前内容",
            ok,
            f"len_before={len(content)} len_after={len(after.get('content', ''))}",
        )
    )

    code, restored2 = skill_action(cfg, "restore_skill", skill_names=[skill])
    item2 = (restored2.get("restored") or [{}])[0]
    ok = code == 200 and item2.get("success") is True
    results.append(Result("TC-07", "restore_skill 幂等（快照保留）", ok, str(item2)))

    code, bad = skill_action(cfg, "update_skill", skill_name="nonexistent_skill_xyz", skill_content="# x")
    ok = code >= 400 or bad.get("success") is False
    results.append(Result("TC-08", "更新不存在 skill 返回错误", ok, f"code={code} body={bad}"))

    code, bad = api(cfg, "POST", "/api/v1/skills", {"agent_name": "no_such_agent", "action": "skill_list"})
    ok = code == 404
    results.append(Result("TC-09", "未知 agent_name 返回 404", ok, f"code={code}"))

    code, bad = api(cfg, "POST", "/api/v1/skills", {"agent_name": agent, "action": "invalid_action"})
    ok = code == 400
    results.append(Result("TC-10", "非法 action 返回 400", ok, f"code={code}"))

    code, health = api(cfg, "GET", "/health", timeout=30)
    ok = code == 200
    results.append(Result("TC-11", "Adapter health 检查", ok, str(health)[:120]))

    conv_reuse = f"tc12-{int(time.time())}"
    skill_action(cfg, "update_skill", skill_name=skill, skill_content=content + "\n\n<!-- tc12 -->\n")
    api(
        cfg,
        "POST",
        f"/api/v1/agents/{agent}/conversations/{conv_reuse}",
        {"query": "推荐理财产品"},
        timeout=300,
    )
    code, t1 = api(cfg, "GET", f"/api/v1/agents/{agent}/traces/{conv_reuse}", timeout=60)
    skill_action(cfg, "restore_skill", skill_names=[skill])
    ok = code == 200 and len(t1.get("calls", [])) > 0
    results.append(Result("TC-12", "热更后单次对话 traces 可采集", ok, f"calls={len(t1.get('calls', []))}"))

    return results


def main() -> None:
    cfg = parse_hotupdate_args("Skill hot-update API integration suite (TC-01~TC-12)")
    results = run_all(cfg)
    passed = sum(1 for r in results if r.passed)
    failed = [r for r in results if not r.passed]
    print("=" * 70)
    print(f"Skill 热更新 API 测试套件 | {cfg.base_url_normalized} | agent={cfg.agent_name}")
    print("=" * 70)
    for r in results:
        status = "PASS" if r.passed else "FAIL"
        print(f"  [{status}] {r.case_id} {r.name}")
        if r.detail:
            print(f"         {r.detail}")
    print("=" * 70)
    print(f"合计: {passed}/{len(results)} 通过")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
