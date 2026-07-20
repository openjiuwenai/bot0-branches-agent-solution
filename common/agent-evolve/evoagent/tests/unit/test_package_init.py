"""Package-level import tests — 验证公共 API 和废弃警告。"""

from __future__ import annotations

import warnings


def test_create_evo_agent_deprecation_warning() -> None:
    """访问 evo_agent.create_evo_agent 时发出 DeprecationWarning。

    Wave 3 中 create_evo_agent 已被 AdapterClient + RemoteAgent 替代，
    但保留向后兼容导出（Wave 4.5 移除）。
    """
    import evo_agent

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        func = evo_agent.create_evo_agent  # noqa: F841

    deprecation_warnings = [w for w in caught if issubclass(w.category, DeprecationWarning)]
    assert len(deprecation_warnings) == 1
    assert "create_evo_agent" in str(deprecation_warnings[0].message)
    assert "AdapterClient" in str(deprecation_warnings[0].message)
