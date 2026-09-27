"""Shared-storage access. Event paths are relative to STORAGE_ROOT and use forward slashes."""

from __future__ import annotations

import json
import os
import tempfile
from collections.abc import Sequence
from pathlib import Path, PurePosixPath

from app.errors import PipelineError
from app.models import Chunk, ErrorCode


def to_posix(path: str) -> str:
    """Event paths use forward slashes; tolerate a Windows-style producer."""
    return path.replace("\\", "/")


class Storage:
    def __init__(self, root: Path) -> None:
        self.root = root.resolve()

    def resolve(self, relative_path: str) -> Path:
        """Resolve an event path, refusing anything that escapes the storage root."""
        rel = PurePosixPath(to_posix(relative_path))
        if not rel.is_absolute() and ".." not in rel.parts:
            path = (self.root / rel).resolve()
            if path.is_relative_to(self.root):  # also rejects symlinks pointing outside
                return path
        raise PipelineError(ErrorCode.FILE_NOT_FOUND, f"Invalid storage path: {relative_path}")

    def require_file(self, relative_path: str) -> Path:
        """Resolve an event path that must point at an existing file."""
        path = self.resolve(relative_path)
        if not path.is_file():
            raise PipelineError(ErrorCode.FILE_NOT_FOUND, f"File not found: {relative_path}")
        return path

    @staticmethod
    def chunks_path(pack_id: int) -> str:
        """Storage-relative chunks.json location (contract: packs/{packId}/chunks.json)."""
        return f"packs/{pack_id}/chunks.json"

    def write_chunks(self, pack_id: int, chunks: Sequence[Chunk]) -> str:
        """Atomically (over)write chunks.json so a re-delivered event simply replaces it and
        the backend never reads a half-written file."""
        relative = self.chunks_path(pack_id)
        target = self.resolve(relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = [chunk.model_dump(by_alias=True) for chunk in chunks]
        fd, tmp_name = tempfile.mkstemp(dir=target.parent, prefix=".chunks-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump(payload, fh, ensure_ascii=False)
            # mkstemp creates 0600; the backend may run as a different user and must read it.
            os.chmod(tmp_name, 0o644)
            os.replace(tmp_name, target)
        except BaseException:
            Path(tmp_name).unlink(missing_ok=True)
            raise
        return relative
