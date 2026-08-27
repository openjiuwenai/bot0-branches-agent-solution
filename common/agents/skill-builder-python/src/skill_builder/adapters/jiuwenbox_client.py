"""Feature-neutral client and policy helpers for the Jiuwenbox runtime."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path, PureWindowsPath
from typing import Any, Literal
from urllib.parse import urlparse

import httpx


DEFAULT_JIUWENBOX_URL = "http://127.0.0.1:8321"
DEFAULT_JIUWENBOX_TIMEOUT_SECONDS = 30.0
DEFAULT_JIUWENBOX_WORKSPACE_PATH = "/workspace"


class JiuwenboxRuntimeError(RuntimeError):
    """Raised when the Jiuwenbox infrastructure cannot satisfy a request."""


def _first_env(*names: str) -> str:
    for name in names:
        value = str(os.getenv(name) or "").strip()
        if value:
            return value
    return ""


def jiuwenbox_base_url() -> str:
    """Return the feature-neutral Jiuwenbox endpoint."""

    return _first_env("JIUWENBOX_URL") or DEFAULT_JIUWENBOX_URL


def jiuwenbox_timeout_seconds() -> float:
    """Return the shared Jiuwenbox client timeout."""

    raw = _first_env("JIUWENBOX_TIMEOUT_SECONDS")
    return float(raw or DEFAULT_JIUWENBOX_TIMEOUT_SECONDS)


def build_jiuwenbox_writable_workspace_policy(
    workspace_path: str = DEFAULT_JIUWENBOX_WORKSPACE_PATH,
) -> dict[str, Any]:
    """Create the policy overlay for a real writable sandbox workspace."""

    return {
        "filesystem_policy": {
            "directories": [{"path": workspace_path, "permissions": "0777"}],
            "read_write": [workspace_path],
        }
    }


def _normalize_host_path_for_prefix_match(value: str) -> str:
    normalized = value.replace("\\", "/")
    while normalized.startswith("//"):
        normalized = normalized[1:]
    lower = normalized.lower()
    git_prefix = ":/program files/git"
    git_prefix_index = lower.find(git_prefix)
    if git_prefix_index == 1:
        normalized = normalized[git_prefix_index + len(git_prefix):] or "/"
    if len(normalized) >= 4 and normalized[0] == "/" and normalized[2] == ":":
        normalized = normalized[1:]
    return normalized.rstrip("/")


def _wsl_unc_to_linux_path(value: str) -> str | None:
    normalized = value.replace("\\", "/")
    lower = normalized.lower()
    for prefix in ("//wsl.localhost/", "//wsl$/"):
        if lower.startswith(prefix):
            parts = normalized[len(prefix):].split("/", 1)
            if len(parts) == 2 and parts[1].strip("/"):
                return f"/{parts[1].strip('/')}"
            return "/"
    return None


def _as_jiuwenbox_absolute_host_path(value: str) -> str:
    wsl_path = _wsl_unc_to_linux_path(value)
    if wsl_path is not None:
        return wsl_path
    normalized = _normalize_host_path_for_prefix_match(value)
    if len(normalized) >= 3 and normalized[1] == ":" and normalized[2] == "/":
        drive = normalized[0].lower()
        rest = normalized[3:].strip("/")
        return f"/mnt/{drive}/{rest}" if rest else f"/mnt/{drive}"
    if normalized.startswith("/"):
        return normalized or "/"
    if normalized:
        return f"/{normalized}"
    return "/"


def host_path_for_jiuwenbox(
    path: Path,
    *,
    host_prefix: str | None = None,
    sandbox_prefix: str | None = None,
) -> str:
    """Return an absolute host path visible to the Jiuwenbox daemon.

    Feature adapters may pass their own mapping explicitly.  When omitted, the
    infrastructure layer reads only the neutral ``JIUWENBOX_*`` contract and
    never reaches into Skill Builder or Skill Test configuration namespaces.
    """

    raw = str(path)
    normalized = _normalize_host_path_for_prefix_match(raw)
    normalized_host_prefix = _normalize_host_path_for_prefix_match(
        host_prefix if host_prefix is not None else _first_env("JIUWENBOX_HOST_PATH_PREFIX")
    )
    normalized_sandbox_prefix = _normalize_host_path_for_prefix_match(
        sandbox_prefix if sandbox_prefix is not None else _first_env("JIUWENBOX_SANDBOX_PATH_PREFIX")
    )
    should_map_prefix = normalized_host_prefix and normalized_sandbox_prefix and (
        normalized == normalized_host_prefix or normalized.startswith(f"{normalized_host_prefix}/")
    )
    if should_map_prefix:
        suffix = normalized[len(normalized_host_prefix):].lstrip("/")
        mapped = f"{normalized_sandbox_prefix}/{suffix}" if suffix else normalized_sandbox_prefix
        return _as_jiuwenbox_absolute_host_path(mapped)
    wsl_path = _wsl_unc_to_linux_path(raw)
    if wsl_path is not None:
        return wsl_path
    if len(raw) >= 3 and raw[1] == ":" and raw[2] in {"\\", "/"}:
        win_path = PureWindowsPath(raw)
        drive = win_path.drive.rstrip(":").lower()
        rest = "/".join(win_path.parts[1:])
        return f"/mnt/{drive}/{rest}" if rest else f"/mnt/{drive}"
    return _as_jiuwenbox_absolute_host_path(normalized)


def merge_jiuwenbox_policy_overlay(base: dict[str, Any], overlay: dict[str, Any]) -> dict[str, Any]:
    """Merge a Jiuwenbox filesystem policy fragment into an existing policy."""

    filesystem_policy = base.setdefault("filesystem_policy", {})
    for key, value in (overlay.get("filesystem_policy") or {}).items():
        if isinstance(value, list):
            filesystem_policy.setdefault(key, []).extend(value)
        else:
            filesystem_policy[key] = value
    return base


@dataclass(frozen=True)
class JiuwenboxSandboxRef:
    """Sandbox identity returned by Jiuwenbox."""

    sandbox_id: str
    phase: str | None = None
    runtime: str | None = None
    created_at: str | None = None
    env: dict[str, str] | None = None


@dataclass(frozen=True)
class JiuwenboxCommandResult:
    """Synchronous command result returned by Jiuwenbox."""

    exit_code: int
    stdout: str = ""
    stderr: str = ""


class JiuwenboxSandboxClient:
    """Narrow HTTP client for Jiuwenbox lifecycle and file operations."""

    def __init__(
        self,
        base_url: str | None = None,
        *,
        timeout_seconds: float | None = None,
        http_client: httpx.Client | None = None,
        error_type: type[RuntimeError] = JiuwenboxRuntimeError,
    ):
        self.base_url = (base_url or jiuwenbox_base_url()).strip()
        self.timeout_seconds = float(timeout_seconds or jiuwenbox_timeout_seconds())
        self.error_type = error_type
        self._owned_client = http_client is None
        self._client = http_client or self._build_http_client(self.base_url, self.timeout_seconds)

    def _error(self, message: str) -> RuntimeError:
        return self.error_type(message)

    def _build_http_client(self, base_url: str, timeout_seconds: float) -> httpx.Client:
        parsed = urlparse(base_url)
        if parsed.scheme == "unix":
            if not parsed.path:
                raise self._error("unix jiuwenbox url must include socket path")
            return httpx.Client(
                transport=httpx.HTTPTransport(uds=parsed.path),
                base_url="http://jiuwenbox",
                timeout=timeout_seconds,
            )
        return httpx.Client(base_url=base_url.rstrip("/"), timeout=timeout_seconds)

    def close(self) -> None:
        if self._owned_client:
            self._client.close()

    def health(self) -> dict[str, Any]:
        return self._json_response(self._client.get("/health"))

    def list_sandboxes(self) -> list[JiuwenboxSandboxRef]:
        response = self._client.get("/api/v1/sandboxes")
        try:
            data = response.json()
        except ValueError as exc:
            raise self._error("jiuwenbox returned non-json sandbox list") from exc
        if response.status_code >= 400 or not isinstance(data, list):
            raise self._error(self._error_message(response))
        result: list[JiuwenboxSandboxRef] = []
        for item in data:
            if not isinstance(item, dict) or not str(item.get("id") or "").strip():
                continue
            result.append(
                JiuwenboxSandboxRef(
                    sandbox_id=str(item["id"]).strip(),
                    phase=str(item.get("phase") or "") or None,
                    runtime=str(item.get("runtime") or "") or None,
                    created_at=str(item.get("created_at") or "") or None,
                    env={str(key): str(value) for key, value in (item.get("env") or {}).items()},
                )
            )
        return result

    def create_sandbox(
        self,
        *,
        env: dict[str, str] | None = None,
        policy: dict[str, Any] | None = None,
        policy_mode: Literal["override", "append"] = "override",
        command: list[str] | None = None,
    ) -> JiuwenboxSandboxRef:
        payload: dict[str, Any] = {"env": env or {}, "policy_mode": policy_mode}
        if command is not None:
            payload["command"] = command
        if policy is not None:
            payload["policy"] = policy
        data = self._json_response(self._client.post("/api/v1/sandboxes", json=payload))
        sandbox_id = str(data.get("id") or "").strip()
        if not sandbox_id:
            raise self._error("jiuwenbox create_sandbox response missing id")
        phase = str(data.get("phase") or "") or None
        if phase == "error":
            error_message = str(
                data.get("error_message") or data.get("error") or data.get("detail") or ""
            ).strip()
            raise self._error(
                error_message or f"jiuwenbox sandbox {sandbox_id} entered error state during startup"
            )
        return JiuwenboxSandboxRef(
            sandbox_id=sandbox_id,
            phase=phase,
            runtime=str(data.get("runtime") or "") or None,
            created_at=str(data.get("created_at") or "") or None,
            env={str(key): str(value) for key, value in (data.get("env") or {}).items()},
        )

    def exec_command(
        self,
        sandbox_id: str,
        command: list[str],
        *,
        workdir: str | None = None,
        env: dict[str, str] | None = None,
        stdin: str | None = None,
        timeout_seconds: int | None = None,
    ) -> JiuwenboxCommandResult:
        payload: dict[str, Any] = {"command": command}
        if workdir is not None:
            payload["workdir"] = workdir
        if env is not None:
            payload["env"] = env
        if stdin is not None:
            payload["stdin"] = stdin
        if timeout_seconds is not None:
            payload["timeout_seconds"] = timeout_seconds
        request_timeout = self.timeout_seconds
        if timeout_seconds is not None:
            request_timeout = max(request_timeout, float(timeout_seconds) + 5.0)
        data = self._json_response(
            self._client.post(
                f"/api/v1/sandboxes/{sandbox_id}/exec",
                json=payload,
                timeout=request_timeout,
            )
        )
        return JiuwenboxCommandResult(
            exit_code=int(data.get("exit_code", -1)),
            stdout=str(data.get("stdout") or ""),
            stderr=str(data.get("stderr") or ""),
        )

    def get_logs(self, sandbox_id: str) -> str:
        response = self._client.get(f"/api/v1/sandboxes/{sandbox_id}/logs")
        if response.status_code >= 400:
            raise self._error(self._error_message(response))
        return response.text

    def delete_sandbox(self, sandbox_id: str) -> None:
        response = self._client.delete(f"/api/v1/sandboxes/{sandbox_id}")
        if response.status_code not in (200, 204, 404):
            raise self._error(self._error_message(response))

    def upload_file(self, sandbox_id: str, local_path: Path, sandbox_path: str) -> None:
        with local_path.open("rb") as file_handle:
            response = self._client.post(
                f"/api/v1/sandboxes/{sandbox_id}/upload",
                params={"sandbox_path": sandbox_path},
                files={"file": (local_path.name, file_handle)},
            )
        if response.status_code not in (200, 204):
            raise self._error(self._error_message(response))

    def download_file(self, sandbox_id: str, sandbox_path: str) -> bytes:
        response = self._client.get(
            f"/api/v1/sandboxes/{sandbox_id}/download",
            params={"sandbox_path": sandbox_path},
        )
        if response.status_code >= 400:
            raise self._error(self._error_message(response))
        return response.content

    def _json_response(self, response: httpx.Response) -> dict[str, Any]:
        if response.status_code >= 400:
            raise self._error(self._error_message(response))
        try:
            data = response.json()
        except ValueError as exc:
            raise self._error("jiuwenbox returned non-json response") from exc
        if not isinstance(data, dict):
            raise self._error("jiuwenbox returned unexpected response shape")
        return data

    @staticmethod
    def _error_message(response: httpx.Response) -> str:
        try:
            data = response.json()
            if isinstance(data, dict):
                return str(data.get("error") or data.get("detail") or response.text or response.status_code)
        except ValueError:
            pass
        return str(response.text or response.status_code)
