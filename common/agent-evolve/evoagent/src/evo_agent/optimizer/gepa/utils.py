"""Shared utilities for GEPA optimizer — image encoding + multimodal content building."""

from __future__ import annotations

import base64
import logging
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_MAX_IMAGE_BYTES = 10 * 1024 * 1024
_MAX_IMAGES_PER_CASE = 5


def encode_image(path: str) -> str | None:
    """Read an image file and return a base64 data URL."""
    try:
        img_path = Path(path)
        if not img_path.is_file():
            logger.warning("[gepa] image file not found: %s", path)
            return None
        size = img_path.stat().st_size
        if size > _MAX_IMAGE_BYTES:
            logger.warning("[gepa] image too large (%d bytes): %s", size, path)
            return None
        data = img_path.read_bytes()
    except Exception:
        logger.warning("[gepa] failed to read image: %s", path, exc_info=True)
        return None
    ext = img_path.suffix.lower().lstrip(".")
    mime_map = {"jpg": "jpeg", "jpeg": "jpeg", "png": "png", "gif": "gif", "webp": "webp"}
    mime = mime_map.get(ext, "jpeg")
    b64 = base64.b64encode(data).decode("ascii")
    return f"data:image/{mime};base64,{b64}"


def build_multimodal_content(
    query: str, images: list[str]
) -> list[dict[str, Any]]:
    """Build OpenAI-compatible multimodal content list."""
    content: list[dict[str, Any]] = []
    if query:
        content.append({"type": "text", "text": query})
    for img_path in images[:_MAX_IMAGES_PER_CASE]:
        url = encode_image(img_path)
        if url:
            content.append({"type": "image_url", "image_url": {"url": url}})
    return content
