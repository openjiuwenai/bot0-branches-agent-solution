"""One-off helper: extract LLM snippets from optimize_balanced30_run.log."""
from __future__ import annotations

import json
import re
from pathlib import Path

LOG = Path(__file__).resolve().parents[2] / "workspace/artifacts/optimize_balanced30_run.log"
OUT = Path(__file__).with_name("_run_snippets.json")


def main() -> None:
    text = LOG.read_text(encoding="utf-8", errors="replace")
    # Parse structlog-ish JSON blobs that contain response_content
    snippets: list[dict[str, str]] = []
    for m in re.finditer(r"\{[^{}]*\"response_content\":\s*\"(?:\\.|[^\"\\])*\"[^{}]*\}", text):
        raw = m.group(0)
        try:
            # Fix common log wrapping: the object may be incomplete; extract field manually
            cm = re.search(r"\"response_content\":\s*\"((?:\\.|[^\"\\])*)\"", raw)
            if not cm:
                continue
            content = json.loads(f'"{cm.group(1)}"')
        except Exception:
            continue
        kind = "other"
        if content.startswith("---\nname: audit-business") or content.startswith("---\\nname:"):
            kind = "skill_variant"
        elif "平均分" in content or "总体结果" in content:
            kind = "rollout_summary"
        elif "关键洞察" in content:
            kind = "semantic_advantage"
        elif '"operation"' in content or "Add" in content[:80]:
            kind = "library_ops"
        snippets.append({"kind": kind, "preview": content[:400], "full_len": len(content), "content": content})

    # Prefer richer regex on whole file for response_content
    all_contents: list[str] = []
    for cm in re.finditer(r"\"response_content\":\s*\"((?:\\.|[^\"\\])*)\"", text):
        try:
            all_contents.append(json.loads(f'"{cm.group(1)}"'))
        except Exception:
            continue

    classified: dict[str, list[str]] = {
        "skill_variant": [],
        "rollout_summary": [],
        "semantic_advantage": [],
        "library_ops": [],
        "other": [],
    }
    for content in all_contents:
        if content.lstrip().startswith("---") and "audit-business" in content[:200]:
            classified["skill_variant"].append(content)
        elif "平均分" in content or "**总体结果**" in content:
            classified["rollout_summary"].append(content)
        elif "关键洞察" in content:
            classified["semantic_advantage"].append(content)
        elif '"operation"' in content:
            classified["library_ops"].append(content)
        else:
            classified["other"].append(content)

    summary = {k: len(v) for k, v in classified.items()}
    payload = {
        "counts": summary,
        "first_summary": (classified["rollout_summary"][0] if classified["rollout_summary"] else ""),
        "second_summary": (classified["rollout_summary"][1] if len(classified["rollout_summary"]) > 1 else ""),
        "first_advantage": (classified["semantic_advantage"][0] if classified["semantic_advantage"] else ""),
        "first_ops": (classified["library_ops"][0] if classified["library_ops"] else ""),
        "variant_heads": [c[:500] for c in classified["skill_variant"][:3]],
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    print("wrote", OUT)


if __name__ == "__main__":
    main()
