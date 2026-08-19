"""Core LLM calling functions."""

from typing import Any, Dict, List, Optional, Union

import requests

from .profile import Profile
from .profile_manager import ProfileManager


def call_llm(
    profile: Union[Profile, Dict[str, Any], str],
    messages: List[Dict[str, Any]],
    stream_enabled: Optional[bool] = None,
    timeout: Optional[int] = None,
    session: Optional[requests.Session] = None,
) -> Dict[str, Any]:
    """Call an LLM using a profile.

    Args:
        profile: A Profile object, a profile dictionary, or a path to a single
            profile YAML file.
        messages: The conversation messages.
        stream_enabled: Override the profile's default stream setting.
        timeout: Override the profile's default timeout.
        session: Optional requests.Session to use.

    Returns:
        The parsed JSON response.

    Raises:
        requests.RequestException: On HTTP errors.
        ValueError: If the profile is invalid.
    """
    if isinstance(profile, str):
        profile = Profile.from_file(profile)
    elif isinstance(profile, dict):
        profile = Profile.from_dict(profile)

    if not isinstance(profile, Profile):
        raise ValueError("profile must be a Profile object, dict, or file path")

    method, url, headers, data, profile_timeout, stream = profile.build_request(
        messages, stream_enabled=stream_enabled
    )

    if timeout is not None:
        profile_timeout = timeout

    req_session = session or requests
    response = req_session.request(
        method=method,
        url=url,
        headers=headers,
        json=data,
        stream=stream,
        timeout=profile_timeout,
    )
    response.raise_for_status()

    if stream:
        return {"stream": response.iter_lines()}

    return response.json()


def call_llm_by_id(
    profile_id: str,
    messages: List[Dict[str, Any]],
    profile_manager: ProfileManager,
    stream_enabled: Optional[bool] = None,
    timeout: Optional[int] = None,
    session: Optional[requests.Session] = None,
) -> Dict[str, Any]:
    """Call an LLM by profile ID using a ProfileManager.

    Args:
        profile_id: The ID of the saved profile.
        messages: The conversation messages.
        profile_manager: The ProfileManager instance to look up the profile.
        stream_enabled: Override the profile's default stream setting.
        timeout: Override the profile's default timeout.
        session: Optional requests.Session to use.

    Returns:
        The parsed JSON response.

    Raises:
        ValueError: If the profile ID is not found.
    """
    profile = profile_manager.get_profile(profile_id)
    if profile is None:
        raise ValueError(f"Profile not found: {profile_id}")

    return call_llm(
        profile,
        messages=messages,
        stream_enabled=stream_enabled,
        timeout=timeout,
        session=session,
    )
