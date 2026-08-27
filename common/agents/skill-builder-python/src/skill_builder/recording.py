# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.

"""Standalone Playwright recording runtime.

The module has no HTTP, ORM or host-service imports. Playwright is imported only
when a recording starts, so validation and packaging remain usable without the
optional recording dependency.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import shutil
import subprocess
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse

from skill_builder.runtime.serialization import json_safe


_LOGGER = logging.getLogger(__name__)


class RecordingError(RuntimeError):
    def __init__(self, message: str, *, status_code: int = 500) -> None:
        super().__init__(message)
        self.status_code = status_code


class RecordingDependencyError(RecordingError):
    pass


@dataclass(frozen=True, slots=True)
class RecordingAction:
    action: str
    x: float | None = None
    y: float | None = None
    text: str | None = None
    key: str | None = None
    delta_y: float | None = None
    url: str | None = None


@dataclass(slots=True)
class ActiveWebRecording:
    id: str
    workspace_id: str
    title: str
    goal: str | None
    start_url: str
    current_url: str | None
    status: str
    started_at: str
    stopped_at: str | None
    recording_path: str
    material_path: str | None
    trace_path: str | None
    error: str | None
    context: Any
    playwright: Any
    page: Any
    interaction_mode: str = "headed"
    navigation_status: str | None = None
    steps: list[dict[str, Any]] = field(default_factory=list)
    event_count: int = 0
    screenshot_count: int = 0
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    def snapshot(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "workspace_id": self.workspace_id,
            "title": self.title,
            "goal": self.goal,
            "start_url": self.start_url,
            "current_url": self.current_url,
            "status": self.status,
            "started_at": self.started_at,
            "stopped_at": self.stopped_at,
            "event_count": self.event_count,
            "screenshot_count": self.screenshot_count,
            "recording_path": self.recording_path,
            "material_path": self.material_path,
            "trace_path": self.trace_path,
            "error": self.error,
            "interaction_mode": "viewer" if self.interaction_mode == "viewer" else "headed",
            "navigation_status": self.navigation_status,
        }


_ActiveWebRecording = ActiveWebRecording
_ACTIVE_WEB_RECORDINGS: dict[str, ActiveWebRecording] = {}


def _iso_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _recording_dir(recording_id: str) -> str:
    return f"playwright/recordings/{recording_id}"


def recording_snapshot(recording: ActiveWebRecording) -> dict[str, Any]:
    return recording.snapshot()


def _assert_recording_url(value: str) -> str:
    url = str(value or "").strip()
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise RecordingError("录屏起始 URL 仅支持 http:// 或 https://", status_code=422)
    return url


def _recording_window_size() -> dict[str, int]:
    def read_int(name: str, fallback: int) -> int:
        try:
            value = int(os.getenv(name) or "")
        except (TypeError, ValueError):
            return fallback
        return value if value > 0 else fallback

    return {
        "width": read_int("WEB_RECORDING_WINDOW_WIDTH", read_int("WEB_RECORDING_WIDTH", 1280)),
        "height": read_int("WEB_RECORDING_WINDOW_HEIGHT", read_int("WEB_RECORDING_HEIGHT", 860)),
    }


def _web_recording_headless() -> bool:
    return str(os.getenv("WEB_RECORDING_HEADLESS") or "auto").strip().lower() in {
        "1", "true", "yes", "on",
    }


def _web_recording_mode() -> str:
    value = str(os.getenv("WEB_RECORDING_HEADLESS") or "auto").strip().lower()
    if value in {"1", "true", "yes", "on", "headless", "viewer"}:
        return "headless"
    if value in {"0", "false", "no", "off", "headed", "desktop"}:
        return "headed"
    return "auto"


def _recording_xauthority() -> str:
    """Resolve the X11 cookie file for service processes with a minimal env.

    Interactive shells usually expose ``HOME``/``XAUTHORITY`` implicitly, but
    a service process may deliberately start with a small environment.
    Without this fallback ``xdpyinfo`` reports ``No authorisation provided`` and
    ``auto`` incorrectly downgrades a usable headed session to the screenshot
    viewer.  Explicit configuration always wins; otherwise use the current
    account's conventional cookie file when it exists.
    """

    configured = str(os.getenv("WEB_RECORDING_XAUTHORITY") or os.getenv("XAUTHORITY") or "").strip()
    if configured:
        return str(Path(configured).expanduser())

    candidates: list[Path] = []
    try:
        candidates.append(Path.home() / ".Xauthority")
    except RuntimeError:
        pass
    # Path.home() can be unavailable when systemd omits HOME; the service runs
    # as root in the supported local deployment, so keep this explicit fallback
    # narrow rather than scanning user directories.
    candidates.append(Path("/root/.Xauthority"))
    for candidate in candidates:
        try:
            if candidate.is_file() and os.access(candidate, os.R_OK):
                return str(candidate)
        except OSError:
            continue
    return ""


def _headed_recording_capability() -> dict[str, Any]:
    display = str(os.getenv("WEB_RECORDING_DISPLAY") or os.getenv("DISPLAY") or "").strip()
    xauthority = _recording_xauthority()
    if not display:
        return {"available": False, "reason": "display_missing", "display": None}
    if xauthority:
        authority = Path(xauthority).expanduser()
        if not authority.is_file() or not os.access(authority, os.R_OK):
            return {
                "available": False,
                "reason": "xauthority_unreadable",
                "display": display,
                "xauthority": str(authority),
            }
    env = dict(os.environ)
    env["DISPLAY"] = display
    if xauthority:
        env["XAUTHORITY"] = xauthority
    try:
        timeout = max(1.0, min(float(os.getenv("WEB_RECORDING_DISPLAY_PROBE_TIMEOUT_SECONDS") or 3), 10.0))
    except ValueError:
        timeout = 3.0
    probe_command = shutil.which("xdpyinfo")
    if not probe_command:
        return {"available": True, "reason": "probe_unavailable", "display": display}
    try:
        result = subprocess.run(
            [probe_command, "-display", display],
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return {"available": True, "reason": "probe_unavailable", "display": display}
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {
            "available": False,
            "reason": "display_probe_failed",
            "display": display,
            "detail": str(exc)[:300],
        }
    if result.returncode != 0:
        return {
            "available": False,
            "reason": "display_unreachable",
            "display": display,
            "detail": (result.stderr or result.stdout or "display probe failed").strip()[:500],
        }
    return {"available": True, "reason": "display_ready", "display": display}


def _resolve_web_recording_headless() -> tuple[bool, dict[str, Any]]:
    mode = _web_recording_mode()
    if mode == "headless":
        return True, {"available": True, "reason": "headless_configured", "mode": mode}
    capability = _headed_recording_capability()
    if mode == "auto":
        return not bool(capability.get("available")), {**capability, "mode": mode}
    if not capability.get("available"):
        detail = str(capability.get("detail") or "").strip()
        raise RecordingError(
            "可见网页录制的图形显示能力预检失败"
            f"（{capability.get('reason') or 'display_unavailable'}）。"
            "请修复 WEB_RECORDING_DISPLAY/WEB_RECORDING_XAUTHORITY，"
            "或设置 WEB_RECORDING_HEADLESS=auto|true 使用内置截图 Viewer。"
            + (f" 原始诊断：{detail}" if detail else ""),
            status_code=503,
        )
    return False, {**capability, "mode": mode}


def _web_recording_launch_environment(*, headless: bool) -> dict[str, str]:
    env = dict(os.environ)
    if headless:
        return env
    display = str(os.getenv("WEB_RECORDING_DISPLAY") or os.getenv("DISPLAY") or "").strip()
    if not display:
        raise RecordingError(
            "可见网页录制需要图形显示会话，但后端未配置 DISPLAY。"
            "请设置 WEB_RECORDING_DISPLAY，或在无桌面部署中设置 WEB_RECORDING_HEADLESS=true。",
            status_code=503,
        )
    env["DISPLAY"] = display
    xauthority = _recording_xauthority()
    if xauthority:
        env["XAUTHORITY"] = xauthority
    return env


def _display_runtime_failure(exc: BaseException) -> bool:
    return bool(re.search(
        r"Missing X server|\$DISPLAY|cannot open display|No protocol specified|"
        r"No auth(?:orisation|orization) provided|X server",
        str(exc),
        re.IGNORECASE,
    ))


def _playwright_browser_install_failure(exc: BaseException) -> bool:
    """Recognize a Playwright/browser revision mismatch as an environment error."""

    message = str(exc).lower()
    return "executable doesn't exist at" in message and "playwright install" in message


async def _launch_recording_context(
    chromium: Any,
    *,
    profile_dir: str,
    launch_options: dict[str, Any],
    headless: bool,
    display_capability: dict[str, Any],
) -> tuple[Any, bool, dict[str, Any]]:
    options = {**launch_options, "headless": headless}
    try:
        context = await chromium.launch_persistent_context(profile_dir, **options)
        return context, headless, display_capability
    except Exception as exc:
        if (
            headless
            or str(display_capability.get("mode") or "") != "auto"
            or not _display_runtime_failure(exc)
        ):
            raise
        capability = {
            **display_capability,
            "available": False,
            "reason": "headed_launch_failed",
            "detail": str(exc)[:500],
            "fallback": "headless",
        }
        context = await chromium.launch_persistent_context(
            profile_dir,
            **{
                **launch_options,
                "headless": True,
                "env": _web_recording_launch_environment(headless=True),
            },
        )
        return context, True, capability


_RECORDING_INIT_SCRIPT = r"""
(() => {
  if (window.__skillBuilderRecorderInstalled) return;
  window.__skillBuilderRecorderInstalled = true;
  const timers = new Map();
  const clean = (value) => String(value || "").replace(/\s+/g, " ").trim().slice(0, 180);
  const selectorFor = (element) => {
    if (!element || element.nodeType !== Node.ELEMENT_NODE) return "";
    if (element.id) return "#" + element.id;
    const parts = [];
    let current = element;
    while (current && current.nodeType === Node.ELEMENT_NODE && parts.length < 5) {
      let part = current.tagName.toLowerCase();
      const name = current.getAttribute("name");
      if (name) part += '[name="' + String(name).replace(/"/g, '\\"') + '"]';
      const testId = current.getAttribute("data-testid") || current.getAttribute("data-test");
      if (testId) part += '[data-testid="' + String(testId).replace(/"/g, '\\"') + '"]';
      parts.unshift(part);
      current = current.parentElement;
    }
    return parts.join(" > ");
  };
  const targetSummary = (target) => {
    const element = target instanceof Element ? target : target?.parentElement;
    if (!element) return null;
    const rect = element.getBoundingClientRect();
    const input = element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement ? element : null;
    const isSecret = input instanceof HTMLInputElement && input.type === "password";
    return {
      selector: selectorFor(element), tagName: element.tagName.toLowerCase(),
      role: element.getAttribute("role"), id: element.id || null,
      name: element.getAttribute("name"), type: element.getAttribute("type"),
      ariaLabel: element.getAttribute("aria-label"), placeholder: element.getAttribute("placeholder"),
      text: clean(element.innerText || element.textContent || ""),
      value: isSecret ? "[secret]" : input ? input.value : null,
      bbox: { x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height) }
    };
  };
  const emit = (payload) => {
    const binding = window.__skillBuilderRecordEvent;
    if (typeof binding !== "function") return;
    Promise.resolve(binding({ ...payload, url: window.location.href, title: document.title })).catch(() => undefined);
  };
  document.addEventListener("click", (event) => emit({ type: "click", target: targetSummary(event.target) }), true);
  document.addEventListener("change", (event) => emit({ type: "change", target: targetSummary(event.target) }), true);
  document.addEventListener("submit", (event) => emit({ type: "submit", target: targetSummary(event.target) }), true);
  document.addEventListener("input", (event) => {
    const selector = selectorFor(event.target); clearTimeout(timers.get(selector));
    timers.set(selector, setTimeout(() => { emit({ type: "input", target: targetSummary(event.target) }); timers.delete(selector); }, 450));
  }, true);
  document.addEventListener("keydown", (event) => {
    if (["Enter", "Tab", "Escape"].includes(event.key)) emit({ type: "keydown", key: event.key, target: targetSummary(event.target) });
  }, true);
})();
"""


def _resolve_safe(root: Path, relative: str) -> Path:
    target = (root / relative).resolve()
    try:
        target.relative_to(root.resolve())
    except ValueError as exc:
        raise RecordingError("录屏文件路径越界", status_code=422) from exc
    return target


def _safe_filename(value: str) -> str:
    normalized = Path(str(value or "download")).name.replace("\x00", "")
    normalized = re.sub(r"[^A-Za-z0-9._\-\u4e00-\u9fff]+", "-", normalized).strip(".-")
    return normalized[:180] or "download"


def _truncate_text(value: str, limit: int) -> str:
    text = str(value or "")
    return text if len(text) <= limit else text[: max(0, limit - 3)] + "..."


def _stringify_recording_target(target: Any) -> str:
    if not isinstance(target, dict):
        return "page"
    parts = [
        target.get("tagName") if isinstance(target.get("tagName"), str) else None,
        f'"{_truncate_text(target.get("text") or "", 60)}"' if target.get("text") else None,
        f'aria-label="{_truncate_text(target.get("ariaLabel") or "", 60)}"' if target.get("ariaLabel") else None,
        f'name="{_truncate_text(target.get("name") or "", 40)}"' if target.get("name") else None,
        target.get("selector") if isinstance(target.get("selector"), str) else None,
    ]
    return " ".join(str(part) for part in parts if part) or "page"


def _render_recording_markdown(recording: ActiveWebRecording, *, as_input_material: bool) -> str:
    lines = [
        f"# {recording.title}", "",
        "这是用户录制的网页操作证据，用于辅助生成 Skill 初稿。" if as_input_material else "这是网页操作流程的实时录制日志。",
        "", f"- 录制 ID：{recording.id}", f"- 状态：{recording.status}",
        f"- 业务目标：{recording.goal or '未提供'}", f"- 起始 URL：{recording.start_url}",
        f"- 当前 URL：{recording.current_url or 'N/A'}", f"- 初始导航：{recording.navigation_status or 'unknown'}",
        f"- 开始时间：{recording.started_at}", f"- 停止时间：{recording.stopped_at or 'N/A'}",
        f"- 事件 JSONL：{_recording_dir(recording.id)}/events.jsonl",
        f"- 截图目录：{_recording_dir(recording.id)}/screenshots/",
        f"- Trace：{recording.trace_path or 'N/A'}", "", "## 证据说明", "",
        "- 录制步骤只证明本次观察到的页面路径、可见 UI 和用户操作，不等同于可复用自动化脚本。",
        "- 密码字段已尽量脱敏为 `[secret]`，但截图仍可能包含敏感页面数据。",
        "- 生成 Skill 前仍需识别变量、成功标准、权限、登录、验证码、内网和写操作风险。",
        "", "## 录制步骤", "",
    ]
    if not recording.steps:
        return "\n".join([*lines, "尚未捕获到操作步骤。", ""])
    for step in recording.steps:
        lines.extend([
            f"### 步骤 {step.get('step')}：{step.get('type')}", "",
            f"- 时间：{step.get('timestamp')}", f"- URL：{step.get('url') or 'N/A'}",
            f"- 页面标题：{step.get('title') or 'N/A'}",
            f"- 操作目标：{_stringify_recording_target(step.get('target'))}",
        ])
        if step.get("key"):
            lines.append(f"- 按键：{step['key']}")
        if step.get("value"):
            lines.append(f"- 输入值：{step['value']}")
        if step.get("screenshotPath"):
            lines.append(f"- 截图：{step['screenshotPath']}")
        if step.get("pageTextPreview"):
            lines.extend(["", "页面文本预览：", "", "```text", str(step["pageTextPreview"]), "```"])
        lines.append("")
    return "\n".join(lines)


def _persist_recording_files(root: Path, recording: ActiveWebRecording) -> None:
    record_dir = _resolve_safe(root, _recording_dir(recording.id))
    record_dir.mkdir(parents=True, exist_ok=True)
    (record_dir / "manifest.json").write_text(
        json.dumps(recording.snapshot(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    target = _resolve_safe(root, recording.recording_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(_render_recording_markdown(recording, as_input_material=False), encoding="utf-8")


async def _capture_recording_step(
    root: Path, recording: ActiveWebRecording, page: Any, payload: dict[str, Any]
) -> None:
    if recording.status != "recording":
        return
    async with recording.lock:
        if recording.status != "recording":
            return
        number = recording.event_count + 1
        screenshot_path = f"{_recording_dir(recording.id)}/screenshots/step-{number:03d}.png"
        saved = None
        try:
            target = _resolve_safe(root, screenshot_path)
            target.parent.mkdir(parents=True, exist_ok=True)
            await page.screenshot(path=str(target), full_page=False)
            recording.screenshot_count += 1
            saved = screenshot_path
        except Exception:
            _LOGGER.debug("Failed to capture a recording screenshot.", exc_info=True)
        try:
            page_text = await page.locator("body").inner_text(timeout=1000)
            page_text = _truncate_text(re.sub(r"\s+", " ", page_text).strip(), 1800)
        except Exception:
            page_text = None
        url = str(payload.get("url") or getattr(page, "url", "") or "")
        title = str(payload.get("title") or "")
        if not title:
            try:
                title = await page.title()
            except Exception:
                _LOGGER.debug("Failed to read the current recording page title.", exc_info=True)
        step = {
            **json_safe(payload, max_text_length=4000),
            "step": number,
            "timestamp": _iso_now(),
            "url": url,
            "title": title,
            "screenshotPath": saved,
            "pageTextPreview": page_text,
        }
        recording.event_count = number
        recording.current_url = url
        recording.steps.append(step)
        events = _resolve_safe(root, f"{_recording_dir(recording.id)}/events.jsonl")
        events.parent.mkdir(parents=True, exist_ok=True)
        with events.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(step, ensure_ascii=False) + "\n")
        _persist_recording_files(root, recording)


async def _capture_recording_download(
    root: Path,
    recording: ActiveWebRecording,
    page: Any,
    download: Any,
    *,
    now_ms: Callable[[], int] | None = None,
) -> None:
    if recording.status != "recording":
        return
    raw = getattr(download, "suggested_filename", None)
    filename = _safe_filename(raw() if callable(raw) else str(raw or "download"))
    timestamp = (now_ms or (lambda: int(time.time() * 1000)))()
    relative = f"{_recording_dir(recording.id)}/downloads/{timestamp}-{filename}"
    try:
        target = _resolve_safe(root, relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        await download.save_as(str(target))
    except Exception:
        relative = ""
    await _capture_recording_step(root, recording, page, {
        "type": "download",
        "target": {"filename": filename, "path": relative or None},
    })


def active_recording(workspace_id: str, recording_id: str | None = None) -> ActiveWebRecording:
    recording = _ACTIVE_WEB_RECORDINGS.get(workspace_id)
    if (
        recording is None
        or (recording_id is not None and recording.id != recording_id)
        or recording.status != "recording"
    ):
        raise RecordingError("当前录屏任务不存在或已经结束", status_code=404)
    return recording


def get_active_recording(workspace_id: str) -> ActiveWebRecording | None:
    """Return the running recording for a workspace, if one exists.

    This is intentionally different from :func:`active_recording`: polling the
    HTTP adapter must be able to represent "no active recording" without
    converting a normal empty state into an exception.
    """

    recording = _ACTIVE_WEB_RECORDINGS.get(str(workspace_id))
    if recording is None or recording.status == "completed":
        return None
    return recording


# Public names used by the host adapter.  The implementation details remain
# private to this module, while the standalone package exposes one stable
# recording surface to the current and future hosts.
def resolve_recording_headless() -> tuple[bool, dict[str, Any]]:
    return _resolve_web_recording_headless()


async def launch_recording_context(*args: Any, **kwargs: Any) -> tuple[Any, bool, dict[str, Any]]:
    return await _launch_recording_context(*args, **kwargs)


def recording_launch_environment(*, headless: bool) -> dict[str, str]:
    return _web_recording_launch_environment(headless=headless)


def recording_now() -> str:
    return _iso_now()


async def capture_recording_frame(*, workspace_id: str, recording_id: str) -> bytes:
    recording = active_recording(workspace_id, recording_id)
    async with recording.lock:
        if recording.page is None or recording.page.is_closed():
            raise RecordingError("录屏页面已经关闭", status_code=409)
        try:
            return await recording.page.screenshot(type="png", animations="disabled")
        except Exception as exc:
            raise RecordingError(f"读取录屏画面失败：{exc}") from exc


async def perform_recording_action(
    *,
    root: Path,
    workspace_id: str,
    recording_id: str,
    action: RecordingAction,
) -> ActiveWebRecording:
    recording = active_recording(workspace_id, recording_id)
    async with recording.lock:
        page = recording.page
        if page is None or page.is_closed():
            raise RecordingError("录屏页面已经关闭", status_code=409)
        try:
            if action.action == "click":
                if action.x is None or action.y is None or action.x < 0 or action.y < 0:
                    raise RecordingError("点击操作需要有效坐标", status_code=422)
                await page.mouse.click(float(action.x), float(action.y))
            elif action.action == "type":
                if action.text is None:
                    raise RecordingError("输入操作需要文本", status_code=422)
                await page.keyboard.insert_text(action.text)
            elif action.action == "press":
                key = str(action.key or "").strip()
                allowed = {
                    "Enter",
                    "Tab",
                    "Escape",
                    "Backspace",
                    "Delete",
                    "ArrowUp",
                    "ArrowDown",
                    "ArrowLeft",
                    "ArrowRight",
                    "PageUp",
                    "PageDown",
                    "Home",
                    "End",
                }
                if key not in allowed:
                    raise RecordingError("不支持的按键", status_code=422)
                await page.keyboard.press(key)
            elif action.action == "scroll":
                await page.mouse.wheel(0, float(action.delta_y or 0))
            elif action.action == "navigate":
                await page.goto(_assert_recording_url(action.url or ""), wait_until="domcontentloaded", timeout=30000)
                recording.navigation_status = "loaded"
                recording.error = None
            elif action.action == "refresh":
                await page.reload(wait_until="domcontentloaded", timeout=30000)
                recording.navigation_status = "loaded"
                recording.error = None
            else:
                raise RecordingError("不支持的录屏操作", status_code=422)
            await page.wait_for_timeout(150)
            recording.current_url = str(page.url or recording.current_url or recording.start_url)
            _persist_recording_files(root, recording)
            return recording
        except RecordingError:
            raise
        except Exception as exc:
            raise RecordingError(f"执行录屏操作失败：{exc}") from exc


async def start_recording(
    *,
    root: Path,
    workspace_id: str,
    start_url: str,
    title: str | None = None,
    goal: str | None = None,
    ensure_browsers: Callable[[], None] | None = None,
    resolve_headless: Callable[[], tuple[bool, dict[str, Any]]] = _resolve_web_recording_headless,
    launch_context: Callable[..., Any] = _launch_recording_context,
    launch_environment: Callable[..., dict[str, str]] = _web_recording_launch_environment,
    now_ms: Callable[[], int] | None = None,
) -> tuple[ActiveWebRecording, dict[str, Any]]:
    if workspace_id in _ACTIVE_WEB_RECORDINGS:
        raise RecordingError("当前工作区已有录屏任务在运行", status_code=409)
    root = root.resolve()
    url = _assert_recording_url(start_url)
    recording_id = uuid.uuid4().hex
    recording_dir = _recording_dir(recording_id)
    for relative in (f"{recording_dir}/screenshots", f"{recording_dir}/downloads", "playwright/profile"):
        _resolve_safe(root, relative).mkdir(parents=True, exist_ok=True)
    if ensure_browsers is not None:
        ensure_browsers()
    try:
        from playwright.async_api import TimeoutError as PlaywrightTimeoutError, async_playwright
    except ModuleNotFoundError as exc:
        raise RecordingDependencyError(
            "录屏能力需要安装可选依赖：pip install 'openjiuwen-skill-builder[recording]'，"
            "然后执行 playwright install chromium。",
            status_code=503,
        ) from exc

    playwright = await async_playwright().start()
    context = None
    try:
        headless, capability = resolve_headless()
        size = _recording_window_size()
        launch_options = {
            "accept_downloads": True,
            "downloads_path": str(_resolve_safe(root, f"{recording_dir}/downloads")),
            "env": launch_environment(headless=headless),
            "viewport": None,
            "args": [
                f"--window-size={size['width']},{size['height']}",
                "--window-position=0,0", "--force-device-scale-factor=1",
                "--high-dpi-support=1", "--no-sandbox", "--disable-dev-shm-usage",
            ],
        }
        context, headless, capability = await launch_context(
            playwright.chromium,
            profile_dir=str(_resolve_safe(root, "playwright/profile")),
            launch_options=launch_options,
            headless=headless,
            display_capability=capability,
        )
        recording = ActiveWebRecording(
            id=recording_id,
            workspace_id=workspace_id,
            title=(str(title or "").strip() or urlparse(url).netloc or "网页录制")[:128],
            goal=str(goal or "").strip() or None,
            start_url=url,
            current_url=url,
            status="recording",
            started_at=_iso_now(),
            stopped_at=None,
            recording_path=f"{recording_dir}/recording.md",
            material_path=None,
            trace_path=None,
            error=None,
            context=context,
            playwright=playwright,
            page=None,
            interaction_mode="viewer" if headless else "headed",
        )
        _ACTIVE_WEB_RECORDINGS[workspace_id] = recording

        async def record_event(source: Any, payload: Any) -> None:
            page = source.get("page") if isinstance(source, dict) else getattr(source, "page", None)
            if page is not None:
                asyncio.create_task(_capture_recording_step(
                    root,
                    recording,
                    page,
                    payload if isinstance(payload, dict) else {"type": "event"},
                ))

        await context.expose_binding("__skillBuilderRecordEvent", record_event)
        await context.add_init_script(_RECORDING_INIT_SCRIPT)
        await context.tracing.start(screenshots=True, snapshots=True, sources=True)

        def attach_page(page: Any) -> None:
            recording.page = page

            def on_navigate(frame: Any) -> None:
                try:
                    if frame.parent_frame:
                        return
                except Exception:
                    _LOGGER.debug("Failed to inspect the recording frame hierarchy.", exc_info=True)
                asyncio.create_task(_capture_recording_step(
                    root, recording, page,
                    {"type": "navigate", "url": getattr(page, "url", "")},
                ))

            page.on("framenavigated", on_navigate)
            page.on("download", lambda download: asyncio.create_task(
                _capture_recording_download(root, recording, page, download, now_ms=now_ms)
            ))

        context.on("page", attach_page)
        for item in context.pages:
            attach_page(item)
        page = context.pages[0] if context.pages else await context.new_page()
        recording.page = page
        try:
            await page.goto(url, wait_until="domcontentloaded", timeout=30000)
            recording.navigation_status = "loaded"
        except PlaywrightTimeoutError:
            recording.navigation_status = "timeout"
            recording.error = "initial_navigation_timeout"
            recording.current_url = str(page.url or url)
        await page.bring_to_front()
        try:
            page_title = await page.title()
        except Exception:
            _LOGGER.debug("Failed to read the initial recording page title.", exc_info=True)
            page_title = ""
        await _capture_recording_step(root, recording, page, {
            "type": "open" if recording.navigation_status == "loaded" else "initial_navigation_timeout",
            "url": page.url,
            "title": page_title,
        })
        _persist_recording_files(root, recording)
        return recording, capability
    except RecordingError:
        if context is not None:
            try:
                await context.close()
            except Exception:
                _LOGGER.debug("Failed to close recording context after a recording error.", exc_info=True)
        try:
            await playwright.stop()
        finally:
            _ACTIVE_WEB_RECORDINGS.pop(workspace_id, None)
        raise
    except Exception as exc:
        if context is not None:
            try:
                await context.close()
            except Exception:
                _LOGGER.debug("Failed to close recording context after startup failure.", exc_info=True)
        await playwright.stop()
        _ACTIVE_WEB_RECORDINGS.pop(workspace_id, None)
        if _display_runtime_failure(exc):
            raise RecordingError(
                "可见录屏浏览器无法连接图形显示会话。请检查 WEB_RECORDING_DISPLAY、"
                f"WEB_RECORDING_XAUTHORITY 和 X11 权限；原始错误：{str(exc)[:1000]}",
                status_code=503,
            ) from exc
        if _playwright_browser_install_failure(exc):
            raise RecordingError(
                "录屏浏览器不可用：当前 Playwright 运行时与浏览器缓存版本不匹配，"
                "请使用启动宿主的同一 Python 环境执行 `playwright install chromium`，"
                "或修正 PLAYWRIGHT_BROWSERS_PATH。"
                f" 原始错误：{str(exc)[:1000]}",
                status_code=503,
            ) from exc
        raise RecordingError(f"启动网页录制失败：{exc}") from exc


async def stop_recording(
    *,
    root: Path,
    workspace_id: str,
    recording_id: str,
) -> tuple[ActiveWebRecording, str]:
    recording = _ACTIVE_WEB_RECORDINGS.get(workspace_id)
    if recording is None or recording.id != recording_id:
        raise RecordingError("当前工作区没有匹配的运行中录屏", status_code=404)
    root = root.resolve()
    recording.status = "stopping"
    _persist_recording_files(root, recording)
    try:
        await recording.context.storage_state(path=str(_resolve_safe(root, "playwright/storage-state.json")))
    except Exception:
        _LOGGER.debug("Failed to persist recording browser storage state.", exc_info=True)
    try:
        trace_path = f"{_recording_dir(recording.id)}/trace.zip"
        await recording.context.tracing.stop(path=str(_resolve_safe(root, trace_path)))
        recording.trace_path = trace_path
    except Exception:
        _LOGGER.debug("Failed to persist the recording trace.", exc_info=True)
    cleanup_error: str | None = None
    try:
        await recording.context.close()
    except Exception as exc:
        cleanup_error = str(exc)[:500]
    finally:
        try:
            await recording.playwright.stop()
        except Exception as exc:
            cleanup_error = cleanup_error or str(exc)[:500]
    recording.status = "completed"
    recording.stopped_at = _iso_now()
    if cleanup_error:
        recording.error = f"browser_cleanup: {cleanup_error}"
    recording.material_path = f"inputs/external-sources/{recording.id}/web-recording.md"
    content = _render_recording_markdown(recording, as_input_material=True)
    target = _resolve_safe(root, recording.material_path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    try:
        _persist_recording_files(root, recording)
    finally:
        _ACTIVE_WEB_RECORDINGS.pop(workspace_id, None)
    return recording, content


__all__ = [
    "ActiveWebRecording",
    "RecordingAction",
    "RecordingDependencyError",
    "RecordingError",
    "_ACTIVE_WEB_RECORDINGS",
    "_ActiveWebRecording",
    "_RECORDING_INIT_SCRIPT",
    "_assert_recording_url",
    "_capture_recording_download",
    "_capture_recording_step",
    "_display_runtime_failure",
    "_headed_recording_capability",
    "_iso_now",
    "_launch_recording_context",
    "_persist_recording_files",
    "_recording_dir",
    "_recording_window_size",
    "_render_recording_markdown",
    "_resolve_web_recording_headless",
    "_stringify_recording_target",
    "_truncate_text",
    "_web_recording_headless",
    "_web_recording_launch_environment",
    "_web_recording_mode",
    "active_recording",
    "get_active_recording",
    "capture_recording_frame",
    "perform_recording_action",
    "recording_snapshot",
    "resolve_recording_headless",
    "launch_recording_context",
    "recording_launch_environment",
    "recording_now",
    "start_recording",
    "stop_recording",
]
