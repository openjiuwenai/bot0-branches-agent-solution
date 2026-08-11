"""
Shared test fixtures + sys.modules stubs for EDPAgent unit tests.

EDPAgent imports openjiuwen at module load time. The runtime ships those
modules; in unit tests we install an import hook that auto-stubs any
openjiuwen submodule with a MagicMock-based module, then overlay real
sentinel values where the code under test depends on identity (e.g.
`INTERRUPTION_KEY`, `AgentRail`, `OutputSchema`).

EDPAgent owns its local event schema in `EDPAgent.events`; tests keep
runtime-only `common.*` modules stubbed only where rails need them.
"""
# pylint: disable=protected-access

from __future__ import annotations

import importlib.abc
import importlib.machinery
import importlib.util
import json
import sys
import types
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest


# ── sys.path: EDPAgent package + a2a_service helpers ─────────────────────────
_EDPAGENT_DIR = Path(__file__).resolve().parent.parent
_COMMUNITY_DIR = _EDPAGENT_DIR.parent
_AGENT_STORE_DIR = _COMMUNITY_DIR.parent
_REPO_ROOT = _AGENT_STORE_DIR.parent
_A2A_SERVICE_DIR = _REPO_ROOT / "agent-runtime" / "applications" / "a2a_service"

for p in (str(_COMMUNITY_DIR), str(_A2A_SERVICE_DIR)):
    if p not in sys.path:
        sys.path.append(p)


# ── openjiuwen + common.* auto-stub via meta path ─────────
_AUTO_STUB_PREFIXES = ("openjiuwen",)


class _AutoStubFinder(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    """Return a permissive MagicMock module for matching dotted names — but
    only when no real implementation can be found via the normal finder chain.

    openjiuwen IS installed in the dev/test venv (per EDPAgent pyproject.toml).
    For modules that exist, defer to real import so framework callbacks etc.
    behave correctly. Only fall back to mock for genuinely missing modules
    (e.g. agent-runtime-only `common.crypto` / `common.logger` when running
    inside agent-store unit tests).
    """

    def __init__(self):
        self._in_lookup = False  # re-entry guard

    def find_spec(self, fullname, path=None, target=None):  # noqa: ARG002
        if not any(
            fullname == p or fullname.startswith(p + ".")
            for p in _AUTO_STUB_PREFIXES
        ):
            return None
        if self._in_lookup:
            return None
        # Try real finder chain first; if anyone else can resolve it, let them.
        self._in_lookup = True
        try:
            real_spec = importlib.util.find_spec(fullname)
        except (ImportError, ValueError, AttributeError):
            real_spec = None
        finally:
            self._in_lookup = False
        if real_spec is not None:
            return None  # real module exists; let normal import handle it
        return importlib.machinery.ModuleSpec(fullname, self, is_package=True)

    def create_module(self, spec):
        mod = types.ModuleType(spec.name)
        # Make attribute access lazy: any unset name returns a MagicMock so
        # `from x import Y` succeeds for unknown Y.
        mock = MagicMock(name=spec.name)

        def _mod_getattr(name, _m=mock):
            return getattr(_m, name)

        mod.__getattr__ = _mod_getattr  # type: ignore[attr-defined]
        mod.__path__ = []  # mark as package so submodule imports keep going
        return mod

    def exec_module(self, module):
        return None


sys.meta_path.append(_AutoStubFinder())  # append: real finders win


def _make_module(name: str) -> types.ModuleType:
    mod = sys.modules.get(name)
    if mod is None:
        # Prefer the real installed module when importable. This avoids breaking
        # third-party code (e.g. openjiuwen controller_output.py) that imports
        # sibling classes (StreamSchemas) from these submodules — a MagicMock
        # would turn StreamSchemas into a mock object and crash pydantic typing
        # at class definition time.
        try:
            importlib.import_module(name)
            return sys.modules[name]
        except ImportError:
            pass
        mod = types.ModuleType(name)
        mock = MagicMock(name=name)

        def _mod_getattr(attr, _m=mock):
            return getattr(_m, attr)

        mod.__getattr__ = _mod_getattr  # type: ignore[attr-defined]
        mod.__path__ = []
        sys.modules[name] = mod
    return mod


def _install_real_sentinels() -> None:
    """Overlay real values where identity matters (constants, base classes)."""
    state_mod = _make_module("openjiuwen.core.single_agent.interrupt.state")
    state_mod.INTERRUPTION_KEY = "_interruption"

    rail_base = _make_module("openjiuwen.core.single_agent.rail.base")

    class AgentRail:
        priority = 0

    class AgentCallbackContext:
        pass

    class ModelCallInputs:
        pass

    rail_base.AgentRail = AgentRail
    rail_base.AgentCallbackContext = AgentCallbackContext
    rail_base.ModelCallInputs = ModelCallInputs

    stream_mod = _make_module("openjiuwen.core.session.stream")

    class OutputSchema:
        def __init__(self, **kwargs: Any) -> None:
            self.__dict__.update(kwargs)

    stream_mod.OutputSchema = OutputSchema

    session_mod = _make_module("openjiuwen.core.session.agent")
    session_mod.create_agent_session = MagicMock(name="create_agent_session")

    # Note: openjiuwen is a real installed package in the venv (per pyproject.toml
    # dep `openjiuwen==0.1.16`). The auto-stub finder above only handles modules
    # NOT yet imported. For tool framework (Tool / ToolCard / Input), the real
    # implementation is used — no sentinel needed.

    crypto_mod = _make_module("common.crypto")

    def _decrypt_config_value(v):
        return v

    crypto_mod.decrypt_config_value = _decrypt_config_value

    # common.logger 真实实现要 fastapi（runtime 包），unit-test venv 没装。
    # 这里强制注入 stub —— 即便真模块在 a2a_service/common/logger.py 里能找到，
    # 我们也在它被首次 import 之前就占位 sys.modules['common.logger']，让 rail
    # 模块导入时拿到 stub 而非真实模块。
    logger_mod = _make_module("common.logger")
    logger_mod.Extra = MagicMock(name="common.logger.Extra")
    logger_mod.Tag = MagicMock(name="common.logger.Tag")
    logger_mod.TagObservation = MagicMock(name="common.logger.TagObservation")
    logger_mod.ObservationType = MagicMock(name="common.logger.ObservationType")
    logger_mod.Level = MagicMock(name="common.logger.Level")

    def _stub_to_logger(*args, **kwargs):
        from loguru import logger
        msg = kwargs.get('message', {})
        if isinstance(msg, str):
            try:
                msg = json.loads(msg)
            except Exception:
                logger.info("[EDP-LLM-TOOL]")
                return
        if isinstance(msg, dict):
            tn = msg.get('tool_name', msg.get('name', ''))
            inp = msg.get('input', {})
            logger.info(f"[EDP-LLM-TOOL] tool_name={tn!r} {inp!r}")
        else:
            logger.info("[EDP-LLM-TOOL]")

    logger_mod.to_logger = _stub_to_logger


_install_real_sentinels()


# ── Fixtures ─────────────────────────────────────────────────────────────────
# ── e2e marker / cli flag ────────────────────────────────────────────────────
def pytest_addoption(parser):
    parser.addoption(
        "--run-e2e",
        action="store_true",
        default=False,
        help="Run e2e tests (real Aliyun LLM, slow + costs $)",
    )


def pytest_collection_modifyitems(config, items):
    if config.getoption("--run-e2e"):
        return
    skip_e2e = pytest.mark.skip(reason="need --run-e2e to enable")
    for item in items:
        if "e2e" in item.keywords:
            item.add_marker(skip_e2e)


@pytest.fixture
def aliyun_env():
    """Load .env.test (Aliyun creds). Skip test if file missing."""
    env_path = Path(__file__).resolve().parent.parent / ".env.test"
    if not env_path.exists():
        pytest.skip(".env.test not found (need Aliyun credentials for e2e)")
    cfg: dict[str, str] = {}
    for line in env_path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            cfg[k.strip()] = v.strip()
    required = {"ALIYUN_API_KEY", "ALIYUN_API_BASE", "ALIYUN_MODEL"}
    missing = required - set(cfg.keys())
    if missing:
        pytest.skip(f"missing env keys: {missing}")
    return cfg


@pytest.fixture(autouse=True)
def _configure_lite_todo_steps():
    """所有测试自动配置 lite_todo 4 项业务步骤——等价于 agent.initialize_dpa()
    加载 AgentRule.md 后调用 configure_steps() 的状态。

    每个 test 跑完会 reset，避免污染其它 test。如果某个 test 想测"未配置时抛错"，
    可以在 test 内部先调 reset_steps()。
    """
    from EDPAgent.tool.lite_todo.models import configure_steps, reset_steps
    configure_steps([
        {"step_id": 1, "content": "推荐理财产品",
         "skill": "rebuild_product_recommend_skill"},
        {"step_id": 2, "content": "交互式理财筛选",
         "skill": "rebuild_interact_finance_rec_skill"},
        {"step_id": 3, "content": "确定购买产品和金额",
         "skill": "rebuild_product_select_skill"},
        {"step_id": 4, "content": "查询理财账户余额，如果资金不足进行资金筹划，并购买理财产品",
         "skill": "model_driven_fund_planning_skill"},
    ])
    yield
    reset_steps()


@pytest.fixture
def fake_session():
    """A session double matching what agent_stream uses."""
    state: dict[str, Any] = {}
    sess = MagicMock(name="AgentSession")
    sess.session_id = "test-conv"

    def _get_state(key):
        return state.get(key)

    def _update_state(d):
        state.update(d)

    sess.get_state.side_effect = _get_state
    sess.update_state.side_effect = _update_state
    sess.pre_run = AsyncMock()
    sess.write_stream = AsyncMock()
    sess._state = state
    return sess


@pytest.fixture
def fake_agent():
    """An agent double whose `.stream()` yields nothing (drives agent_stream)."""
    agent = MagicMock(name="ReActAgent")
    agent.card = MagicMock()

    async def empty_stream(inputs, session):  # noqa: ARG001
        if False:
            yield  # pragma: no cover
        return

    agent.stream = empty_stream
    return agent
