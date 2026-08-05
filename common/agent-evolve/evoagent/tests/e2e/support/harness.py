"""Process harness for the live managed-document optimization E2E."""

from __future__ import annotations

import asyncio
import json
import os
import shlex
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from types import TracebackType
from typing import Any, Self, TextIO
from unittest.mock import patch

import httpx
import yaml

from evo_agent.config import EvolveConfig
from evo_agent.optimizer_runner import run_optimization
from evo_agent.types import OptimizeReport, OptimizeRequest
from tests.e2e.support.scripted_llm import ScriptedLLM

_AGENT_NAME = "e2e_agent"
_DOC_KIND = "agent_rule"
_RULE = """---
name: e2e-rule-agent
description: Managed-document E2E rule
---
# Agent Rule

ANSWER=OLD
"""


class ManagedDocE2EHarness:
    """Launch a fake business Agent and the real sibling Adapter CLI."""

    def __init__(self, root: Path) -> None:
        self._root = root
        self._repo_root = Path(__file__).resolve().parents[3]
        configured_adapter = os.environ.get("EVO_ADAPTER_REPO")
        self._adapter_repo = (
            Path(configured_adapter).expanduser().resolve()
            if configured_adapter
            else self._repo_root.parent / "EvoAgentAdapter"
        )
        self._agent_port = _free_port()
        self._adapter_port = _free_port()
        while self._adapter_port == self._agent_port:
            self._adapter_port = _free_port()
        self._agent_url = f"http://127.0.0.1:{self._agent_port}"
        self._adapter_url = f"http://127.0.0.1:{self._adapter_port}"
        self._rule_path = root / "agent" / "AgentRule.md"
        self._log_dir = root / "agent-logs"
        self._log_path = self._log_dir / "process_e2e.log"
        self._adapter_config = root / "adapter.yaml"
        self._dataset_manifest = root / "dataset.yaml"
        self._processes: list[tuple[str, subprocess.Popen[str], TextIO]] = []
        self._http = httpx.Client(trust_env=False)

    def __enter__(self) -> Self:
        try:
            self._validate_adapter_repo()
            self._write_fixtures()
            self._start_agent()
            self._wait_http(f"{self._agent_url}/health", process_name="mock Agent")
            self._start_adapter()
            self._wait_http(f"{self._adapter_url}/health", process_name="Adapter")
            self._bootstrap_applied_revision()
            return self
        except BaseException:
            self._stop_processes()
            raise

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc_value: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        del exc_type, exc_value, traceback
        self._stop_processes()

    def _stop_processes(self) -> None:
        for _name, process, _stream in reversed(self._processes):
            if process.poll() is None:
                process.terminate()
        for _name, process, stream in reversed(self._processes):
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            stream.close()
        self._http.close()

    def ask(self, query: str) -> str:
        conversation_id = f"manual-{uuid.uuid4().hex}"
        response = self._http.post(
            f"{self._adapter_url}/api/v1/agents/{_AGENT_NAME}/conversations/{conversation_id}",
            json={"query": query},
            timeout=10,
        )
        response.raise_for_status()
        return str(response.json()["answer"])

    def optimize(self) -> OptimizeReport:
        config = EvolveConfig(
            llm_provider="OpenAI",
            artifact_dir=self._root / "artifacts",
            remote_timeout=10.0,
            remote_max_retries=0,
            managed_doc_apply_deadline=30.0,
        )
        request = OptimizeRequest(
            scenario="edp_agent",
            agent_name=_AGENT_NAME,
            managed_doc_kind=_DOC_KIND,
            dataset_manifest_path=self._dataset_manifest,
            adapter_url=self._adapter_url,
            num_epochs=1,
            batch_size=2,
            hyperparams={
                "accumulation": 1,
                "minibatch_size": 2,
                "edit_budget": 1,
                "num_parallel": 1,
                "parallelism": 1,
                "use_slow_update": False,
                "use_meta_skill": False,
                "trace_max_retries": 3,
                "trace_retry_backoff": 0.05,
                "validation_max_case_attempts": 1,
            },
        )
        with patch("evo_agent.optimizer_runner._create_llm", return_value=ScriptedLLM()):
            return asyncio.run(run_optimization(request, config))

    def get_managed_doc(self) -> dict[str, Any]:
        response = self._http.post(
            f"{self._adapter_url}/api/v1/managed-docs",
            json={
                "agent_name": _AGENT_NAME,
                "doc_kind": _DOC_KIND,
                "action": "content",
            },
            timeout=5,
        )
        response.raise_for_status()
        return response.json()

    def get_task(self, task_id: str) -> dict[str, Any]:
        response = self._http.get(
            f"{self._adapter_url}/api/v1/managed-docs/tasks/{task_id}", timeout=5
        )
        response.raise_for_status()
        return response.json()

    def get_optimization_traces(self) -> list[dict[str, Any]]:
        response = self._http.get(
            f"{self._adapter_url}/api/v1/agents/{_AGENT_NAME}/traces", timeout=5
        )
        response.raise_for_status()
        conversation_ids = [
            conversation_id
            for conversation_id in response.json()["conversation_ids"]
            if not conversation_id.startswith("manual-")
        ]
        traces: list[dict[str, Any]] = []
        for conversation_id in conversation_ids:
            cleaned = self._http.get(
                f"{self._adapter_url}/api/v1/agents/{_AGENT_NAME}/cleaned-traces/{conversation_id}",
                timeout=5,
            )
            cleaned.raise_for_status()
            body = cleaned.json()
            if body.get("messages"):
                traces.append(body)
        if not traces:
            raise RuntimeError("Adapter produced no cleaned optimization traces")
        return traces

    def _validate_adapter_repo(self) -> None:
        if not (self._adapter_repo / "pyproject.toml").is_file():
            raise RuntimeError(
                "EvoAgentAdapter repository not found. Set EVO_ADAPTER_REPO to its path; "
                f"looked in {self._adapter_repo}"
            )
        if shutil.which("uv") is None:
            raise RuntimeError("uv is required to launch the sibling EvoAgentAdapter")

    def _write_fixtures(self) -> None:
        self._rule_path.parent.mkdir(parents=True)
        self._log_dir.mkdir(parents=True)
        self._rule_path.write_text(_RULE, encoding="utf-8")
        self._log_path.touch()
        helper = Path(__file__).with_name("restart_mock_agent.py")
        restart_cmd = shlex.join(
            [sys.executable, str(helper), f"{self._agent_url}/__test__/restart"]
        )
        config = {
            "host": "127.0.0.1",
            "port": self._adapter_port,
            "poll_interval": 1,
            "start_from": "head",
            "log_dir": str(self._log_dir),
            "output_dir": str(self._root / "adapter-output"),
            "offset_file": str(self._root / "adapter-offsets.json"),
            "managed_doc_defaults": {"profile": "burst"},
            "agents": [
                {
                    "name": _AGENT_NAME,
                    "log_dir": str(self._log_dir),
                    "log_pattern": "process_*.log",
                    "output_dir": str(self._root / "adapter-output"),
                    "offset_file": str(self._root / "adapter-offsets.json"),
                    "agent_url": self._agent_url,
                    "project_id": "e2e-project",
                    "agent_id": "e2e-agent",
                    "timeout": 10,
                    "managed_docs": [
                        {
                            "kind": _DOC_KIND,
                            "path": str(self._rule_path),
                            "allow_root": str(self._rule_path.parent),
                            "apply": "restart",
                            "restart_cmd": restart_cmd,
                            "restart_timeout": 3,
                            "health_url": f"{self._agent_url}/health",
                            "health_down_timeout": 2.0,
                            "health_up_timeout": 3.0,
                            "health_up_consecutive": 1,
                            "health_poll_interval": 0.05,
                            "max_attempts": 1,
                            "backoff_base": 0.0,
                            "backoff_max": 0.0,
                        }
                    ],
                }
            ],
        }
        self._adapter_config.write_text(yaml.safe_dump(config, sort_keys=False), encoding="utf-8")
        cases = [
            {
                "case_id": f"rule-case-{index}",
                "inputs": {"query": f"Report the active rule version ({index})."},
                "label": {"expected_result": "NEW"},
            }
            for index in range(4)
        ]
        (self._root / "items.json").write_text(
            json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        manifest = {
            "schema_version": "1.0",
            "name": "managed_doc_e2e",
            "cases": "items.json",
            "train_split": 0.5,
            "seed": 0,
            "evaluator": {
                "type": "custom",
                "dotted_path": "tests.e2e.support.rule_evaluator.RuleAnswerEvaluator",
                "kwargs": {},
            },
        }
        self._dataset_manifest.write_text(
            yaml.safe_dump(manifest, sort_keys=False), encoding="utf-8"
        )

    def _start_agent(self) -> None:
        script = Path(__file__).with_name("mock_agent.py")
        env = {
            **os.environ,
            "PYTHONUNBUFFERED": "1",
            "EVO_E2E_RULE_PATH": str(self._rule_path),
            "EVO_E2E_AGENT_LOG": str(self._log_path),
        }
        self._spawn(
            "mock Agent",
            [sys.executable, str(script), "--port", str(self._agent_port)],
            cwd=self._repo_root,
            env=env,
        )

    def _start_adapter(self) -> None:
        env = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith("ADAPTER_") and key != "VIRTUAL_ENV"
        }
        loopback_hosts = "127.0.0.1,localhost"
        for key in ("NO_PROXY", "no_proxy"):
            current = env.get(key, "").strip(",")
            env[key] = f"{current},{loopback_hosts}" if current else loopback_hosts
        env.update({"PYTHONUNBUFFERED": "1", "ADAPTER_LOG_LEVEL": "WARNING"})
        self._spawn(
            "Adapter",
            [
                "uv",
                "run",
                "--project",
                str(self._adapter_repo),
                "agent-adapter",
                "--config",
                str(self._adapter_config),
            ],
            cwd=self._adapter_repo,
            env=env,
        )

    def _spawn(self, name: str, command: list[str], *, cwd: Path, env: dict[str, str]) -> None:
        log_path = self._root / f"{name.lower().replace(' ', '-')}.log"
        stream = log_path.open("w", encoding="utf-8")
        process = subprocess.Popen(  # noqa: S603
            command,
            cwd=cwd,
            env=env,
            stdout=stream,
            stderr=subprocess.STDOUT,
            text=True,
        )
        self._processes.append((name, process, stream))

    def _wait_http(self, url: str, *, process_name: str, timeout: float = 15.0) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            process = next(process for name, process, _ in self._processes if name == process_name)
            if process.poll() is not None:
                raise RuntimeError(self._process_failure(process_name, process.returncode))
            try:
                response = self._http.get(url, timeout=0.5)
                if response.status_code == 200:
                    return
            except httpx.HTTPError:
                pass
            time.sleep(0.05)
        raise TimeoutError(f"{process_name} did not become ready at {url}")

    def _bootstrap_applied_revision(self) -> None:
        snapshot = self.get_managed_doc()
        response = self._http.post(
            f"{self._adapter_url}/api/v1/managed-docs",
            json={
                "agent_name": _AGENT_NAME,
                "doc_kind": _DOC_KIND,
                "action": "update",
                "content": snapshot["content"],
            },
            timeout=5,
        )
        response.raise_for_status()
        body = response.json()
        task_id = body.get("task_id")
        if task_id is not None:
            self._wait_task(str(task_id))
        confirmed = self.get_managed_doc()
        if confirmed["pending_apply"] or (
            confirmed["file_revision"] != confirmed["applied_revision"]
        ):
            raise RuntimeError(f"failed to bootstrap managed-doc baseline: {confirmed}")

    def _wait_task(self, task_id: str, timeout: float = 10.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            task = self.get_task(task_id)
            if task["status"] == "SUCCEEDED":
                return task
            if task["status"] == "FAILED":
                raise RuntimeError(f"managed-doc task failed: {task}")
            time.sleep(0.05)
        raise TimeoutError(f"managed-doc task did not finish: {task_id}")

    def _process_failure(self, name: str, returncode: int | None) -> str:
        log_path = self._root / f"{name.lower().replace(' ', '-')}.log"
        log = log_path.read_text(encoding="utf-8") if log_path.exists() else ""
        return f"{name} exited with {returncode}:\n{log[-4000:]}"


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])
