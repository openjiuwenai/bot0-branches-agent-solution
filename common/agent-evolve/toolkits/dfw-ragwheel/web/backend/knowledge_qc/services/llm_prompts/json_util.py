from __future__ import annotations

import json
import re
from typing import Any, Dict


def parse_json_object(text: str, default: Dict[str, Any] = None) -> Dict[str, Any]:
    default = default or {}
    text = text.strip()
    fence = re.search(r"```(?:json)?\s*([\s\S]*?)```", text)
    if fence:
        text = fence.group(1).strip()
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            return obj
    except json.JSONDecodeError:
        pass
    return default
