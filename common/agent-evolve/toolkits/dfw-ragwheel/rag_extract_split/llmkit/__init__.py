"""llmkit: A lightweight toolkit for managing and calling LLM APIs."""

from pathlib import Path

from .caller import call_llm, call_llm_by_id
from .profile import Profile, substitute_variables
from .profile_manager import ProfileManager
from .template import Template, TemplateManager
from .tester import check_profile

__version__ = "0.1.0"

__all__ = [
    "Profile",
    "ProfileManager",
    "Template",
    "TemplateManager",
    "call_llm",
    "call_llm_by_id",
    "check_profile",
    "substitute_variables",
]

# Path helpers for web/llmkit bundled templates
_BUNDLE_DIR = Path(__file__).resolve().parent
BUILTIN_TEMPLATES_DIR = str(_BUNDLE_DIR / "templates")


def get_default_data_dir(project_root: Path) -> Path:
    """Return the default directory for llmkit user data (profiles + custom templates)."""
    return project_root / "data" / "llmkit"
