from __future__ import annotations

import zipfile
from io import BytesIO
from pathlib import Path

import pytest

from skill_builder.application.draft_package_validation import validate_draft_package
from skill_builder.application.package_builder import (
    build_skill_export_archive,
    resolve_skill_package_metadata,
)


def write_skill(root: Path, name: str = "safe-skill") -> Path:
    target = root / "generated-skill" / "SKILL.md"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        f"---\nname: {name}\ndescription: Safe package test.\n---\n\n# Safe\n",
        encoding="utf-8",
    )
    return target


@pytest.mark.parametrize(
    ("mutation", "finding_id"),
    [
        (
            lambda root: (root / "generated-skill" / "SKILL.md").write_text(
                "# missing frontmatter\n", encoding="utf-8"
            ),
            "skill_frontmatter_invalid",
        ),
        (
            lambda root: write_skill(root, "Invalid_Name"),
            "skill_name_invalid",
        ),
        (
            lambda root: (
                (root / "generated-skill" / "validation").mkdir(parents=True),
                (root / "generated-skill" / "validation" / "result.json").write_text(
                    "{}", encoding="utf-8"
                ),
            ),
            "reserved_package_path",
        ),
    ],
)
def test_package_rejects_structural_and_reserved_paths(
    tmp_path: Path,
    mutation,
    finding_id: str,
) -> None:
    write_skill(tmp_path)
    mutation(tmp_path)
    result = validate_draft_package(tmp_path)
    assert result.ok is False
    assert finding_id in {item["id"] for item in result.errors}


def test_package_rejects_symlink(tmp_path: Path) -> None:
    write_skill(tmp_path)
    outside = tmp_path / "outside.md"
    outside.write_text("outside", encoding="utf-8")
    reference = tmp_path / "generated-skill" / "references"
    reference.mkdir(parents=True)
    (reference / "outside.md").symlink_to(outside)

    result = validate_draft_package(tmp_path)
    assert result.ok is False
    assert "unsafe_package_symlink" in {item["id"] for item in result.errors}


def test_export_contains_only_skill_package_files(tmp_path: Path) -> None:
    generated = tmp_path / "generated-skill"
    write_skill(tmp_path)
    (generated / "references").mkdir()
    (generated / "references" / "rules.md").write_text("# Rules\n", encoding="utf-8")
    (tmp_path / "validation").mkdir()
    (tmp_path / "validation" / "report.json").write_text("{}", encoding="utf-8")
    metadata = resolve_skill_package_metadata(
        skill_name="safe-skill",
        fallback_skill_name="safe-skill",
        display_name="Safe Skill",
        description="Safe package test.",
        version="0.1.0",
        tags=(),
    )

    content, filename, digest = build_skill_export_archive(generated, metadata)
    assert filename == "safe-skill-0.1.0-skill.zip"
    assert digest
    with zipfile.ZipFile(BytesIO(content)) as package:
        assert set(package.namelist()) == {"SKILL.md", "references/rules.md"}
