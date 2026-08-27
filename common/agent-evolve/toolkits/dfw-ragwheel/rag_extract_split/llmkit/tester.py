"""Simple testing utilities for LLM profiles."""

from typing import Any, Dict, Optional

import requests

from .caller import call_llm
from .profile import Profile


def check_profile(
    profile: Profile,
    test_message: Optional[str] = None,
    timeout: int = 30,
) -> Dict[str, Any]:
    """Test whether a profile can successfully call the LLM endpoint.

    This performs a simple non-streaming request, mainly to verify that:
    - The URL is reachable.
    - The API key is valid.

    Args:
        profile: The profile to test.
        test_message: Optional custom test message. Defaults to a simple prompt.
        timeout: Short timeout for the test request.

    Returns:
        A dictionary with the test result:
        - success: bool
        - status_code: HTTP status code (if available)
        - message: Human-readable result message
        - response: Parsed response body (if success)
        - error: Error details (if failed)
    """
    if test_message is None:
        test_message = "Hi, this is a connection test. Please reply 'ok'."

    messages = [{"role": "user", "content": test_message}]

    try:
        result = call_llm(
            profile,
            messages=messages,
            stream_enabled=False,
            timeout=timeout,
        )
        return {
            "success": True,
            "status_code": 200,
            "message": "Connection test passed.",
            "response": result,
            "error": None,
        }
    except requests.exceptions.HTTPError as e:
        status_code = e.response.status_code if e.response is not None else None
        return {
            "success": False,
            "status_code": status_code,
            "message": f"HTTP error during test: {e}",
            "response": None,
            "error": {"type": "http_error", "detail": str(e), "status_code": status_code},
        }
    except requests.exceptions.ConnectionError as e:
        return {
            "success": False,
            "status_code": None,
            "message": f"Connection failed. Please check the base URL: {e}",
            "response": None,
            "error": {"type": "connection_error", "detail": str(e)},
        }
    except requests.exceptions.Timeout as e:
        return {
            "success": False,
            "status_code": None,
            "message": f"Request timed out: {e}",
            "response": None,
            "error": {"type": "timeout", "detail": str(e)},
        }
    except Exception as e:
        return {
            "success": False,
            "status_code": None,
            "message": f"Test failed: {e}",
            "response": None,
            "error": {"type": type(e).__name__, "detail": str(e)},
        }
