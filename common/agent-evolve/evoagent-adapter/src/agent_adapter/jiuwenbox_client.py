"""Minimal jiuwenbox management-API client for sandbox file CRUD.

Used by the jiuwenbox skill backend to list/read/write SKILL.md inside a
running sandbox without sharing a host bind mount.
"""

from __future__ import annotations

from typing import Any

import httpx
import structlog

logger = structlog.get_logger(__name__)


class JiuwenBoxClientError(Exception):
    """Raised when a jiuwenbox API call fails."""

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class JiuwenBoxClient:
    """Sync HTTP client for jiuwenbox ``/api/v1`` sandbox file endpoints."""

    def __init__(self, base_url: str, *, timeout: float = 30.0, trust_env: bool = False) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout
        self._trust_env = trust_env

    def _client(self, *, timeout: float | None = None) -> httpx.Client:
        return httpx.Client(
            timeout=self._timeout if timeout is None else timeout,
            trust_env=self._trust_env,
        )

    def _url(self, path: str) -> str:
        return f"{self._base_url}{path}"

    def list_sandboxes(self) -> list[dict[str, Any]]:
        with self._client() as client:
            resp = client.get(self._url("/api/v1/sandboxes"))
        if resp.status_code != 200:
            raise JiuwenBoxClientError(
                f"list sandboxes failed: HTTP {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )
        data = resp.json()
        if isinstance(data, list):
            return data
        if isinstance(data, dict) and "items" in data:
            items = data["items"]
            return items if isinstance(items, list) else []
        return []

    def list_files(
        self,
        sandbox_id: str,
        sandbox_path: str,
        *,
        recursive: bool = False,
        include_files: bool = True,
        include_dirs: bool = True,
    ) -> list[dict[str, Any]]:
        params = {
            "sandbox_path": sandbox_path,
            "recursive": str(recursive).lower(),
            "include_files": str(include_files).lower(),
            "include_dirs": str(include_dirs).lower(),
        }
        with self._client() as client:
            resp = client.get(
                self._url(f"/api/v1/sandboxes/{sandbox_id}/files"),
                params=params,
            )
        if resp.status_code != 200:
            raise JiuwenBoxClientError(
                f"list files failed: HTTP {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )
        payload = resp.json()
        items = payload.get("items", []) if isinstance(payload, dict) else []
        return items if isinstance(items, list) else []

    def download_file(self, sandbox_id: str, sandbox_path: str) -> bytes:
        with self._client() as client:
            resp = client.get(
                self._url(f"/api/v1/sandboxes/{sandbox_id}/download"),
                params={"sandbox_path": sandbox_path},
            )
        if resp.status_code != 200:
            raise JiuwenBoxClientError(
                f"download failed: HTTP {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )
        return resp.content

    def upload_file(
        self,
        sandbox_id: str,
        sandbox_path: str,
        content: bytes,
        *,
        file_name: str = "SKILL.md",
    ) -> None:
        with self._client() as client:
            resp = client.post(
                self._url(f"/api/v1/sandboxes/{sandbox_id}/upload"),
                params={"sandbox_path": sandbox_path},
                files={"file": (file_name, content, "application/octet-stream")},
            )
        if resp.status_code not in (200, 204):
            raise JiuwenBoxClientError(
                f"upload failed: HTTP {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )

    def exec_command(
        self,
        sandbox_id: str,
        command: list[str],
        *,
        timeout_seconds: float | None = 30.0,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"command": command}
        if timeout_seconds is not None:
            body["timeout_seconds"] = timeout_seconds
        with self._client(timeout=(timeout_seconds or 30.0) + 5.0) as client:
            resp = client.post(
                self._url(f"/api/v1/sandboxes/{sandbox_id}/exec"),
                json=body,
            )
        if resp.status_code != 200:
            raise JiuwenBoxClientError(
                f"exec failed: HTTP {resp.status_code}: {resp.text}",
                status_code=resp.status_code,
            )
        data = resp.json()
        return data if isinstance(data, dict) else {}

    def health(self) -> bool:
        try:
            with self._client(timeout=5.0) as client:
                resp = client.get(self._url("/health"))
            return resp.status_code == 200
        except httpx.HTTPError:
            return False
