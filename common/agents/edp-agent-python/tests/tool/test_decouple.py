"""AgentRule与Skill解耦优化测试用例自动化脚本 (TC-01 ~ TC-29).

Coverage: agent_rule.py + agent.py decouple functions + prompt.py
Depends on EDPAgent/tests/conftest.py sys.modules stub isolation.

Matches docs/AgentRule与Skill解耦优化测试用例.md one-to-one.
"""
from __future__ import annotations

import asyncio
import ast
import tempfile
import textwrap
import warnings
import zipfile
from pathlib import Path

import pytest


# === helpers ===

def _build_skills_root(base: Path, skills: list[str] | None = None,
                       scenarios: list[str] | None = None,
                       scripts_map: dict[str, dict[str, str]] | None = None) -> Path:
    skills_dir = base / "skills"
    skills_dir.mkdir(parents=True, exist_ok=True)
    (skills_dir / "__init__.py").write_text("#\n", encoding="utf-8")
    for name in (skills or []):
        sub = skills_dir / name
        sub.mkdir(exist_ok=True)
        frontmatter = "---\nname: " + name + "\n"
        if scripts_map and name in scripts_map:
            frontmatter += "scripts:\n"
            for k, v in scripts_map[name].items():
                frontmatter += f"  {k}: \"{v}\"\n"
        frontmatter += "---\n# body\n"
        (sub / "SKILL.md").write_text(frontmatter, encoding="utf-8")
    scen = skills_dir / "scenarios"
    scen.mkdir(exist_ok=True)
    for name in (scenarios or []):
        (scen / name).write_text("---\nname: s\n---\n", encoding="utf-8")
    return skills_dir


class _FakeAgent:
    def __init__(self) -> None:
        self.registered_paths: list[str] = []

    async def register_skill(self, path: str) -> None:
        self.registered_paths.append(path)


# AST extract agent.py decouple functions
_AGENT_PY = Path(__file__).resolve().parent.parent.parent / "agent.py"


def _extract_agent_symbols(names: set[str]) -> dict:
    src = _AGENT_PY.read_text(encoding="utf-8")
    tree = ast.parse(src)
    chunks: list[str] = []
    chunks.append(
        "from __future__ import annotations\n"
        "from pathlib import Path\n"
        "import logging\n"
        "import zipfile\n"
        "logger = logging.getLogger('test')\n"
    )
    for node in tree.body:
        if isinstance(node, (ast.AsyncFunctionDef, ast.FunctionDef)) and node.name in names:
            chunks.append(ast.get_source_segment(src, node))
        elif isinstance(node, ast.Assign):
            for tgt in node.targets:
                if isinstance(tgt, ast.Name) and tgt.id in names:
                    chunks.append(ast.get_source_segment(src, node))
    ns: dict = {}
    exec(compile("\n\n".join(chunks), str(_AGENT_PY), "exec"), ns)
    return ns


_AGENT_NS_CACHE: dict | None = None


def _agent_ns() -> dict:
    global _AGENT_NS_CACHE
    if _AGENT_NS_CACHE is None:
        _AGENT_NS_CACHE = _extract_agent_symbols({
            "_get_required_skills_for_scenario",
            "_validate_scenario_skills",
            "_register_scenario_skills",
            "_is_placeholder_only",
            "_PLACEHOLDER_SKILL_NAME",
            "_zip_skills_dir_to_file",
        })
    return _AGENT_NS_CACHE


# === 2. Scenario loading (TC-01 ~ TC-03, TC-28) ===

class TestScenarioLoading:

    @staticmethod
    def test_tc01_load_real_scenario():
        from EDPAgent.agent_rule import load_scenario_config
        target = (
            Path(__file__).resolve().parents[1].parent
            / "skills" / "scenarios" / "AgentRule_wealth_purchase.md"
        )
        if not target.exists():
            pytest.skip(f"scenario file not found: {target}")
        cfg = load_scenario_config(target)
        assert len(cfg.todolist_steps) == 4
        skills = {s.skill for s in cfg.todolist_steps}
        assert skills == {
            "product_recommend_skill", "interact_finance_rec_skill",
            "product_select_skill", "fund_planning_skill",
        }

    @staticmethod
    def test_tc02_md_priority_over_yaml(tmp_path):
        from EDPAgent.agent_rule import find_scenario_file
        (tmp_path / "AgentRule_demo.md").write_text("---\nname: demo\n---\n", encoding="utf-8")
        (tmp_path / "AgentRule_demo.yaml").write_text("name: demo\n", encoding="utf-8")
        assert find_scenario_file(tmp_path, "AgentRule_demo").suffix == ".md"

    @staticmethod
    def test_tc03_not_found_raises(tmp_path):
        from EDPAgent.agent_rule import find_scenario_file
        with pytest.raises(FileNotFoundError):
            find_scenario_file(tmp_path, "AgentRule_missing")

    @staticmethod
    def test_tc28_yaml_only_fallback(tmp_path):
        from EDPAgent.agent_rule import find_scenario_file
        (tmp_path / "AgentRule_y.yaml").write_text("name: y\n", encoding="utf-8")
        assert find_scenario_file(tmp_path, "AgentRule_y").suffix == ".yaml"


# === 3. On-demand registration (TC-04 ~ TC-10) ===

class TestOnDemandRegistration:

    @staticmethod
    def test_tc04_required_skills_from_scenario():
        from EDPAgent.agent_rule import ScenarioConfig, ScenarioScopeConfig, TodoStepConfig
        scenario = ScenarioConfig(
            name="test",
            scope=ScenarioScopeConfig(),
            todolist_steps=[
                TodoStepConfig(step_id=1, content="a", skill="skill_a"),
                TodoStepConfig(step_id=2, content="b", skill="skill_b"),
            ],
            skill_routing=[{"trigger": "always", "skill": "skill_c"}],
        )
        result = _agent_ns()["_get_required_skills_for_scenario"](scenario)
        assert result == {"skill_a", "skill_b", "skill_c"}

    @staticmethod
    def test_tc05_required_skills_none_scenario():
        assert _agent_ns()["_get_required_skills_for_scenario"](None) == set()

    @staticmethod
    def test_tc06_validate_all_exist(tmp_path):
        _build_skills_root(tmp_path, skills=["skill_a", "skill_b"])
        _agent_ns()["_validate_scenario_skills"](tmp_path / "skills", {"skill_a", "skill_b"})

    @staticmethod
    def test_tc07_validate_missing_raises(tmp_path):
        _build_skills_root(tmp_path, skills=["skill_a"])
        with pytest.raises(RuntimeError, match="skill_b"):
            _agent_ns()["_validate_scenario_skills"](tmp_path / "skills", {"skill_a", "skill_b"})

    @staticmethod
    def test_tc08_placeholder_only_true():
        assert _agent_ns()["_is_placeholder_only"]({"_placeholder_"}) is True

    @staticmethod
    def test_tc09_placeholder_only_false():
        assert _agent_ns()["_is_placeholder_only"]({"_placeholder_", "skill_a"}) is False
        assert _agent_ns()["_is_placeholder_only"]({"skill_a"}) is False

    @staticmethod
    def test_tc10_placeholder_only_empty():
        assert _agent_ns()["_is_placeholder_only"](set()) is False


# === 4. Skill registration paths (TC-11 ~ TC-13) ===

class TestRegisterScenarioSkills:

    @staticmethod
    def test_tc11_local_path_registration(tmp_path):
        skills_root = _build_skills_root(tmp_path, skills=["skill_a"])
        agent = _FakeAgent()
        count = asyncio.run(_agent_ns()["_register_scenario_skills"](agent, skills_root, {"skill_a"}))
        assert count == 1
        assert agent.registered_paths == [str(skills_root / "skill_a")]

    @staticmethod
    def test_tc12_remote_prefix_registration(tmp_path):
        skills_root = _build_skills_root(tmp_path, skills=["skill_a", "skill_b", "skill_c"])
        agent = _FakeAgent()
        count = asyncio.run(_agent_ns()["_register_scenario_skills"](
            agent, skills_root, {"skill_a", "skill_c"},
            register_path_prefix="/tmp/skills",
        ))
        assert count == 2
        assert sorted(agent.registered_paths) == ["/tmp/skills/skill_a", "/tmp/skills/skill_c"]

    @staticmethod
    def test_tc13_trailing_slash(tmp_path):
        skills_root = _build_skills_root(tmp_path, skills=["skill_a"])
        agent = _FakeAgent()
        count = asyncio.run(_agent_ns()["_register_scenario_skills"](
            agent, skills_root, {"skill_a"},
            register_path_prefix="/tmp/skills/",
        ))
        assert count == 1
        assert agent.registered_paths == ["/tmp/skills/skill_a"]


# === 5. ScriptsConfig (TC-14 ~ TC-18) ===

class TestScriptsConfig:

    @staticmethod
    def test_tc14_two_level_lookup():
        from EDPAgent.agent_rule import ScriptsConfig
        cfg = ScriptsConfig(extra_scripts={"product_select_intro": "ok"})
        assert cfg.get_response_template("tool_start") == "\u6b63\u5728\u8c03\u7528\uff1a{tool_name}"
        assert cfg.get_response_template("product_select_intro") == "ok"

    @staticmethod
    def test_tc15_business_keys_not_polluting_schema():
        from EDPAgent.agent_rule import ScriptsConfig
        fields = set(ScriptsConfig.model_fields.keys())
        assert "product_select_intro" not in fields
        assert "extra_scripts" in fields

    @staticmethod
    def test_tc16_default_fallback():
        from EDPAgent.agent_rule import ScriptsConfig
        cfg = ScriptsConfig()
        assert cfg.get_response_template("missing_key", "FALLBACK") == "FALLBACK"

    @staticmethod
    def test_tc17_collect_skill_scripts(tmp_path):
        from EDPAgent.agent_rule import collect_skill_scripts
        _build_skills_root(tmp_path, skills=["skill_a"],
                           scripts_map={"skill_a": {"intro": "hi", "confirm": "ok"}})
        result = collect_skill_scripts(tmp_path / "skills")
        assert result.get("intro") == "hi"
        assert result.get("confirm") == "ok"

    @staticmethod
    def test_tc18_collect_skill_scripts_no_scripts(tmp_path):
        from EDPAgent.agent_rule import collect_skill_scripts
        _build_skills_root(tmp_path, skills=["skill_a"])
        result = collect_skill_scripts(tmp_path / "skills")
        assert result == {}


# === 6. Config models (TC-19 ~ TC-23) ===

class TestConfigModels:

    @staticmethod
    def test_tc19_deprecation_warning(tmp_path):
        from EDPAgent.agent_rule import load_agent_rule
        rule = tmp_path / "AgentRule.md"
        rule.write_text(
            "---\nscope:\n  allowed: 'old'\n"
            "todolist_steps:\n  - step_id: 1\n    content: 'a'\n    skill: 's'\n"
            "---\n# Body\n", encoding="utf-8",
        )
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            load_agent_rule(rule)
        deprec = [w for w in caught if issubclass(w.category, DeprecationWarning)]
        assert len(deprec) >= 1

    @staticmethod
    def test_tc20_enable_resume_scripts_default():
        from EDPAgent.agent_rule import FixedScriptsConfig
        assert FixedScriptsConfig().enable_resume_scripts is True

    @staticmethod
    def test_tc21_configure_steps_empty():
        from EDPAgent.tool.lite_todo.models import configure_steps
        with pytest.raises(ValueError):
            configure_steps([])

    @staticmethod
    def test_tc22_configure_steps_duplicate_step_id():
        from EDPAgent.tool.lite_todo.models import configure_steps
        with pytest.raises(ValueError):
            configure_steps([
                {"step_id": 1, "content": "a", "skill": "s1"},
                {"step_id": 1, "content": "b", "skill": "s2"},
            ])

    @staticmethod
    def test_tc23_configure_steps_negative_step_id():
        from EDPAgent.tool.lite_todo.models import configure_steps
        with pytest.raises(ValueError):
            configure_steps([{"step_id": -1, "content": "a", "skill": "s"}])


# === 7. Prompt (TC-24) ===

class TestPrompt:

    @staticmethod
    def test_tc24_build_system_prompt_with_scenario():
        from EDPAgent.prompt import build_system_prompt
        from EDPAgent.agent_rule import ScenarioConfig, ScenarioScopeConfig, TodoStepConfig
        scenario = ScenarioConfig(
            name="test_scenario",
            scope=ScenarioScopeConfig(allowed=["biz_A"], denied=["biz_B"]),
            todolist_steps=[TodoStepConfig(step_id=1, content="do_task", skill="skill_x")],
        )
        result = build_system_prompt(scenario)
        assert "test_scenario" in result
        assert "biz_A" in result
        assert "biz_B" in result
        assert "do_task" in result
        assert "call_mcp" in result


# === 8. Zip filter (TC-25) ===

class TestZipFilter:

    @staticmethod
    def test_tc25_zip_filters_scenarios(tmp_path):
        skills_root = _build_skills_root(tmp_path, skills=["skill_a"],
                                          scenarios=["AgentRule_x.md"])
        out_zip = tmp_path / "test.zip"
        _agent_ns()["_zip_skills_dir_to_file"](skills_root, out_zip)
        assert out_zip.exists()
        with zipfile.ZipFile(out_zip, "r") as zf:
            names = zf.namelist()
        assert any("skill_a/SKILL.md" in n for n in names)
        assert not any("scenarios" in n for n in names)


# === 9. Data models (TC-26, TC-27, TC-29) ===

class TestDataModels:

    @staticmethod
    def test_tc26_scenario_config_full_construction():
        from EDPAgent.agent_rule import (
            ScenarioConfig, ScenarioScopeConfig, TodoStepConfig,
            ScenarioSkillRouting, ScenarioArchitectureConfig,
        )
        cfg = ScenarioConfig(
            name="full_test",
            scope=ScenarioScopeConfig(allowed=["A"], denied=["B"]),
            todolist_steps=[TodoStepConfig(step_id=1, content="step1", skill="s1")],
            skill_routing=[ScenarioSkillRouting(trigger="always", skill="s2")],
            architecture=ScenarioArchitectureConfig(
                type="mcp_first", applicable_skills=["s1", "s2"],
            ),
            description="desc",
        )
        assert cfg.name == "full_test"
        assert len(cfg.todolist_steps) == 1
        assert len(cfg.skill_routing) == 1

    @staticmethod
    def test_tc27_agent_rule_config_scope_defaults():
        from EDPAgent.agent_rule import AgentRuleConfig
        cfg = AgentRuleConfig()
        assert cfg.scope.allowed == ""

    @staticmethod
    def test_tc29_required_skills_from_architecture():
        from EDPAgent.agent_rule import ScenarioConfig, ScenarioScopeConfig, ScenarioArchitectureConfig
        scenario = ScenarioConfig(
            name="arch_test",
            scope=ScenarioScopeConfig(),
            todolist_steps=[],
            skill_routing=[],
            architecture=ScenarioArchitectureConfig(applicable_skills=["skill_x"]),
        )
        result = _agent_ns()["_get_required_skills_for_scenario"](scenario)
        assert "skill_x" in result
