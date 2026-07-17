"""EvolveConfig 单元测试。"""

from __future__ import annotations

from pathlib import Path

import pytest

from evo_agent.config import EvolveConfig


class TestEvolveConfig:
    def test_default_config(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """验证关键默认值正确。"""
        # 隔离 .env 文件影响
        for key in [
            "EVO_LLM_API_KEY",
            "EVO_LLM_BASE_URL",
            "EVO_OPTIMIZER_MODEL",
            "EVO_TARGET_MODEL",
            "EVO_ADAPTER_URL",
            "EVO_ALLOWED_DATA_ROOTS",
            "EVO_DEFAULT_EPOCHS",
            "EVO_DEFAULT_BATCH_SIZE",
            "EVO_REMOTE_TIMEOUT",
            "EVO_REMOTE_MAX_RETRIES",
            "EVO_REMOTE_PARALLEL",
        ]:
            monkeypatch.delenv(key, raising=False)
        config = EvolveConfig(_env_file=None)
        assert config.llm_api_key == ""
        assert config.llm_base_url == "https://api.openai.com/v1"
        assert config.optimizer_model == "gpt-4o"
        assert config.remote_timeout == 300.0
        assert config.remote_max_retries == 2
        assert config.remote_parallel == 4
        assert config.default_epochs == 3
        assert config.default_batch_size == 4
        assert config.workspace_root == Path("./workspace")

    def test_env_override(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        """验证环境变量覆盖配置。"""
        monkeypatch.setenv("EVO_LLM_API_KEY", "test-key")
        monkeypatch.setenv("EVO_OPTIMIZER_MODEL", "gpt-4o-mini")
        monkeypatch.setenv("EVO_REMOTE_TIMEOUT", "600")

        config = EvolveConfig()
        assert config.llm_api_key == "test-key"
        assert config.optimizer_model == "gpt-4o-mini"
        assert config.remote_timeout == 600.0

    def test_no_remote_endpoint_field(self) -> None:
        """remote_endpoint 字段已从 EvolveConfig 移除。"""
        config = EvolveConfig(_env_file=None)
        assert not hasattr(config, "remote_endpoint")

    # --- W8.1: adapter_url + allowed_data_roots ---

    def test_config_adapter_url_default(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """adapter_url 默认为空字符串。"""
        monkeypatch.delenv("EVO_ADAPTER_URL", raising=False)
        config = EvolveConfig(_env_file=None)
        assert config.adapter_url == ""

    def test_config_adapter_url_from_env(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """EVO_ADAPTER_URL 环境变量被正确读取。"""
        monkeypatch.setenv("EVO_ADAPTER_URL", "http://adapter:9090")
        config = EvolveConfig()
        assert config.adapter_url == "http://adapter:9090"

    def test_config_allowed_data_roots_default(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """allowed_data_roots 默认为两个默认路径。"""
        monkeypatch.delenv("EVO_ALLOWED_DATA_ROOTS", raising=False)
        config = EvolveConfig(_env_file=None)
        assert len(config.allowed_data_roots) == 2
        assert Path("/data/evo_agent") in config.allowed_data_roots
        assert Path("/tmp/evo_agent") in config.allowed_data_roots

    def test_config_allowed_data_roots_from_env(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """EVO_ALLOWED_DATA_ROOTS 逗号分隔字符串解析为 list[Path]。"""
        monkeypatch.setenv("EVO_ALLOWED_DATA_ROOTS", "/custom/data,/other/path")
        config = EvolveConfig()
        assert config.allowed_data_roots == [
            Path("/custom/data"),
            Path("/other/path"),
        ]

    # --- ICBC provider 配置 ---

    def test_llm_provider_default_openai(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """llm_provider 默认 OpenAI，ICBC 字段默认空。"""
        for key in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(key, raising=False)
        config = EvolveConfig(_env_file=None)
        assert config.llm_provider == "OpenAI"
        assert config.icbc_token == ""
        assert config.icbc_user_id == ""
        assert config.icbc_endpoint == ""

    def test_icbc_mode_missing_fields_raises(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """ICBC 模式下三凭证字段任一缺失 → ValueError，消息列缺失字段。"""
        for key in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(key, raising=False)
        with pytest.raises(ValueError) as exc_info:
            EvolveConfig(_env_file=None, llm_provider="ICBC", icbc_token="")
        msg = str(exc_info.value)
        # 三字段都缺失，均应列名
        assert "icbc_token" in msg
        assert "icbc_user_id" in msg
        assert "icbc_endpoint" in msg

    def test_icbc_mode_partial_missing_raises(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """ICBC 模式下仅缺 user_id → ValueError 只列 user_id。"""
        for key in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(key, raising=False)
        with pytest.raises(ValueError) as exc_info:
            EvolveConfig(
                _env_file=None,
                llm_provider="ICBC",
                icbc_token="t",
                icbc_endpoint="e",
            )
        assert "icbc_user_id" in str(exc_info.value)
        assert "icbc_token" not in str(exc_info.value)

    def test_icbc_mode_all_fields_present_ok(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """ICBC 模式三字段齐备 → 构造成功。"""
        for key in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(key, raising=False)
        config = EvolveConfig(
            _env_file=None,
            llm_provider="ICBC",
            icbc_token="t",
            icbc_user_id="u",
            icbc_endpoint="http://icbc/mlpmodelservice/aigc/chat/completions",
        )
        assert config.llm_provider == "ICBC"
        assert config.icbc_token == "t"

    def test_openai_mode_no_icbc_validation(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """OpenAI 默认模式不校验 ICBC 字段（空也通过）——行为不回归。"""
        for key in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(key, raising=False)
        # 显式 OpenAI + ICBC 字段全空
        config = EvolveConfig(_env_file=None, llm_provider="OpenAI")
        assert config.llm_provider == "OpenAI"

    def test_icbc_fields_from_env(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """EVO_LLM_PROVIDER/EVO_ICBC_* 环境变量被 pydantic-settings 正确读取。"""
        monkeypatch.setenv("EVO_LLM_PROVIDER", "ICBC")
        monkeypatch.setenv("EVO_ICBC_TOKEN", "env-token")
        monkeypatch.setenv("EVO_ICBC_USER_ID", "env-user")
        monkeypatch.setenv("EVO_ICBC_ENDPOINT", "http://env-icbc/svc.htm")
        config = EvolveConfig()
        assert config.llm_provider == "ICBC"
        assert config.icbc_token == "env-token"
        assert config.icbc_user_id == "env-user"
        assert config.icbc_endpoint == "http://env-icbc/svc.htm"

    def test_llm_provider_lowercase_normalized(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """``llm_provider='icbc'``（小写）归一为 ``ICBC`` 并触发 ICBC 校验。"""
        for key in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(key, raising=False)
        # 缺凭证 → 归一后仍 fail-fast
        with pytest.raises(ValueError):
            EvolveConfig(_env_file=None, llm_provider="icbc")
        # 齐备 → 归一为 ICBC
        config = EvolveConfig(
            _env_file=None,
            llm_provider="icbc",
            icbc_token="t",
            icbc_user_id="u",
            icbc_endpoint="http://icbc/svc.htm",
        )
        assert config.llm_provider == "ICBC"

    def test_llm_provider_unknown_falls_through_to_openai(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """未知 provider 不归一、不校验 ICBC 字段（保持现状，不静默走 ICBC）。"""
        for key in ("EVO_LLM_PROVIDER", "EVO_ICBC_TOKEN", "EVO_ICBC_USER_ID", "EVO_ICBC_ENDPOINT"):
            monkeypatch.delenv(key, raising=False)
        config = EvolveConfig(_env_file=None, llm_provider="azure")
        assert config.llm_provider == "azure"
