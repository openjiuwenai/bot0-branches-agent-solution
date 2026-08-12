#!/usr/bin/env python3
"""E2E test: adapter -> SkillHub publish/list/get/pull/delete."""
import json
import sys
import urllib.request

BASE = "http://localhost:8900/api/v1/skills"
AGENT = "edp_agent"
SKILL = "demo-skill"
VERSION = "1.0.1"


def call(action: str, **kwargs) -> dict:
    body = {"agent_name": AGENT, "action": action, **kwargs}
    req = urllib.request.Request(
        BASE,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())


def main() -> int:
    print("=== 1. publish_skill ===")
    pub = call(
        "publish_skill",
        skill_name=SKILL,
        plugin_version=VERSION,
        version_desc="adapter e2e test",
    )
    print(json.dumps(pub, indent=2, ensure_ascii=False))
    asset_id = pub["asset_id"]
    assert pub["skill_name"] == SKILL
    assert pub["version"] == VERSION

    print("\n=== 2. list_hub_skills ===")
    lst = call("list_hub_skills", page=1, page_size=20, keyword=SKILL)
    print(json.dumps(lst, indent=2, ensure_ascii=False))
    assert lst.get("total", 0) >= 1

    print("\n=== 3. get_hub_version ===")
    ver = call("get_hub_version", asset_id=asset_id, version=VERSION)
    print(json.dumps(ver, indent=2, ensure_ascii=False))
    assert ver.get("asset_id") == asset_id

    print("\n=== 4. pull_skill ===")
    pull = call("pull_skill", asset_id=asset_id, version=VERSION, overwrite=True)
    print(json.dumps(pull, indent=2, ensure_ascii=False))
    assert pull["skill_name"] == SKILL

    print("\n=== 5. delete_hub_version ===")
    deleted = call("delete_hub_version", asset_id=asset_id, version=VERSION)
    print(json.dumps(deleted, indent=2, ensure_ascii=False))
    assert deleted.get("deleted") is True

    print("\n[OK] All e2e tests passed!")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print(f"\n[FAIL] E2E failed: {e}", file=sys.stderr)
        sys.exit(1)
