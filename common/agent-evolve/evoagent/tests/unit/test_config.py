"""EvolveConfig 单元测试。"""

from __future__ import annotations

import inspect
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

    # --- CustomSSE provider 配置 ---

    def test_llm_provider_default_openai(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """llm_provider 默认 OpenAI，CustomSSE 字段默认空。"""
        for key in (
            "EVO_LLM_PROVIDER",
            "EVO_CUSTOM_SSE_TOKEN",
            "EVO_CUSTOM_SSE_USER_ID",
            "EVO_CUSTOM_SSE_ENDPOINT",
        ):
            monkeypatch.delenv(key, raising=False)
        config = EvolveConfig(_env_file=None)
        assert config.llm_provider == "OpenAI"
        assert config.custom_sse_token == ""
        assert config.custom_sse_user_id == ""
        assert config.custom_sse_endpoint == ""

    def test_custom_sse_mode_missing_fields_raises(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """CustomSSE 模式下三凭证字段任一缺失 → ValueError，消息列缺失字段。"""
        for key in (
            "EVO_LLM_PROVIDER",
            "EVO_CUSTOM_SSE_TOKEN",
            "EVO_CUSTOM_SSE_USER_ID",
            "EVO_CUSTOM_SSE_ENDPOINT",
        ):
            monkeypatch.delenv(key, raising=False)
        with pytest.raises(ValueError) as exc_info:
            EvolveConfig(_env_file=None, llm_provider="CustomSSE", custom_sse_token="")
        msg = str(exc_info.value)
        # 三字段都缺失，均应列名
        assert "custom_sse_token" in msg
        assert "custom_sse_user_id" in msg
        assert "custom_sse_endpoint" in msg

    def test_custom_sse_mode_partial_missing_raises(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """CustomSSE 模式下仅缺 user_id → ValueError 只列 user_id。"""
        for key in (
            "EVO_LLM_PROVIDER",
            "EVO_CUSTOM_SSE_TOKEN",
            "EVO_CUSTOM_SSE_USER_ID",
            "EVO_CUSTOM_SSE_ENDPOINT",
        ):
            monkeypatch.delenv(key, raising=False)
        with pytest.raises(ValueError) as exc_info:
            EvolveConfig(
                _env_file=None,
                llm_provider="CustomSSE",
                custom_sse_token="t",
                custom_sse_endpoint="e",
            )
        assert "custom_sse_user_id" in str(exc_info.value)
        assert "custom_sse_token" not in str(exc_info.value)

    def test_custom_sse_mode_all_fields_present_ok(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """CustomSSE 模式三字段齐备 → 构造成功。"""
        for key in (
            "EVO_LLM_PROVIDER",
            "EVO_CUSTOM_SSE_TOKEN",
            "EVO_CUSTOM_SSE_USER_ID",
            "EVO_CUSTOM_SSE_ENDPOINT",
        ):
            monkeypatch.delenv(key, raising=False)
        config = EvolveConfig(
            _env_file=None,
            llm_provider="CustomSSE",
            custom_sse_token="t",
            custom_sse_user_id="u",
            custom_sse_endpoint="https://llm-gateway.example.com/v1/chat/completions",
            custom_sse_context_window_tokens=32768,
        )
        assert config.llm_provider == "CustomSSE"
        assert config.custom_sse_token == "t"

    def test_custom_sse_mode_requires_declared_context_window(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """服务端选模型时不得猜测 OpenAI 默认上下文窗口。"""
        monkeypatch.delenv("EVO_CUSTOM_SSE_CONTEXT_WINDOW_TOKENS", raising=False)

        with pytest.raises(ValueError, match="custom_sse_context_window_tokens"):
            EvolveConfig(
                _env_file=None,
                llm_provider="CustomSSE",
                custom_sse_token="t",
                custom_sse_user_id="u",
                custom_sse_endpoint="https://llm-gateway.example.com/v1/chat/completions",
            )

    def test_openai_mode_no_custom_sse_validation(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """OpenAI 默认模式不校验 CustomSSE 字段（空也通过）——行为不回归。"""
        for key in (
            "EVO_LLM_PROVIDER",
            "EVO_CUSTOM_SSE_TOKEN",
            "EVO_CUSTOM_SSE_USER_ID",
            "EVO_CUSTOM_SSE_ENDPOINT",
        ):
            monkeypatch.delenv(key, raising=False)
        # 显式 OpenAI + CustomSSE 字段全空
        config = EvolveConfig(_env_file=None, llm_provider="OpenAI")
        assert config.llm_provider == "OpenAI"

    def test_custom_sse_fields_from_env(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """EVO_LLM_PROVIDER/EVO_CUSTOM_SSE_* 环境变量被 pydantic-settings 正确读取。"""
        monkeypatch.setenv("EVO_LLM_PROVIDER", "CustomSSE")
        monkeypatch.setenv("EVO_CUSTOM_SSE_TOKEN", "env-token")
        monkeypatch.setenv("EVO_CUSTOM_SSE_USER_ID", "env-user")
        monkeypatch.setenv(
            "EVO_CUSTOM_SSE_ENDPOINT", "https://env-gateway.example.com/v1/chat/completions"
        )
        monkeypatch.setenv("EVO_CUSTOM_SSE_CONTEXT_WINDOW_TOKENS", "32768")
        config = EvolveConfig()
        assert config.llm_provider == "CustomSSE"
        assert config.custom_sse_token == "env-token"
        assert config.custom_sse_user_id == "env-user"
        assert config.custom_sse_endpoint == "https://env-gateway.example.com/v1/chat/completions"

    def test_llm_provider_lowercase_normalized(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """``llm_provider='custom_sse'``（小写）归一为 ``CustomSSE`` 并触发 CustomSSE 校验。"""
        for key in (
            "EVO_LLM_PROVIDER",
            "EVO_CUSTOM_SSE_TOKEN",
            "EVO_CUSTOM_SSE_USER_ID",
            "EVO_CUSTOM_SSE_ENDPOINT",
        ):
            monkeypatch.delenv(key, raising=False)
        # 缺凭证 → 归一后仍 fail-fast
        with pytest.raises(ValueError):
            EvolveConfig(_env_file=None, llm_provider="custom_sse")
        # 齐备 → 归一为 CustomSSE
        config = EvolveConfig(
            _env_file=None,
            llm_provider="custom_sse",
            custom_sse_token="t",
            custom_sse_user_id="u",
            custom_sse_endpoint="https://llm-gateway.example.com/v1/chat/completions",
            custom_sse_context_window_tokens=32768,
        )
        assert config.llm_provider == "CustomSSE"

    def test_llm_provider_unknown_falls_through_to_openai(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """未知 provider 不归一、不校验 CustomSSE 字段（保持现状，不静默走 CustomSSE）。"""
        for key in (
            "EVO_LLM_PROVIDER",
            "EVO_CUSTOM_SSE_TOKEN",
            "EVO_CUSTOM_SSE_USER_ID",
            "EVO_CUSTOM_SSE_ENDPOINT",
        ):
            monkeypatch.delenv(key, raising=False)
        config = EvolveConfig(_env_file=None, llm_provider="azure")
        assert config.llm_provider == "azure"

    # --- managed-doc 配置 DTO ---

    def test_managed_doc_apply_deadline_default(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """managed_doc_apply_deadline 默认 600s（spec 部署契约）。"""
        monkeypatch.delenv("EVO_MANAGED_DOC_APPLY_DEADLINE", raising=False)
        config = EvolveConfig(_env_file=None)
        assert config.managed_doc_apply_deadline == 600.0

    def test_managed_doc_apply_deadline_from_env(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """EVO_MANAGED_DOC_APPLY_DEADLINE 环境变量注入。"""
        monkeypatch.setenv("EVO_MANAGED_DOC_APPLY_DEADLINE", "1200.0")
        monkeypatch.setenv("EVO_MANAGED_DOC_CANCEL_ROLLBACK_DEADLINE", "1500.0")
        config = EvolveConfig()
        assert config.managed_doc_apply_deadline == 1200.0

    def test_cancel_rollback_deadline_must_exceed_apply_deadline(self) -> None:
        with pytest.raises(ValueError, match="cancel_rollback_deadline"):
            EvolveConfig(
                _env_file=None,
                managed_doc_apply_deadline=120.0,
                managed_doc_cancel_rollback_deadline=120.0,
            )

    def test_managed_doc_protected_sections_default_empty(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """managed_doc_protected_sections 默认空 dict。"""
        monkeypatch.delenv("EVO_MANAGED_DOC_PROTECTED_SECTIONS", raising=False)
        config = EvolveConfig(_env_file=None)
        assert config.managed_doc_protected_sections == {}

    def test_managed_doc_protected_sections_from_env_json(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """EVO_MANAGED_DOC_PROTECTED_SECTIONS JSON 注入：dict[str, list[ProtectedSectionConfig]]，
        key 为精确 doc_kind。"""
        monkeypatch.setenv(
            "EVO_MANAGED_DOC_PROTECTED_SECTIONS",
            '{"agent_rule": [{"start_marker": "<a>", "end_marker": "</a>"}]}',
        )
        config = EvolveConfig()
        sections = config.managed_doc_protected_sections["agent_rule"]
        assert len(sections) == 1
        assert sections[0].start_marker == "<a>"
        assert sections[0].end_marker == "</a>"

    def test_managed_doc_content_policies_default_empty(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """managed_doc_content_policies 默认空 dict；缺省 selector 在 runner 侧默认 preserving。"""
        monkeypatch.delenv("EVO_MANAGED_DOC_CONTENT_POLICIES", raising=False)
        config = EvolveConfig(_env_file=None)
        assert config.managed_doc_content_policies == {}

    def test_managed_doc_content_policies_from_env_json(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """EVO_MANAGED_DOC_CONTENT_POLICIES JSON 注入：dict[str, Literal[...]]。"""
        monkeypatch.setenv(
            "EVO_MANAGED_DOC_CONTENT_POLICIES",
            '{"agent_rule": "passthrough", "other": "preserving"}',
        )
        config = EvolveConfig()
        assert config.managed_doc_content_policies == {
            "agent_rule": "passthrough",
            "other": "preserving",
        }

    def test_managed_doc_content_policies_rejects_invalid_literal(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """content_policies 值只能是 preserving/passthrough，非法值 fail-fast。"""
        monkeypatch.setenv(
            "EVO_MANAGED_DOC_CONTENT_POLICIES",
            '{"agent_rule": "merge"}',
        )
        with pytest.raises(Exception):
            EvolveConfig()

    def test_protected_section_config_immutable(self) -> None:
        """ProtectedSectionConfig immutable（frozen），字段不可变更。"""
        from evo_agent.config import ProtectedSectionConfig

        ps = ProtectedSectionConfig(start_marker="<a>", end_marker="</a>")
        with pytest.raises(Exception):
            ps.start_marker = "<b>"  # type: ignore[misc]

    def test_config_does_not_import_adapter_client(self) -> None:
        """config.py 是叶子模块，不反向依赖 adapter_client。

        检查 AST 的 import 节点（而非源码子串），允许注释/docstring 提及
        adapter_client 但不允许实际 import。
        """
        import ast

        from evo_agent import config as config_module

        tree = ast.parse(inspect.getsource(config_module))
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                for alias in node.names:
                    assert not alias.name.startswith("evo_agent.adapter_client"), (
                        f"config.py 禁止 import adapter_client: {alias.name}"
                    )
            elif isinstance(node, ast.ImportFrom):
                mod = node.module or ""
                assert mod != "evo_agent.adapter_client" and not mod.startswith(
                    "evo_agent.adapter_client."
                ), f"config.py 禁止 from-import adapter_client: {mod}"
