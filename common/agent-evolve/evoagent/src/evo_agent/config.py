"""EvolveConfig — pydantic-settings 单例，集中管理所有配置。"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class ProtectedSectionConfig(BaseModel):
    """managed-doc 受保护区段配置 DTO（叶子 config.py 定义，不导入 adapter_client）。

    runner 把它映射为 adapter_client 侧的 frozen ``ProtectedSection``。一对
    ``(start_marker, end_marker)`` 在 baseline 中圈定一段必须原样保留的原文；
    marker 缺失/重复/交叉/嵌套由 ContentPolicy 初始化时 fail-fast。
    """

    model_config = ConfigDict(frozen=True)

    start_marker: str
    end_marker: str


class EvolveConfig(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="EVO_")

    # ── LLM ──
    llm_provider: str = "OpenAI"  # "OpenAI" | "ICBC"，EVO_LLM_PROVIDER
    llm_api_key: str = ""
    llm_base_url: str = "https://api.openai.com/v1"
    optimizer_model: str = "gpt-4o"
    target_model: str = "gpt-4o"
    # LLM HTTP 调用超时（秒）。大 prompt / 慢模型需调大（对齐 bank 150 / 建议 300）。
    # openjiuwen Model 默认 60s 易 408。EVO_LLM_TIMEOUT。
    llm_timeout: float = 300.0
    # ICBC 内网 provider 凭证（仅 llm_provider=="ICBC" 时必填）
    icbc_token: str = ""  # EVO_ICBC_TOKEN（JWT，走 Secret）
    icbc_user_id: str = ""  # EVO_ICBC_USER_ID（固定值）
    icbc_endpoint: str = ""  # EVO_ICBC_ENDPOINT（chat/completions URL）
    icbc_timeout: float = 120.0  # EVO_ICBC_TIMEOUT，流式 read 超时（秒）
    icbc_context_window_tokens: int | None = None
    icbc_output_reserve_tokens: int = 2048
    icbc_chars_per_token: float = 2.0
    icbc_completion_signal: Literal["done", "eof", "either"] = "done"

    # Local prompt/output planning applies to every provider and stage.
    llm_context_window_tokens: int = 32768
    llm_output_reserve_tokens: int = 2048
    llm_safety_margin_tokens: int = 512
    llm_chars_per_token: float = 2.0
    llm_stage_output_reserve_tokens: dict[str, int] = Field(
        default_factory=lambda: {"evaluator": 1200, "reflect": 3000}
    )

    # ── 远程通信（AdapterClient 使用） ──
    remote_timeout: float = 300.0
    remote_max_retries: int = 2
    remote_parallel: int = 4

    # ── 优化超参 ──
    default_epochs: int = 3
    default_batch_size: int = 4
    accumulation: int = 2
    minibatch_size: int = 8
    edit_budget: int = 10
    scheduler_mode: str = "constant"  # constant | linear | cosine
    update_mode: str = "patch"  # 首轮仅 patch
    use_slow_update: bool = True
    use_meta_skill: bool = True
    # ── Frontmatter 优化开关 ──
    # True（默认）：写回冻结 frontmatter + LLM 反思输入 strip frontmatter（body-only）；
    # False：frontmatter 全程参与（LLM 可见、可被 edit 改动并回写）。EVO_PRESERVE_FRONTMATTER
    preserve_frontmatter: bool = True
    score_threshold: float = 0.5
    parallelism: int = 4
    validation_max_case_attempts: int = 2
    validation_min_success_ratio: float = 1.0
    validation_require_same_case_set: bool = True

    # ── 路径 ──
    workspace_root: Path = Path("./workspace")
    output_root: Path = Path("./workspace/outputs")
    artifact_dir: Path = Path("./workspace/artifacts")
    evoagent_control_db_path: Path = Field(
        default=Path("./workspace/evoagent-control.db"),
        validation_alias="EVOAGENT_CONTROL_DB_PATH",
    )
    # golden_data 工具的持久化知识库根：离线建 GU 的最终产物（global_understanding/
    # 下 index.md / system_wide.md / per_skill/<skill>.md 等）落此，多次 build 累积/覆盖。
    # builder 的 run workspace（中间结果）仍走 artifact_dir，与此分离。EVO_GOLDEN_DATA_DIR。
    golden_data_dir: Path = Path("./workspace/golden_data")

    # ── Wave 8: 平台 API 配置 ──
    adapter_url: str = ""  # EVO_ADAPTER_URL — Adapter sidecar 地址
    allowed_data_roots: list[Path] | str = [  # EVO_ALLOWED_DATA_ROOTS（逗号分隔）
        Path("/data/evo_agent"),
        Path("/tmp/evo_agent"),
    ]

    # ── managed-doc 单文档优化 ──
    # EVO_MANAGED_DOC_APPLY_DEADLINE：apply 同步等待总时限（秒），默认 600s。
    # job-start 校验 deadline ≥ max_task_seconds + 10s（runner F7）。
    managed_doc_apply_deadline: float = 600.0
    # Must exceed Adapter apply deadline; includes in-flight completion + rollback.
    managed_doc_cancel_rollback_deadline: float = 900.0
    # EVO_MANAGED_DOC_PROTECTED_SECTIONS（JSON）：dict[doc_kind, list[ProtectedSectionConfig]]，
    # key 为精确 doc_kind。默认空（无受保护区段）。runner 映射为 adapter_client ProtectedSection。
    managed_doc_protected_sections: dict[str, list[ProtectedSectionConfig]] = Field(
        default_factory=dict
    )
    # EVO_MANAGED_DOC_CONTENT_POLICIES（JSON）：dict[doc_kind, "preserving"|"passthrough"]。
    # 缺省 kind 走 preserving（runner 侧 selector 默认）。
    managed_doc_content_policies: dict[str, Literal["preserving", "passthrough"]] = Field(
        default_factory=dict
    )

    @model_validator(mode="before")
    @classmethod
    def _parse_data_roots(cls, data: Any) -> Any:
        """解析逗号分隔的路径字符串为 list[Path]，并 resolve 以处理 symlink。"""
        if isinstance(data, dict):
            v = data.get("allowed_data_roots")
            if isinstance(v, str):
                data["allowed_data_roots"] = [
                    Path(p.strip()).resolve() for p in v.split(",") if p.strip()
                ]
            elif isinstance(v, list):
                data["allowed_data_roots"] = [
                    p.resolve() if isinstance(p, Path) else Path(p).resolve() for p in v
                ]
        return data

    @model_validator(mode="after")
    def _validate_icbc_config(self) -> Self:
        """ICBC 模式 fail-fast：provider 凭证与 context window 必填。

        ``llm_provider`` 大小写归一（``icbc`` → ``ICBC``），避免填小写被
        静默走 OpenAI 默认路径。OpenAI 模式不校验 ICBC 字段，保持
        ``llm_api_key`` 允许空的现状。
        """
        provider = self.llm_provider.strip()
        if provider.casefold() == "icbc":
            object.__setattr__(self, "llm_provider", "ICBC")
            missing = [
                name
                for name in (
                    "icbc_token",
                    "icbc_user_id",
                    "icbc_endpoint",
                    "icbc_context_window_tokens",
                )
                if not getattr(self, name)
            ]
            if missing:
                raise ValueError(
                    f"ICBC 模式下必填字段缺失: {missing}"
                    "（设 llm_provider='OpenAI' 或补齐 EVO_ICBC_*）"
                )
        else:
            object.__setattr__(self, "llm_provider", provider)
        return self

    @model_validator(mode="after")
    def _validate_managed_doc_deadlines(self) -> Self:
        if self.managed_doc_cancel_rollback_deadline <= self.managed_doc_apply_deadline:
            raise ValueError(
                "managed_doc_cancel_rollback_deadline must exceed managed_doc_apply_deadline"
            )
        return self

    @staticmethod
    @lru_cache
    def get() -> EvolveConfig:
        return EvolveConfig()
