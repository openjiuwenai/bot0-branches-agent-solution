"""Run Adapter unit tests for sandbox-mode Skill backend (TC-U*).

Usage (from EvoAgentAdapter root or this directory):
    python run_unit_suite.py
"""

from __future__ import annotations

import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ADAPTER_ROOT = Path(__file__).resolve().parents[3]
REPORT_DIR = Path(__file__).resolve().parents[1] / "reports"
TEST_FILES = [
    "tests/unit/test_jiuwenbox_skill_store.py",
    "tests/unit/test_sandbox_resolve.py",
]


def main() -> int:
    py = ADAPTER_ROOT / ".venv" / "Scripts" / "python.exe"
    if not py.is_file():
        py = Path(sys.executable)

    cmd = [str(py), "-m", "pytest", *TEST_FILES, "-v", "--tb=line"]
    print("=" * 70)
    print(f"沙箱模式单元套件 | cwd={ADAPTER_ROOT}")
    print(f"cmd: {' '.join(cmd)}")
    print("=" * 70)
    proc = subprocess.run(
        cmd,
        cwd=str(ADAPTER_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    out = (proc.stdout or "") + (proc.stderr or "")
    print(out)
    passed = proc.returncode == 0

    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    report = REPORT_DIR / f"unit_suite_{stamp}.json"
    report.write_text(
        json.dumps(
            {
                "meta": {
                    "generated_at_utc": datetime.now(timezone.utc).isoformat(),
                    "python": str(py),
                    "cwd": str(ADAPTER_ROOT),
                    "tests": TEST_FILES,
                    "exit_code": proc.returncode,
                    "passed": passed,
                },
                "stdout": out[-20000:],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"REPORT_JSON={report}")
    print(f"合计: {'PASS' if passed else 'FAIL'}")
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
