"""Template definition and validation for LLM profiles."""

import copy
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

import yaml


@dataclass
class Template:
    """Represents an LLM interface template.

    A template defines the structure of an LLM profile, including:
    - UI form fields for GUI tools
    - Default values for generating a new profile
    - Schema rules for validating a profile
    """

    name: str
    display_name: str
    description: str
    version: str
    ui: Dict[str, Any] = field(default_factory=dict)
    defaults: Dict[str, Any] = field(default_factory=dict)
    schema: Dict[str, Any] = field(default_factory=dict)

    @property
    def form_fields(self) -> List[Dict[str, Any]]:
        """Return UI form field definitions."""
        return self.ui.get("form", [])

    def generate_scaffold(self, name: str = "my_llm") -> Dict[str, Any]:
        """Generate a default profile scaffold from this template.

        Args:
            name: Default name for the generated profile.

        Returns:
            A dictionary representing a new profile with default values.
        """
        scaffold = copy.deepcopy(self.defaults)
        scaffold["template"] = self.name
        scaffold["name"] = name
        if "id" not in scaffold:
            import uuid

            scaffold["id"] = f"llm_{uuid.uuid4().hex[:8]}"
        return scaffold

    def validate(self, profile: Dict[str, Any]) -> List[str]:
        """Validate a profile against this template's schema.

        Args:
            profile: The profile dictionary to validate.

        Returns:
            A list of error messages. Empty if valid.
        """
        errors = []
        schema_sections = self.schema.get("sections", {})

        for section_name, section_schema in schema_sections.items():
            section_value = profile.get(section_name)
            if section_schema.get("required") and section_value is None:
                errors.append(f"Missing required section: '{section_name}'")
                continue
            if not isinstance(section_value, dict):
                continue

            field_errors = self._validate_fields(
                section_value, section_schema.get("fields", {}), prefix=section_name
            )
            errors.extend(field_errors)

        # 顶层字段校验：name / template / id
        for top_field in ("name", "template"):
            if not profile.get(top_field):
                errors.append(f"Missing required field: '{top_field}'")

        return errors

    def _validate_fields(
        self, data: Dict[str, Any], fields: Dict[str, Any], prefix: str = ""
    ) -> List[str]:
        """Recursively validate fields against schema definitions."""
        errors = []
        for field_name, field_schema in fields.items():
            full_name = f"{prefix}.{field_name}" if prefix else field_name
            value = data.get(field_name)

            if field_schema.get("required") and (value is None or value == ""):
                errors.append(f"Missing required field: '{full_name}'")
                continue

            if value is None:
                continue

            expected_type = field_schema.get("type")
            if expected_type and not self._check_type(value, expected_type):
                errors.append(
                    f"Field '{full_name}' should be {expected_type}, got {type(value).__name__}"
                )
                continue

            # Recurse into nested dict fields
            nested_fields = field_schema.get("fields")
            if expected_type == "dict" and nested_fields and isinstance(value, dict):
                nested_errors = self._validate_fields(value, nested_fields, prefix=full_name)
                errors.extend(nested_errors)

        return errors

    @staticmethod
    def _check_type(value: Any, expected_type: str) -> bool:
        """Check if a value matches the expected type string."""
        type_map = {
            "string": str,
            "int": int,
            "float": (int, float),
            "bool": bool,
            "list": list,
            "dict": dict,
        }
        python_type = type_map.get(expected_type)
        if python_type is None:
            return True
        # bool is subclass of int in Python, so treat bool separately
        if expected_type == "int" and isinstance(value, bool):
            return False
        return isinstance(value, python_type)

    @classmethod
    def from_file(cls, path: str) -> "Template":
        """Load a template from a YAML file."""
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        return cls(
            name=data.get("name", ""),
            display_name=data.get("display_name", data.get("name", "")),
            description=data.get("description", ""),
            version=data.get("version", ""),
            ui=data.get("ui", {}),
            defaults=data.get("defaults", {}),
            schema=data.get("schema", {}),
        )

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "Template":
        """Load a template from a dictionary."""
        return cls(
            name=data.get("name", ""),
            display_name=data.get("display_name", data.get("name", "")),
            description=data.get("description", ""),
            version=data.get("version", ""),
            ui=data.get("ui", {}),
            defaults=data.get("defaults", {}),
            schema=data.get("schema", {}),
        )


class TemplateManager:
    """Manages loading and listing of LLM templates."""

    def __init__(self, *template_dirs: str):
        """Initialize with one or more template directories.

        Later directories can override earlier ones with the same template name.
        """
        self._template_dirs: List[Path] = [Path(d) for d in template_dirs]
        self._templates: Dict[str, Template] = {}
        self._load_all()

    def _load_all(self) -> None:
        """Load all templates from registered directories."""
        for directory in self._template_dirs:
            if not directory.exists():
                continue
            for file_path in directory.glob("*.yaml"):
                try:
                    template = Template.from_file(str(file_path))
                    self._templates[template.name] = template
                except Exception as e:
                    raise ValueError(f"Failed to load template {file_path}: {e}") from e

    def list_templates(self) -> List[Dict[str, str]]:
        """Return a list of template summaries for UI display."""
        return [
            {
                "name": t.name,
                "display_name": t.display_name,
                "description": t.description,
                "version": t.version,
            }
            for t in self._templates.values()
        ]

    def get_template(self, name: str) -> Optional[Template]:
        """Get a template by name."""
        return self._templates.get(name)

    def has_template(self, name: str) -> bool:
        """Check if a template exists."""
        return name in self._templates

    def save_user_template(self, name: str, yaml_text: str) -> "Template":
        """保存用户自定义模板 YAML 到用户模板目录并重新加载。"""
        template_data = yaml.safe_load(yaml_text)
        if not isinstance(template_data, dict):
            raise ValueError("Invalid YAML format")
        tmpl_name = template_data.get("name")
        if not tmpl_name:
            raise ValueError("Template 'name' is required")
        # 验证模板可被加载
        template = Template.from_dict(template_data)
        save_path = self.user_templates_dir / f"{tmpl_name}.yaml"
        save_path.write_text(yaml_text, encoding="utf-8")
        self.reload()
        return template

    def reload(self) -> None:
        """Reload all templates from disk."""
        self._templates.clear()
        self._load_all()

    def get_template_path(self, name: str) -> Optional[Path]:
        """Return the path to a template YAML file, preferring user overrides."""
        for directory in reversed(self._template_dirs):
            path = directory / f"{name}.yaml"
            if path.exists():
                return path
        return None
