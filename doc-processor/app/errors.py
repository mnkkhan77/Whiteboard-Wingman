"""Domain errors that map 1:1 to contract error codes."""

from __future__ import annotations

from app.models import ErrorCode


class PipelineError(Exception):
    """A deterministic failure: reported to the backend as-is and never retried."""

    def __init__(self, code: ErrorCode, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
