#!/usr/bin/env python3
"""Fail if a tracked Markdown file points at a missing local path."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
SKIP_PREFIXES = ("http://", "https://", "mailto:", "tel:")
SKIP_DIRS = {".git", ".gradle", ".kotlin", "build", "node_modules", ".cursor"}


def local_target(raw: str) -> str | None:
    href = raw.strip()
    if not href or href.startswith("#") or href.startswith("<"):
        return None
    if href.startswith(SKIP_PREFIXES):
        return None
    if href.startswith("http:") or href.startswith("https:"):
        return None
    path = href.split("#", 1)[0].split("?", 1)[0]
    if not path or path.startswith("http"):
        return None
    return path


def markdown_files() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*.md"):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        files.append(path)
    return sorted(files)


def main() -> int:
    missing: list[str] = []
    for doc in markdown_files():
        text = doc.read_text(encoding="utf-8")
        for match in LINK.finditer(text):
            target = local_target(match.group(1))
            if target is None:
                continue
            resolved = (doc.parent / target).resolve()
            try:
                resolved.relative_to(ROOT)
            except ValueError:
                continue
            if not resolved.exists():
                rel = doc.relative_to(ROOT)
                missing.append(f"{rel}: {target}")
    if missing:
        print("Broken local markdown links:")
        for item in missing:
            print(f"  {item}")
        return 1
    print(f"Checked {len(markdown_files())} markdown files. Local links exist.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
