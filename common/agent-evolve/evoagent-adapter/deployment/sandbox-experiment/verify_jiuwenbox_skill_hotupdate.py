#!/usr/bin/env python3
"""Minimal verification: Adapter jiuwenbox skill backend against a live jiuwenbox.

Creates a temporary sandbox, seeds /tmp/skills/demo_skill/SKILL.md, then
exercises list/read/update/restore via JiuwenBoxSkillStore (same path Adapter uses).

Usage:
  python deployment/sandbox-experiment/verify_jiuwenbox_skill_hotupdate.py \\
    --jiuwenbox-url http://127.0.0.1:8321
"""

from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

import httpx

# Allow running without install: add src to path
_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(_ROOT / "src"))

from agent_adapter.jiuwenbox_client import JiuwenBoxClient  # noqa: E402
from agent_adapter.jiuwenbox_skill_store import JiuwenBoxSkillStore  # noqa: E402


def _create_sandbox(base_url: str) -> str:
    with httpx.Client(timeout=60.0, trust_env=False) as client:
        resp = client.post(f"{base_url.rstrip('/')}/api/v1/sandboxes", json={})
        resp.raise_for_status()
        return str(resp.json()["id"])


def _delete_sandbox(base_url: str, sandbox_id: str) -> None:
    try:
        with httpx.Client(timeout=60.0, trust_env=False) as client:
            client.delete(f"{base_url.rstrip('/')}/api/v1/sandboxes/{sandbox_id}")
    except httpx.HTTPError:
        pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jiuwenbox-url", default="http://127.0.0.1:8321")
    parser.add_argument("--keep-sandbox", action="store_true")
    args = parser.parse_args()
    base = args.jiuwenbox_url.rstrip("/")

    with httpx.Client(timeout=5.0, trust_env=False) as client:
        health = client.get(f"{base}/health")
    if health.status_code != 200:
        print(f"FAIL: jiuwenbox health HTTP {health.status_code}")
        return 1
    print(f"OK health {base}")

    sandbox_id = _create_sandbox(base)
    print(f"OK created sandbox {sandbox_id}")

    client = JiuwenBoxClient(base)
    try:
        client.upload_file(
            sandbox_id,
            "/tmp/skills/demo_skill/SKILL.md",
            b"# Demo skill\noriginal\n",
        )
        print("OK seeded /tmp/skills/demo_skill/SKILL.md")

        with tempfile.TemporaryDirectory() as tmp:
            store = JiuwenBoxSkillStore(
                agent_names={"edp_agent"},
                client=client,
                remote_skills_dir="/tmp/skills",
                local_meta_root=Path(tmp),
                sandbox_id=sandbox_id,
                sandbox_id_resolve="fixed",
            )
            names = [s.name for s in store.list_skills("edp_agent")]
            assert names == ["demo_skill"], names
            print(f"OK skill_list {names}")

            doc = store.read_skill("edp_agent", "demo_skill")
            assert "original" in doc.content
            print("OK skill_content")

            store.update_skill("edp_agent", "demo_skill", "# Demo skill\nhotupdate marker\n")
            raw = client.download_file(sandbox_id, "/tmp/skills/demo_skill/SKILL.md")
            assert b"hotupdate marker" in raw, raw
            print("OK update_skill -> jiuwenbox download confirms")

            restored = store.restore_skills("edp_agent", ["demo_skill"])
            assert restored[0].success
            raw2 = client.download_file(sandbox_id, "/tmp/skills/demo_skill/SKILL.md")
            assert b"original" in raw2 and b"hotupdate marker" not in raw2
            print("OK restore_skill")

        print("PASS: jiuwenbox skill hot-update path verified")
        return 0
    finally:
        if not args.keep_sandbox:
            _delete_sandbox(base, sandbox_id)
            print(f"OK deleted sandbox {sandbox_id}")


if __name__ == "__main__":
    raise SystemExit(main())
