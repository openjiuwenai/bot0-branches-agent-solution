"""凭证提供者 — 支持 env 与 vault 两种模式。"""

from __future__ import annotations

import os
from typing import Protocol

from ..config import CredentialConfig


class CredentialProvider(Protocol):
    """凭证提供者接口。"""

    def resolve(self, placeholder: str) -> str:
        """将占位符解析为实际值。"""
        ...


class EnvCredentialProvider:
    """环境变量凭证提供者。"""

    def resolve(self, placeholder: str) -> str:
        if not isinstance(placeholder, str):
            return str(placeholder)
        if placeholder.startswith("env:"):
            return os.environ.get(placeholder[4:], "")
        return placeholder


class VaultCredentialProvider:
    """HashiCorp Vault 凭证提供者。

    需安装 hvac：pip install openjiuwen-db-connector[vault]
    """

    def __init__(self, config: CredentialConfig) -> None:
        self._config = config
        self._client = None  # 延迟初始化

    def _get_client(self):
        if self._client is None:
            try:
                import hvac
            except ImportError as e:
                raise RuntimeError("需安装 hvac：pip install openjiuwen-db-connector[vault]") from e
            addr = self._resolve_env(self._config.vault.addr)
            self._client = hvac.Client(url=addr)
        return self._client

    @staticmethod
    def _resolve_env(value: str) -> str:
        if value.startswith("env:"):
            return os.environ.get(value[4:], "")
        return value

    def resolve(self, placeholder: str) -> str:
        if not isinstance(placeholder, str):
            return str(placeholder)
        if not placeholder.startswith("vault:"):
            # 非 vault 占位符，走 env 兜底
            if placeholder.startswith("env:"):
                return os.environ.get(placeholder[4:], "")
            return placeholder

        # vault:path/to/secret#field
        ref = placeholder[6:]
        if "#" in ref:
            secret_path, field = ref.rsplit("#", 1)
        else:
            secret_path, field = ref, "password"

        client = self._get_client()
        result = client.secrets.kv.v2.read_secret_version(
            path=secret_path,
            mount_point=self._config.vault.mount,
        )
        return result["data"]["data"].get(field, "")


def create_credential_provider(config: CredentialConfig) -> CredentialProvider:
    """根据配置创建凭证提供者。"""
    if config.provider == "vault":
        return VaultCredentialProvider(config)
    return EnvCredentialProvider()
