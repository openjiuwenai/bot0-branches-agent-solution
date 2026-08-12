"""
Phase3 集成测试：验证话术查找链完整性与场景切换功能。

测试范围：
1. ScriptsConfig.get_response_template() 两级查找机制
2. collect_skill_scripts() 从 SKILL.md 收集业务话术
3. load_scenario_config() 加载场景配置
4. 场景切换：active_scenario 覆盖 AgentRuleConfig 内联配置
"""
from __future__ import annotations

import os
from pathlib import Path
from unittest.mock import patch

import pytest

# ── 确保 EDPAgent 可导入 ──────────────────────────────────────────────
_EDPAGENT_DIR = Path(__file__).resolve().parent.parent
if str(_EDPAGENT_DIR) not in os.sys.path:
    os.sys.path.insert(0, str(_EDPAGENT_DIR))


# ============================================================================
# 3.11 话术查找链完整性
# ============================================================================

class TestScriptLookupChain:
    """验证 get_response_template() 两级查找机制完整性。"""

    @staticmethod
    def test_common_script_found_via_named_field():
        """通用话术通过具名字段查找成功。"""
        from EDPAgent.agent_rule import ScriptsConfig

        cfg = ScriptsConfig()
        assert cfg.get_response_template("tool_start") == "正在调用：{tool_name}"
        assert cfg.get_response_template("task_cancelled") == "好的，已为您取消当前操作。如需其他帮助，请随时告诉我。"
        assert cfg.get_response_template("out_of_scope") == "正在学习中，暂不支持该业务。"

    @staticmethod
    def test_business_script_found_via_extra_scripts():
        """业务话术通过 extra_scripts dict 查找成功。"""
        from EDPAgent.agent_rule import ScriptsConfig

        cfg = ScriptsConfig(extra_scripts={
            "product_recommend_success": "我找到以上您可能感兴趣的产品",
            "fund_planning_buy_failed": "购买失败，请重新尝试",
        })
        assert cfg.get_response_template("product_recommend_success") == "我找到以上您可能感兴趣的产品"
        assert cfg.get_response_template("fund_planning_buy_failed") == "购买失败，请重新尝试"

    @staticmethod
    def test_common_field_takes_priority_over_extra_scripts():
        """当 extra_scripts 中存在与通用字段同名的 key 时，具名字段优先。"""
        from EDPAgent.agent_rule import ScriptsConfig

        cfg = ScriptsConfig(extra_scripts={
            "tool_start": "不应命中",
        })
        # 具名字段优先
        assert cfg.get_response_template("tool_start") == "正在调用：{tool_name}"

    @staticmethod
    def test_missing_key_returns_default():
        """查找不存在的 key 时返回默认值。"""
        from EDPAgent.agent_rule import ScriptsConfig

        cfg = ScriptsConfig()
        assert cfg.get_response_template("nonexistent_key") == ""
        assert cfg.get_response_template("nonexistent_key", "fallback") == "fallback"

    @staticmethod
    def test_extra_scripts_empty_by_default():
        """默认 extra_scripts 为空 dict。"""
        from EDPAgent.agent_rule import ScriptsConfig

        cfg = ScriptsConfig()
        assert cfg.extra_scripts == {}

    @staticmethod
    def test_all_common_scripts_accessible():
        """验证所有 12 个通用话术字段均可通过 get_response_template 查找。"""
        from EDPAgent.agent_rule import ScriptsConfig

        cfg = ScriptsConfig()
        common_keys = [
            "tool_start", "tool_end",
            "todo_start", "todo_end",
            "todolist_start", "todolist_end",
            "interrupt_start", "request_start",
            "planning_start",
            "task_cancelled", "cancel_confirm",
            "out_of_scope",
        ]
        for key in common_keys:
            value = cfg.get_response_template(key)
            assert value, f"通用话术 {key} 不应为空"
            assert value != "", f"通用话术 {key} 应有默认值"


class TestCollectSkillScripts:
    """验证 collect_skill_scripts() 从 SKILL.md 收集业务话术。"""

    @staticmethod
    def test_collects_scripts_from_skill_md(tmp_path):
        """从 SKILL.md frontmatter 中正确收集 scripts 字段。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        # 创建模拟 skill 目录
        skill_dir = tmp_path / "test_skill"
        skill_dir.mkdir()
        (skill_dir / "SKILL.md").write_text(
            "---\n"
            "name: test_skill\n"
            "scripts:\n"
            "  test_success: \"操作成功\"\n"
            "  test_failed: \"操作失败\"\n"
            "---\n"
            "# Test Skill\n",
            encoding="utf-8",
        )

        result = collect_skill_scripts(tmp_path)
        assert result == {"test_success": "操作成功", "test_failed": "操作失败"}

    @staticmethod
    def test_merges_scripts_from_multiple_skills(tmp_path):
        """多个 SKILL.md 的 scripts 合并到一个 dict。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        for name in ("skill_a", "skill_b"):
            d = tmp_path / name
            d.mkdir()
            (d / "SKILL.md").write_text(
                "---\n"
                f"name: {name}\n"
                "scripts:\n"
                f"  {name}_key: \"{name} value\"\n"
                "---\n",
                encoding="utf-8",
            )

        result = collect_skill_scripts(tmp_path)
        assert result == {"skill_a_key": "skill_a value", "skill_b_key": "skill_b value"}

    @staticmethod
    def test_skips_non_directory_entries(tmp_path):
        """跳过非目录文件。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        # 创建一个普通文件（非目录）
        (tmp_path / "readme.txt").write_text("not a skill dir", encoding="utf-8")

        result = collect_skill_scripts(tmp_path)
        assert result == {}

    @staticmethod
    def test_skips_scenarios_directory(tmp_path):
        """跳过 scenarios 目录。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        scenarios_dir = tmp_path / "scenarios"
        scenarios_dir.mkdir()
        (scenarios_dir / "SKILL.md").write_text(
            "---\nscripts:\n  should_not_appear: \"bad\"\n---\n",
            encoding="utf-8",
        )

        result = collect_skill_scripts(tmp_path)
        assert result == {}

    @staticmethod
    def test_skips_skill_without_skil_md(tmp_path):
        """跳过没有 SKILL.md 的目录。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        (tmp_path / "empty_skill").mkdir()

        result = collect_skill_scripts(tmp_path)
        assert result == {}

    @staticmethod
    def test_skips_skill_without_frontmatter(tmp_path):
        """跳过没有 frontmatter 的 SKILL.md。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        skill_dir = tmp_path / "no_fm_skill"
        skill_dir.mkdir()
        (skill_dir / "SKILL.md").write_text(
            "# No frontmatter\nJust body text\n",
            encoding="utf-8",
        )

        result = collect_skill_scripts(tmp_path)
        assert result == {}

    @staticmethod
    def test_skips_skill_without_scripts_field(tmp_path):
        """跳过有 frontmatter 但没有 scripts 字段的 SKILL.md。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        skill_dir = tmp_path / "no_scripts_skill"
        skill_dir.mkdir()
        (skill_dir / "SKILL.md").write_text(
            "---\nname: no_scripts_skill\n---\n# Body\n",
            encoding="utf-8",
        )

        result = collect_skill_scripts(tmp_path)
        assert result == {}

    @staticmethod
    def test_nonexistent_dir_returns_empty(tmp_path):
        """skills 目录不存在时返回空 dict。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        result = collect_skill_scripts(tmp_path / "nonexistent")
        assert result == {}

    @staticmethod
    def test_real_skills_dir_collects_all_scripts():
        """从实际 skills 目录收集所有 SKILL.md 话术。"""
        from EDPAgent.agent_rule import collect_skill_scripts

        skills_dir = _EDPAGENT_DIR / "skills"
        if not skills_dir.exists() or not any(skills_dir.glob("*/SKILL.md")):
            pytest.skip("skills 目录不存在或无 SKILL.md 业务话术文件")

        result = collect_skill_scripts(skills_dir)

        # 验证关键业务话术存在
        expected_keys = [
            "product_recommend_success",
            "product_recommend_empty",
            "product_recommend_no_card",
            "product_select_confirm",
            "product_select_missing_product",
            "product_select_missing_amount",
            "product_select_invalid",
            "fund_planning_success",
            "fund_planning_buy_failed",
            "fund_planning_transfer_limit",
            "fund_planning_wealth_insufficient",
            "fund_planning_both_insufficient",
            "fund_planning_balance_insufficient",
            "fund_planning_card_mismatch",
            "fund_planning_purchase_aborted",
            "fund_planning_session_timeout",
            "mcp_result_empty",
        ]
        for key in expected_keys:
            assert key in result, f"业务话术 {key} 未从 SKILL.md 收集到"

    @staticmethod
    def test_end_to_end_lookup_with_collected_scripts():
        """端到端：collect_skill_scripts → extra_scripts → get_response_template。"""
        from EDPAgent.agent_rule import ScriptsConfig, collect_skill_scripts

        skills_dir = _EDPAGENT_DIR / "skills"
        if not skills_dir.exists() or not any(skills_dir.glob("*/SKILL.md")):
            pytest.skip("skills 目录不存在或无 SKILL.md 业务话术文件")

        # 1. 收集业务话术
        skill_scripts = collect_skill_scripts(skills_dir)
        assert skill_scripts, "应收集到至少一个业务话术"

        # 2. 构建 ScriptsConfig
        cfg = ScriptsConfig(extra_scripts=skill_scripts)

        # 3. 验证通用话术查找
        assert cfg.get_response_template("tool_start") == "正在调用：{tool_name}"

        # 4. 验证业务话术查找
        assert cfg.get_response_template("product_recommend_success") != ""
        assert cfg.get_response_template("fund_planning_buy_failed") != ""

        # 5. 验证不存在 key 的默认值
        assert cfg.get_response_template("nonexistent") == ""


# ============================================================================
# 3.12 场景切换功能
# ============================================================================

class TestLoadScenarioConfig:
    """验证 load_scenario_config() 加载场景配置。"""

    @staticmethod
    def test_loads_valid_scenario(tmp_path):
        """加载有效的场景配置文件。"""
        from EDPAgent.agent_rule import load_scenario_config

        scenario_file = tmp_path / "test_scenario.yaml"
        scenario_file.write_text(
            "name: 测试场景\n"
            "description: 测试场景描述\n"
            "scope:\n"
            "  allowed:\n"
            "    - '业务A'\n"
            "  denied:\n"
            "    - '业务B'\n"
            "todolist_steps:\n"
            "  - step_id: 1\n"
            "    content: '步骤1'\n"
            "    skill: 'skill_a'\n"
            "skill_routing:\n"
            "  - trigger: '用户请求A'\n"
            "    skill: 'skill_a'\n"
            "    priority: 1\n",
            encoding="utf-8",
        )

        cfg = load_scenario_config(scenario_file)
        assert cfg.name == "测试场景"
        assert cfg.description == "测试场景描述"
        assert "业务A" in cfg.scope.allowed
        assert "业务B" in cfg.scope.denied
        assert len(cfg.todolist_steps) == 1
        assert cfg.todolist_steps[0].step_id == 1
        assert cfg.todolist_steps[0].skill == "skill_a"
        assert len(cfg.skill_routing) == 1
        assert cfg.skill_routing[0].trigger == "用户请求A"

    @staticmethod
    def test_loads_minimal_scenario(tmp_path):
        """加载仅含 name 的最小场景配置。"""
        from EDPAgent.agent_rule import load_scenario_config

        scenario_file = tmp_path / "minimal.yaml"
        scenario_file.write_text(
            "name: 最小场景\n",
            encoding="utf-8",
        )

        cfg = load_scenario_config(scenario_file)
        assert cfg.name == "最小场景"
        assert cfg.description == ""
        assert cfg.scope.allowed == []
        assert cfg.scope.denied == []
        assert cfg.todolist_steps == []
        assert cfg.skill_routing == []

    @staticmethod
    def test_raises_on_missing_file(tmp_path):
        """文件不存在时抛出 FileNotFoundError。"""
        from EDPAgent.agent_rule import load_scenario_config

        with pytest.raises(FileNotFoundError, match="Scenario config not found"):
            load_scenario_config(tmp_path / "nonexistent.yaml")


class TestScenarioSwitching:
    """验证场景切换功能。"""

    @staticmethod
    def test_active_scenario_overrides_todolist_steps(tmp_path):
        """active_scenario.todolist_steps 优先于 AgentRuleConfig.todolist_steps。"""
        from EDPAgent.agent_rule import (
            AgentRuleConfig,
            ScenarioConfig,
            TodoStepConfig,
        )

        # AgentRuleConfig 有内联步骤
        inline_steps = [
            TodoStepConfig(step_id=1, content="内联步骤", skill="inline_skill"),
        ]
        rule_cfg = AgentRuleConfig(todolist_steps=inline_steps)

        # active_scenario 有场景步骤
        scenario = ScenarioConfig(
            name="测试场景",
            todolist_steps=[
                TodoStepConfig(step_id=1, content="场景步骤1", skill="scenario_skill_1"),
                TodoStepConfig(step_id=2, content="场景步骤2", skill="scenario_skill_2"),
            ],
        )
        rule_cfg.active_scenario = scenario

        # 场景步骤优先
        steps_source = (
            rule_cfg.active_scenario.todolist_steps
            if rule_cfg.active_scenario and rule_cfg.active_scenario.todolist_steps
            else rule_cfg.todolist_steps
        )
        assert len(steps_source) == 2
        assert steps_source[0].skill == "scenario_skill_1"

    @staticmethod
    def test_falls_back_to_inline_when_no_active_scenario():
        """无 active_scenario 时回退到 AgentRuleConfig 内联步骤。"""
        from EDPAgent.agent_rule import AgentRuleConfig, TodoStepConfig

        inline_steps = [
            TodoStepConfig(step_id=1, content="内联步骤", skill="inline_skill"),
        ]
        rule_cfg = AgentRuleConfig(todolist_steps=inline_steps)

        # active_scenario 为 None
        steps_source = (
            rule_cfg.active_scenario.todolist_steps
            if rule_cfg.active_scenario and rule_cfg.active_scenario.todolist_steps
            else rule_cfg.todolist_steps
        )
        assert len(steps_source) == 1
        assert steps_source[0].skill == "inline_skill"

    @staticmethod
    def test_falls_back_to_inline_when_scenario_has_no_steps():
        """active_scenario 存在但无 todolist_steps 时回退到内联步骤。"""
        from EDPAgent.agent_rule import AgentRuleConfig, ScenarioConfig, TodoStepConfig

        inline_steps = [
            TodoStepConfig(step_id=1, content="内联步骤", skill="inline_skill"),
        ]
        rule_cfg = AgentRuleConfig(todolist_steps=inline_steps)
        rule_cfg.active_scenario = ScenarioConfig(name="空场景")

        steps_source = (
            rule_cfg.active_scenario.todolist_steps
            if rule_cfg.active_scenario and rule_cfg.active_scenario.todolist_steps
            else rule_cfg.todolist_steps
        )
        assert len(steps_source) == 1
        assert steps_source[0].skill == "inline_skill"

    @staticmethod
    def test_load_real_scenario_config():
        """加载实际的场景配置文件。"""
        from EDPAgent.agent_rule import load_scenario_config

        # 尝试加载 Markdown 格式的场景配置
        scenario_path = _EDPAGENT_DIR / "skills" / "scenarios" / "AgentRule_wealth_purchase.md"
        if not scenario_path.exists():
            pytest.skip("场景配置文件不存在")

        cfg = load_scenario_config(scenario_path)
        assert cfg.name == "理财购买"
        assert len(cfg.todolist_steps) == 4
        assert cfg.todolist_steps[0].step_id == 1
        assert cfg.todolist_steps[0].skill == "product_recommend_skill"
        assert len(cfg.scope.allowed) > 0
        assert len(cfg.scope.denied) > 0
        assert len(cfg.skill_routing) >= 3

    @staticmethod
    def test_scenario_discovery_default():
        """ScenarioDiscoveryConfig 默认值正确。"""
        from EDPAgent.agent_rule import ScenarioDiscoveryConfig

        cfg = ScenarioDiscoveryConfig()
        assert cfg.base_path == "skills/scenarios"
        assert cfg.active_scenario == "AgentRule_wealth_purchase"

    @staticmethod
    def test_active_scenario_env_override():
        """通过环境变量 ACTIVE_SCENARIO 覆盖场景名。"""
        # 模拟 agent.py 中的逻辑
        from EDPAgent.agent_rule import ScenarioDiscoveryConfig

        cfg = ScenarioDiscoveryConfig(active_scenario="理财购买")

        with patch.dict(os.environ, {"ACTIVE_SCENARIO": "股票购买"}):
            scenario_name = os.environ.get("ACTIVE_SCENARIO") or cfg.active_scenario
            assert scenario_name == "股票购买"

        # 无环境变量时使用默认
        with patch.dict(os.environ, {}, clear=True):
            # 确保没有 ACTIVE_SCENARIO
            os.environ.pop("ACTIVE_SCENARIO", None)
            scenario_name = os.environ.get("ACTIVE_SCENARIO") or cfg.active_scenario
            assert scenario_name == "理财购买"


class TestFullIntegration:
    """完整集成测试：从文件加载到话术查找全链路。"""

    @staticmethod
    def test_full_chain_from_files_to_lookup(tmp_path):
        """从 AgentRule.md + SKILL.md + scenario.md 到话术查找的完整链路。"""
        from EDPAgent.agent_rule import (
            ScriptsConfig,
            collect_skill_scripts,
            load_agent_rule,
            load_scenario_config,
        )

        # 1. 创建模拟 AgentRule.md
        rule_md = tmp_path / "AgentRule.md"
        rule_md.write_text(
            "---\n"
            "scope:\n"
            "  allowed: \"test\"\n"
            "  out_of_scope_message: \"尚在学习中\"\n"
            "scenario_discovery:\n"
            "  base_path: \"skills/scenarios\"\n"
            "  active_scenario: \"test_scenario\"\n"
            "---\n"
            "# AgentRule Body\n",
            encoding="utf-8",
        )

        # 2. 创建模拟场景配置（Markdown 格式）
        scenarios_dir = tmp_path / "skills" / "scenarios"
        scenarios_dir.mkdir(parents=True)
        (scenarios_dir / "AgentRule_test_scenario.md").write_text(
            "---\n"
            "name: test_scenario\n"
            "todolist_steps:\n"
            "  - step_id: 1\n"
            "    content: '测试步骤'\n"
            "    skill: 'test_skill'\n"
            "---\n"
            "# Test Scenario\n",
            encoding="utf-8",
        )

        # 3. 创建模拟 SKILL.md
        skills_dir = tmp_path / "skills"
        skill_dir = skills_dir / "test_skill"
        skill_dir.mkdir()
        (skill_dir / "SKILL.md").write_text(
            "---\n"
            "name: test_skill\n"
            "scripts:\n"
            "  test_success: \"测试成功话术\"\n"
            "  test_failed: \"测试失败话术\"\n"
            "---\n"
            "# Test Skill\n",
            encoding="utf-8",
        )

        # 4. 加载 AgentRule
        rule_cfg = load_agent_rule(rule_md)
        assert rule_cfg.scenario_discovery.active_scenario == "test_scenario"

        # 5. 加载场景配置
        scenario_path = scenarios_dir / "AgentRule_test_scenario.md"
        scenario_cfg = load_scenario_config(scenario_path)
        assert scenario_cfg.name == "test_scenario"
        assert len(scenario_cfg.todolist_steps) == 1

        # 6. 收集业务话术
        skill_scripts = collect_skill_scripts(skills_dir)
        assert "test_success" in skill_scripts

        # 7. 构建 ScriptsConfig 并验证两级查找
        scripts_cfg = ScriptsConfig(extra_scripts=skill_scripts)
        assert scripts_cfg.get_response_template("tool_start") == "正在调用：{tool_name}"
        assert scripts_cfg.get_response_template("test_success") == "测试成功话术"
        assert scripts_cfg.get_response_template("test_failed") == "测试失败话术"
        assert scripts_cfg.get_response_template("nonexistent") == ""
