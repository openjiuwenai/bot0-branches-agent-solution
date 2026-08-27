"""Small, deterministic acceptance service owned by SkillBuilder Core.

Hosts provide only ``SkillBuilderExecutionPort``; Core owns command selection,
the bounded offline replay protocol, assertions, and the final result.
"""

from __future__ import annotations

import ast
import csv
import json
import re
import shutil
import sys
import time
import uuid
from datetime import datetime, timezone
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from skill_builder.application.agent_findings import reconcile_agent_findings
from skill_builder.application.artifact_digest import candidate_artifact_signature
from skill_builder.application.capability_observation import (
    observe_capability_relationships,
    python_runtime_call_capabilities,
)
from skill_builder.application.draft_package_validation import validate_draft_package
from skill_builder.application.python_name_analysis import (
    analyze_python_module_name_conflicts,
    analyze_undefined_python_names,
)
from skill_builder.ports import ExecutionRequest, SkillBuilderExecutionPort
from skill_builder.runtime.gate_settings import (
    resolve_capability_gate_mode,
    resolve_documentation_gate_mode,
    resolve_offline_protocol_gate_mode,
)
from skill_builder.application.implementation_integrity import (
    documented_cli_entrypoints,
    empty_conditional_branch_signals,
    missing_package_references,
    offline_cli_coverage_signals,
    platform_only_document_references,
    reserved_example_endpoint_signals,
    self_check_production_usage_signals,
    unused_mapping_input_signals,
)
from skill_builder.application.offline_validation import (
    SELF_CHECK_PLANNED_STATUS,
    _command_script,
    html_fixture_encoding_issues,
    replay_self_check_cases,
    scenario_has_structured_inputs,
    scenario_invalid_input_requires_nonzero_exit,
    scenario_input_fixture_issues,
    scenario_output_invariants,
    scenario_required_contract_ids,
    self_check_package_input_issues,
    validate_self_check_summary,
)
from skill_builder.application.implementation_evidence import (
    implementation_evidence_issues,
)
from skill_builder.application.fixture_builder import (
    platform_fixture_business_replay_issues,
    platform_owned_fixture_paths,
)
from skill_builder.application.input_contracts import scenario_structured_input_contracts


_INPUT_ARGUMENT_RE = re.compile(r"add_argument\(\s*['\"]--input['\"]")
_OUTPUT_ARGUMENT_RE = re.compile(r"add_argument\(\s*['\"]--output['\"]")
_OUTPUT_DIR_ARGUMENT_RE = re.compile(r"add_argument\(\s*['\"]--output-dir['\"]")
_VALIDATE_ONLY_ARGUMENT_RE = re.compile(
    r"add_argument\(\s*['\"]--validate-only['\"]"
)
_OFFLINE_ARGUMENT_RE = re.compile(r"add_argument\(\s*['\"]--offline['\"]")
_USE_FIXTURE_ARGUMENT_RE = re.compile(
    r"add_argument\(\s*['\"]--use-fixture['\"]"
)
_QUERY_MODE_ARGUMENT_RE = re.compile(
    r"add_argument\(\s*['\"]--query-mode['\"]"
)
# A first-version Skill is allowed to expose a small positional CLI as well as
# the preferred ``--input`` form.  Keep this detection deliberately narrow:
# only the conventional ``sys.argv[1]``/``Path(sys.argv[1])`` input shape is
# inferred, never arbitrary command-line text.
_POSITIONAL_INPUT_RE = re.compile(r"(?:sys\.argv\s*\[\s*1\s*\]|argv\s*\[\s*1\s*\])")
_OUTPUT_TOKEN_RE = re.compile(r"['\"]--output['\"]|\b--output\b")
_OUTPUT_DIRECTORY_DIRECT_RE = re.compile(
    r"(?:os\.makedirs|os\.mkdir)\s*\(\s*(?:args\.)?output\b"
    r"|Path\s*\(\s*(?:args\.)?output\s*\)\.mkdir\s*\("
    r"|os\.path\.join\(\s*(?:args\.)?output\s*,",
    re.IGNORECASE,
)
_LOCAL_LINK_RE = re.compile(r"\]\((?!https?://|mailto:)([^)#]+)")
_OFFLINE_DIAGNOSTIC_ENTRYPOINTS = frozenset(
    {"self_check.py", "run_offline_test.py", "offline_test.py"}
)
_BUSINESS_LITERAL_KEY_RE = re.compile(
    r"(?:"
    r"客户.{0,6}(?:名称|姓名|编号)|企业.{0,6}(?:名称|编号)|公司名称|对方户名|"
    r"(?:放款|贷款|汇款|交易|合同|订单|账户|账号|借据).{0,6}(?:金额|编号|号码|号)|"
    r"(?:金额|余额|价格|单价|总价|手机号|电话号码|身份证号|地址)|"
    r"customer.{0,8}(?:name|id|code)|company.{0,8}(?:name|id)|counterparty|"
    r"(?:loan|payment|transaction|contract|order|account).{0,8}(?:amount|id|no|number|name)|"
    r"(?:amount|balance|price|phone|address)"
    r")",
    re.IGNORECASE,
)

# These findings describe the shape/evidence of the generated self-check
# harness.  They are useful diagnostics, but are not by themselves proof that
# the exported business entrypoint is broken.  In particular, optional
# browser/network dependencies are not guaranteed to exist in the host
# validation sandbox.  The gate is shadowed by default and can be explicitly
# enforced by deployment configuration.
_OFFLINE_PROTOCOL_FINDING_IDS = frozenset(
    {
        "self_check_protocol_invalid",
    }
)
_OFFLINE_PROTOCOL_CHECK_IDS = frozenset(
    {
        "offline_replay",
    }
)
_BLOCKING_OFFLINE_PROTOCOL_ISSUE_IDS = frozenset(
    {
        "self_check_business_value_assertion_missing",
        "self_check_output_assertion_missing",
        "self_check_happy_path_exit_codes_invalid",
        "self_check_happy_path_allows_blocked",
        "self_check_package_input_missing",
        "self_check_platform_fixture_not_business_evidence",
        "self_check_invalid_input_exit_codes_invalid",
    }
)
_OPTIONAL_EXTERNAL_DEPENDENCY_RE = re.compile(
    r"(?:ModuleNotFoundError|ImportError).*\b(?:playwright|selenium|puppeteer|"
    r"requests|httpx|aiohttp)\b|(?:No module named|cannot import name).*\b(?:playwright|"
    r"selenium|puppeteer|requests|httpx|aiohttp)\b",
    re.IGNORECASE,
)

ACCEPTANCE_RESULT_PATH = "validation/diagnostics/self_check.json"
ACCEPTANCE_SUMMARY_PATH = "validation/diagnostics/self_check_summary.md"

_CHECK_LABELS = {
    "package_structure": "包结构与元数据",
    "python_syntax": "Python 脚本语法",
    "python_module_names": "Python 模块命名冲突",
    "python_undefined_names": "Python 未定义名称",
    "agent_findings_reconciliation": "Agent 发现复核",
    "capability_observations": "能力声明与实现观察",
    "skill_references": "包内文件引用",
    "package_portability": "导出包可移植性",
    "sample_data_literals": "样例业务数据隔离",
    "external_endpoints": "外部端点可配置性",
    "structured_output_fields": "结构化业务输出存活性",
    "required_branches": "生产分支完整性",
    "declared_inputs": "输入消费完整性",
    "self_check_production_usage": "自检生产代码覆盖",
    "offline_cli_coverage": "离线 CLI 编排覆盖",
    "input_fixture_contract": "结构化输入样例契约",
    "html_fixture_encoding": "HTML 样例编码契约",
    "documented_cli_startup": "文档化 CLI 启动检查",
    "documented_cli_presence": "文档化 CLI 文件完整性",
    "offline_replay": "平台独立离线重放",
    "implementation_evidence": "契约实现证据",
    "capability_contract": "能力契约一致性",
    "confirmed_decisions": "已确认决策一致性",
    "offline_smoke": "离线脚本检查",
    "self_check_package_immutability": "自检包只读约束",
    "external_runtime": "外部运行能力",
}


@dataclass(frozen=True, slots=True)
class AcceptanceResult:
    status: str
    outcome: str
    delivery_status: str
    findings: tuple[dict[str, Any], ...]
    checks: tuple[dict[str, Any], ...]
    diagnostics: dict[str, Any]
    summary: dict[str, Any]

    @property
    def ok(self) -> bool:
        return self.status in {"pass", "warn"}

    def to_result(self) -> dict[str, Any]:
        blockers = tuple(
            str(item.get("id"))
            for item in self.findings
            if item.get("severity") == "fail" and item.get("id")
        )
        return {
            "schemaVersion": "skill-builder-acceptance/v1",
            "status": self.status,
            "outcome": self.outcome,
            "deliveryStatus": self.delivery_status,
            "blockingFailureIds": list(blockers),
            "blockingCheckIds": list(blockers),
            "findings": list(self.findings),
            "checks": list(self.checks),
            "diagnostics": dict(self.diagnostics),
            "warnings": [
                item for item in self.findings if item.get("severity") == "warn"
            ],
            "summary": dict(self.summary),
        }


def acceptance_result_payload(
    result: AcceptanceResult | dict[str, Any],
    *,
    generated_at: str | None = None,
) -> dict[str, Any]:
    """Return the durable, machine-readable projection of one acceptance run.

    ``AcceptanceResult`` remains the only validation fact source.  The small
    amount of metadata below describes when and where that fact was persisted;
    it does not add another pass/fail rule.
    """

    payload = result.to_result() if isinstance(result, AcceptanceResult) else dict(result)
    payload.setdefault("generatedAt", generated_at or datetime.now(timezone.utc).isoformat())
    payload.setdefault("artifacts", {
        "result": ACCEPTANCE_RESULT_PATH,
        "summary": ACCEPTANCE_SUMMARY_PATH,
    })
    return payload


def _markdown_cell(value: Any) -> str:
    return str(value or "").replace("|", "\\|").replace("\n", " ").strip()


def _check_label(value: Any) -> str:
    text = str(value or "检查项")
    return _CHECK_LABELS.get(text, text)


def _check_status_label(value: Any) -> str:
    text = str(value or "unknown").lower()
    return {
        "pass": "通过",
        "warn": "有提示",
        "partial": "部分完成",
        "fail": "失败",
        "skip": "未执行",
        "not_run": "未执行",
        "info": "仅观察",
    }.get(text, text)


def render_acceptance_summary(
    payload: dict[str, Any],
    agent_self_check: dict[str, Any] | None = None,
) -> str:
    """Render a readable projection without re-evaluating acceptance rules."""

    status = str(payload.get("status") or "unknown").lower()
    status_label = {
        "pass": "✅ 通过",
        "warn": "⚠️ 通过（有警告）",
        "fail": "❌ 失败",
    }.get(status, status)
    summary = payload.get("summary") if isinstance(payload.get("summary"), dict) else {}
    checks = payload.get("checks") if isinstance(payload.get("checks"), list) else []
    findings = payload.get("findings") if isinstance(payload.get("findings"), list) else []
    package_check = next(
        (
            check
            for check in checks
            if isinstance(check, dict) and check.get("id") == "package_structure"
        ),
        {},
    )
    package_files = package_check.get("files") if isinstance(package_check, dict) else []
    package_files = [str(path) for path in package_files if str(path).strip()]
    lines = [
        "# Skill 验收报告",
        "",
        f"- 总体状态：{status_label}",
        f"- 验证时间：{payload.get('generatedAt') or '未知'}",
        f"- 验证耗时：{summary.get('elapsedSeconds', '未知')} 秒",
        f"- 交付状态：{payload.get('deliveryStatus') or '未知'}",
        "- 生成文件数：{}".format(
            package_check.get("fileCount", len(package_files))
            if isinstance(package_check, dict)
            else len(package_files)
        ),
        "",
    ]
    if isinstance(agent_self_check, dict):
        agent_status = str(agent_self_check.get("status") or "not_run").lower()
        agent_label = {
            "pass": "✅ 完成",
            "warn": "⚠️ 有提示",
            "partial": "◐ 部分完成",
            "fail": "❌ 发现问题",
            "not_run": "— 未执行",
        }.get(agent_status, agent_status)
        lines.extend([
            "## Agent 自验证",
            "",
            f"- 状态：{agent_label}",
            f"- 摘要：{_markdown_cell(agent_self_check.get('summary')) or '未提供摘要。'}",
            "",
            "| 检查项 | 状态 | 结果 |",
            "| --- | --- | --- |",
        ])
        agent_checks = agent_self_check.get("checks") if isinstance(agent_self_check.get("checks"), list) else []
        if agent_checks:
            for check in agent_checks:
                if not isinstance(check, dict):
                    continue
                command = check.get("command")
                rendered_command = (
                    " ".join(str(part) for part in command)
                    if isinstance(command, list)
                    else str(command or "").strip()
                )
                result_text = _markdown_cell(check.get("message"))
                if rendered_command:
                    result_text = f"{result_text}；命令：`{_markdown_cell(rendered_command)}`"
                if check.get("exitCode") is not None:
                    result_text = f"{result_text}；退出码：{_markdown_cell(check.get('exitCode'))}"
                lines.append(
                    "| "
                    + " | ".join(
                        (
                            _markdown_cell(check.get("title") or check.get("id")),
                            _markdown_cell(_check_status_label(check.get("status"))),
                            result_text,
                        )
                    )
                    + " |"
                )
        else:
            lines.append("| - | - | Agent 未记录具体检查项。 |")
        unverified = agent_self_check.get("unverified") if isinstance(agent_self_check.get("unverified"), list) else []
        if unverified:
            lines.extend(["", "未验证能力："])
            lines.extend(f"- {_markdown_cell(item)}" for item in unverified if str(item).strip())
        lines.append("")
    lines.extend([
        "## 生成文件",
        "",
    ])
    if package_files:
        lines.extend(f"- `{path}`" for path in package_files)
    else:
        lines.append("- 未记录可导出的 Skill 文件。")
    lines.extend(
        [
            "",
        "## 平台检查",
        "",
        "| 检查项 | 状态 | 说明 |",
        "| --- | --- | --- |",
        ]
    )
    if checks:
        for check in checks:
            if not isinstance(check, dict):
                continue
            lines.append(
                "| "
                + " | ".join(
                    (
                        _markdown_cell(_check_label(check.get("id"))),
                        _markdown_cell(_check_status_label(check.get("status"))),
                        _markdown_cell(check.get("message")),
                    )
                )
                + " |"
            )
    else:
        lines.append("| - | - | 没有记录检查项。 |")

    commands = [
        (check.get("id"), check.get("command"))
        for check in checks
        if isinstance(check, dict) and check.get("command")
    ]
    lines.extend(["", "## 执行命令", ""])
    if commands:
        for check_id, command in commands:
            rendered = " ".join(str(part) for part in command) if isinstance(command, list) else str(command)
            lines.append(f"- `{_markdown_cell(check_id)}`：`{_markdown_cell(rendered)}`")
    else:
        lines.append("- 本次没有需要执行的脚本命令。")

    lines.extend(["", "## 问题与提示", ""])
    if findings:
        for finding in findings:
            if not isinstance(finding, dict):
                continue
            location = f"（{finding.get('path')}）" if finding.get("path") else ""
            lines.append(
                f"- **{_markdown_cell(finding.get('severity'))}** "
                f"`{_markdown_cell(finding.get('id'))}`："
                f"{_markdown_cell(finding.get('message'))}{location}"
            )
    else:
        lines.append("- 无。")

    lines.extend([
        "",
        "## 验证边界",
        "",
        "- 平台检查：Skill 包结构、frontmatter、Python 语法和未定义名称、包内引用，以及可用时的一次离线 smoke。",
        "- 能力实现：外部能力是否真实可达仍记为未验证；但发布脚本中的占位、NotImplemented 或 pass-only 核心实现会阻断 ready。",
        "- 在线能力：真实浏览器、网络/API 和外部站点仅在实际执行并有证据时记录为通过。",
        "",
        "## 下一步",
        "",
    ])
    next_steps = summary.get("nextSteps") if isinstance(summary.get("nextSteps"), list) else []
    lines.extend(f"- {str(step).strip()}" for step in next_steps if str(step).strip())
    if not next_steps:
        lines.append("- 当前没有平台侧阻断项，可查看生成的 Skill 包或进行显式运行验证。")
    return "\n".join(lines).rstrip() + "\n"


def persist_acceptance_files(
    root: Path,
    payload: dict[str, Any],
    agent_self_check: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Persist the JSON fact and Markdown projection in a local workspace."""

    documents = {
        ACCEPTANCE_RESULT_PATH: json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        ACCEPTANCE_SUMMARY_PATH: render_acceptance_summary(payload, agent_self_check),
    }
    written: list[str] = []
    errors: list[str] = []
    for relative, content in documents.items():
        target = root / relative
        temporary = target.with_name(f".{target.name}.tmp")
        try:
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary.write_text(content, encoding="utf-8")
            temporary.replace(target)
            written.append(relative)
        except OSError as exc:
            errors.append(f"{relative}: {str(exc)[:300]}")
            temporary.unlink(missing_ok=True)
    return {"ok": not errors, "paths": written, "errors": errors}


def acceptance_exception_payload(message: str) -> dict[str, Any]:
    """Represent an unexpected acceptance error without hiding it from users."""

    return acceptance_result_payload(
        {
            "schemaVersion": "skill-builder-acceptance/v1",
            "status": "fail",
            "outcome": "failed",
            "deliveryStatus": "blocked",
            "blockingFailureIds": ["acceptance_internal_error"],
            "blockingCheckIds": ["acceptance_internal_error"],
            "findings": [
                {
                    "id": "acceptance_internal_error",
                    "rootCauseId": "acceptance_internal_error",
                    "severity": "fail",
                    "category": "platform_runtime",
                    "audience": "user",
                    "repairable": False,
                    "title": "Skill 自验收",
                    "message": message[:1000],
                }
            ],
            "checks": [],
            "warnings": [],
            "summary": {
                "status": "fail",
                "title": "Skill 自验收未完成",
                "message": "平台验收服务发生异常，未提交 Skill 草稿。",
                "nextSteps": ["检查平台验收错误后重新提交草稿。"],
            },
        }
    )


def _finding(
    finding_id: str,
    message: str,
    *,
    severity: str,
    path: str | None = None,
    category: str = "acceptance",
    owner: str = "package",
    repairable: bool | None = None,
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "id": finding_id,
        "rootCauseId": finding_id,
        "severity": severity,
        "category": category,
        "audience": "user",
        "failureOwner": owner,
        "repairable": (
            severity == "fail" and owner == "package"
            if repairable is None
            else bool(repairable)
        ),
        "title": "Skill 自验收",
        "message": message,
    }
    if path:
        value["path"] = path
    return value


def _resolved_capability_contract_issues(root: Path) -> list[dict[str, Any]]:
    """Return platform-owned contradictions from the persisted projection."""

    try:
        manifest = json.loads(
            (root / "validation" / "artifact_manifest.json").read_text(encoding="utf-8")
        )
    except (OSError, TypeError, ValueError):
        return []
    resolved = manifest.get("resolvedCapabilityContract") if isinstance(manifest, dict) else None
    if not isinstance(resolved, dict):
        return []
    issues = [
        {"kind": "declared_contract_conflict", "message": str(message)[:1000]}
        for message in resolved.get("conflicts") or []
        if str(message or "").strip()
    ]
    required = {
        str(key): bool(value)
        for key, value in (resolved.get("requiredCapabilities") or {}).items()
        if isinstance(value, bool)
    }
    non_runtime_modes = {"manual", "file", "fixture"}
    runtime_names = {
        "api_runtime",
        "browser_runtime",
        "external_runtime",
        "collection_script",
    }
    for decision in manifest.get("confirmedDecisions") or []:
        if not isinstance(decision, dict):
            continue
        if str(decision.get("semanticConcept") or "") != "acquisition_mode":
            continue
        mode = str(decision.get("semanticValue") or decision.get("value") or "").strip()
        enabled = sorted(name for name in runtime_names if required.get(name))
        if mode in non_runtime_modes and enabled:
            issues.append(
                {
                    "kind": "non_runtime_acquisition_requires_runtime",
                    "decisionId": decision.get("decisionId"),
                    "mode": mode,
                    "requiredCapabilities": enabled,
                }
            )
    return issues


def _reference_only_requirement_evidence(root: Path) -> list[dict[str, Any]]:
    scenario = _load_json_object(root / "validation" / "scenario_contract.json")
    result: list[dict[str, Any]] = []
    for requirement in scenario.get("resolvedRequirements") or []:
        if not isinstance(requirement, dict):
            continue
        evidence_refs = [
            str(item).strip()
            for item in requirement.get("evidenceRefs") or []
            if str(item or "").strip()
        ]
        if not evidence_refs or str(requirement.get("sourceQuote") or "").strip():
            continue
        result.append(
            {
                "requirementId": requirement.get("requirementId"),
                "concept": requirement.get("concept"),
                "evidenceStatus": str(
                    requirement.get("evidenceStatus") or "reference_only"
                ),
                "evidenceRefs": evidence_refs,
            }
        )
    return result


def _apply_delivery_review_evidence_policy(
    *,
    findings: list[dict[str, Any]],
    checks: list[dict[str, Any]],
    documented_entrypoints: set[str] | list[str] | tuple[str, ...],
) -> None:
    """Make review disposition depend on deterministic package evidence."""

    replay_passed = any(
        item.get("id") == "offline_replay" and item.get("status") == "pass"
        for item in checks
    )
    legacy_smoke_passed = bool(
        not replay_passed
        and any(
            item.get("id") == "offline_smoke" and item.get("status") == "pass"
            for item in checks
        )
    )
    has_failures = any(item.get("severity") == "fail" for item in findings)
    executable_verified = bool(documented_entrypoints) and (
        replay_passed or legacy_smoke_passed
    ) and not has_failures
    for finding in findings:
        finding_id = str(finding.get("id") or "")
        if finding_id == "requirement_evidence_reference_only" and executable_verified:
            finding.pop("reviewRequired", None)
            finding["reviewSatisfiedBy"] = (
                "offline_replay" if replay_passed else "deterministic_smoke"
            )
        elif (
            finding_id == "structured_self_check_missing"
            and legacy_smoke_passed
            and not has_failures
        ):
            finding.pop("reviewRequired", None)
            finding["reviewSatisfiedBy"] = "deterministic_smoke"
        elif finding_id == "implementation_evidence_incomplete" and not documented_entrypoints:
            finding["reviewRequired"] = True


def _check(check_id: str, status: str, message: str, **extra: Any) -> dict[str, Any]:
    value = {"id": check_id, "status": status, "message": message}
    value.update(extra)
    return value


def _signature_changes(
    before: tuple[tuple[str, str], ...],
    after: tuple[tuple[str, str], ...],
) -> list[dict[str, str]]:
    baseline = dict(before)
    candidate = dict(after)
    changes: list[dict[str, str]] = []
    for path in sorted(set(baseline) | set(candidate)):
        if baseline.get(path) == candidate.get(path):
            continue
        change = "modified"
        if path not in baseline:
            change = "added"
        elif path not in candidate:
            change = "deleted"
        changes.append({"path": path, "change": change})
    return changes


def _apply_offline_protocol_gate(
    *,
    findings: list[dict[str, Any]],
    checks: list[dict[str, Any]],
    mode: str,
) -> None:
    """Apply the explicit rollout policy to self-check protocol evidence.

    The underlying validators remain strict and their details are persisted.
    Shadow mode only changes delivery severity/status for protocol/harness
    findings; deterministic package and business execution failures remain
    blocking. A missing controller-generated plan is a package completeness
    failure and is intentionally outside this rollout policy. ``wouldBlock``
    makes the policy decision visible to operators.
    """

    normalized_mode = str(mode or "shadow").strip().lower()
    if normalized_mode != "shadow":
        return
    blocking_protocol_findings = set()
    for finding in findings:
        if str(finding.get("id") or "") != "self_check_protocol_invalid":
            continue
        for issue in finding.get("details") or []:
            if not isinstance(issue, dict):
                continue
            if str(issue.get("id") or "") in _BLOCKING_OFFLINE_PROTOCOL_ISSUE_IDS:
                blocking_protocol_findings.add(id(finding))
                break
    for finding in findings:
        finding_id = str(finding.get("id") or "")
        root_cause_id = str(finding.get("rootCauseId") or "")
        agent_protocol_id = root_cause_id.removeprefix("agent:")
        if (
            finding_id not in _OFFLINE_PROTOCOL_FINDING_IDS
            and agent_protocol_id not in _OFFLINE_PROTOCOL_FINDING_IDS
            and not finding.get("offlineProtocolGateEligible")
        ):
            continue
        if finding.get("severity") != "fail":
            continue
        if id(finding) in blocking_protocol_findings:
            continue
        finding["severity"] = "warn"
        finding["gateMode"] = "shadow"
        finding["wouldBlock"] = True
        finding["reviewRequired"] = True
        # Protocol failures identify the harness/evidence boundary, not a
        # package repair target.  Prevent the bounded repair loop from
        # rewriting business files to satisfy a host-dependent check.
        finding["repairable"] = False
        finding["failureOwner"] = (
            "environment"
            if finding.get("offlineProtocolGateEligible")
            else "controller"
        )
    downgraded_replay = any(
        str(item.get("id") or "") == "offline_replay_failed"
        and item.get("offlineProtocolGateEligible")
        and item.get("severity") == "warn"
        for item in findings
    )
    for check in checks:
        check_id = str(check.get("id") or "")
        downgrade_check = (
            check_id in _OFFLINE_PROTOCOL_CHECK_IDS
            and check.get("status") == "fail"
            and (check_id != "offline_replay" or downgraded_replay)
        )
        if downgrade_check:
            check["status"] = "warn"
            check["gateMode"] = "shadow"
            check["wouldBlock"] = True
    # A structured protocol/summary failure is represented by offline_smoke,
    # but a real internal command failure has its own business finding and
    # must remain fail.  Downgrade only when no such hard failure exists.
    hard_execution_ids = {
        "offline_smoke_failed",
        "offline_smoke_business_failed",
    }
    has_hard_execution_failure = any(
        str(item.get("id") or "") in hard_execution_ids
        and item.get("severity") == "fail"
        for item in findings
    )
    has_missing_controller_plan = any(
        str(item.get("id") or "") == "structured_self_check_missing"
        and item.get("severity") == "fail"
        for item in findings
    )
    if (
        not has_hard_execution_failure
        and not blocking_protocol_findings
        and not has_missing_controller_plan
    ):
        for check in checks:
            if check.get("id") == "offline_smoke" and check.get("status") == "fail":
                check["status"] = "warn"
                check["gateMode"] = "shadow"
                check["wouldBlock"] = True


_OPTIONAL_EXTERNAL_RUNTIME_RE = re.compile(
    r"(?:ModuleNotFoundError|ImportError|No module named|cannot import name).*"
    r"\b(?:playwright|selenium|puppeteer|requests|httpx|aiohttp)\b|"
    r"(?:playwright|selenium|puppeteer|browser|浏览器).{0,120}"
    r"(?:不可用|未安装|启动失败|not available|not installed|blocked|"
    r"executable (?:doesn't|does not) exist|install (?:playwright|chromium))|"
    r"(?:executable (?:doesn't|does not) exist|playwright install)",
    re.IGNORECASE,
)
_PYTHON_RUNTIME_EXCEPTION_RE = re.compile(
    r"\b(?:NameError|UnboundLocalError|AttributeError|TypeError|ValueError|"
    r"ZeroDivisionError|KeyError|IndexError|AssertionError|SyntaxError):",
    re.IGNORECASE,
)


def _replay_python_runtime_failures(replay: Any) -> list[dict[str, Any]]:
    """Return one repairable runtime root only when it explains every failed case."""

    failed_cases = {
        str(item.get("id") or ""): item
        for item in getattr(replay, "checks", ()) or ()
        if isinstance(item, dict) and str(item.get("status") or "") == "fail"
    }
    if not failed_cases:
        return []
    details: list[dict[str, Any]] = []
    explained: set[str] = set()
    for issue in getattr(replay, "issues", ()) or ():
        if not isinstance(issue, dict):
            continue
        case_id = str(issue.get("caseId") or "")
        case = failed_cases.get(case_id)
        if case is None or str(case.get("kind") or "") not in {
            "happy_path",
            "business_rule",
            "file_handoff",
            "external_offline",
        }:
            continue
        stderr = str(issue.get("stderr") or "")
        actual = str(issue.get("actual") or "")
        diagnostics = f"{stderr}\n{actual}"
        if (
            str(issue.get("id") or "")
            in {
                "self_check_replay_command_failed",
                "self_check_replay_assertion_failed",
            }
            and _PYTHON_RUNTIME_EXCEPTION_RE.search(diagnostics)
        ):
            explained.add(case_id)
            projected = {}
            for key in (
                "id",
                "caseId",
                "message",
                "stderr",
                "actual",
                "assertion",
                "command",
                "actualExitCode",
                "expectedExitCodes",
                "producerPaths",
            ):
                if issue.get(key) not in (None, "", []):
                    projected[key] = issue.get(key)
            details.append(projected)
    return details if explained == set(failed_cases) else []


def _replay_missing_output_failures(replay: Any) -> list[dict[str, Any]]:
    """Return one CLI-output root when successful commands omit every expected file."""

    failed_cases = {
        str(item.get("id") or ""): item
        for item in getattr(replay, "checks", ()) or ()
        if isinstance(item, dict) and str(item.get("status") or "") == "fail"
    }
    if not failed_cases:
        return []
    issues_by_case: dict[str, list[dict[str, Any]]] = {}
    for issue in getattr(replay, "issues", ()) or ():
        if isinstance(issue, dict):
            issues_by_case.setdefault(str(issue.get("caseId") or ""), []).append(
                issue
            )
    details: list[dict[str, Any]] = []
    for case_id, case in failed_cases.items():
        commands = [
            command
            for command in case.get("commands") or []
            if isinstance(command, dict)
        ]
        case_issues = issues_by_case.get(case_id, [])
        invalid_missing_output_case = (
            not commands
            or any(
                command.get("timedOut") is True
                or command.get("exitCode") not in (0, None)
                for command in commands
            )
            or not case_issues
            or any(
                str(issue.get("id") or "")
                != "self_check_replay_assertion_failed"
                or str(issue.get("sourceError") or "") != "missing"
                for issue in case_issues
            )
        )
        if invalid_missing_output_case:
            return []
        for issue in case_issues:
            projected = {}
            for key in ("id", "caseId", "message", "assertion", "sourceError", "producerPaths"):
                if issue.get(key) not in (None, "", []):
                    projected[key] = issue.get(key)
            details.append(projected)
    return details


def _replay_is_external_only(
    replay: Any,
    *,
    external_entrypoints: set[str] | None = None,
) -> bool:
    """Whether all failed replay cases are external-runtime cases.

    A browser/API replay cannot be interpreted as an offline business failure
    when its optional runtime is absent. Invalid-input, business-rule and file
    handoff cases remain authoritative and are never downgraded.
    """

    checks = list(getattr(replay, "checks", ()) or ())
    failed = [item for item in checks if str(item.get("status") or "") == "fail"]
    if not failed:
        return False
    external = set(external_entrypoints or set())
    for case in failed:
        if str(case.get("kind") or "") not in {
            "happy_path",
            "external_offline",
        }:
            return False
        commands = [
            command for command in case.get("commands") or []
            if isinstance(command, dict)
        ]
        if not commands:
            return False
        # A failed case is environment-only only when every command in it
        # crosses a known external entrypoint and emitted an optional-runtime
        # diagnostic.  A model-authored ``kind`` is deliberately ignored.
        for command in commands:
            script = _command_script(command.get("command") or [])
            diagnostics = "\n".join(
                str(command.get(name) or "") for name in ("stdout", "stderr")
            )
            if (
                script not in external
                or _PYTHON_RUNTIME_EXCEPTION_RE.search(diagnostics)
                or not _OPTIONAL_EXTERNAL_RUNTIME_RE.search(diagnostics)
            ):
                return False
    return True


def _external_success_evidence(
    root: Path,
    replay_checks: list[dict[str, Any]],
    *,
    external_entrypoints: set[str] | None = None,
) -> bool:
    """Whether an external production entrypoint consumed a response fixture."""

    owned_inputs = platform_owned_fixture_paths(root, root / "generated-skill")
    external = set(external_entrypoints or set())
    blocked_markers = re.compile(
        r"(?:\bblocked\b|待查询|暂无法确定|浏览器.+失败|API.+失败|未获取到有效)",
        re.IGNORECASE,
    )
    for case in replay_checks:
        if str(case.get("status") or "") != "pass" or str(
            case.get("kind") or ""
        ) not in {"happy_path", "business_rule", "file_handoff"}:
            continue
        assertion_text = json.dumps(
            case.get("assertions") or [],
            ensure_ascii=False,
            default=str,
        )
        if blocked_markers.search(assertion_text):
            continue
        for command in case.get("commands") or []:
            if not isinstance(command, dict):
                continue
            command_tokens = command.get("command") or []
            if _command_script(command_tokens) not in external:
                continue
            fixture_paths = set()
            for token in command_tokens:
                normalized_token = str(token)
                if normalized_token.startswith(("fixtures/", "generated-skill/fixtures/")):
                    fixture_paths.add(normalized_token.removeprefix("generated-skill/"))
            response_fixtures = set()
            for path in fixture_paths - owned_inputs:
                if Path(path).suffix.lower() in {".json", ".jsonl", ".html", ".htm"}:
                    response_fixtures.add(path)
            if response_fixtures:
                return True
    return False


def _choose_smoke_target(
    generated: Path,
    *,
    excluded_fixture_paths: set[str] | None = None,
) -> tuple[Path, Path] | None:
    scripts = sorted((generated / "scripts").glob("*.py"))
    try:
        skill_text = (generated / "SKILL.md").read_text(
            encoding="utf-8",
            errors="replace",
        )
    except OSError:
        skill_text = ""
    excluded = set(excluded_fixture_paths or set())
    fixtures = []
    fixtures_root = generated / "fixtures"
    if fixtures_root.is_dir():
        supported_suffixes = {".csv", ".json", ".jsonl", ".xlsx", ".md", ".txt"}
        for path in fixtures_root.rglob("*"):
            if not path.is_file():
                continue
            relative = path.relative_to(generated).as_posix()
            if relative not in excluded and path.suffix.lower() in supported_suffixes:
                fixtures.append(path)
        fixtures.sort()
    candidates: list[tuple[int, Path, Path]] = []
    for script in scripts:
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if _script_input_mode(source) and fixtures:
            stem = script.stem.lower()
            lowered = source.lower()
            for fixture in fixtures:
                suffix = fixture.suffix.lower()
                score = 0
                # Validation/checklist entry points are the only safe default
                # for a package with several pipeline scripts.  Orchestrators
                # such as run_inspection.py may require a browser or network.
                if any(token in stem for token in ("validate", "check", "lint")):
                    score += 100
                if stem.startswith("run_") or stem.endswith("_pipeline"):
                    score -= 60
                if suffix == ".csv":
                    score += 30 if ("csv" in lowered or "dictreader" in lowered) else 0
                    score += 50 if "checklist" in stem else 0
                elif suffix in {".json", ".jsonl"}:
                    score += 30 if ("json.load" in lowered or "json.loads" in lowered) else 0
                elif suffix == ".xlsx":
                    score += 40 if ("openpyxl" in lowered or "load_workbook" in lowered) else 0
                elif suffix in {".txt", ".md"}:
                    score += 10 if "open(" in lowered or "read_text" in lowered else 0
                # Prefer a fixture whose name resembles the script input.
                if any(token in fixture.stem.lower() for token in stem.replace("_", "-").split("-")):
                    score += 8
                # A package may contain several valid-looking happy fixtures.
                # Prefer the one the Skill documents for its production CLI.
                fixture_path = fixture.relative_to(generated).as_posix()
                if fixture_path in skill_text:
                    score += 200
                fixture_name = fixture.stem.lower().replace("_", "-")
                if "happy" in fixture_name:
                    score += 100
                elif "business" in fixture_name:
                    score += 80
                elif "valid" in fixture_name:
                    score += 60
                if any(
                    token in fixture_name
                    for token in ("invalid", "missing", "empty", "error")
                ):
                    score -= 150
                candidates.append((score, script, fixture))
    if not candidates:
        return None
    _score, script, fixture = max(
        candidates,
        key=lambda item: (item[0], -len(item[1].name), item[1].name, item[2].name),
    )
    return script, fixture


def _choose_offline_pipeline_entry(generated: Path) -> Path | None:
    """Select one explicit package-owned offline pipeline entry point.

    A generated multi-step Skill may expose ``run_offline_test.py`` or
    ``self_check.py``.  Once present, Core executes that single entry instead
    of guessing an order from individual filenames.  The legacy heuristic is
    retained only for packages that do not provide an explicit entry.
    """

    scripts = generated / "scripts"
    if not scripts.is_dir():
        return None
    for name in ("self_check.py", "run_offline_test.py", "offline_test.py"):
        candidate = scripts / name
        if candidate.is_file():
            return candidate
    return None


def _script_input_mode(source: str) -> str | None:
    """Return the safe CLI input shape understood by the smoke runner.

    ``--input`` is preferred for newly authored Skills.  Positional inputs are
    still recognized so a first-version package is not silently reported as
    ``skip`` merely because its generated script uses ``sys.argv[1]``.
    """

    if _INPUT_ARGUMENT_RE.search(source):
        return "flag"
    if _POSITIONAL_INPUT_RE.search(source):
        return "positional"
    return None


def _script_supports_output(source: str) -> bool:
    """Return whether a script exposes the conventional output option."""

    return bool(_OUTPUT_ARGUMENT_RE.search(source) or _OUTPUT_TOKEN_RE.search(source))


def _script_output_mode(source: str) -> str | None:
    """Return whether the conventional output argument is a file or directory."""

    if _OUTPUT_DIR_ARGUMENT_RE.search(source):
        return "directory"
    if not _script_supports_output(source):
        return None
    if _OUTPUT_DIRECTORY_DIRECT_RE.search(source):
        return "directory"
    aliases = set()
    for match in re.finditer(
        r"\b([A-Za-z_]\w*)\s*=\s*(?:Path\s*\(\s*)?args\.output\s*\)?",
        source,
    ):
        aliases.add(match.group(1))
    if any(
        re.search(
            rf"(?:os\.makedirs|os\.mkdir)\s*\(\s*{re.escape(alias)}\b"
            rf"|Path\s*\(\s*{re.escape(alias)}\s*\)\.mkdir\s*\("
            rf"|\b{re.escape(alias)}\.mkdir\s*\("
            rf"|os\.path\.join\(\s*{re.escape(alias)}\s*,",
            source,
            re.IGNORECASE,
        )
        for alias in aliases
    ):
        return "directory"
    return "file"


_BUSINESS_OUTPUT_SUFFIXES = frozenset(
    {".csv", ".html", ".json", ".jsonl", ".md", ".txt", ".xlsx"}
)


def _script_output_suffix(source: str, *, documentation: str = "") -> str:
    """Infer the output suffix from argparse, then the documented CLI."""

    try:
        tree = ast.parse(source)
    except SyntaxError:
        return ".json"
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or not node.args:
            continue
        function = node.func
        if not (
            isinstance(function, ast.Attribute) and function.attr == "add_argument"
            or isinstance(function, ast.Name) and function.id == "add_argument"
        ):
            continue
        first = node.args[0]
        if not isinstance(first, ast.Constant) or first.value != "--output":
            continue
        default = ""
        for keyword in node.keywords:
            if keyword.arg != "default" or not isinstance(keyword.value, ast.Constant):
                continue
            if isinstance(keyword.value.value, str):
                default = keyword.value.value
                break
        suffix = Path(default).suffix.lower()
        if suffix in _BUSINESS_OUTPUT_SUFFIXES:
            return suffix
    for match in re.finditer(
        r"--output(?!-dir)\b(?:\s+|=)[\"']?([^\s`\"']+)",
        documentation,
        re.IGNORECASE,
    ):
        suffix = Path(match.group(1).rstrip(".,;:)])")).suffix.lower()
        if suffix in _BUSINESS_OUTPUT_SUFFIXES:
            return suffix
    return ".json"


def _business_output_invariant_issues(path: Path) -> list[dict[str, Any]]:
    """Check a few format-level business invariants without guessing semantics."""

    def reject_non_finite(value: str) -> None:
        raise ValueError(f"non-finite JSON number: {value}")

    try:
        payload = json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=reject_non_finite,
        )
    except (OSError, TypeError, ValueError) as exc:
        return [
            {
                "id": "business_output_json_invalid",
                "path": path.name,
                "message": str(exc)[:500],
            }
        ]
    if not isinstance(payload, dict):
        return []
    issues: list[dict[str, Any]] = []
    for total_key, valid_key, error_key in (
        ("total_rows", "valid_rows", "error_rows"),
        ("total_count", "valid_count", "error_count"),
    ):
        values = [payload.get(key) for key in (total_key, valid_key, error_key)]
        if not all(isinstance(value, int) and not isinstance(value, bool) for value in values):
            continue
        total, valid, error = values
        if total != valid + error:
            issues.append(
                {
                    "id": "business_output_count_mismatch",
                    "path": path.name,
                    "totalField": total_key,
                    "validField": valid_key,
                    "errorField": error_key,
                    "actual": {total_key: total, valid_key: valid, error_key: error},
                }
            )
    return issues


def _materialized_output_files(output_path: Path | None) -> list[Path]:
    """Resolve file, directory, or conventional output-prefix materialization."""

    if output_path is None:
        return []
    if output_path.is_dir():
        return sorted(path for path in output_path.rglob("*") if path.is_file())
    if output_path.is_file():
        return [output_path]
    return sorted(
        path
        for path in output_path.parent.glob(f"{output_path.name}.*")
        if path.is_file()
    )


def _remove_materialized_output(output_path: Path | None) -> None:
    if output_path is None:
        return
    files = _materialized_output_files(output_path)
    if output_path.is_dir():
        shutil.rmtree(output_path, ignore_errors=True)
        return
    for path in files:
        path.unlink(missing_ok=True)


def _materialize_csv_edge_fixture(
    root: Path,
    fixture: Path,
) -> Path | None:
    """Create one private invalid-row variant from a real business CSV fixture."""

    if fixture.suffix.lower() != ".csv":
        return None
    try:
        with fixture.open(encoding="utf-8", newline="") as stream:
            reader = csv.DictReader(stream)
            rows = list(reader)
            headers = list(reader.fieldnames or [])
    except (OSError, UnicodeError, csv.Error):
        return None
    if not headers or not rows:
        return None
    if any(any(key not in headers for key in row) for row in rows):
        return None
    try:
        scenario = json.loads(
            (root / "validation" / "scenario_contract.json").read_text(
                encoding="utf-8"
            )
        )
    except (OSError, TypeError, ValueError):
        return None
    contracts = scenario_structured_input_contracts(scenario)
    required = []
    for contract in contracts:
        if "csv" not in str(contract.get("format") or "").lower():
            continue
        for name in contract.get("required_columns") or []:
            normalized_name = str(name).strip()
            if normalized_name:
                required.append(normalized_name)
    field = next((name for name in required if name in headers), headers[0])
    rows[0][field] = ""
    target = (
        root
        / "workspace"
        / "verify"
        / f".skill-builder-edge-{uuid.uuid4().hex}.csv"
    )
    target.parent.mkdir(parents=True, exist_ok=True)
    try:
        with target.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=headers)
            writer.writeheader()
            writer.writerows(rows)
    except (OSError, ValueError, csv.Error):
        target.unlink(missing_ok=True)
        return None
    return target


def _append_runtime_fixture_validation_rule(
    validation_rules: list[dict[str, Any]],
    fields: list[dict[str, Any]],
    rule: Any,
    *,
    field_name: str = "",
    requirement_id: str = "",
) -> None:
    if rule in (None, "", [], {}):
        return
    rule_text = rule if isinstance(rule, str) else json.dumps(rule, ensure_ascii=False, sort_keys=True)
    matching_fields = (
        [field_name]
        if field_name
        else [
            str(field["name"]).strip()
            for field in fields
            if str(field["name"]).strip() in rule_text
        ]
    )
    for matching_field in matching_fields or [""]:
        projected_rule: dict[str, Any] = {"rule": rule}
        if matching_field:
            projected_rule["field"] = matching_field
        if requirement_id:
            projected_rule["requirementId"] = requirement_id
        if projected_rule not in validation_rules:
            validation_rules.append(projected_rule)


def _runtime_fixture_input_contracts(
    root: Path,
    fixture: Path,
) -> list[dict[str, Any]]:
    """Project the Scenario contract relevant to one failed input fixture."""

    scenario = _load_json_object(root / "validation" / "scenario_contract.json")
    suffix = fixture.suffix.lower()
    format_tokens = {
        ".csv": ("csv",),
        ".xls": ("xls", "excel", "表格"),
        ".xlsx": ("xlsx", "excel", "表格"),
        ".json": ("json",),
        ".jsonl": ("jsonl", "ndjson"),
    }.get(suffix, ())
    if not format_tokens:
        return []
    result: list[dict[str, Any]] = []
    for contract in scenario_structured_input_contracts(scenario):
        format_value = str(contract.get("format") or "").strip()
        format_text = format_value.lower()
        if format_tokens and not any(token in format_text for token in format_tokens):
            continue
        fields = [
            field
            for field in contract.get("fields") or []
            if isinstance(field, dict) and str(field.get("name") or "").strip()
        ]
        validation_rules: list[dict[str, Any]] = []

        for field in fields:
            rule = None
            for key in ("validation", "validationRule", "validation_rule", "校验规则", "校验"):
                if field.get(key) not in (None, "", [], {}):
                    rule = field.get(key)
                    break
            _append_runtime_fixture_validation_rule(
                validation_rules,
                fields,
                rule,
                field_name=str(field["name"]).strip(),
            )
        contract_rules = []
        for key in ("validationRules", "validation_rules", "validation", "校验规则"):
            if contract.get(key) not in (None, "", [], {}):
                contract_rules = contract.get(key)
                break
        for rule in contract_rules if isinstance(contract_rules, list) else [contract_rules]:
            _append_runtime_fixture_validation_rule(validation_rules, fields, rule)
        for requirement in scenario.get("resolvedRequirements") or []:
            if not isinstance(requirement, dict):
                continue
            rule = requirement.get("value") or requirement.get("sourceQuote")
            _append_runtime_fixture_validation_rule(
                validation_rules,
                fields,
                rule,
                requirement_id=str(
                    requirement.get("requirementId") or ""
                ).strip(),
            )
        projected: dict[str, Any] = {
            "format": format_value,
            "fields": [str(field["name"]).strip() for field in fields],
            "requiredFields": [
                str(field["name"]).strip()
                for field in fields
                if field.get("required") is True
            ],
            "validationRules": validation_rules,
        }
        if str(contract.get("name") or "").strip():
            projected["name"] = str(contract["name"]).strip()
        result.append(projected)
    return result


def _safe_local_mode_arguments(source: str) -> list[str]:
    """Return only explicit CLI modes that promise no external runtime."""

    if _USE_FIXTURE_ARGUMENT_RE.search(source):
        return ["--use-fixture"]
    if _OFFLINE_ARGUMENT_RE.search(source):
        return ["--offline"]
    if _QUERY_MODE_ARGUMENT_RE.search(source) and re.search(
        r"['\"]offline['\"]", source, re.IGNORECASE
    ):
        return ["--query-mode", "offline"]
    return []


_OFFLINE_EXTERNAL_CLAIM_RE = re.compile(
    r"(?:基于|依据).{0,24}(?:公开|官方|在线|实时).{0,12}(?:查询|检索|数据|结果)"
    r"|(?:based on|using).{0,24}(?:official|public|online|live).{0,16}"
    r"(?:query|search|data|results?)",
    re.IGNORECASE,
)
_OFFLINE_NEGATION_RE = re.compile(
    r"(?:未|没有|并未|不曾).{0,12}(?:查询|检索|访问|联网)"
    r"|(?:离线|本地).{0,12}(?:模拟|规则|fixture)"
    r"|(?:not|never|without).{0,12}(?:queried|searched|accessed|online)"
    r"|offline.{0,12}(?:simulation|rules?|fixture)",
    re.IGNORECASE,
)


def _offline_output_claim_issues(output_path: Path) -> list[dict[str, Any]]:
    """Reject affirmative online evidence claims from a proven offline run."""

    files = (
        sorted(path for path in output_path.rglob("*") if path.is_file())
        if output_path.is_dir()
        else [output_path]
        if output_path.is_file()
        else []
    )
    issues: list[dict[str, Any]] = []
    for path in files:
        if path.suffix.lower() not in {".md", ".txt", ".json", ".jsonl"}:
            continue
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for line_number, line in enumerate(lines, start=1):
            if _OFFLINE_EXTERNAL_CLAIM_RE.search(line) and not _OFFLINE_NEGATION_RE.search(
                line
            ):
                issues.append(
                    {
                        "id": "offline_output_external_evidence_claim",
                        "path": path.name,
                        "line": line_number,
                        "text": line[:500],
                    }
                )
    return issues


def _external_runtime_scripts(scripts: list[Path]) -> list[str]:
    """List scripts whose execution depends on a network/browser runtime.

    This is an evidence boundary, not a business gate: the package may still
    be delivered, but acceptance must say that the capability was not run.
    """

    result: list[str] = []
    for script in scripts:
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if python_runtime_call_capabilities(source):
            result.append(script.as_posix())
    return result


def self_check_protocol_requirements(
    root: Path,
    generated: Path | None = None,
    *,
    external_runtime_scripts: set[str] | list[str] | tuple[str, ...] | None = None,
) -> dict[str, Any]:
    """Return the single package/Scenario context used to validate replay plans."""

    package_root = generated or root / "generated-skill"
    documented_entrypoints = documented_cli_entrypoints(package_root)
    if external_runtime_scripts is None:
        scripts_dir = package_root / "scripts"
        scripts = (
            sorted(scripts_dir.rglob("*.py"))
            if scripts_dir.is_dir()
            else []
        )
        external_paths = set(_external_runtime_scripts(scripts))
    else:
        external_paths = {
            str(path).replace("\\", "/")
            for path in external_runtime_scripts
        }
    external_entrypoints = {
        relative
        for relative in documented_entrypoints
        if (package_root / relative).as_posix() in external_paths
    }
    output_fields, output_sections = scenario_output_invariants(root)
    return {
        "documented_entrypoints": documented_entrypoints,
        "required_contract_ids": scenario_required_contract_ids(root),
        "require_invalid_input": scenario_has_structured_inputs(root),
        "require_invalid_nonzero_exit": scenario_invalid_input_requires_nonzero_exit(root),
        "require_business_value_assertion": bool(
            documented_entrypoints
            and scenario_has_structured_inputs(root)
            and not external_entrypoints
        ),
        # Multiple public CLIs are not necessarily a pipeline. Each CLI still
        # participates in coverage diagnostics, while file_handoff remains an
        # optional explicit case when one command actually consumes another's
        # output.
        "require_file_handoff": False,
        "external_entrypoints": external_entrypoints,
        "required_output_fields": output_fields,
        "required_output_sections": output_sections,
    }


def _fixture_business_literals(generated: Path) -> dict[str, list[str]]:
    """Index distinctive sample values from exported fixtures."""

    fixtures = generated / "fixtures"
    values: dict[str, list[str]] = {}

    def add(value: Any, source: Path) -> None:
        if isinstance(value, bool) or value is None:
            return
        text = str(value).strip()
        if len(text) < 4 or text.lower() in {"none", "null", "true", "false", "n/a"}:
            return
        values.setdefault(text, []).append(source.relative_to(generated).as_posix())

    def visit(value: Any, source: Path) -> None:
        if isinstance(value, dict):
            for item in value.values():
                visit(item, source)
        elif isinstance(value, list):
            for item in value:
                visit(item, source)
        else:
            add(value, source)

    if not fixtures.is_dir():
        return values
    for fixture in sorted(path for path in fixtures.rglob("*") if path.is_file()):
        suffix = fixture.suffix.lower()
        try:
            if suffix in {".json", ".jsonl"}:
                if suffix == ".json":
                    visit(json.loads(fixture.read_text(encoding="utf-8")), fixture)
                else:
                    for line in fixture.read_text(encoding="utf-8").splitlines():
                        if line.strip():
                            visit(json.loads(line), fixture)
            elif suffix == ".csv":
                with fixture.open("r", encoding="utf-8-sig", newline="") as handle:
                    for row in csv.reader(handle):
                        for item in row:
                            add(item, fixture)
            elif suffix in {".md", ".markdown", ".txt"}:
                for line in fixture.read_text(encoding="utf-8", errors="replace").splitlines():
                    if "|" not in line:
                        continue
                    for cell in line.strip().strip("|").split("|"):
                        add(cell, fixture)
        except (OSError, TypeError, ValueError, csv.Error):
            continue
    return values


def _hardcoded_sample_business_values(generated: Path, scripts: list[Path]) -> list[dict[str, Any]]:
    """Detect fixture record values copied into production result dictionaries.

    Requiring a business-sensitive dictionary key and an exact fixture match
    keeps this guard focused on record data, not URLs, selectors, enums, or
    documented business thresholds.
    """

    fixture_values = _fixture_business_literals(generated)
    result: list[dict[str, Any]] = []
    if not fixture_values:
        return result
    for script in scripts:
        if script.name in _OFFLINE_DIAGNOSTIC_ENTRYPOINTS:
            continue
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
            tree = ast.parse(source)
        except (OSError, SyntaxError):
            continue
        for node in ast.walk(tree):
            if not isinstance(node, ast.Dict):
                continue
            for key_node, value_node in zip(node.keys, node.values):
                if not (
                    isinstance(key_node, ast.Constant)
                    and isinstance(key_node.value, str)
                    and isinstance(value_node, ast.Constant)
                    and isinstance(value_node.value, (str, int, float))
                    and not isinstance(value_node.value, bool)
                ):
                    continue
                key = key_node.value.strip()
                value = str(value_node.value).strip()
                if not _BUSINESS_LITERAL_KEY_RE.search(key) or value not in fixture_values:
                    continue
                result.append(
                    {
                        "path": script.relative_to(generated).as_posix(),
                        "line": int(getattr(value_node, "lineno", getattr(node, "lineno", 1))),
                        "field": key,
                        "value": value[:200],
                        "fixtureSources": sorted(set(fixture_values[value]))[:10],
                    }
                )
    return result


_UNRESOLVED_OUTPUT_RE = re.compile(
    r"(?:待查询|待确定|待解析|尚未查询|尚未确定|not\s+(?:queried|resolved|parsed)|to\s+be\s+(?:queried|resolved|parsed))",
    re.IGNORECASE,
)
_OPTION_LIST_RE = re.compile(
    r"(?:可选|支持|选项|二选一|(?:选择|采用).{0,8}(?:之一|其一)|choices?|options?|either)",
    re.IGNORECASE,
)
_NEGATED_OPTION_RE = re.compile(
    r"(?:不支持|禁止|不得|不能|仅支持|固定为|fixed\s+(?:at|to)|unsupported|invalid)",
    re.IGNORECASE,
)
_CONFIG_KEY_RE = re.compile(r"`([A-Za-z_][A-Za-z0-9_.-]*)`")
_RUNTIME_CONFIG_CONTEXT_RE = re.compile(
    r"(?:参数|配置|选项|默认|固定|限制|命令行|CLI|"
    r"--[A-Za-z0-9_-]+|\b(?:config|option|parameter|default|fixed|limit|max)\b)",
    re.IGNORECASE,
)
_DECISION_NUMBER_RE = re.compile(r"(?<!\d)[-+]?\d+(?:\.\d+)?(?!\d)")
_CONFIGURABLE_OPTION_RE = re.compile(
    r"(?:configurable|selective|选择性|可配置|用户.{0,8}(?:指定|选择|配置)|按用户)",
    re.IGNORECASE,
)


def _is_statically_empty(node: ast.AST) -> bool:
    if isinstance(node, ast.Constant):
        return node.value in {None, ""}
    return isinstance(node, (ast.List, ast.Tuple, ast.Set, ast.Dict)) and not (
        node.elts if hasattr(node, "elts") else node.keys
    )


def _self_attribute(target: ast.AST) -> str | None:
    if (
        isinstance(target, ast.Attribute)
        and isinstance(target.value, ast.Name)
        and target.value.id == "self"
    ):
        return target.attr
    return None


def _attribute_name(target: ast.AST) -> str | None:
    return target.attr if isinstance(target, ast.Attribute) else None


def _unresolved_output_fallback(node: ast.AST) -> str | None:
    if not isinstance(node, ast.BoolOp) or not isinstance(node.op, ast.Or):
        return None
    for value in node.values[1:]:
        if (
            isinstance(value, ast.Constant)
            and isinstance(value.value, str)
            and _UNRESOLVED_OUTPUT_RE.search(value.value)
        ):
            return value.value
    return None


def _attribute_names(node: ast.AST) -> set[str]:
    return {
        item.attr
        for item in ast.walk(node)
        if isinstance(item, ast.Attribute) and isinstance(item.ctx, ast.Load)
    }


def _dead_structured_output_fields(
    generated: Path,
    scripts: list[Path],
) -> list[dict[str, Any]]:
    """Find output fields that can only render an unresolved placeholder.

    This deliberately requires three static facts in one production script:
    an empty ``self.<field>`` initialization, no non-empty assignment to that
    field, and use of that field with an explicit unresolved fallback in an
    output expression. Optional empty metadata is therefore not rejected.
    """

    result: list[dict[str, Any]] = []
    for script in scripts:
        if script.name in _OFFLINE_DIAGNOSTIC_ENTRYPOINTS:
            continue
        try:
            source = script.read_text(encoding="utf-8", errors="replace")
            tree = ast.parse(source)
        except (OSError, SyntaxError):
            continue
        empty_initializers: dict[str, int] = {}
        live_assignments: set[str] = set()
        for node in ast.walk(tree):
            if isinstance(node, (ast.Assign, ast.AnnAssign)):
                targets = node.targets if isinstance(node, ast.Assign) else [node.target]
                value = node.value
                for target in targets:
                    name = _attribute_name(target)
                    if not name or value is None:
                        continue
                    if _self_attribute(target) and _is_statically_empty(value):
                        empty_initializers.setdefault(name, int(getattr(node, "lineno", 1)))
                    elif not _is_statically_empty(value):
                        live_assignments.add(name)
            elif isinstance(node, ast.AugAssign):
                name = _attribute_name(node.target)
                if name:
                    live_assignments.add(name)

        reported: set[str] = set()
        for node in ast.walk(tree):
            fallback = _unresolved_output_fallback(node)
            if fallback is None:
                continue
            for field in sorted(_attribute_names(node)):
                if (
                    field not in empty_initializers
                    or field in live_assignments
                    or field in reported
                ):
                    continue
                result.append(
                    {
                        "path": script.relative_to(generated).as_posix(),
                        "field": field,
                        "initializedAtLine": empty_initializers[field],
                        "outputAtLine": int(getattr(node, "lineno", 1)),
                        "fallback": fallback[:100],
                    }
                )
                reported.add(field)
    return result


def _load_json_object(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8", errors="replace"))
    except (OSError, TypeError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _decision_token(value: Any) -> str:
    return " ".join(str(value or "").split()).strip()


def _line_contains_token(line: str, token: str) -> bool:
    if not token:
        return False
    if re.fullmatch(r"[A-Za-z0-9_.-]+", token):
        return re.search(rf"(?<![A-Za-z0-9_.-]){re.escape(token)}(?![A-Za-z0-9_.-])", line) is not None
    return token in line


def _line_token_spans(line: str, token: str) -> list[tuple[int, int]]:
    """Return exact token spans so overlapping Chinese labels are not double-counted."""

    if not token:
        return []
    if re.fullmatch(r"[A-Za-z0-9_.-]+", token):
        pattern = re.compile(
            rf"(?<![A-Za-z0-9_.-]){re.escape(token)}(?![A-Za-z0-9_.-])"
        )
        return [match.span() for match in pattern.finditer(line)]
    return [match.span() for match in re.finditer(re.escape(token), line)]


def _line_contains_distinct_options(
    line: str,
    selected_tokens: list[str],
    alternative_tokens: list[str],
) -> bool:
    """Require selected and alternative mentions to occupy distinct text spans."""

    selected_spans = [
        span
        for token in selected_tokens
        for span in _line_token_spans(line, token)
    ]
    if not selected_spans:
        return False
    for token in alternative_tokens:
        for alternative_start, alternative_end in _line_token_spans(line, token):
            if not any(
                selected_start <= alternative_start
                and alternative_end <= selected_end
                for selected_start, selected_end in selected_spans
            ):
                return True
    return False


def _decision_number_tokens(*values: Any) -> list[str]:
    """Extract numeric semantics even when an option value is enum-shaped.

    Scenario choices commonly encode a fixed quantity as ``first_50`` while
    presenting it as ``前 50 条``. Treat both representations as the same
    fixed value so a generated runtime cannot quietly expose it as an input.
    """

    return list(
        dict.fromkeys(
            match.group(0)
            for value in values
            for match in _DECISION_NUMBER_RE.finditer(_decision_token(value))
        )
    )


def _confirmed_decision_conflicts(root: Path, generated: Path) -> list[dict[str, Any]]:
    """Find exported choices that contradict a confirmed exclusive option."""

    manifest = _load_json_object(root / "validation" / "artifact_manifest.json")
    scenario = _load_json_object(root / "validation" / "scenario_contract.json")
    confirmed = manifest.get("confirmedDecisions")
    pending = scenario.get("pendingDecisions")
    if not isinstance(confirmed, list) or not isinstance(pending, list):
        return []
    decisions = {
        str(item.get("decisionId") or ""): item
        for item in pending
        if isinstance(item, dict) and str(item.get("decisionId") or "")
    }
    try:
        skill_lines = (generated / "SKILL.md").read_text(
            encoding="utf-8", errors="replace"
        ).splitlines()
    except OSError:
        skill_lines = []
    production_sources: list[tuple[Path, str]] = []
    scripts_dir = generated / "scripts"
    if scripts_dir.is_dir():
        for path in sorted(scripts_dir.rglob("*.py")):
            if path.name in _OFFLINE_DIAGNOSTIC_ENTRYPOINTS:
                continue
            try:
                production_sources.append(
                    (path, path.read_text(encoding="utf-8", errors="replace"))
                )
            except OSError:
                continue

    result: list[dict[str, Any]] = []
    for confirmation in confirmed:
        if not isinstance(confirmation, dict):
            continue
        decision_id = str(confirmation.get("decisionId") or "")
        decision = decisions.get(decision_id)
        options = decision.get("options") if isinstance(decision, dict) else None
        if not isinstance(options, list) or len(options) < 2:
            continue
        selected_value = confirmation.get("value")
        selected_display = _decision_token(confirmation.get("displayValue"))
        selected: dict[str, Any] | None = None
        for option in options:
            if not isinstance(option, dict):
                continue
            if selected_value not in (None, "") and option.get("value") == selected_value:
                selected = option
                break
            if selected_display and selected_display == _decision_token(option.get("label")):
                selected = option
                break
        if selected is None:
            continue
        selected_semantics = " ".join(
            _decision_token(selected.get(key))
            for key in ("value", "label", "description")
        )
        if _CONFIGURABLE_OPTION_RE.search(selected_semantics):
            continue
        selected_tokens = []
        selected_token_candidates = (
            _decision_token(selected.get("value")),
            _decision_token(selected.get("label")),
            selected_display,
        )
        for token in selected_token_candidates:
            if token and token not in selected_tokens:
                selected_tokens.append(token)
        selected_numbers = _decision_number_tokens(
            selected.get("value"),
            selected.get("label"),
            selected.get("description"),
            confirmation.get("semanticValue"),
            selected_display,
        )
        selected_tokens.extend(
            token for token in selected_numbers if token not in selected_tokens
        )
        has_fixed_numeric_value = bool(selected_numbers)
        alternatives = [
            option
            for option in options
            if isinstance(option, dict) and option is not selected
        ]
        alternative_tokens = []
        for option in alternatives:
            option_tokens = (
                _decision_token(option.get("value")),
                _decision_token(option.get("label")),
            )
            for token in option_tokens:
                if token and token not in alternative_tokens:
                    alternative_tokens.append(token)
        conflicting_lines: list[dict[str, Any]] = []
        config_keys: set[str] = set()
        for line_number, line in enumerate(skill_lines, start=1):
            has_selected = any(_line_contains_token(line, token) for token in selected_tokens)
            if (
                has_selected
                and has_fixed_numeric_value
                and _RUNTIME_CONFIG_CONTEXT_RE.search(line)
            ):
                config_keys.update(_CONFIG_KEY_RE.findall(line))
            documents_alternative = (
                has_selected
                and _line_contains_distinct_options(
                    line,
                    selected_tokens,
                    alternative_tokens,
                )
                and _OPTION_LIST_RE.search(line)
                and not _NEGATED_OPTION_RE.search(line)
            )
            if documents_alternative:
                conflicting_lines.append(
                    {"path": "SKILL.md", "line": line_number, "text": line[:300]}
                )

        dynamic_reads: list[dict[str, Any]] = []
        for config_key in sorted(config_keys):
            runtime_read_pattern = re.compile(
                rf"(?:"
                rf"\.get\(\s*['\"]{re.escape(config_key)}['\"](?:\s*,|\s*\))"
                rf"|\[\s*['\"]{re.escape(config_key)}['\"]\s*\]"
                rf")"
            )
            for path, source in production_sources:
                for match in runtime_read_pattern.finditer(source):
                    dynamic_reads.append(
                        {
                            "path": path.relative_to(generated).as_posix(),
                            "line": source.count("\n", 0, match.start()) + 1,
                            "configKey": config_key,
                        }
                    )
        if conflicting_lines or dynamic_reads:
            result.append(
                {
                    "decisionId": decision_id,
                    "title": decision.get("title") or decision_id,
                    "confirmedValue": selected.get("value"),
                    "confirmedDisplayValue": selected.get("label") or selected_display,
                    "conflictingDocumentation": conflicting_lines[:20],
                    "dynamicRuntimeReads": dynamic_reads[:20],
                }
            )
    return result


async def accept_skill_package(
    root: Path,
    *,
    execution_port: SkillBuilderExecutionPort | None = None,
    smoke_timeout_seconds: int = 60,
    agent_self_check: dict[str, Any] | None = None,
    heuristic_gate_mode: str | None = None,
    offline_protocol_gate_mode: str | None = None,
) -> AcceptanceResult:
    """Run portable checks and at most one host-sandboxed offline smoke."""

    started = time.monotonic()
    resolved_offline_protocol_gate_mode = resolve_offline_protocol_gate_mode(
        offline_protocol_gate_mode
    )
    package = validate_draft_package(root)
    findings: list[dict[str, Any]] = [*package.errors, *package.warnings]
    replayed_cases: list[dict[str, Any]] = []
    checks: list[dict[str, Any]] = [
        _check(
            "package_structure",
            "pass" if package.ok else "fail",
            "Skill 包结构和 frontmatter 校验完成。",
            files=list(package.files),
            fileCount=len(package.files),
        )
    ]

    generated = root / "generated-skill"
    scripts = sorted(path for path in (generated / "scripts").rglob("*.py")) if (generated / "scripts").is_dir() else []
    external_scripts = _external_runtime_scripts(scripts)
    external_script_paths = {
        Path(path).resolve()
        for path in external_scripts
    }
    capability_contract_issues = _resolved_capability_contract_issues(root)
    if capability_contract_issues:
        finding = _finding(
            "capability_contract_conflict",
            "平台能力契约存在内部冲突，不能把该问题交给包修复阶段。",
            severity="fail",
            category="contract_integrity",
            owner="controller",
            repairable=False,
        )
        finding["details"] = capability_contract_issues[:20]
        findings.append(finding)
    checks.append(
        _check(
            "capability_contract",
            "fail" if capability_contract_issues else "pass",
            "平台能力契约一致性检查完成。",
            issues=capability_contract_issues,
        )
    )
    platform_only_references = platform_only_document_references(generated)
    if platform_only_references:
        finding = _finding(
            "nonportable_package_reference",
            "导出文档引用了仅在 SkillBuilder workspace 中存在的路径；请将必要证据归纳或复制到 references/，并改用包内相对路径。",
            severity="fail",
            category="package_integrity",
        )
        finding["details"] = platform_only_references[:50]
        findings.append(finding)
    checks.append(
        _check(
            "package_portability",
            "fail" if platform_only_references else "pass",
            "导出文档可移植性检查完成。",
            platformOnlyReferences=platform_only_references,
        )
    )

    hardcoded_sample_values = _hardcoded_sample_business_values(generated, scripts)
    if hardcoded_sample_values:
        finding = _finding(
            "sample_business_data_hardcoded",
            "运行脚本把 fixture 中的样例客户、账户或交易值硬编码为业务结果；请改为从实际输入或页面结果读取。",
            severity="fail",
            category="executable_integrity",
            repairable=False,
        )
        finding["details"] = hardcoded_sample_values[:50]
        findings.append(finding)
    checks.append(
        _check(
            "sample_data_literals",
            "fail" if hardcoded_sample_values else "pass",
            "样例业务数据与运行实现隔离检查完成。",
            hardcodedValues=hardcoded_sample_values,
        )
    )
    reserved_endpoints = reserved_example_endpoint_signals(scripts, generated)
    if reserved_endpoints:
        finding = _finding(
            "reserved_example_endpoint",
            "生产脚本使用了保留示例域名；请通过 CLI、环境变量或配置提供真实端点。",
            severity="fail",
            category="executable_integrity",
            repairable=False,
        )
        finding["details"] = reserved_endpoints[:50]
        findings.append(finding)
    checks.append(
        _check(
            "external_endpoints",
            "fail" if reserved_endpoints else "pass",
            "外部端点可配置性检查完成。",
            reservedExampleEndpoints=reserved_endpoints,
        )
    )
    dead_output_fields = _dead_structured_output_fields(generated, scripts)
    if dead_output_fields:
        finding = _finding(
            "structured_output_field_never_populated",
            (
                "运行脚本把关键结构化输出字段固定保留为空，并在最终输出中降级为待查询/待确定；"
                "请从实际输入或外部响应解析并填充字段，同时让离线自检断言解析结果非空。"
            ),
            severity="fail",
            category="executable_integrity",
            repairable=False,
        )
        finding["details"] = dead_output_fields[:50]
        findings.append(finding)
    checks.append(
        _check(
            "structured_output_fields",
            "fail" if dead_output_fields else "pass",
            "结构化业务输出字段存活性检查完成。",
            deadFields=dead_output_fields,
        )
    )
    empty_branches = empty_conditional_branch_signals(scripts, generated)
    if empty_branches:
        finding = _finding(
            "empty_required_branch",
            "生产脚本包含只执行 pass 的条件分支，分支能力不会产生实现结果。",
            severity="fail",
            category="executable_integrity",
            repairable=False,
        )
        finding["details"] = empty_branches[:50]
        findings.append(finding)
    checks.append(
        _check(
            "required_branches",
            "fail" if empty_branches else "pass",
            "生产脚本条件分支完整性检查完成。",
            issues=empty_branches,
        )
    )
    unused_inputs = unused_mapping_input_signals(scripts, generated)
    if unused_inputs:
        finding = _finding(
            "declared_input_not_consumed",
            "生产脚本读取了输入字段但没有将其用于业务处理或输出。",
            severity="fail",
            category="executable_integrity",
            repairable=False,
        )
        finding["details"] = unused_inputs[:50]
        findings.append(finding)
    checks.append(
        _check(
            "declared_inputs",
            "fail" if unused_inputs else "pass",
            "声明输入消费完整性检查完成。",
            issues=unused_inputs,
        )
    )
    self_check_usage = self_check_production_usage_signals(generated)
    if self_check_usage:
        finding = _finding(
            "self_check_not_exercising_production",
            "离线自检入口没有导入或调用生产脚本，不能作为生产实现的执行证据。",
            severity="fail",
            category="executable_integrity",
        )
        finding["details"] = self_check_usage[:20]
        findings.append(finding)
    checks.append(
        _check(
            "self_check_production_usage",
            "fail" if self_check_usage else "pass",
            "离线自检生产代码调用检查完成。",
            issues=self_check_usage,
        )
    )
    cli_coverage_issues = offline_cli_coverage_signals(
        generated,
        excluded_paths=set(external_scripts),
    )
    if cli_coverage_issues:
        finding = _finding(
            "offline_cli_entrypoint_not_exercised",
            (
                "离线自检没有经过包内公开 CLI/流水线边界；直接调用内部函数无法验证参数解析、"
                "字段映射、文件交接和输出序列化。"
            ),
            severity="fail",
            category="executable_integrity",
        )
        finding["details"] = cli_coverage_issues[:50]
        findings.append(finding)
    checks.append(
        _check(
            "offline_cli_coverage",
            "fail" if cli_coverage_issues else "pass",
            "离线公开 CLI 与统一自检调用链检查完成。",
            issues=cli_coverage_issues,
        )
    )
    evidence_issues = implementation_evidence_issues(root)
    reference_only_requirements = _reference_only_requirement_evidence(root)
    if reference_only_requirements:
        finding = _finding(
            "requirement_evidence_reference_only",
            (
                "ScenarioContract 的普通业务要求只有材料路径引用，没有逐句原文证据；"
                "Skill 可继续验证和导出，但自动发布前需要人工核对这些要求。"
            ),
            severity="warn",
            category="contract_consistency",
            owner="controller",
            repairable=False,
        )
        finding["reviewRequired"] = True
        finding["details"] = reference_only_requirements[:50]
        findings.append(finding)
    checks.append(
        _check(
            "requirement_evidence",
            "warn" if reference_only_requirements else "pass",
            "ScenarioContract 普通业务要求的材料证据检查完成。",
            referenceOnlyRequirements=reference_only_requirements[:50],
        )
    )
    if evidence_issues:
        finding = _finding(
            "implementation_evidence_incomplete",
            "ScenarioContract 的业务要求或规则没有映射到可核验的包内实现证据。",
            severity="warn",
            category="contract_consistency",
            repairable=False,
        )
        finding["details"] = evidence_issues[:100]
        findings.append(finding)
    checks.append(
        _check(
            "implementation_evidence",
            "warn" if evidence_issues else "pass",
            "契约到实现证据映射检查完成。",
            issues=evidence_issues,
        )
    )

    decision_conflicts = _confirmed_decision_conflicts(root, generated)
    runtime_decision_conflicts = [
        item for item in decision_conflicts if item.get("dynamicRuntimeReads")
    ]
    documentation_decision_conflicts = [
        {
            **item,
            "dynamicRuntimeReads": [],
        }
        for item in decision_conflicts
        if item.get("conflictingDocumentation")
    ]
    resolved_documentation_gate_mode = resolve_documentation_gate_mode(
        heuristic_gate_mode
    )
    resolved_capability_gate_mode = resolve_capability_gate_mode(
        heuristic_gate_mode
    )
    if runtime_decision_conflicts:
        finding = _finding(
            "confirmed_decision_not_enforced",
            (
                "最终 Skill 的生产脚本仍动态读取 HITL 已固定的配置；"
                "请在运行实现中落实已确认值。"
            ),
            severity="fail",
            category="contract_consistency",
        )
        finding["details"] = runtime_decision_conflicts[:20]
        findings.append(finding)
    if documentation_decision_conflicts:
        heuristic_enforced = resolved_documentation_gate_mode == "enforce"
        finding = _finding(
            "confirmed_decision_documentation_suspected",
            (
                "文档文本疑似同时描述 HITL 已确认值和其他选项；该自然语言检查"
                + ("当前处于 enforce 模式。" if heuristic_enforced else "仅处于 shadow 模式，不阻断交付。")
            ),
            severity="fail" if heuristic_enforced else "warn",
            category="contract_consistency",
        )
        finding.update(
            {
                "gateMode": resolved_documentation_gate_mode,
                "wouldBlock": True,
                "details": documentation_decision_conflicts[:20],
            }
        )
        findings.append(finding)
    decision_check_status = (
        "fail"
        if runtime_decision_conflicts
        or (
            documentation_decision_conflicts
            and resolved_documentation_gate_mode == "enforce"
        )
        else "warn"
        if documentation_decision_conflicts
        else "pass"
    )
    checks.append(
        _check(
            "confirmed_decisions",
            decision_check_status,
            "已确认 HITL 决策与导出包一致性检查完成。",
            conflicts=decision_conflicts,
            heuristicGateMode=resolved_documentation_gate_mode,
        )
    )
    syntax_failed = False
    undefined_name_issues: list[dict[str, Any]] = []
    undefined_name_skips: list[dict[str, str]] = []
    for script in scripts:
        relative = script.relative_to(root).as_posix()
        try:
            source = script.read_text(encoding="utf-8")
            ast.parse(source, filename=relative)
        except (OSError, UnicodeError, SyntaxError) as exc:
            syntax_failed = True
            findings.append(
                _finding(
                    "python_syntax_invalid",
                    f"Python 脚本无法解析：{str(exc)[:500]}",
                    severity="fail",
                    path=relative,
                    category="executable_integrity",
                )
            )
            continue
        name_analysis = analyze_undefined_python_names(source, filename=relative)
        if name_analysis["undefinedNames"]:
            undefined_name_issues.append(
                {
                    "path": relative,
                    "names": name_analysis["undefinedNames"],
                }
            )
        if name_analysis["skippedReason"]:
            undefined_name_skips.append(
                {
                    "path": relative,
                    "reason": str(name_analysis["skippedReason"]),
                }
            )
    checks.append(
        _check(
            "python_syntax",
            "fail" if syntax_failed else "pass",
            "脚本 Python 语法检查完成。" if scripts else "未生成 Python 脚本。",
            files=[path.relative_to(root).as_posix() for path in scripts],
        )
    )
    module_name_conflicts = analyze_python_module_name_conflicts(generated, scripts)
    if module_name_conflicts:
        finding = _finding(
            "python_module_name_conflict",
            "包内 Python 模块名与标准库冲突，可能导致依赖导入到错误模块；请重命名并同步更新全部引用。",
            severity="fail",
            category="executable_integrity",
        )
        finding["details"] = module_name_conflicts[:50]
        findings.append(finding)
    checks.append(
        _check(
            "python_module_names",
            "fail" if module_name_conflicts else "pass",
            "Python 标准库模块名冲突检查完成。",
            conflicts=module_name_conflicts,
        )
    )
    if undefined_name_issues:
        details = "; ".join(
            f"{item['path']}: {', '.join(name['name'] for name in item['names'])}"
            for item in undefined_name_issues[:20]
        )
        finding = _finding(
            "python_undefined_names",
            f"Python 脚本引用了未定义名称：{details}",
            severity="fail",
            category="executable_integrity",
        )
        finding["details"] = undefined_name_issues
        findings.append(finding)
    checks.append(
        _check(
            "python_undefined_names",
            "fail" if undefined_name_issues else "pass",
            (
                "Python 未定义名称检查失败。"
                if undefined_name_issues
                else "Python 未定义名称检查完成。" if scripts else "未生成 Python 脚本。"
            ),
            issues=undefined_name_issues,
            skipped=undefined_name_skips,
        )
    )

    if external_scripts:
        findings.append(
            _finding(
                "external_runtime_unverified",
                "检测到依赖浏览器或网络的脚本；本次验收未执行外部系统，不能据此声明业务能力通过。",
                severity="warn",
                category="external_capability",
            )
        )
        checks.append(
            _check(
                "external_runtime",
                "unverified",
                "浏览器/API 等外部运行能力未执行，结果只能标记为未验证。",
                files=[path.relative_to(root).as_posix() for path in scripts if path.as_posix() in external_scripts],
            )
        )

    capability_diagnostics = observe_capability_relationships(
        generated,
        scripts=scripts,
        external_runtime_scripts=set(external_scripts),
        agent_self_check=agent_self_check,
    )
    observations = capability_diagnostics.get("observations") or []
    blocking_placeholder_signals = capability_diagnostics.get("blockingPlaceholderSignals") or []
    no_op_self_check_signals = capability_diagnostics.get("noOpSelfCheckSignals") or []
    missing_required_capabilities = capability_diagnostics.get("missingRequiredRuntimeCapabilities") or []
    unbacked_claim_signals = capability_diagnostics.get("unbackedClaimSignals") or []
    unbacked_runtime_declarations = capability_diagnostics.get("unbackedRuntimeDeclarations") or []
    agent_status_inconsistencies = capability_diagnostics.get("agentStatusInconsistencies") or []
    if blocking_placeholder_signals:
        paths = sorted(
            {
                str(item.get("path") or "")
                for item in blocking_placeholder_signals
                if str(item.get("path") or "").strip()
            }
        )
        finding = _finding(
            "placeholder_implementation",
            "发布脚本仍包含占位、模拟、NotImplemented 或 pass-only 核心实现："
            + ", ".join(paths[:10]),
            severity="fail",
            category="executable_integrity",
        )
        finding["details"] = blocking_placeholder_signals[:40]
        findings.append(finding)
    if no_op_self_check_signals:
        finding = _finding(
            "offline_self_check_noop",
            "显式离线自检入口可以在未执行有效断言时成功，不能作为交付证据。",
            severity="fail",
            category="executable_integrity",
            repairable=False,
        )
        finding["details"] = no_op_self_check_signals[:20]
        findings.append(finding)
    if missing_required_capabilities:
        finding = _finding(
            "required_capability_not_implemented",
            (
                "ScenarioContract 或已确认 HITL 要求浏览器/API 运行能力，但当前包没有对应可执行入口；"
                "必须实现已确认能力，不能改写为人工或外部系统边界绕过。"
            ),
            severity="fail",
            category="capability_consistency",
            repairable=False,
        )
        finding["details"] = missing_required_capabilities[:20]
        findings.append(finding)
    unbacked_capability_declarations = [
        *unbacked_claim_signals[:20],
        *unbacked_runtime_declarations[:20],
    ]
    if unbacked_capability_declarations:
        heuristic_enforced = resolved_capability_gate_mode == "enforce"
        finding = _finding(
            "capability_declaration_unbacked",
            (
                "Skill 文本声明了浏览器/API 等运行能力，但包内没有对应入口，也没有明确限定为人工或外部系统边界。"
            ),
            severity="fail" if heuristic_enforced else "warn",
            category="capability_consistency",
        )
        finding.update(
            {
                "gateMode": resolved_capability_gate_mode,
                "wouldBlock": True,
                "details": unbacked_capability_declarations,
            }
        )
        findings.append(finding)
    if agent_status_inconsistencies:
        finding = _finding(
            "agent_self_check_capability_status_inconsistent",
            "Agent 自验证标记为 pass，但包内实际外部运行入口仍被列为未验证；请改为 partial/warn 或补充真实执行证据。",
            severity="warn",
            category="capability_consistency",
            repairable=False,
        )
        finding["details"] = agent_status_inconsistencies[:20]
        findings.append(finding)
    checks.append(
        _check(
            "capability_observations",
            "fail"
            if (
                blocking_placeholder_signals or missing_required_capabilities
            )
            else "fail"
            if (
                unbacked_capability_declarations
                and resolved_capability_gate_mode == "enforce"
            )
            else "warn"
            if unbacked_capability_declarations or no_op_self_check_signals or agent_status_inconsistencies
            else "info",
            (
                "检测到能力声明、实现或自检入口不一致。"
                if (
                    blocking_placeholder_signals
                    or no_op_self_check_signals
                    or missing_required_capabilities
                    or unbacked_claim_signals
                    or unbacked_runtime_declarations
                    or agent_status_inconsistencies
                )
                else f"记录 {len(observations)} 项能力声明/入口观察。"
                if observations
                else "未记录能力声明/入口差异。"
            ),
            observationCount=len(observations),
            blockingSignals=blocking_placeholder_signals,
            noOpSelfCheckSignals=no_op_self_check_signals,
            missingRequiredCapabilities=missing_required_capabilities,
            unbackedCapabilityDeclarations=[
                *unbacked_claim_signals,
                *unbacked_runtime_declarations,
            ],
            agentStatusInconsistencies=agent_status_inconsistencies,
        )
    )

    skill_entry = generated / "SKILL.md"
    skill_documentation = ""
    if skill_entry.is_file():
        text = skill_entry.read_text(encoding="utf-8", errors="replace")
        skill_documentation = text
        missing_links: list[str] = []
        for target in _LOCAL_LINK_RE.findall(text):
            normalized = target.strip().lstrip("./")
            # Markdown templates commonly use placeholders such as
            # ``{sourceUrl}``; these are output fields, not package paths.
            if not normalized or "{" in normalized or "}" in normalized:
                continue
            if not (generated / normalized).exists():
                missing_links.append(normalized)
        if missing_links:
            findings.append(
                _finding(
                    "skill_reference_missing",
                    f"SKILL.md 引用了包内不存在的文件：{', '.join(missing_links[:10])}",
                    severity="fail",
                    path="generated-skill/SKILL.md",
                    category="package_integrity",
                )
            )
            checks.append(_check("skill_references", "fail", "包内引用完整性检查失败。", missing=missing_links))
        else:
            missing_resources = missing_package_references(generated)
            if missing_resources:
                findings.append(
                    _finding(
                        "skill_reference_missing",
                        "包内文档引用了不存在的本地文件。",
                        severity="fail",
                        path="generated-skill/SKILL.md",
                        category="package_integrity",
                    )
                )
                findings[-1]["details"] = missing_resources[:50]
                checks.append(
                    _check("skill_references", "fail", "包内资源引用完整性检查失败。", missing=missing_resources)
                )
            else:
                checks.append(_check("skill_references", "pass", "包内引用完整。"))

    self_check_requirements = self_check_protocol_requirements(
        root,
        generated,
        external_runtime_scripts=external_scripts,
    )
    documented_entrypoints = set(
        self_check_requirements["documented_entrypoints"]
    )
    missing_documented_entrypoints = sorted(
        path
        for path in documented_entrypoints
        if not (generated / path).is_file()
    )
    if missing_documented_entrypoints:
        finding = _finding(
            "documented_cli_entrypoint_missing",
            "Skill 文档公开了不存在的生产 CLI；缺少入口的 Skill 不能运行。",
            severity="fail",
            path="generated-skill/SKILL.md",
            category="executable_integrity",
        )
        finding["details"] = missing_documented_entrypoints
        findings.append(finding)
    checks.append(
        _check(
            "documented_cli_presence",
            "fail" if missing_documented_entrypoints else "pass",
            "文档化 CLI 文件完整性检查完成。",
            missing=missing_documented_entrypoints,
        )
    )
    external_entrypoints = set(self_check_requirements["external_entrypoints"])
    input_contract_issues = scenario_input_fixture_issues(root, generated)
    if input_contract_issues:
        owned_fixtures = platform_owned_fixture_paths(root, generated)
        structured_fixtures = set()
        fixtures_root = generated / "fixtures"
        if fixtures_root.is_dir():
            for path in fixtures_root.rglob("*"):
                if path.is_file() and path.suffix.lower() in {".csv", ".json", ".jsonl", ".xlsx"}:
                    structured_fixtures.add(path.relative_to(generated).as_posix())
        controller_owned = bool(
            structured_fixtures
            and structured_fixtures.issubset(owned_fixtures)
        )
        finding = _finding(
            "input_fixture_contract_invalid",
            "结构化输入 fixture 与 ScenarioContract 的表头、列宽、必填字段或基础类型不一致。",
            severity="fail",
            category="contract_consistency",
            owner="controller" if controller_owned else "package",
            repairable=not controller_owned,
        )
        finding["details"] = input_contract_issues[:50]
        findings.append(finding)
    checks.append(
        _check(
            "input_fixture_contract",
            "fail" if input_contract_issues else "pass",
            "结构化输入 fixture 契约检查完成。",
            issues=input_contract_issues,
        )
    )

    html_encoding_issues = html_fixture_encoding_issues(generated)
    for issue in html_encoding_issues:
        finding = _finding(
            "html_fixture_charset_invalid",
            str(issue.get("message") or "HTML fixture 编码契约不完整。"),
            severity="fail",
            path=f"generated-skill/{issue['path']}",
            category="package_integrity",
        )
        finding["details"] = issue
        findings.append(finding)
    checks.append(
        _check(
            "html_fixture_encoding",
            "fail" if html_encoding_issues else "pass",
            "HTML fixture UTF-8 编码声明检查完成。",
            issues=html_encoding_issues,
        )
    )

    pipeline_entry = _choose_offline_pipeline_entry(generated)
    target = (
        None
        if pipeline_entry is not None
        else _choose_smoke_target(
            generated,
            excluded_fixture_paths=platform_owned_fixture_paths(root, generated),
        )
    )
    if documented_entrypoints and pipeline_entry is None:
        finding = _finding(
            "structured_self_check_missing",
            (
                "包内存在文档化 CLI，但没有统一 self_check.py/run_offline_test.py/"
                "offline_test.py 入口；当前只能确认入口存在，业务能力需要人工复核。"
            ),
            severity="warn",
            path="generated-skill/scripts/self_check.py",
            category="executable_integrity",
            repairable=False,
        )
        finding["reviewRequired"] = True
        finding["details"] = sorted(documented_entrypoints)
        findings.append(finding)
    if pipeline_entry is not None:
        pipeline_source = pipeline_entry.read_text(encoding="utf-8", errors="replace")
        if not _OUTPUT_DIR_ARGUMENT_RE.search(pipeline_source):
            findings.append(
                _finding(
                    "structured_self_check_protocol_missing",
                    "统一自检入口必须支持 --output-dir 并生成 self_check_summary.json。",
                    severity="fail",
                    path=pipeline_entry.relative_to(root).as_posix(),
                    category="executable_integrity",
                )
            )

    if pipeline_entry is None and target is None:
        checks.append(_check("offline_smoke", "skip", "没有可离线运行入口。"))
    elif execution_port is None:
        finding = _finding(
            "offline_smoke_unavailable",
            "检测到可离线运行入口，但宿主未提供 ExecutionPort；未执行脚本。",
            severity="warn",
            category="external_capability",
            owner="environment",
            repairable=False,
        )
        finding["reviewRequired"] = True
        findings.append(finding)
        checks.append(_check("offline_smoke", "unverified", "宿主未提供执行端口，离线脚本未验证。"))
    else:
        startup_results: list[dict[str, Any]] = []
        for relative in sorted(documented_entrypoints):
            startup_command = [sys.executable, relative, "--help"]
            try:
                startup = await execution_port.run(
                    ExecutionRequest(
                        command=tuple(startup_command),
                        cwd=generated,
                        timeout_seconds=min(20, max(1, int(smoke_timeout_seconds))),
                        env={"PYTHONPATH": "."},
                    )
                )
            except Exception as exc:
                startup_results.append(
                    {"path": relative, "status": "unavailable", "error": str(exc)[:500]}
                )
                findings.append(
                    _finding(
                        "documented_cli_startup_unavailable",
                        f"文档化 CLI 无法执行启动检查：{relative}：{str(exc)[:500]}",
                        severity="warn",
                        path=f"generated-skill/{relative}",
                        category="external_capability",
                    )
                )
                continue
            startup_status = (
                "fail"
                if startup.timed_out or startup.exit_code not in (0, None)
                else "pass"
            )
            startup_stderr = (startup.stderr or "")[-1000:]
            optional_external_dependency = bool(
                relative in external_entrypoints
                and _OPTIONAL_EXTERNAL_DEPENDENCY_RE.search(startup_stderr)
            )
            if optional_external_dependency:
                startup_status = "unavailable"
            startup_results.append(
                {
                    "path": relative,
                    "status": startup_status,
                    "exitCode": startup.exit_code,
                    "timedOut": startup.timed_out,
                    "stderr": startup_stderr,
                }
            )
            if startup_status == "fail":
                findings.append(
                    _finding(
                        "documented_cli_startup_failed",
                        f"文档化 CLI 无法在包根目录启动：{relative}。",
                        severity="fail",
                        path=f"generated-skill/{relative}",
                        category="execution",
                    )
                )
                findings[-1]["details"] = startup_results[-1]
            elif optional_external_dependency:
                findings.append(
                    _finding(
                        "documented_cli_startup_unavailable",
                        f"文档化外部 CLI 缺少可选运行依赖，当前沙箱未执行：{relative}。",
                        severity="warn",
                        path=f"generated-skill/{relative}",
                        category="external_capability",
                        owner="environment",
                        repairable=False,
                    )
                )
        checks.append(
            _check(
                "documented_cli_startup",
                "fail" if any(item["status"] == "fail" for item in startup_results) else "warn"
                if any(item["status"] == "unavailable" for item in startup_results)
                else "pass",
                "文档化 CLI 的 --help/导入启动检查完成。",
                results=startup_results,
            )
        )

        if pipeline_entry is not None and _OUTPUT_DIR_ARGUMENT_RE.search(pipeline_source):
            output_name = f".skill-builder-pipeline-{uuid.uuid4().hex}"
            output_path = root / "workspace" / "verify" / output_name
            output_path.mkdir(parents=True, exist_ok=True)
            command = [
                sys.executable,
                pipeline_entry.relative_to(generated).as_posix(),
                "--output-dir",
                f"../workspace/verify/{output_name}",
            ]
            package_signature_before = candidate_artifact_signature(root)
            package_snapshot_root = (
                root
                / "workspace"
                / "verify"
                / f".acceptance-package-snapshot-{uuid.uuid4().hex}"
            )
            package_snapshot = package_snapshot_root / "generated-skill"
            package_snapshot_root.mkdir(parents=True, exist_ok=False)
            shutil.copytree(generated, package_snapshot)
            result = None
            execution_error: Exception | None = None
            try:
                result = await execution_port.run(
                    ExecutionRequest(
                        command=tuple(command),
                        cwd=generated,
                        timeout_seconds=max(1, int(smoke_timeout_seconds)),
                        env={"PYTHONPATH": "."},
                    )
                )
            except Exception as exc:
                execution_error = exc
            finally:
                package_mutations = _signature_changes(
                    package_signature_before,
                    candidate_artifact_signature(root),
                )
                if package_mutations:
                    shutil.rmtree(generated)
                    shutil.copytree(package_snapshot, generated)
                    finding = _finding(
                        "self_check_package_mutated",
                        "统一离线自检修改了待交付 Skill 包；平台已恢复执行前版本。",
                        severity="fail",
                        path=pipeline_entry.relative_to(root).as_posix(),
                        category="execution",
                    )
                    finding["details"] = package_mutations[:50]
                    findings.append(finding)
                checks.append(
                    _check(
                        "self_check_package_immutability",
                        "fail" if package_mutations else "pass",
                        "统一离线自检执行前后包内容一致性检查完成。",
                        changedPaths=package_mutations,
                    )
                )
                shutil.rmtree(package_snapshot_root, ignore_errors=True)
            if execution_error is not None:
                findings.append(
                    _finding(
                        "offline_smoke_unavailable",
                        f"宿主执行端口不可用，统一离线自检未执行：{str(execution_error)[:500]}",
                        severity="warn",
                        category="external_capability",
                    )
                )
                checks.append(
                    _check("offline_smoke", "unverified", "宿主执行端口不可用。", command=command)
                )
            if result is not None:
                summary_path = output_path / "self_check_summary.json"
                if result.timed_out:
                    findings.append(
                        _finding(
                            "offline_smoke_timeout",
                            "统一离线自检超时。",
                            severity="fail",
                            category="execution",
                        )
                    )
                    checks.append(_check("offline_smoke", "fail", "统一离线自检超时。", command=command))
                elif not summary_path.is_file():
                    findings.append(
                        _finding(
                            "self_check_summary_missing",
                            "统一离线自检未生成 self_check_summary.json。",
                            severity="fail",
                            path=pipeline_entry.relative_to(root).as_posix(),
                            category="execution",
                        )
                    )
                    checks.append(
                        _check("offline_smoke", "fail", "统一离线自检缺少结构化摘要。", command=command)
                    )
                else:
                    summary_payload: dict[str, Any] = {}
                    protocol = None
                    protocol_issues: list[dict[str, Any]] = []
                    try:
                        summary_payload = json.loads(summary_path.read_text(encoding="utf-8"))
                    except (OSError, TypeError, ValueError) as exc:
                        protocol = None
                        findings.append(
                            _finding(
                                "self_check_summary_invalid",
                                f"self_check_summary.json 不是有效 JSON：{str(exc)[:500]}",
                                severity="fail",
                                category="execution",
                            )
                        )
                    else:
                        protocol = validate_self_check_summary(
                            summary_payload,
                            **self_check_requirements,
                        )
                        package_input_issues = list(
                            self_check_package_input_issues(
                                generated,
                                protocol.cases,
                            )
                        )
                        protocol_issues = [
                            *protocol.issues,
                            *package_input_issues,
                            *platform_fixture_business_replay_issues(
                                root,
                                generated,
                                protocol.cases,
                            ),
                        ]
                        if result.exit_code not in (0, None):
                            findings.append(
                                _finding(
                                    "offline_self_check_process_failed",
                                    "统一离线自检进程以非零退出码结束。",
                                    severity="fail",
                                    path=pipeline_entry.relative_to(root).as_posix(),
                                    category="execution",
                                )
                            )
                        if protocol_issues:
                            finding = _finding(
                                "self_check_protocol_invalid",
                                "统一离线自检摘要不满足结构化协议或覆盖要求。",
                                severity="fail",
                                path=pipeline_entry.relative_to(root).as_posix(),
                                category="execution",
                            )
                            finding["details"] = protocol_issues[:50]
                            findings.append(finding)
                        summary_status = str(summary_payload.get("status"))
                        replay_cases: tuple[dict[str, Any], ...] = ()
                        if summary_status == "fail":
                            findings.append(
                                _finding(
                                    "offline_smoke_business_failed",
                                    "统一离线自检声明器执行失败或报告了失败结果。",
                                    severity="fail",
                                    path=pipeline_entry.relative_to(root).as_posix(),
                                    category="execution",
                                )
                            )
                        elif summary_status in {"pass", SELF_CHECK_PLANNED_STATUS}:
                            missing_input_cases = {
                                str(issue.get("caseId") or "")
                                for issue in package_input_issues
                                if str(issue.get("caseId") or "")
                            }
                            replay_cases = tuple(
                                case
                                for case in protocol.cases
                                if str(case.get("id") or "") not in missing_input_cases
                            )
                        if summary_status in {
                            "pass",
                            SELF_CHECK_PLANNED_STATUS,
                        } and replay_cases:
                            replay = await replay_self_check_cases(
                                root=root,
                                generated=generated,
                                cases=replay_cases,
                                execution_port=execution_port,
                                timeout_seconds=smoke_timeout_seconds,
                            )
                            replayed_cases.extend(replay.checks)
                            if not replay.ok:
                                runtime_failures = _replay_python_runtime_failures(
                                    replay
                                )
                                missing_outputs = (
                                    []
                                    if runtime_failures
                                    else _replay_missing_output_failures(replay)
                                )
                                external_only = _replay_is_external_only(
                                    replay,
                                    external_entrypoints=external_entrypoints,
                                )
                                finding = _finding(
                                    "offline_replay_failed",
                                    "平台独立重放命令或输出断言未通过。",
                                    severity=(
                                        "warn"
                                        if runtime_failures or missing_outputs
                                        else "fail"
                                    ),
                                    path=pipeline_entry.relative_to(root).as_posix(),
                                    category="execution",
                                    repairable=False,
                                )
                                finding["details"] = list(replay.issues)[:50]
                                if runtime_failures:
                                    finding["wouldBlock"] = True
                                    finding["reviewRequired"] = False
                                    producer_paths = []
                                    for item in runtime_failures:
                                        for path in item.get("producerPaths") or []:
                                            if str(path).strip():
                                                producer_paths.append(str(path))
                                    runtime_finding = _finding(
                                        "python_runtime_exception",
                                        "生产脚本在离线重放中触发明确的 Python 运行时异常。",
                                        severity="fail",
                                        path=(
                                            f"generated-skill/{producer_paths[0]}"
                                            if producer_paths
                                            else pipeline_entry.relative_to(root).as_posix()
                                        ),
                                        category="execution",
                                    )
                                    runtime_finding["details"] = runtime_failures[:20]
                                    findings.append(runtime_finding)
                                elif missing_outputs:
                                    finding["wouldBlock"] = True
                                    producer_paths = []
                                    for item in missing_outputs:
                                        for path in item.get("producerPaths") or []:
                                            if str(path).strip():
                                                producer_paths.append(str(path))
                                    output_finding = _finding(
                                        "expected_output_missing",
                                        "生产 CLI 成功退出，但没有在声明路径生成必需输出。",
                                        severity="fail",
                                        path=(
                                            f"generated-skill/{producer_paths[0]}"
                                            if producer_paths
                                            else pipeline_entry.relative_to(root).as_posix()
                                        ),
                                        category="execution",
                                    )
                                    output_finding["details"] = missing_outputs[:20]
                                    findings.append(output_finding)
                                elif external_only:
                                    finding["offlineProtocolGateEligible"] = True
                                findings.append(finding)
                            checks.append(
                                _check(
                                    "offline_replay",
                                    "pass" if replay.ok else "fail",
                                    "平台独立重放结构化自检用例完成。",
                                    results=list(replay.checks),
                                )
                            )
                    checks.append(
                        _check(
                            "offline_smoke",
                            "fail"
                            if result.exit_code not in (0, None)
                            or protocol is None
                            or bool(protocol_issues)
                            or str(
                                summary_payload.get("status")
                                if isinstance(summary_payload, dict)
                                else ""
                            ) not in {"pass", SELF_CHECK_PLANNED_STATUS}
                            else "pass",
                            "统一离线自检及结构化摘要检查完成。",
                            command=command,
                            exitCode=result.exit_code,
                        )
                    )
            shutil.rmtree(output_path, ignore_errors=True)
        elif (
            pipeline_entry is None
            and target is not None
            and target[0].resolve() in external_script_paths
        ):
            script, fixture = target
            source = script.read_text(encoding="utf-8", errors="replace")
            input_validation: dict[str, Any] | None = None
            local_mode_arguments = _safe_local_mode_arguments(source)
            if local_mode_arguments:
                command = [sys.executable, script.relative_to(generated).as_posix()]
                input_mode = _script_input_mode(source)
                command.extend(
                    ["--input", fixture.relative_to(generated).as_posix()]
                    if input_mode == "flag"
                    else [fixture.relative_to(generated).as_posix()]
                )
                output_path: Path | None = None
                output_mode = _script_output_mode(source)
                if output_mode == "directory":
                    output_path = (
                        root
                        / "workspace"
                        / "verify"
                        / f".skill-builder-offline-{uuid.uuid4().hex}"
                    )
                    output_path.mkdir(parents=True, exist_ok=True)
                    output_flag = (
                        "--output-dir"
                        if _OUTPUT_DIR_ARGUMENT_RE.search(source)
                        else "--output"
                    )
                    command.extend(
                        [output_flag, f"../workspace/verify/{output_path.name}"]
                    )
                elif output_mode == "file":
                    output_suffix = _script_output_suffix(
                        source,
                        documentation=skill_documentation,
                    )
                    output_path = (
                        root
                        / "workspace"
                        / "verify"
                        / f".skill-builder-offline-{uuid.uuid4().hex}{output_suffix}"
                    )
                    output_path.parent.mkdir(parents=True, exist_ok=True)
                    command.extend(
                        ["--output", f"../workspace/verify/{output_path.name}"]
                    )
                command.extend(local_mode_arguments)
                validation_result = await execution_port.run(
                    ExecutionRequest(
                        command=tuple(command),
                        cwd=generated,
                        timeout_seconds=max(1, int(smoke_timeout_seconds)),
                        env={"PYTHONPATH": "."},
                    )
                )
                validation_failed = bool(
                    validation_result.timed_out
                    or validation_result.exit_code not in (0, None)
                )
                input_validation = {
                    "mode": "offline_business_replay",
                    "status": "fail" if validation_failed else "pass",
                    "command": command,
                    "exitCode": validation_result.exit_code,
                    "timedOut": validation_result.timed_out,
                }
                if validation_failed:
                    finding = _finding(
                        "offline_smoke_failed",
                        "外部入口的显式本地模式执行失败。",
                        severity="fail",
                        path=script.relative_to(root).as_posix(),
                        category="execution",
                    )
                    finding["details"] = {
                        **input_validation,
                        "fixture": fixture.relative_to(generated).as_posix(),
                        "stdout": (validation_result.stdout or "")[-2000:],
                        "stderr": (validation_result.stderr or "")[-2000:],
                    }
                    findings.append(finding)
                elif output_path is not None:
                    output_files = _materialized_output_files(output_path)
                    if not output_files:
                        findings.append(
                            _finding(
                                "expected_output_missing",
                                "生产 CLI 本地模式成功退出，但没有生成业务输出。",
                                severity="fail",
                                path=script.relative_to(root).as_posix(),
                                category="execution",
                            )
                        )
                    invariant_issues = []
                    for path in output_files:
                        if path.suffix.lower() == ".json":
                            invariant_issues.extend(_business_output_invariant_issues(path))
                    if invariant_issues:
                        finding = _finding(
                            "business_output_invariant_failed",
                            "本地模式输出违反通用结构化业务不变量。",
                            severity="fail",
                            path=script.relative_to(root).as_posix(),
                            category="execution",
                        )
                        finding["details"] = invariant_issues[:20]
                        findings.append(finding)
                    claim_issues = [
                        issue
                        for path in output_files
                        for issue in _offline_output_claim_issues(path)
                    ]
                    if claim_issues:
                        finding = _finding(
                            "offline_output_evidence_mismatch",
                            "离线运行产物包含未经验证的在线/官方证据声明。",
                            severity="fail",
                            path=script.relative_to(root).as_posix(),
                            category="execution",
                        )
                        finding["details"] = claim_issues[:20]
                        findings.append(finding)
                _remove_materialized_output(output_path)
            elif _VALIDATE_ONLY_ARGUMENT_RE.search(source):
                command = [sys.executable, script.relative_to(generated).as_posix()]
                input_mode = _script_input_mode(source)
                command.extend(
                    ["--input", fixture.relative_to(generated).as_posix()]
                    if input_mode == "flag"
                    else [fixture.relative_to(generated).as_posix()]
                )
                command.append("--validate-only")
                validation_result = await execution_port.run(
                    ExecutionRequest(
                        command=tuple(command),
                        cwd=generated,
                        timeout_seconds=max(1, min(30, int(smoke_timeout_seconds))),
                        env={"PYTHONPATH": "."},
                    )
                )
                validation_failed = bool(
                    validation_result.timed_out
                    or validation_result.exit_code not in (0, None)
                )
                input_validation = {
                    "status": "fail" if validation_failed else "pass",
                    "command": command,
                    "exitCode": validation_result.exit_code,
                    "timedOut": validation_result.timed_out,
                }
                if validation_failed:
                    finding = _finding(
                        "runtime_fixture_mismatch",
                        "业务 fixture 未通过生产入口的安全输入预检。",
                        severity="fail",
                        path=script.relative_to(root).as_posix(),
                        category="execution",
                    )
                    finding["details"] = {
                        **input_validation,
                        "fixture": fixture.relative_to(generated).as_posix(),
                        "inputContracts": _runtime_fixture_input_contracts(
                            root,
                            fixture,
                        ),
                        "stdout": (validation_result.stdout or "")[-2000:],
                        "stderr": (validation_result.stderr or "")[-2000:],
                    }
                    findings.append(finding)
            elif scenario_has_structured_inputs(root):
                finding = _finding(
                    "external_input_validation_missing",
                    "结构化外部生产入口缺少安全本地输入预检模式。",
                    severity="fail",
                    path=script.relative_to(root).as_posix(),
                    category="execution",
                )
                finding["details"] = {
                    "fixture": fixture.relative_to(generated).as_posix(),
                    "requiredModes": ["explicit_offline_or_fixture_mode", "--validate-only"],
                }
                findings.append(finding)
                input_validation = {
                    "status": "missing",
                    "fixture": fixture.relative_to(generated).as_posix(),
                }
            checks.append(
                _check(
                    "offline_smoke",
                    "fail"
                    if input_validation
                    and input_validation["status"] in {"fail", "missing"}
                    else "unverified",
                    (
                        "外部入口的本地安全检查失败或缺失；未启动浏览器/API。"
                        if input_validation
                        and input_validation["status"] in {"fail", "missing"}
                        else "外部浏览器/API 生产入口不在默认离线 smoke 中直接执行。"
                    ),
                    command=None,
                    entrypoint=target[0].relative_to(generated).as_posix(),
                    inputValidation=input_validation,
                )
            )
        elif pipeline_entry is None and target is not None:
            script, fixture = target
            source = script.read_text(encoding="utf-8", errors="replace")
            command = [sys.executable, script.relative_to(generated).as_posix()]
            input_mode = _script_input_mode(source)
            command.extend(
                ["--input", fixture.relative_to(generated).as_posix()]
                if input_mode == "flag"
                else [fixture.relative_to(generated).as_posix()]
            )
            output_path: Path | None = None
            output_mode = _script_output_mode(source)
            if output_mode == "directory":
                output_name = f".skill-builder-smoke-{uuid.uuid4().hex}"
                output_path = root / "workspace" / "verify" / output_name
                output_path.mkdir(parents=True, exist_ok=True)
                output_flag = (
                    "--output-dir"
                    if _OUTPUT_DIR_ARGUMENT_RE.search(source)
                    else "--output"
                )
                command.extend([output_flag, f"../workspace/verify/{output_name}"])
            elif output_mode == "file":
                output_name = (
                    f".skill-builder-smoke-{uuid.uuid4().hex}"
                    f"{_script_output_suffix(source, documentation=skill_documentation)}"
                )
                output_path = root / "workspace" / "verify" / output_name
                output_path.parent.mkdir(parents=True, exist_ok=True)
                command.extend(["--output", f"../workspace/verify/{output_name}"])
            result = await execution_port.run(
                ExecutionRequest(
                    command=tuple(command),
                    cwd=generated,
                    timeout_seconds=max(1, int(smoke_timeout_seconds)),
                    env={"PYTHONPATH": "."},
                )
            )
            legacy_failed = result.timed_out or result.exit_code not in (0, None)
            if legacy_failed:
                finding = _finding(
                    "offline_smoke_failed",
                    f"离线脚本执行失败（退出码 {result.exit_code}）。",
                    severity="fail",
                    path=script.relative_to(root).as_posix(),
                    category="execution",
                )
                diagnostics = f"{result.stdout}\n{result.stderr}"
                finding["details"] = {
                    "command": command,
                    "fixture": fixture.relative_to(generated).as_posix(),
                    "exitCode": result.exit_code,
                    "timedOut": result.timed_out,
                    "stdout": (result.stdout or "")[-2000:],
                    "stderr": (result.stderr or "")[-2000:],
                }
                if (
                    script.relative_to(generated).as_posix()
                    in external_entrypoints
                    and _OPTIONAL_EXTERNAL_RUNTIME_RE.search(diagnostics)
                ):
                    finding["offlineProtocolGateEligible"] = True
                findings.append(finding)
            checks.append(
                _check(
                    "offline_smoke",
                    "fail" if legacy_failed else "pass",
                    "兼容离线 smoke 执行完成。",
                    command=command,
                )
            )
            materialized_outputs = _materialized_output_files(output_path)
            legacy_json_path = next(
                (
                    path
                    for path in materialized_outputs
                    if path.suffix.lower() == ".json"
                ),
                None,
            )
            if (
                not legacy_failed
                and output_path is not None
                and not materialized_outputs
            ):
                findings.append(
                    _finding(
                        "expected_output_missing",
                        "生产 CLI 成功退出，但没有生成声明的业务输出。",
                        severity="fail",
                        path=script.relative_to(root).as_posix(),
                        category="execution",
                        repairable=False,
                    )
                )
            if not legacy_failed and legacy_json_path is not None:
                try:
                    legacy_payload = json.loads(
                        legacy_json_path.read_text(encoding="utf-8")
                    )
                except (OSError, TypeError, ValueError):
                    legacy_payload = None
                legacy_status = (
                    str(legacy_payload.get("status") or "").strip().lower()
                    if isinstance(legacy_payload, dict)
                    else ""
                )
                legacy_business_failed = legacy_payload is None or legacy_status in {
                    "fail", "failed", "error", "has_errors"
                } or (
                    isinstance(legacy_payload, dict)
                    and isinstance(legacy_payload.get("errors"), list)
                    and bool(legacy_payload.get("errors"))
                )
                if legacy_business_failed:
                    findings.append(
                        _finding(
                            "offline_smoke_business_failed",
                            f"离线脚本业务结果失败（status={legacy_status or 'invalid'}）。",
                            severity="fail",
                            path=script.relative_to(root).as_posix(),
                            category="execution",
                        )
                    )
                invariant_issues = (
                    _business_output_invariant_issues(legacy_json_path)
                    if legacy_payload is not None
                    else []
                )
                if invariant_issues:
                    finding = _finding(
                        "business_output_invariant_failed",
                        "离线脚本输出违反通用结构化业务不变量。",
                        severity="fail",
                        path=script.relative_to(root).as_posix(),
                        category="execution",
                    )
                    finding["details"] = invariant_issues[:20]
                    findings.append(finding)
            edge_fixture = (
                _materialize_csv_edge_fixture(root, fixture)
                if not legacy_failed and output_path is not None
                else None
            )
            edge_output: Path | None = None
            if edge_fixture is not None:
                edge_command = list(command)
                fixture_token = fixture.relative_to(generated).as_posix()
                edge_input_token = f"../workspace/verify/{edge_fixture.name}"
                edge_command = [
                    edge_input_token if token == fixture_token else token
                    for token in edge_command
                ]
                if output_mode == "directory":
                    edge_output = (
                        root
                        / "workspace"
                        / "verify"
                        / f".skill-builder-edge-output-{uuid.uuid4().hex}"
                    )
                    edge_output.mkdir(parents=True, exist_ok=True)
                else:
                    edge_output = (
                        root
                        / "workspace"
                        / "verify"
                        / (
                            f".skill-builder-edge-output-{uuid.uuid4().hex}"
                            f"{_script_output_suffix(source, documentation=skill_documentation)}"
                        )
                    )
                if output_path is not None:
                    output_token = f"../workspace/verify/{output_path.name}"
                    edge_output_token = f"../workspace/verify/{edge_output.name}"
                    edge_command = [
                        edge_output_token if token == output_token else token
                        for token in edge_command
                    ]
                edge_result = await execution_port.run(
                    ExecutionRequest(
                        command=tuple(edge_command),
                        cwd=generated,
                        timeout_seconds=max(1, int(smoke_timeout_seconds)),
                        env={"PYTHONPATH": "."},
                    )
                )
                if edge_result.timed_out:
                    finding = _finding(
                        "offline_smoke_timeout",
                        "CSV 边界输入检查超时。",
                        severity="fail",
                        path=script.relative_to(root).as_posix(),
                        category="execution",
                    )
                    finding["details"] = {"command": edge_command}
                    findings.append(finding)
                elif edge_result.exit_code in (0, None):
                    edge_json = next(
                        (
                            path
                            for path in _materialized_output_files(edge_output)
                            if path.suffix.lower() == ".json"
                        ),
                        None,
                    )
                    edge_issues = (
                        _business_output_invariant_issues(edge_json)
                        if edge_json is not None
                        else []
                    )
                    if edge_issues:
                        finding = _finding(
                            "business_output_invariant_failed",
                            "CSV 边界输入输出违反通用结构化业务不变量。",
                            severity="fail",
                            path=script.relative_to(root).as_posix(),
                            category="execution",
                        )
                        finding["details"] = edge_issues[:20]
                        findings.append(finding)
                edge_fixture.unlink(missing_ok=True)
                _remove_materialized_output(edge_output)
            _remove_materialized_output(output_path)

    required_runtime = set()
    runtime_capabilities = {"api_runtime", "browser_runtime", "external_runtime"}
    scenario_capabilities = capability_diagnostics.get("scenarioRequiredCapabilities") or {}
    for key, enabled in scenario_capabilities.items():
        normalized_key = str(key)
        if enabled is True and normalized_key in runtime_capabilities:
            required_runtime.add(normalized_key)
    if required_runtime and not _external_success_evidence(
        root,
        replayed_cases,
        external_entrypoints=external_entrypoints,
    ):
        finding = _finding(
            "required_external_capability_not_verified",
            (
                "必需外部能力尚无通过生产入口的受控响应重放；"
                "当前只能确认入口存在，不能确认其会产生有效业务结果。"
            ),
            severity="warn",
            category="external_capability",
            owner="environment",
            repairable=False,
        )
        finding["reviewRequired"] = True
        finding["details"] = {"requiredCapabilities": sorted(required_runtime)}
        findings.append(finding)

    agent_reconciliation = reconcile_agent_findings(
        root=root,
        agent_self_check=agent_self_check,
        platform_findings=findings,
    )
    findings.extend(agent_reconciliation["findings"])
    if agent_reconciliation["check"] is not None:
        checks.append(agent_reconciliation["check"])
    capability_diagnostics["agentFindingReconciliation"] = agent_reconciliation["diagnostics"]

    _apply_offline_protocol_gate(
        findings=findings,
        checks=checks,
        mode=resolved_offline_protocol_gate_mode,
    )
    _apply_delivery_review_evidence_policy(
        findings=findings,
        checks=checks,
        documented_entrypoints=set(
            self_check_requirements.get("documented_entrypoints") or []
        ),
    )
    capability_diagnostics["offlineProtocolGate"] = {
        "mode": resolved_offline_protocol_gate_mode,
        "default": "shadow",
        "diagnosticOnlyFindingIds": sorted(_OFFLINE_PROTOCOL_FINDING_IDS),
    }

    has_failures = any(item.get("severity") == "fail" for item in findings)
    has_warnings = any(item.get("severity") == "warn" for item in findings)
    review_required = any(item.get("reviewRequired") is True for item in findings)
    status = "fail" if has_failures else "warn" if has_warnings else "pass"
    elapsed = round(time.monotonic() - started, 3)
    return AcceptanceResult(
        status=status,
        outcome="failed" if has_failures else "needs_review" if review_required else "accepted",
        delivery_status="blocked" if has_failures else "needs_review" if review_required else "ready",
        findings=tuple(findings),
        checks=tuple(checks),
        diagnostics={"capabilityObservations": capability_diagnostics},
        summary={
            "status": status,
            "title": "Skill 自验收完成" if not has_failures else "Skill 自验收发现问题",
            "message": (
                "静态包检查和可用的离线检查已完成。"
                if not has_failures
                else "请根据验收 findings 修复草稿后重新验收。"
            ),
            "elapsedSeconds": elapsed,
            "nextSteps": [] if not has_failures else ["修复阻断 findings 后重新验证。"],
        },
    )


__all__ = [
    "ACCEPTANCE_RESULT_PATH",
    "ACCEPTANCE_SUMMARY_PATH",
    "AcceptanceResult",
    "accept_skill_package",
    "acceptance_exception_payload",
    "acceptance_result_payload",
    "persist_acceptance_files",
    "render_acceptance_summary",
]
