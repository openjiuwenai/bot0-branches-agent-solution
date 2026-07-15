"""Skill 加载 — 读取目标 skill 的 SKILL.md 内容。"""

from __future__ import annotations

from pathlib import Path


class SkillLoader:
    """加载目标 skill 的 SKILL.md 内容。"""

    @staticmethod
    def load(skill_path: Path) -> str:
        """读取 skill 目录下的 SKILL.md，返回完整 markdown 内容。"""
        skill_md = skill_path / "SKILL.md"
        if not skill_md.exists():
            raise FileNotFoundError(f"SKILL.md not found in {skill_path}")
        return skill_md.read_text(encoding="utf-8")
