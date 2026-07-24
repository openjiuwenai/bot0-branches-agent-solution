"""时延掩盖测试用例自动化脚本（TC-01 ~ TC-24）。

覆盖范围：fixed_script_feeder.py + agent_rule.py（FixedScriptsConfig / ScriptsConfigData）。
依赖 EDPAgent/tests/conftest.py 的 sys.modules stub 隔离。

与 docs/时延掩盖测试用例.md 一一对应。
"""
from __future__ import annotations

import math
import textwrap
from pathlib import Path

import pytest


# ═══════════════════════════════════════════════════════════════
# 辅助：构造最小可用的 FixedScriptsConfig
# ═══════════════════════════════════════════════════════════════

def _cfg(**kwargs):
    from EDPAgent.agent_rule import FixedScriptsConfig

    defaults = dict(
        scripts=[], default_scripts=[], execution_scripts=[],
        resume_scripts=[], query_patterns=[], chars_per_frame=2,
        tokens_between_frames=1, min_interval_ms=0,
        enable_resume_scripts=True,
    )
    defaults.update(kwargs)
    return FixedScriptsConfig(**defaults)


# ═══════════════════════════════════════════════════════════════
# 2. 字符切片测试 (TC-01 ~ TC-04)
# ═══════════════════════════════════════════════════════════════

class TestSplitScriptsIntoFrames:
    """TC-01 ~ TC-04"""

    def test_tc01_basic_chinese(self):
        """TC-01：基本中文字符切片。"""
        from EDPAgent.fixed_script_feeder import split_scripts_into_frames

        s = "正在为您分析理财产品..."
        frames = split_scripts_into_frames([s], 4)
        assert "".join(frames) == s
        assert all(len(f) <= 4 for f in frames)
        assert len(frames) == math.ceil(len(s) / 4)

    def test_tc02_zero_defense(self):
        """TC-02：chars_per_frame=0 防御。"""
        from EDPAgent.fixed_script_feeder import split_scripts_into_frames

        assert split_scripts_into_frames(["Hello", "World"], 0) == ["Hello", "World"]

    def test_tc03_negative_defense(self):
        """TC-03：chars_per_frame<0 防御。"""
        from EDPAgent.fixed_script_feeder import split_scripts_into_frames

        assert split_scripts_into_frames(["Hi"], -1) == ["Hi"]

    def test_tc04_empty_list(self):
        """TC-04：空列表。"""
        from EDPAgent.fixed_script_feeder import split_scripts_into_frames

        assert split_scripts_into_frames([], 4) == []


# ═══════════════════════════════════════════════════════════════
# 3. 阶段话术选择测试 (TC-05 ~ TC-10)
# ═══════════════════════════════════════════════════════════════

class TestSelectFixedScripts:
    """TC-05 ~ TC-10"""

    def test_tc05_planning_query_match(self):
        """TC-05：planning 阶段 query 匹配。"""
        from EDPAgent.fixed_script_feeder import select_fixed_scripts
        from EDPAgent.agent_rule import QueryPatternScripts

        cfg = _cfg(query_patterns=[QueryPatternScripts(
            keywords=["推荐", "看看"],
            scripts=["正在为您搜索相关内容..."],
        )])
        out = select_fixed_scripts(
            "推荐一些产品看看", cfg,
            is_resume=False, is_first_thinking_round=True,
        )
        assert out == ["正在为您搜索相关内容..."]

    def test_tc06_planning_no_match_default(self):
        """TC-06：planning 无匹配走 default。"""
        from EDPAgent.fixed_script_feeder import select_fixed_scripts
        from EDPAgent.agent_rule import QueryPatternScripts

        cfg = _cfg(
            query_patterns=[QueryPatternScripts(
                keywords=["推荐"], scripts=["推荐话术"],
            )],
            default_scripts=["默认话术"],
        )
        out = select_fixed_scripts(
            "我想转账", cfg,
            is_resume=False, is_first_thinking_round=True,
        )
        assert out == ["默认话术"]

    def test_tc07_executing_stage(self):
        """TC-07：executing 阶段。"""
        from EDPAgent.fixed_script_feeder import select_fixed_scripts

        cfg = _cfg(execution_scripts=["正在分析执行结果..."], scripts=["兜底"])
        out = select_fixed_scripts(
            "anything", cfg,
            is_resume=False, is_first_thinking_round=False,
        )
        assert out == ["正在分析执行结果..."]

    def test_tc08_resuming_4_level_fallback(self):
        """TC-08：resuming 启用 4 级降级链。"""
        from EDPAgent.fixed_script_feeder import select_fixed_scripts

        # Level 1: resume
        cfg = _cfg(
            resume_scripts=["R"], execution_scripts=["E"],
            default_scripts=["D"], scripts=["S"],
        )
        assert select_fixed_scripts("c", cfg, is_resume=True) == ["R"]

        # Level 2: execution
        cfg2 = _cfg(execution_scripts=["E"], default_scripts=["D"], scripts=["S"])
        assert select_fixed_scripts("c", cfg2, is_resume=True) == ["E"]

        # Level 3: default
        cfg3 = _cfg(default_scripts=["D"], scripts=["S"])
        assert select_fixed_scripts("c", cfg3, is_resume=True) == ["D"]

        # Level 4: scripts
        cfg4 = _cfg(scripts=["S"])
        assert select_fixed_scripts("c", cfg4, is_resume=True) == ["S"]

    def test_tc09_resuming_disabled_short_circuit(self):
        """TC-09：resuming 关闭短路返回 []。"""
        from EDPAgent.fixed_script_feeder import select_fixed_scripts

        cfg = _cfg(
            resume_scripts=["R"], execution_scripts=["E"],
            default_scripts=["D"], scripts=["S"],
            enable_resume_scripts=False,
        )
        out = select_fixed_scripts("continue", cfg, is_resume=True)
        assert out == []

    def test_tc10_executing_fallback_chain(self):
        """TC-10：is_first_thinking_round=False 且非 resume 走 executing 降级链。"""
        from EDPAgent.fixed_script_feeder import select_fixed_scripts

        # execution first
        cfg1 = _cfg(execution_scripts=["执行中"], default_scripts=["默认"], scripts=["兜底"])
        assert select_fixed_scripts("x", cfg1, is_resume=False, is_first_thinking_round=False) == ["执行中"]

        # then default
        cfg2 = _cfg(default_scripts=["默认"], scripts=["兜底"])
        assert select_fixed_scripts("x", cfg2, is_resume=False, is_first_thinking_round=False) == ["默认"]

        # finally scripts
        cfg3 = _cfg(scripts=["兜底"])
        assert select_fixed_scripts("x", cfg3, is_resume=False, is_first_thinking_round=False) == ["兜底"]


# ═══════════════════════════════════════════════════════════════
# 4. FixedScriptFeeder 状态机测试 (TC-11 ~ TC-17)
# ═══════════════════════════════════════════════════════════════

class TestFixedScriptFeeder:
    """TC-11 ~ TC-17"""

    def _feeder(self, **kwargs):
        from EDPAgent.fixed_script_feeder import FixedScriptFeeder

        defaults = dict(
            scripts=["AAA", "BBB", "CCC"],
            tokens_between_frames=2,
            chars_per_frame=3,
            min_interval_ms=0,
        )
        defaults.update(kwargs)
        return FixedScriptFeeder(_cfg(**defaults))

    def test_tc11_threshold_not_reached(self):
        """TC-11：阈值未达不发帧。"""
        feeder = self._feeder()
        assert feeder.feed_token(1) == []

    def test_tc12_threshold_reached_push_frame(self):
        """TC-12：阈值达推送帧。"""
        feeder = self._feeder()
        assert feeder.feed_token(1) == []          # 1 < 2
        assert feeder.feed_token(1) == ["AAA"]     # 2 == 2
        assert feeder.feed_token(2) == ["BBB"]
        assert feeder.feed_token(2) == ["CCC"]

    def test_tc13_r1_silent_after_all_sent(self):
        """TC-13：R1 全部发完后再调返回 []。"""
        feeder = self._feeder()
        feeder.feed_token(2)  # AAA
        feeder.feed_token(2)  # BBB
        feeder.feed_token(2)  # CCC
        assert feeder.all_sent is True
        assert feeder.feed_token(10) == []

    def test_tc14_drain_all(self):
        """TC-14：drain_all 立即清空剩余帧。"""
        feeder = self._feeder()
        feeder.feed_token(2)  # AAA
        remaining = feeder.drain_all()
        assert remaining == ["BBB", "CCC"]
        assert feeder.all_sent is True
        assert feeder.feed_token(10) == []

    def test_tc15_drain_all_bypasses_throttle(self):
        """TC-15：drain_all 不受 min_interval_ms 约束。"""
        feeder = self._feeder(
            scripts=["X", "Y", "Z"],
            tokens_between_frames=1, chars_per_frame=1,
            min_interval_ms=1000,
        )
        feeder.feed_token(1)  # X
        remaining = feeder.drain_all()
        assert remaining == ["Y", "Z"]

    def test_tc16_all_sent_property(self):
        """TC-16：all_sent 属性。"""
        feeder = self._feeder()
        assert feeder.all_sent is False
        feeder.feed_token(2)  # AAA
        feeder.feed_token(2)  # BBB
        feeder.feed_token(2)  # CCC
        assert feeder.all_sent is True
        feeder2 = self._feeder()
        feeder2.feed_token(2)  # AAA
        feeder2.drain_all()
        assert feeder2.all_sent is True

    def test_tc17_mark_llm_ended_flag_only(self):
        """TC-17：mark_llm_ended 仅设标志。"""
        feeder = self._feeder()
        feeder.feed_token(2)  # AAA
        feeder.feed_token(2)  # BBB
        feeder.feed_token(2)  # CCC
        feeder.mark_llm_ended()
        # 行为由 all_sent 决定，不受标志影响
        assert feeder.feed_token(10) == []


# ═══════════════════════════════════════════════════════════════
# 5. 时间节流测试 (TC-18 ~ TC-19)
# ═══════════════════════════════════════════════════════════════

class TestMinIntervalThrottle:
    """TC-18 ~ TC-19"""

    def test_tc18_throttle_interval_not_reached(self, monkeypatch):
        """TC-18：间隔不足不发帧。"""
        from EDPAgent import fixed_script_feeder as mod
        from EDPAgent.fixed_script_feeder import FixedScriptFeeder

        fake_now = {"v": 1000.0}
        monkeypatch.setattr(mod.time, "monotonic", lambda: fake_now["v"])

        cfg = _cfg(
            scripts=["X", "Y"], tokens_between_frames=1,
            chars_per_frame=1, min_interval_ms=100,
        )
        feeder = FixedScriptFeeder(cfg)

        out1 = feeder.feed_token(1)
        assert out1 == ["X"]

        # +50ms → 间隔不足
        fake_now["v"] += 0.05
        out2 = feeder.feed_token(1)
        assert out2 == []

    def test_tc19_throttle_interval_reached(self, monkeypatch):
        """TC-19：间隔足够正常发帧。"""
        from EDPAgent import fixed_script_feeder as mod
        from EDPAgent.fixed_script_feeder import FixedScriptFeeder

        fake_now = {"v": 1000.0}
        monkeypatch.setattr(mod.time, "monotonic", lambda: fake_now["v"])

        cfg = _cfg(
            scripts=["X", "Y"], tokens_between_frames=1,
            chars_per_frame=1, min_interval_ms=100,
        )
        feeder = FixedScriptFeeder(cfg)

        out1 = feeder.feed_token(1)
        assert out1 == ["X"]

        # +50ms → 不足
        fake_now["v"] += 0.05
        out2 = feeder.feed_token(1)
        assert out2 == []

        # +100ms more → 足够
        fake_now["v"] += 0.10
        out3 = feeder.feed_token(1)
        assert out3 == ["Y"]
        assert feeder.all_sent is True


# ═══════════════════════════════════════════════════════════════
# 6. 配置模型测试 (TC-20 ~ TC-24)
# ═══════════════════════════════════════════════════════════════

class TestConfigModels:
    """TC-20 ~ TC-24"""

    def test_tc20_enable_resume_scripts_default_true(self):
        """TC-20：FixedScriptsConfig enable_resume_scripts 默认 True。"""
        from EDPAgent.agent_rule import FixedScriptsConfig

        cfg = FixedScriptsConfig()
        assert cfg.enable_resume_scripts is True

    def test_tc21_load_missing_enable_resume_scripts(self, tmp_path):
        """TC-21：ScriptsConfigData 解析缺 enable_resume_scripts 字段。"""
        from EDPAgent.agent_rule import load_scripts_config

        p = tmp_path / "ScriptsConfig.md"
        p.write_text(
            textwrap.dedent("""\
            ---
            think_chunk_mode: fixed_script
            think_chunk_fixed_scripts:
              scripts: []
              default_scripts: []
              execution_scripts: []
              resume_scripts: []
              query_patterns: []
              chars_per_frame: 2
              tokens_between_frames: 1
              min_interval_ms: 0
              # 注意：故意不写 enable_resume_scripts
            ---
            # body
            """),
            encoding="utf-8",
        )
        data = load_scripts_config(p)
        assert data.think_chunk_fixed_scripts.enable_resume_scripts is True

    def test_tc22_think_chunk_mode_field(self, tmp_path):
        """TC-22：ScriptsConfigData think_chunk_mode 字段。"""
        from EDPAgent.agent_rule import load_scripts_config, ThinkChunkMode

        p = tmp_path / "ScriptsConfig.md"
        p.write_text(
            textwrap.dedent("""\
            ---
            think_chunk_mode: fixed_script
            think_chunk_fixed_scripts:
              scripts: []
              default_scripts: []
              execution_scripts: []
              resume_scripts: []
              query_patterns: []
            ---
            # body
            """),
            encoding="utf-8",
        )
        data = load_scripts_config(p)
        assert data.think_chunk_mode == ThinkChunkMode.FIXED_SCRIPT

    def test_tc23_full_field_construction(self):
        """TC-23：FixedScriptsConfig 完整字段构造。"""
        from EDPAgent.agent_rule import FixedScriptsConfig, QueryPatternScripts

        cfg = FixedScriptsConfig(
            scripts=["S1"], default_scripts=["D1"],
            execution_scripts=["E1"], resume_scripts=["R1"],
            query_patterns=[QueryPatternScripts(
                keywords=["k1"], scripts=["P1"],
            )],
            chars_per_frame=5, tokens_between_frames=3,
            min_interval_ms=200, enable_resume_scripts=False,
        )
        assert cfg.scripts == ["S1"]
        assert cfg.default_scripts == ["D1"]
        assert cfg.execution_scripts == ["E1"]
        assert cfg.resume_scripts == ["R1"]
        assert len(cfg.query_patterns) == 1
        assert cfg.chars_per_frame == 5
        assert cfg.tokens_between_frames == 3
        assert cfg.min_interval_ms == 200
        assert cfg.enable_resume_scripts is False

    def test_tc24_all_pools_empty_returns_empty(self):
        """TC-24：空场景所有池为空返回 []。"""
        from EDPAgent.fixed_script_feeder import select_fixed_scripts

        cfg = _cfg()  # 所有池均为空
        # planning
        assert select_fixed_scripts("test", cfg, is_resume=False, is_first_thinking_round=True) == []
        # executing
        assert select_fixed_scripts("test", cfg, is_resume=False, is_first_thinking_round=False) == []
        # resuming (enabled)
        assert select_fixed_scripts("test", cfg, is_resume=True) == []
