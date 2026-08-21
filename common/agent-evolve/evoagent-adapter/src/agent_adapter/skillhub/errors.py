"""SkillHub client and service errors."""

from __future__ import annotations


class SkillHubError(Exception):
    """Base error for SkillHub operations."""

    def __init__(
        self,
        message: str,
        *,
        status_code: int = 0,
        error_code: str | None = None,
    ) -> None:
        self.status_code = status_code
        self.error_code = error_code
        super().__init__(message)


class SkillHubAuthError(SkillHubError):
    """Authentication failed (401)."""


class SkillHubNotFoundError(SkillHubError):
    """Resource not found (404)."""


class SkillHubConflictError(SkillHubError):
    """Version or state conflict (409)."""


class SkillHubValidationError(SkillHubError):
    """Local validation failed before calling Hub (400)."""
