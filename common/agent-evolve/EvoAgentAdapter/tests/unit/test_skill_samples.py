"""Tests against committed sample data under data/skills/."""

from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from agent_adapter.api.app import create_app
from agent_adapter.config import load_config
from agent_adapter.skill_store import SkillStore

_ADAPTER_ROOT = Path(__file__).resolve().parents[2]
_CONFIG_PATH = _ADAPTER_ROOT / "agent_adapter_config.yaml"
_EDP_SKILLS = _ADAPTER_ROOT / "data" / "skills" / "edp_agent"


@pytest.fixture
def sample_store() -> SkillStore:
    config = load_config(_CONFIG_PATH)
    return SkillStore.from_agent_configs(
        skills_root=Path(config.skills_root),
        agents=config.agents,
    )


class TestSampleSkillData:
    """Verify data/skills sample tree matches contract examples."""

    EXPECTED_EDP_SKILLS = {
        "product_recommend_skill",
        "interact_finance_rec_skill",
        "product_select_skill",
        "fund_planning_skill",
    }

    def test_edp_agent_skill_directories_exist(self) -> None:
        assert _EDP_SKILLS.is_dir()
        names = {
            p.name
            for p in _EDP_SKILLS.iterdir()
            if p.is_dir() and not p.name.startswith(".")
        }
        assert names == self.EXPECTED_EDP_SKILLS

    def test_list_edp_skills_via_store(self, sample_store: SkillStore) -> None:
        names = {s.name for s in sample_store.list_skills("edp_agent")}
        assert names == self.EXPECTED_EDP_SKILLS

    def test_read_product_recommend_skill(self, sample_store: SkillStore) -> None:
        doc = sample_store.read_skill("edp_agent", "product_recommend_skill")
        assert doc.skill_name == "product_recommend_skill"
        assert "Product Recommend Skill" in doc.content
        assert doc.revision

    def test_skill_list_api_with_sample_data(self) -> None:
        config = load_config(_CONFIG_PATH)
        app = create_app(config)
        client = TestClient(app)

        response = client.post(
            "/api/v1/skills",
            json={"agent_name": "edp_agent", "action": "skill_list"},
        )

        assert response.status_code == 200
        names = {item["name"] for item in response.json()["skills"]}
        assert names == self.EXPECTED_EDP_SKILLS

    def test_demo_agent_samples_on_disk(self) -> None:
        demo_root = _ADAPTER_ROOT / "data" / "skills" / "demo_agent"
        assert demo_root.is_dir()
        names = {p.name for p in demo_root.iterdir() if p.is_dir()}
        assert names == {"greeting_skill", "faq_skill"}
