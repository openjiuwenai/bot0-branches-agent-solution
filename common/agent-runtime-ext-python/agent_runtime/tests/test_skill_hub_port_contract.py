# coding: utf-8

"""Skill Hub 端口契约判据（Feat-Func-005b §2.3、§6.2、附录 A.3）。

**期望值来源是独立事实源，不是被测代码**：

- 九个失败分类的取值与顺序，逐字取自上游扩展实现的枚举定义
  （`openJiuwen/agent-solution/common/agent-runtime-ext-java/agent-service-spec-ext/
  src/main/java/com/openjiuwen/service/spec/ext/skillhub/SkillHubErrorCategory.java`）；
- `LocalSkillEntry` 的两个字段名取自同目录 `dto/LocalSkillEntry.java` 的记录声明；
- `SkillHubConfig` 的字段集取自同目录 `SkillHubConfig.java`；
- 各默认值取自详设 §6.2 的配置属性表（该表逐行写明取值出处）。

**这组判据必须能红**：把分类枚举删掉一个取值、把 `encrypted_token` 的归一去掉、
或给 `complete` 补一个默认值，对应的判据立刻失败。
"""
from __future__ import annotations

import dataclasses
from pathlib import Path

import pytest

from agent_runtime.ports.secret import SecretValue
from agent_runtime.ports.skill_hub import (
    LocalSkillEntry,
    SkillDownloadReport,
    SkillHubConfig,
    SkillHubError,
    SkillHubErrorCategory,
    SkillHubFetchConfig,
    SkillHubProvider,
    SkillHubRetryConfig,
    SkillMaterialInstaller,
    SkillTargetResolver,
)

#: 上游枚举的九个取值，**按其声明顺序逐字抄录**。附录 A.3：取值集合是跨语言互通的
#: 词汇表，按「我方用不上」裁剪会让两侧说不到一起去。
_UPSTREAM_CATEGORIES = (
    "CONNECT_FAILED",
    "AUTH_FAILED",
    "ACCESS_DENIED",
    "NOT_FOUND",
    "DOWNLOAD_FAILED",
    "CHECKSUM_MISMATCH",
    "INSTALL_FAILED",
    "UNSUPPORTED",
    "UNKNOWN",
)


def test_error_category_matches_upstream_verbatim():
    """九个分类取值与上游逐字一致，一个不多一个不少（详设 §1.4 术语对齐表）。"""
    assert tuple(c.name for c in SkillHubErrorCategory) == _UPSTREAM_CATEGORIES


def test_error_carries_category_and_reason():
    """失败以枚举承载分类，调用方读枚举分支而不解析文本（详设 §8 开篇）。"""
    err = SkillHubError(SkillHubErrorCategory.AUTH_FAILED, "status=401")
    assert isinstance(err, RuntimeError)
    assert err.category is SkillHubErrorCategory.AUTH_FAILED
    assert err.reason == "status=401"


def test_local_skill_entry_has_exactly_two_fields():
    """材料条目字段面与上游同名记录逐字一致，不增字段（详设 §2.3.2）。"""
    names = tuple(f.name for f in dataclasses.fields(LocalSkillEntry))
    assert names == ("skill_id", "local_path")
    entry = LocalSkillEntry(skill_id="asset-1", local_path=Path("/tmp/x"))
    assert entry.local_path == Path("/tmp/x")


def test_download_report_requires_explicit_complete():
    """`complete` 无默认值——实现者必须显式给出（详设 §2.3.2 字段级约束表）。

    有默认值时，忘了赋值的实现会以「默认成功」的形态混过去，而调用方据它
    决定要不要启动后台重试。
    """
    with pytest.raises(TypeError):
        SkillDownloadReport(entries=())  # type: ignore[call-arg]
    report = SkillDownloadReport(entries=(), complete=False)
    assert report.entries == ()
    assert report.complete is False


def test_config_defaults_match_design_table():
    """配置默认值逐项对准详设 §6.2 的属性表。"""
    cfg = SkillHubConfig()
    assert cfg.enabled is False          # 默认关闭：未配置即整条链路不装配
    assert cfg.endpoint == ""
    assert cfg.auth_type == "bearer"     # 2026-07-21 上游由 system-token 改为 bearer
    assert cfg.local_dir == ""
    assert cfg.provider == ""
    assert cfg.fetch == SkillHubFetchConfig()
    assert cfg.retry == SkillHubRetryConfig()


def test_fetch_and_retry_defaults_match_design_table():
    """取材与重试参数的默认值逐项对准详设 §6.2。"""
    fetch = SkillHubFetchConfig()
    assert (fetch.page_size, fetch.concurrency) == (200, 4)
    assert (fetch.connect_timeout_s, fetch.request_timeout_s) == (10.0, 30.0)
    assert fetch.download_timeout_s == 600.0
    retry = SkillHubRetryConfig()
    assert (retry.initial_delay_s, retry.period_s, retry.max_attempts) == (5.0, 30.0, 120)


def test_encrypted_token_is_masked_type_and_normalized():
    """凭据以掩码类型承载，且裸字符串在构造时被归一（详设 §6.3、P4）。

    **归一不可省**：类型标注拦不住运行期传进来的裸字符串，而配置加载器正是
    最可能传裸字符串的地方；少了归一，脱敏就只是「调用方配合才生效」的约定。
    """
    secret = "ciphertext-must-never-appear-in-logs"
    cfg = SkillHubConfig(enabled=True, encrypted_token=secret)  # type: ignore[arg-type]
    assert isinstance(cfg.encrypted_token, SecretValue)
    assert cfg.encrypted_token.reveal() == secret
    # V11：配置对象经任何字符串化路径都不得吐出密文
    assert secret not in repr(cfg)
    assert secret not in str(cfg)
    assert secret not in f"装配失败：{cfg}"
    assert secret not in f"{cfg.encrypted_token}"


def test_config_is_immutable():
    """配置不可变：装配后被某个组件改掉，其余组件读到的值就取决于调用顺序。"""
    cfg = SkillHubConfig()
    with pytest.raises(dataclasses.FrozenInstanceError):
        cfg.endpoint = "https://example.invalid"  # type: ignore[misc]


def test_protocols_are_runtime_checkable_with_full_method_face():
    """三个端口都是运行期可检查协议，方法面与详设 §2.3.1 一致。"""
    assert set(SkillHubProvider.__protocol_attrs__) == {  # type: ignore[attr-defined]
        "start", "download", "verify", "stop",
    }
    assert set(SkillMaterialInstaller.__protocol_attrs__) == {  # type: ignore[attr-defined]
        "install",
    }
    assert set(SkillTargetResolver.__protocol_attrs__) == {  # type: ignore[attr-defined]
        "resolve_skill_target",
    }


def test_ports_module_has_no_framework_dependency():
    """端口层零框架依赖（详设 §3.3）：只许标准库与本仓内层。

    这条与依赖方向门禁同源，在判据里再锁一次的理由：门禁读的是全仓扫描结果，
    而这一条是本模块自己的契约，改坏它时应当在本组判据里当场转红。
    """
    import agent_runtime.ports.skill_hub as mod

    source = Path(mod.__file__).read_text(encoding="utf-8")
    for banned in ("import httpx", "import redis", "import pydantic", "from a2a"):
        assert banned not in source
