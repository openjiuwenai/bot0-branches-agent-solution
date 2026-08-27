"""Unit tests for SkillHub packager."""

from __future__ import annotations

import textwrap

import pytest

from agent_adapter.skillhub.packager import build_zip, extract_zip, sha256_hex, validate_skill_directory
from agent_adapter.skillhub.errors import SkillHubValidationError


def _write_skill(tmp_path, name: str = "demo-skill") -> None:
    skill_dir = tmp_path / name
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text(
        textwrap.dedent(
            f"""\
            ---
            name: {name}
            description: demo
            ---

            # Demo
            """
        ),
        encoding="utf-8",
    )


class TestSkillHubPackager:
    @staticmethod
    def test_build_and_extract_roundtrip(tmp_path):
        _write_skill(tmp_path)
        skill_dir = tmp_path / "demo-skill"
        zip_bytes = build_zip(skill_dir)
        assert zip_bytes
        assert len(sha256_hex(zip_bytes)) == 64

        target = tmp_path / "agent"
        target.mkdir()
        extracted = extract_zip(zip_bytes, target)
        assert extracted.name == "demo-skill"
        assert (extracted / "SKILL.md").read_text(encoding="utf-8").startswith("---")

    @staticmethod
    def test_build_zip_includes_plugin_yaml(tmp_path):
        _write_skill(tmp_path)
        skill_dir = tmp_path / "demo-skill"
        zip_bytes = build_zip(skill_dir)
        import io
        import zipfile

        with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
            names = zf.namelist()
            assert "demo-skill/plugin.yaml" in names
            assert "demo-skill/demo-skill/SKILL.md" in names

    @staticmethod
    def test_extract_root_skill_md(tmp_path):
        _write_skill(tmp_path)
        skill_md = (tmp_path / "demo-skill" / "SKILL.md").read_text(encoding="utf-8")
        import io
        import zipfile

        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, mode="w") as zf:
            zf.writestr("SKILL.md", skill_md)
        target = tmp_path / "agent"
        target.mkdir()
        extracted = extract_zip(buffer.getvalue(), target)
        assert extracted.name == "demo-skill"
        assert (extracted / "SKILL.md").is_file()

    @staticmethod
    def test_validate_directory_name_mismatch(tmp_path):
        _write_skill(tmp_path, name="demo-skill")
        bad_dir = tmp_path / "wrong-name"
        bad_dir.mkdir()
        (bad_dir / "SKILL.md").write_text(
            (tmp_path / "demo-skill" / "SKILL.md").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
        with pytest.raises(SkillHubValidationError):
            validate_skill_directory(bad_dir)
