"""Detect Git LFS pointer files so loaders fail before inventing columns."""

from __future__ import annotations

from pathlib import Path

from driftzero_ml.datasets.errors import DatasetLfsMissing

LFS_BANNER = "version https://git-lfs.github.com/spec/v1"
FETCH_DOC = "scripts/fetch_datasets.md"


def is_lfs_pointer(path: Path) -> bool:
    """True when the file is a Git LFS pointer, not the payload."""

    if not path.is_file():
        return False
    with path.open("rb") as handle:
        head = handle.read(len(LFS_BANNER) + 8)
    return head.startswith(LFS_BANNER.encode("ascii"))


def pointer_payload_size(path: Path) -> int | None:
    """Return the LFS `size` field, or None if this is not a pointer."""

    if not is_lfs_pointer(path):
        return None
    for line in path.read_text(encoding="ascii").splitlines():
        if line.startswith("size "):
            return int(line.split()[1])
    return None


def require_real_file(path: Path, *, dataset: str, fetch_hint: str) -> Path:
    """Return an on-disk payload or explain how to pull LFS."""

    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise DatasetLfsMissing(
            f"{dataset} file is missing: {resolved}. {fetch_hint} See {FETCH_DOC}."
        )
    if is_lfs_pointer(resolved):
        size = pointer_payload_size(resolved)
        size_note = f" Pointer size field is {size} bytes." if size is not None else ""
        raise DatasetLfsMissing(
            f"{dataset} at {resolved} is still a Git LFS pointer.{size_note} "
            f"{fetch_hint} See {FETCH_DOC}."
        )
    return resolved
