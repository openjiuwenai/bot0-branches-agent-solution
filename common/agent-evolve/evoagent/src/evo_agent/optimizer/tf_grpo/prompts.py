"""TF-GRPO prompt templates — bundled defaults + scenario override.

Lookup order:
1. ``<scenarios_dir>/<scenario_name>/prompts/<name>.md``
2. ``evo_agent/optimizer/tf_grpo/templates/<name>.md``
"""

from __future__ import annotations

import functools
import os
from collections.abc import Callable, Mapping
from pathlib import Path

from evo_agent.paths import SCENARIOS_DIR

_TEMPLATES_DIR = Path(__file__).parent / "templates"

# Stage template names (override these under scenarios/.../prompts/).
VARIANT_EMPTY = "variant_empty"
VARIANT_WITH_EXPERIENCE = "variant_with_experience"
VARIANT_FOCUS_EMPTY = "variant_focus_empty"
VARIANT_FOCUS_WITH_EXPERIENCE = "variant_focus_with_experience"
ROLLOUT_SUMMARY = "rollout_summary"
SEMANTIC_ADVANTAGE = "semantic_advantage"
SEMANTIC_ADVANTAGE_NOTE_WITH_VARIANCE = "semantic_advantage_note_with_variance"
SEMANTIC_ADVANTAGE_NOTE_NO_VARIANCE = "semantic_advantage_note_no_variance"
LIBRARY_UPDATE = "library_update"


def _load_uncached(name: str) -> str:
    path = _TEMPLATES_DIR / f"{name}.md"
    if not path.is_file():
        available = sorted(p.stem for p in _TEMPLATES_DIR.glob("*.md"))
        raise FileNotFoundError(
            f"TF-GRPO prompt template '{name}' not found. Available: {available}"
        )
    return path.read_text(encoding="utf-8")


_cached_load: Callable[[str], str] = functools.lru_cache(maxsize=None)(_load_uncached)


def load_tf_grpo_prompt(
    name: str,
    *,
    scenario_name: str | None = None,
    scenarios_dir: Path | str | None = None,
) -> str:
    """Load a TF-GRPO prompt template, preferring scenario override when present."""
    if scenario_name:
        root = Path(scenarios_dir) if scenarios_dir is not None else SCENARIOS_DIR
        override = root / scenario_name / "prompts" / f"{name}.md"
        if override.is_file():
            return override.read_text(encoding="utf-8")

    if os.environ.get("EVO_DISABLE_PROMPT_CACHE") == "1":
        return _load_uncached(name)
    return _cached_load(name)


def render_prompt(template: str, values: Mapping[str, object]) -> str:
    """Replace ``{key}`` placeholders. Unknown braces (e.g. JSON examples) are kept.

    Only exact ``{name}`` tokens listed in ``values`` are substituted; this avoids
    ``str.format`` breaking on literal ``{`` / ``}`` in few-shot JSON blocks.
    """
    result = template
    for key, value in values.items():
        result = result.replace("{" + key + "}", str(value))
    return result
