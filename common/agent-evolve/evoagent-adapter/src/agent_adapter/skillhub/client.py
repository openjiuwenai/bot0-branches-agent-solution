"""HTTP client for SkillHub marketplace REST API."""

from __future__ import annotations

from typing import Any, Literal

import httpx

from agent_adapter.skillhub.errors import (
    SkillHubAuthError,
    SkillHubConflictError,
    SkillHubError,
    SkillHubNotFoundError,
)


class SkillHubClient:
    """Thin wrapper around SkillHub /api/v1 marketplace endpoints."""

    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        auth_mode: Literal["bearer", "system_token"] = "system_token",
        connect_timeout: float = 30.0,
        publish_timeout: float = 120.0,
    ) -> None:
        base = base_url.rstrip("/")
        if not base:
            raise ValueError("skillhub base_url is required")
        if not token.strip():
            raise ValueError("skillhub token is required")
        self._api_base = f"{base}/api/v1"
        self._auth_mode = auth_mode
        self._token = token.strip()
        self._connect_timeout = connect_timeout
        self._publish_timeout = publish_timeout

    def _auth_headers(self) -> dict[str, str]:
        if self._auth_mode == "bearer":
            return {"Authorization": f"Bearer {self._token}"}
        return {"X-System-Token": self._token}

    def _unwrap(self, response: httpx.Response) -> dict[str, Any]:
        if response.status_code == 401:
            raise SkillHubAuthError("SkillHub authentication failed", status_code=401)
        if response.status_code == 404:
            raise SkillHubNotFoundError("SkillHub resource not found", status_code=404)
        if response.status_code == 409:
            detail = _extract_error_message(response)
            raise SkillHubConflictError(detail, status_code=409)
        if response.status_code >= 400:
            detail = _extract_error_message(response)
            raise SkillHubError(detail, status_code=response.status_code)

        payload = response.json()
        if not isinstance(payload, dict):
            return {}
        if "data" in payload:
            data = payload.get("data")
            return data if isinstance(data, dict) else {"items": data}
        return payload

    def list_plugins(
        self,
        *,
        page: int = 1,
        page_size: int = 20,
        plugin_type: str = "skill",
        search_keyword: str | None = None,
        publisher_id: str | None = None,
        asset_id: str | None = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "page": page,
            "page_size": page_size,
            "plugin_type": plugin_type,
        }
        if search_keyword:
            params["search_keyword"] = search_keyword
        if publisher_id:
            params["publisher_id"] = publisher_id
        if asset_id:
            params["asset_id"] = asset_id
        with httpx.Client(timeout=self._connect_timeout, trust_env=False) as client:
            response = client.get(
                f"{self._api_base}/plugins",
                params=params,
                headers=self._auth_headers(),
            )
        return self._unwrap(response)

    def get_version(self, asset_id: str, version: str) -> dict[str, Any]:
        with httpx.Client(timeout=self._connect_timeout, trust_env=False) as client:
            response = client.get(
                f"{self._api_base}/plugins/{asset_id}/versions/{version}",
                headers=self._auth_headers(),
            )
        return self._unwrap(response)

    def get_artifact_download(self, asset_id: str, *, version: str) -> dict[str, Any]:
        with httpx.Client(timeout=self._connect_timeout, trust_env=False) as client:
            response = client.get(
                f"{self._api_base}/artifacts/{asset_id}",
                params={"version": version},
                headers=self._auth_headers(),
            )
        return self._unwrap(response)

    def download_zip(self, asset_id: str, *, version: str) -> bytes:
        artifact = self.get_artifact_download(asset_id, version=version)
        download_url = str(artifact.get("download_url") or "")
        if not download_url:
            raise SkillHubError("SkillHub artifact response missing download_url")
        with httpx.Client(timeout=self._publish_timeout, trust_env=False) as client:
            response = client.get(download_url)
        if response.status_code >= 400:
            raise SkillHubError(
                f"Failed to download skill zip: HTTP {response.status_code}",
                status_code=response.status_code,
            )
        return response.content

    def publish(
        self,
        *,
        zip_bytes: bytes,
        checksum_sha256: str,
        plugin_version: str,
        plugin_id: str | None = None,
        version_desc: str | None = None,
        force: bool = False,
    ) -> dict[str, Any]:
        files = {"file": ("skill.zip", zip_bytes, "application/zip")}
        data: dict[str, str] = {"plugin_version": plugin_version}
        if plugin_id:
            data["plugin_id"] = plugin_id
        if version_desc:
            data["version_desc"] = version_desc
        if force:
            data["force"] = "true"
        headers = {
            **self._auth_headers(),
            "X-Checksum-SHA256": checksum_sha256,
        }
        with httpx.Client(timeout=self._publish_timeout, trust_env=False) as client:
            response = client.post(
                f"{self._api_base}/plugins",
                data=data,
                files=files,
                headers=headers,
            )
        return self._unwrap(response)

    def delete_version(self, asset_id: str, version: str) -> dict[str, Any]:
        with httpx.Client(timeout=self._connect_timeout, trust_env=False) as client:
            response = client.delete(
                f"{self._api_base}/plugins/{asset_id}/versions/{version}",
                headers=self._auth_headers(),
            )
        return self._unwrap(response)


def _extract_error_message(response: httpx.Response) -> str:
    try:
        payload = response.json()
    except ValueError:
        return response.text or f"HTTP {response.status_code}"
    if not isinstance(payload, dict):
        return f"HTTP {response.status_code}"
    if isinstance(payload.get("message"), str):
        return payload["message"]
    detail = payload.get("detail")
    if isinstance(detail, str):
        return detail
    if isinstance(detail, dict):
        message = detail.get("message")
        if isinstance(message, str):
            return message
    error = payload.get("error")
    if isinstance(error, dict) and isinstance(error.get("message"), str):
        return error["message"]
    return f"HTTP {response.status_code}"
