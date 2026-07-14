"""Shared configuration for Skill hot-update manual test scripts.

Values are resolved in order: CLI arguments > environment variables > defaults.

Environment variables:
    ADAPTER_URL          Base URL of EvoAgentAdapter (default: http://127.0.0.1:8900)
    ADAPTER_AGENT_NAME   Target agent_name in API body/path (default: edp_agent)
    ADAPTER_SKILL_NAME   Skill to read/update/restore (default: product_recommend_skill)
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class HotUpdateConfig:
    base_url: str
    agent_name: str
    skill_name: str

    @property
    def base_url_normalized(self) -> str:
        return self.base_url.rstrip("/")


def _default_base_url() -> str:
    return os.environ.get("ADAPTER_URL", "http://127.0.0.1:8900")


def _default_agent_name() -> str:
    return os.environ.get("ADAPTER_AGENT_NAME", "edp_agent")


def _default_skill_name() -> str:
    return os.environ.get("ADAPTER_SKILL_NAME", "product_recommend_skill")


def add_hotupdate_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--base-url",
        default=_default_base_url(),
        help="Adapter base URL (env: ADAPTER_URL)",
    )
    parser.add_argument(
        "--agent-name",
        default=_default_agent_name(),
        help="agent_name for Skill API and conversation paths (env: ADAPTER_AGENT_NAME)",
    )
    parser.add_argument(
        "--skill-name",
        default=_default_skill_name(),
        help="Skill directory name under agent skills_dir (env: ADAPTER_SKILL_NAME)",
    )


def config_from_args(args: argparse.Namespace) -> HotUpdateConfig:
    return HotUpdateConfig(
        base_url=args.base_url,
        agent_name=args.agent_name,
        skill_name=args.skill_name,
    )


def parse_hotupdate_args(description: str) -> HotUpdateConfig:
    parser = argparse.ArgumentParser(description=description)
    add_hotupdate_args(parser)
    return config_from_args(parser.parse_args())
