"""Locate a local IO-VNBD checkout. Raw files stay out of Git."""

from __future__ import annotations

from pathlib import Path

SOURCE_URL = "https://github.com/onyekpeu/IO-VNBD"
PREFERRED_RELATIVE = "Synchronised V abd S datasets"


class IOVNBDMissing(FileNotFoundError):
    """Raised when the mandatory dataset has not been fetched locally."""


def require_local_root(root: Path) -> Path:
    """Return an existing IO-VNBD root or explain how to fetch it.

    The GitHub zip entries are Git LFS pointers. Clone the repo and pull LFS
    objects into `data/raw/io_vnbd/` before inspection. Do not invent columns.
    """

    resolved = root.expanduser().resolve()
    if not resolved.is_dir():
        raise IOVNBDMissing(
            f"IO-VNBD is not at {resolved}. Clone {SOURCE_URL} (Git LFS) into "
            f"data/raw/io_vnbd/ and inspect files under '{PREFERRED_RELATIVE}'. "
            "Column names are not assumed until a local table is inspected. "
            "Exact commands: scripts/fetch_datasets.md."
        )
    return resolved
