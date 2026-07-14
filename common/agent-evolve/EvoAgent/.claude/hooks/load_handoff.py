#!/usr/bin/env python3
"""SessionStart hook: 读取 docs/handoff/ 中最新文档并注入上下文。"""

import json
import os
import sys
from datetime import datetime
from pathlib import Path

HANDOFF_DIR_NAME = "docs/handoff"


def find_latest_handoff(project_dir: Path) -> Path | None:
    """返回 docs/handoff/ 下修改时间最新的非隐藏文件。"""
    handoff_dir = project_dir / HANDOFF_DIR_NAME
    if not handoff_dir.is_dir():
        return None

    candidates = [
        f
        for f in handoff_dir.iterdir()
        if f.is_file() and not f.name.startswith(".") and f.name != "README.md"
    ]
    if not candidates:
        return None

    return max(candidates, key=lambda f: f.stat().st_mtime)


def build_context(handoff_path: Path) -> str:
    """构造注入到 additionalContext 的文本。"""
    content = handoff_path.read_text(encoding="utf-8").strip()
    relative_path = handoff_path.relative_to(handoff_path.parents[2])  # docs/handoff/filename
    mtime = datetime.fromtimestamp(handoff_path.stat().st_mtime).strftime("%Y-%m-%d %H:%M")

    return (
        f"[Handoff] 最新交接文档: {relative_path} (更新于 {mtime})\n"
        f"{'=' * 60}\n"
        f"{content}\n"
        f"{'=' * 60}\n"
        f"请在回复前先总结此交接文档的关键状态和待办事项，并基于此继续工作。"
    )


def main() -> None:
    # 读取 hook 输入（session 元数据，可用于日志）
    try:
        _input_data = json.loads(sys.stdin.read())
    except Exception:
        pass

    project_dir_str = os.environ.get("CLAUDE_PROJECT_DIR", "")
    if not project_dir_str:
        try:
            project_dir_str = _input_data.get("cwd", ".")
        except Exception:
            project_dir_str = "."

    project_dir = Path(project_dir_str)
    handoff_path = find_latest_handoff(project_dir)

    if handoff_path is None:
        # 无 handoff 文档时不输出任何内容，静默退出
        sys.exit(0)

    context = build_context(handoff_path)
    output = {
        "hookSpecificOutput": {
            "hookEventName": "SessionStart",
            "additionalContext": context,
        }
    }
    print(json.dumps(output, ensure_ascii=False))


if __name__ == "__main__":
    main()
