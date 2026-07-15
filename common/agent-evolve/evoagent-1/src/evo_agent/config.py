"""EvolveConfig — pydantic-settings 单例，集中管理所有配置。"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Any, Self

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class EvolveConfig(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_prefix="EVO_")

    # ── LLM ──
    llm_provider: str = "OpenAI"  # "OpenAI" | "ICBC"，EVO_LLM_PROVIDER
    llm_api_key: str = ""
    llm_base_url: str = "https://api.openai.com/v1"
    optimizer_model: str = "gpt-4o"
    target_model: str = "gpt-4o"
    # ICBC 内网 provider 凭证（仅 llm_provider=="ICBC" 时必填）
    icbc_token: str = ""  # EVO_ICBC_TOKEN（JWT，走 Secret）
    icbc_user_id: str = ""  # EVO_ICBC_USER_ID（固定值）
    icbc_endpoint: str = ""  # EVO_ICBC_ENDPOINT（chat/completions URL）
    icbc_timeout: float = 120.0  # EVO_ICBC_TIMEOUT，流式 read 超时（秒）

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

    # ── 路径 ──
    workspace_root: Path = Path("./workspace")
    output_root: Path = Path("./workspace/outputs")
    artifact_dir: Path = Path("./workspace/artifacts")

    # ── Wave 8: 平台 API 配置 ──
    adapter_url: str = ""  # EVO_ADAPTER_URL — Adapter sidecar 地址
    allowed_data_roots: list[Path] | str = [  # EVO_ALLOWED_DATA_ROOTS（逗号分隔）
        Path("/data/evo_agent"),
        Path("/tmp/evo_agent"),
    ]

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
        """ICBC 模式 fail-fast：llm_provider=="ICBC" 时三凭证字段必填。

        ``llm_provider`` 大小写归一（``icbc`` → ``ICBC``），避免填小写被
        静默走 OpenAI 默认路径。OpenAI 模式不校验 ICBC 字段，保持
        ``llm_api_key`` 允许空的现状。
        """
        provider = self.llm_provider.strip()
        if provider.casefold() == "icbc":
            object.__setattr__(self, "llm_provider", "ICBC")
            missing = [
                name
                for name in ("icbc_token", "icbc_user_id", "icbc_endpoint")
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

    @staticmethod
    @lru_cache
    def get() -> EvolveConfig:
        return EvolveConfig()
