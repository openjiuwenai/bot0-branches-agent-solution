#!/usr/bin/env python3
"""E2E test: adapter -> SkillHub publish/list/get/pull/delete."""
import json
import logging
import sys
import urllib.request

BASE = "http://localhost:8900/api/v1/skills"
AGENT = "edp_agent"
SKILL = "demo-skill"
VERSION = "1.0.1"

logger = logging.getLogger(__name__)


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
    logger.info("=== 1. publish_skill ===")
    pub = call(
        "publish_skill",
        skill_name=SKILL,
        plugin_version=VERSION,
        version_desc="adapter e2e test",
    )
    logger.info("%s", json.dumps(pub, indent=2, ensure_ascii=False))
    asset_id = pub["asset_id"]
    if pub["skill_name"] != SKILL or pub["version"] != VERSION:
        logger.error("publish_skill returned unexpected skill_name/version")
        return 1

    logger.info("=== 2. list_hub_skills ===")
    lst = call("list_hub_skills", page=1, page_size=20, keyword=SKILL)
    logger.info("%s", json.dumps(lst, indent=2, ensure_ascii=False))
    if lst.get("total", 0) < 1:
        logger.error("list_hub_skills returned empty result")
        return 1

    logger.info("=== 3. get_hub_version ===")
    ver = call("get_hub_version", asset_id=asset_id, version=VERSION)
    logger.info("%s", json.dumps(ver, indent=2, ensure_ascii=False))
    if ver.get("asset_id") != asset_id:
        logger.error("get_hub_version asset_id mismatch")
        return 1

    logger.info("=== 4. pull_skill ===")
    pull = call("pull_skill", asset_id=asset_id, version=VERSION, overwrite=True)
    logger.info("%s", json.dumps(pull, indent=2, ensure_ascii=False))
    if pull["skill_name"] != SKILL:
        logger.error("pull_skill returned unexpected skill_name")
        return 1

    logger.info("=== 5. delete_hub_version ===")
    deleted = call("delete_hub_version", asset_id=asset_id, version=VERSION)
    logger.info("%s", json.dumps(deleted, indent=2, ensure_ascii=False))
    if deleted.get("deleted") is not True:
        logger.error("delete_hub_version did not confirm deletion")
        return 1

    logger.info("[OK] All e2e tests passed!")
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    try:
        sys.exit(main())
    except Exception as exc:
        logger.exception("[FAIL] E2E failed: %s", exc)
        sys.exit(1)
