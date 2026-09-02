"""Explicit dataset fetch failures. Never silent-empty."""

from __future__ import annotations


class DatasetMissing(FileNotFoundError):
    """Raised when a local dataset root or required file is absent."""


class DatasetLfsMissing(DatasetMissing):
    """Raised when Git LFS pointer files are present but objects were not pulled."""
