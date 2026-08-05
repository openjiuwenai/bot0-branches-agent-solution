"""skill 双源适配（记忆 (c)）—— 一个 ``SkillProvider`` protocol + 两适配器。

- ``LocalSkillProvider``：包 ``evo_agent.skill_loader.SkillLoader.load``（本地
  ``<skill_root>/<name>/SKILL.md``）。
- ``AdapterSkillProvider``：包 ``adapter_client`` 的 async ``skill_list()`` /
  ``skill_content(name)``（POST /api/v1/skills，与 bank 8900 zdt_agent 同契约），
  用 ``evo_agent.llm.invocation._get_invocation_loop().submit().result()`` 同步包装。
- ``make_skill_provider``：配置切换两源。

evo_agent 的 ``StandardTrajectory`` 无 ``script.skill`` 归属信号（与 bank bundle 不同），
EB 链路经此协议取 skill 列表与内容，供 GU 归纳与 EB 路由使用。
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Literal, Protocol, runtime_checkable

from evo_agent.llm.invocation import _get_invocation_loop
from evo_agent.skill_loader import SkillLoader

__all__ = [
    "AdapterSkillProvider",
    "LocalSkillProvider",
    "SkillProvider",
    "make_skill_provider",
]


@runtime_checkable
class SkillProvider(Protocol):
    """skill 源协议：列出 skill 名 + 取 skill 文档内容。"""

    def list_skills(self) -> list[str]:
        """返回已知 skill 名列表。"""
        ...

    def get_skill_content(self, name: str) -> str:
        """返回指定 skill 的 SKILL.md 完整 markdown 内容。"""
        ...


class LocalSkillProvider:
    """本地 skill 源：从 ``skill_root`` 目录树读 SKILL.md。

    目录约定：``<skill_root>/<skill_name>/SKILL.md``。``list_skills`` 扫描含
    ``SKILL.md`` 的子目录；``get_skill_content`` 经 ``SkillLoader.load`` 读取。
    """

    def __init__(self, skill_root: Path) -> None:
        self._skill_root = Path(skill_root)

    def list_skills(self) -> list[str]:
        if not self._skill_root.exists() or not self._skill_root.is_dir():
            return []
        return sorted(
            p.name for p in self._skill_root.iterdir() if p.is_dir() and (p / "SKILL.md").exists()
        )

    def get_skill_content(self, name: str) -> str:
        return SkillLoader.load(self._skill_root / name)


class AdapterSkillProvider:
    """远程 skill 源：包 ``adapter_client``（async ``skill_list`` / ``skill_content``）。

    async 方法用 ``_get_invocation_loop().submit().result()`` 同步包装，与
    ``TrajectoryGoalGenerator`` 一致。``adapter_client`` 需已建立（或可自建 httpx
    client 的实例）；``skill_list()`` 返回 ``[{"name": ...}, ...]``。
    """

    def __init__(self, adapter_client: Any) -> None:
        self._adapter_client = adapter_client

    def list_skills(self) -> list[str]:
        raw = _get_invocation_loop().submit(self._adapter_client.skill_list()).result()
        return [s.get("name", "") for s in raw if isinstance(s, dict) and s.get("name")]

    def get_skill_content(self, name: str) -> str:
        return _get_invocation_loop().submit(self._adapter_client.skill_content(name)).result()


def make_skill_provider(
    source: Literal["local", "adapter"],
    *,
    skill_root: Path | None = None,
    adapter_client: Any = None,
) -> SkillProvider:
    """按 source 切换 skill 源适配器。

    - ``"local"``：需 ``skill_root``，返回 ``LocalSkillProvider``。
    - ``"adapter"``：需 ``adapter_client``，返回 ``AdapterSkillProvider``。
    """
    if source == "local":
        if skill_root is None:
            raise ValueError("LocalSkillProvider requires skill_root")
        return LocalSkillProvider(skill_root=skill_root)
    if source == "adapter":
        if adapter_client is None:
            raise ValueError("AdapterSkillProvider requires adapter_client")
        return AdapterSkillProvider(adapter_client=adapter_client)
    raise ValueError(f"Unknown skill provider source: {source!r} (use 'local' or 'adapter')")
