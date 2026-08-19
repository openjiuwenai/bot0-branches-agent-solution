"""Profile definition, variable substitution, and request building."""

import copy
import os
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import yaml


@dataclass
class BuiltRequest:
    """HTTP request components produced by Profile.build_request."""

    method: str
    url: str
    headers: Dict[str, Any]
    data: Dict[str, Any]
    timeout: int
    stream: bool


@dataclass
class Profile:
    """Represents a saved LLM configuration instance.

    A profile is self-contained: it carries all information needed to make an
    HTTP request to an LLM endpoint, independent of any template file.
    """

    id: str
    name: str
    template: str
    connection: Dict[str, Any] = field(default_factory=dict)
    request: Dict[str, Any] = field(default_factory=dict)
    runtime: Dict[str, Any] = field(default_factory=dict)

    @property
    def base_url(self) -> str:
        """Return the configured base URL."""
        return str(self.connection.get("base_url", "")).rstrip("/")

    @property
    def timeout(self) -> int:
        """Return the configured timeout in seconds."""
        return int(self.connection.get("timeout", 600))

    @property
    def stream_enabled(self) -> bool:
        """Return the default stream setting."""
        return bool(self.runtime.get("stream_enabled", False))

    def build_request(
        self, messages: List[Dict[str, Any]], stream_enabled: Optional[bool] = None
    ) -> BuiltRequest:
        """Build all components needed for an HTTP request.

        Args:
            messages: The conversation messages to send.
            stream_enabled: Override the default stream setting.

        Returns:
            BuiltRequest with method, url, headers, data, timeout, and stream.
        """
        method = self.request.get("method", "POST")
        url_suffix = self.request.get("url_suffix", "")
        url = self.base_url + url_suffix

        context = {"connection": self.connection}
        headers = substitute_variables(copy.deepcopy(self.request.get("headers", {})), context)
        data = substitute_variables(copy.deepcopy(self.request.get("data", {})), context)
        data["messages"] = messages

        timeout = self.timeout
        stream = self.stream_enabled if stream_enabled is None else stream_enabled

        return BuiltRequest(method, url, headers, data, timeout, stream)

    def to_dict(self) -> Dict[str, Any]:
        """Serialize profile to a dictionary."""
        return {
            "id": self.id,
            "name": self.name,
            "template": self.template,
            "connection": copy.deepcopy(self.connection),
            "request": copy.deepcopy(self.request),
            "runtime": copy.deepcopy(self.runtime),
        }

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Profile":
        """Create a profile from a dictionary."""
        return cls(
            id=data.get("id", ""),
            name=data.get("name", ""),
            template=data.get("template", ""),
            connection=data.get("connection", {}),
            request=data.get("request", {}),
            runtime=data.get("runtime", {}),
        )

    @classmethod
    def from_file(cls, path: str) -> "Profile":
        """Load a single-profile YAML file."""
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        return cls.from_dict(data)


def substitute_variables(value: Any, context: Dict[str, Any]) -> Any:
    """Recursively substitute variables in a value.

    Supported variable formats:
    - ${connection.key} -> references connection section
    - ${env.ENV_VAR}    -> references environment variable

    Args:
        value: The value to process (string, dict, list, or other).
        context: A dictionary of available variable namespaces.

    Returns:
        The value with variables substituted.
    """
    if isinstance(value, dict):
        return {k: substitute_variables(v, context) for k, v in value.items()}
    if isinstance(value, list):
        return [substitute_variables(v, context) for v in value]
    if not isinstance(value, str):
        return value

    def _replacer(match: re.Match) -> str:
        var_path = match.group(1).strip()
        parts = var_path.split(".")

        if len(parts) >= 2 and parts[0] == "connection":
            conn = context.get("connection", {})
            val = conn
            for part in parts[1:]:
                if isinstance(val, dict) and part in val:
                    val = val[part]
                else:
                    return match.group(0)  # keep original if not found
            return str(val)

        if len(parts) >= 2 and parts[0] == "env":
            env_name = ".".join(parts[1:])
            return os.environ.get(env_name, match.group(0))

        return match.group(0)

    return re.sub(r"\$\{([^}]+)\}", _replacer, value)
