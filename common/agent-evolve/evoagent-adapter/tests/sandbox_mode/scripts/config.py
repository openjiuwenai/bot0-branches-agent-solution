"""Shared configuration for sandbox-mode test scripts.

Resolution order: CLI arguments > environment variables > defaults.

Environment variables:
    ADAPTER_URL          Adapter base URL (default: http://127.0.0.1:18900)
    EDP_URL              EDPAgent health base (default: http://127.0.0.1:18001)
    JIUWENBOX_URL        jiuwenbox management API (default: http://127.0.0.1:8321)
    ADAPTER_AGENT_NAME   Target agent_name (default: edp_agent)
    ADAPTER_SKILL_NAME   Skill under remote_skills_dir (default: product_recommend_skill)
    REMOTE_SKILLS_DIR    Path inside sandbox (default: /tmp/skills)
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass


@dataclass(frozen=True)
class SandboxModeConfig:
    adapter_url: str
    edp_url: str
    jiuwenbox_url: str
    agent_name: str
    skill_name: str
    remote_skills_dir: str

    @property
    def adapter_url_normalized(self) -> str:
        return self.adapter_url.rstrip("/")

    @property
    def edp_url_normalized(self) -> str:
        return self.edp_url.rstrip("/")

    @property
    def jiuwenbox_url_normalized(self) -> str:
        return self.jiuwenbox_url.rstrip("/")

    @property
    def remote_skill_md(self) -> str:
        root = self.remote_skills_dir.rstrip("/") or "/tmp/skills"
        return f"{root}/{self.skill_name}/SKILL.md"


def _env(key: str, default: str) -> str:
    return os.environ.get(key, default)


def add_sandbox_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--adapter-url",
        default=_env("ADAPTER_URL", "http://127.0.0.1:18900"),
        help="Adapter base URL (env: ADAPTER_URL)",
    )
    parser.add_argument(
        "--edp-url",
        default=_env("EDP_URL", "http://127.0.0.1:18001"),
        help="EDPAgent health/base URL (env: EDP_URL)",
    )
    parser.add_argument(
        "--jiuwenbox-url",
        default=_env("JIUWENBOX_URL", "http://127.0.0.1:8321"),
        help="jiuwenbox management API (env: JIUWENBOX_URL)",
    )
    parser.add_argument(
        "--agent-name",
        default=_env("ADAPTER_AGENT_NAME", "edp_agent"),
        help="agent_name (env: ADAPTER_AGENT_NAME)",
    )
    parser.add_argument(
        "--skill-name",
        default=_env("ADAPTER_SKILL_NAME", "product_recommend_skill"),
        help="Skill name (env: ADAPTER_SKILL_NAME)",
    )
    parser.add_argument(
        "--remote-skills-dir",
        default=_env("REMOTE_SKILLS_DIR", "/tmp/skills"),
        help="Skills root inside sandbox (env: REMOTE_SKILLS_DIR)",
    )


def config_from_args(args: argparse.Namespace) -> SandboxModeConfig:
    return SandboxModeConfig(
        adapter_url=args.adapter_url,
        edp_url=args.edp_url,
        jiuwenbox_url=args.jiuwenbox_url,
        agent_name=args.agent_name,
        skill_name=args.skill_name,
        remote_skills_dir=args.remote_skills_dir,
    )


def parse_sandbox_args(description: str) -> SandboxModeConfig:
    parser = argparse.ArgumentParser(description=description)
    add_sandbox_args(parser)
    return config_from_args(parser.parse_args())
