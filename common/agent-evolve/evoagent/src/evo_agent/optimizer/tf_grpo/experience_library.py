"""Experience library for TF-GRPO."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class ExperienceEntry:
    """Single experiential insight retained across TF-GRPO epochs."""

    content: str
    domain: str = "markdown"
    confidence: float = 0.8
    created_at: str = field(default_factory=_utc_now)
    last_used: str = field(default_factory=_utc_now)
    success_count: int = 0
    failure_count: int = 0


@dataclass
class LibraryOperation:
    """One CRUD decision against the experience library."""

    operation: str  # Add | Delete | Modify | Keep
    content: str | None = None
    index: int | None = None


class ExperienceLibrary:
    """External experience store E used by Training-Free GRPO (no param updates)."""

    def __init__(self, domain: str = "markdown", *, max_experiences: int = 10) -> None:
        self.domain = domain
        self.max_experiences = max(1, max_experiences)
        self.experiences: list[ExperienceEntry] = []

    def add(self, content: str, domain: str | None = None, confidence: float = 0.8) -> None:
        text = (content or "").strip()
        if not text:
            return
        self.experiences.append(
            ExperienceEntry(
                content=text,
                domain=domain or self.domain,
                confidence=confidence,
            )
        )
        self._trim()

    def delete(self, index: int) -> None:
        if 0 <= index < len(self.experiences):
            self.experiences.pop(index)

    def modify(self, index: int, new_content: str) -> None:
        text = (new_content or "").strip()
        if not text or not (0 <= index < len(self.experiences)):
            return
        entry = self.experiences[index]
        entry.content = text
        entry.last_used = _utc_now()

    def get_relevant(self, domain: str | None = None, max_count: int | None = None) -> list[str]:
        target = domain or self.domain
        limit = max_count if max_count is not None else self.max_experiences
        relevant = [
            e for e in self.experiences if e.domain in (target, "general", self.domain)
        ]
        relevant.sort(key=lambda e: (e.confidence, e.last_used), reverse=True)
        return [e.content for e in relevant[:limit]]

    def to_prompt_context(self, domain: str | None = None) -> str:
        items = self.get_relevant(domain)
        if not items:
            return ""
        lines = [
            "# 已学习经验",
            "",
            "基于以往优化尝试，请应用以下洞察：",
            "",
        ]
        for i, exp in enumerate(items, 1):
            lines.append(f"{i}. {exp}")
        lines.append("")
        return "\n".join(lines)

    def apply_operations(self, operations: list[LibraryOperation]) -> list[str]:
        """Apply Add/Delete/Modify/Keep ops; returns human-readable log lines."""
        log: list[str] = []
        # Delete high indices first so earlier indices stay stable within one batch
        deletes = sorted(
            [op for op in operations if op.operation == "Delete" and op.index is not None],
            key=lambda op: op.index or 0,
            reverse=True,
        )
        for op in deletes:
            assert op.index is not None
            if 0 <= op.index < len(self.experiences):
                deleted = self.experiences[op.index].content
                self.delete(op.index)
                log.append(f"Deleted: {deleted[:80]}")

        for op in operations:
            kind = op.operation
            if kind == "Delete":
                continue
            if kind == "Add" and op.content:
                self.add(op.content)
                log.append(f"Added: {op.content[:80]}")
            elif kind == "Modify" and op.content is not None and op.index is not None:
                if 0 <= op.index < len(self.experiences):
                    self.modify(op.index, op.content)
                    log.append(f"Modified index {op.index}")
            elif kind == "Keep":
                log.append("Keep")
        self._trim()
        return log

    def _trim(self) -> None:
        if len(self.experiences) <= self.max_experiences:
            return
        self.experiences.sort(key=lambda e: (e.confidence, e.last_used), reverse=True)
        self.experiences = self.experiences[: self.max_experiences]

    def to_dict(self) -> dict[str, Any]:
        return {
            "domain": self.domain,
            "max_experiences": self.max_experiences,
            "experiences": [asdict(e) for e in self.experiences],
        }

    def save(self, filepath: Path) -> None:
        filepath.parent.mkdir(parents=True, exist_ok=True)
        filepath.write_text(
            json.dumps(self.to_dict(), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    @classmethod
    def load(cls, filepath: Path) -> ExperienceLibrary:
        data = json.loads(filepath.read_text(encoding="utf-8"))
        lib = cls(
            domain=data.get("domain", "markdown"),
            max_experiences=int(data.get("max_experiences", 10)),
        )
        for raw in data.get("experiences", []):
            lib.experiences.append(ExperienceEntry(**raw))
        return lib
