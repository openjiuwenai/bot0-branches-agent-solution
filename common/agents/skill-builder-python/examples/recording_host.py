"""Host adapter example for optional Playwright material recording."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from skill_builder.recording import (
    RecordingAction,
    capture_recording_frame,
    get_active_recording,
    perform_recording_action,
    recording_snapshot,
    start_recording,
    stop_recording,
)


class RecordingHost:
    """Map authenticated host endpoints to the standalone recording core.

    Callers must apply tenant/workspace authorization and URL/network policy
    before invoking these methods. Active recordings are process-local.
    """

    def __init__(self, workspace_root: Path, workspace_id: str) -> None:
        self.workspace_root = workspace_root.resolve()
        self.workspace_id = workspace_id

    async def start(
        self,
        *,
        start_url: str,
        title: str | None = None,
        goal: str | None = None,
    ) -> dict[str, Any]:
        # Enforce host URL/domain/network policy before this call.
        recording, capability = await start_recording(
            root=self.workspace_root,
            workspace_id=self.workspace_id,
            start_url=start_url,
            title=title,
            goal=goal,
        )
        return {
            **recording_snapshot(recording),
            "display_capability": capability,
        }

    def status(self) -> dict[str, Any] | None:
        recording = get_active_recording(self.workspace_id)
        return recording_snapshot(recording) if recording is not None else None

    async def frame(self, recording_id: str) -> bytes:
        return await capture_recording_frame(
            workspace_id=self.workspace_id,
            recording_id=recording_id,
        )

    async def action(
        self,
        recording_id: str,
        *,
        action: str,
        x: float | None = None,
        y: float | None = None,
        text: str | None = None,
        key: str | None = None,
        delta_y: float | None = None,
        url: str | None = None,
    ) -> dict[str, Any]:
        # Enforce host authorization for risky/irreversible actions first.
        recording = await perform_recording_action(
            root=self.workspace_root,
            workspace_id=self.workspace_id,
            recording_id=recording_id,
            action=RecordingAction(
                action=action,
                x=x,
                y=y,
                text=text,
                key=key,
                delta_y=delta_y,
                url=url,
            ),
        )
        return recording_snapshot(recording)

    async def stop(self, recording_id: str) -> dict[str, Any]:
        recording, markdown = await stop_recording(
            root=self.workspace_root,
            workspace_id=self.workspace_id,
            recording_id=recording_id,
        )
        if not recording.material_path:
            raise RuntimeError("recording completed without a material path")
        material_path = (self.workspace_root / recording.material_path).resolve()
        # Register material_path and markdown in the host asset store here.
        return {
            **recording_snapshot(recording),
            "material_path": str(material_path),
            "material_markdown": markdown,
        }


__all__ = ["RecordingHost"]
