"""Manager for LLM profile persistence in YAML files."""

import copy
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml

from .profile import Profile


class ProfileManager:
    """Manages a collection of LLM profiles stored in a single YAML file.

    The YAML file has the following structure:

        version: "1.0"
        profiles:
          - id: llm_001
            name: 公司 GPT-4o
            template: openai_compatible
            connection: {...}
            request: {...}
            runtime: {...}
    """

    def __init__(self, profiles_path: str):
        """Initialize with the path to the profiles YAML file.

        Args:
            profiles_path: Path to the YAML file. If it does not exist, it will
                be created on the first save.
        """
        self._path = Path(profiles_path)
        self._data: Dict[str, Any] = {"version": "1.0", "profiles": []}
        self._profiles: Dict[str, Profile] = {}
        self._load()

    def _load(self) -> None:
        """Load profiles from disk."""
        if not self._path.exists():
            return

        with open(self._path, "r", encoding="utf-8") as f:
            self._data = yaml.safe_load(f) or {"version": "1.0", "profiles": []}

        profiles_list = self._data.get("profiles", [])
        self._profiles = {}
        for item in profiles_list:
            if not isinstance(item, dict):
                continue
            profile = Profile.from_dict(item)
            self._profiles[profile.id] = profile

    def _save(self) -> None:
        """Persist profiles to disk."""
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._data["profiles"] = [p.to_dict() for p in self._profiles.values()]
        with open(self._path, "w", encoding="utf-8") as f:
            yaml.safe_dump(self._data, f, sort_keys=False, allow_unicode=True)

    def list_profiles(self) -> List[Dict[str, str]]:
        """Return a list of profile summaries for UI display."""
        return [
            {
                "id": p.id,
                "name": p.name,
                "template": p.template,
            }
            for p in self._profiles.values()
        ]

    def get_profile(self, profile_id: str) -> Optional[Profile]:
        """Get a profile by ID."""
        return self._profiles.get(profile_id)

    def get_profile_by_name(self, name: str) -> Optional[Profile]:
        """Get a profile by its display name."""
        for profile in self._profiles.values():
            if profile.name == name:
                return profile
        return None

    def save_profile(self, profile: Profile) -> None:
        """Save or update a profile."""
        self._profiles[profile.id] = profile
        self._save()

    def save_profile_dict(self, data: Dict[str, Any]) -> Profile:
        """Save or update a profile from a dictionary."""
        profile = Profile.from_dict(data)
        self._profiles[profile.id] = profile
        self._save()
        return profile

    def delete_profile(self, profile_id: str) -> bool:
        """Delete a profile by ID. Returns True if deleted."""
        if profile_id in self._profiles:
            del self._profiles[profile_id]
            self._save()
            return True
        return False

    def exists(self, profile_id: str) -> bool:
        """Check if a profile exists."""
        return profile_id in self._profiles

    def reload(self) -> None:
        """Reload profiles from disk."""
        self._load()

    def export_profile(self, profile_id: str) -> Optional[Dict[str, Any]]:
        """Export a single profile as a dictionary."""
        profile = self._profiles.get(profile_id)
        if profile is None:
            return None
        return profile.to_dict()

    def get_all_profiles(self) -> List[Profile]:
        """Return all profiles as Profile objects."""
        return list(self._profiles.values())
