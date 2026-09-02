"""Inspect a delimited table and record what is actually on disk."""

from __future__ import annotations

import csv
import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class ColumnReport:
    name: str
    non_empty: int
    inferred_kind: str
    sample_values: tuple[str, ...]


@dataclass(frozen=True)
class TableReport:
    path: str
    sha256: str
    row_count: int
    delimiter: str
    columns: tuple[ColumnReport, ...]

    @property
    def column_names(self) -> tuple[str, ...]:
        return tuple(column.name for column in self.columns)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_table_text(path: Path, encoding: str | None = None) -> str:
    """Read a table. IO-VNBD smartphone CSVs use latin-1 for m/s²."""

    if encoding is not None:
        return path.read_text(encoding=encoding)
    try:
        return path.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return path.read_text(encoding="latin-1")


def inspect_delimited_table(
    path: Path,
    *,
    encoding: str | None = None,
    max_sample_values: int = 3,
) -> TableReport:
    """Read headers and rows exactly as stored. No column-name mapping."""

    if not path.is_file():
        raise FileNotFoundError(f"table not found: {path}")
    text = read_table_text(path, encoding)
    if not text.strip():
        raise ValueError(f"table is empty: {path}")
    dialect = csv.Sniffer().sniff(text[:4096], delimiters=",\t;")
    reader = csv.reader(text.splitlines(), dialect)
    try:
        header = next(reader)
    except StopIteration as exc:
        raise ValueError(f"table has no header: {path}") from exc
    names = [item.strip() for item in header]
    if not names or any(not name for name in names):
        raise ValueError(f"header contains an empty column name: {path}")
    if len(names) != len(set(names)):
        raise ValueError(f"duplicate column names in {path}: {names}")

    columns: list[list[str]] = [[] for _ in names]
    row_count = 0
    for row in reader:
        if not row or all(not cell.strip() for cell in row):
            continue
        if len(row) != len(names):
            raise ValueError(
                f"{path} row {row_count + 1} has {len(row)} fields, expected {len(names)}"
            )
        row_count += 1
        for index, cell in enumerate(row):
            columns[index].append(cell.strip())

    reports = tuple(
        _column_report(name, values, max_sample_values)
        for name, values in zip(names, columns)
    )
    return TableReport(
        path=str(path),
        sha256=file_sha256(path),
        row_count=row_count,
        delimiter=dialect.delimiter,
        columns=reports,
    )


def _column_report(name: str, values: Iterable[str], max_sample_values: int) -> ColumnReport:
    filled = [value for value in values if value != ""]
    kinds = {_infer_kind(value) for value in filled}
    if not kinds:
        kind = "empty"
    elif kinds <= {"int"}:
        kind = "int"
    elif kinds <= {"int", "float"}:
        kind = "float"
    else:
        kind = "string"
    return ColumnReport(
        name=name,
        non_empty=len(filled),
        inferred_kind=kind,
        sample_values=tuple(filled[:max_sample_values]),
    )


def _infer_kind(value: str) -> str:
    try:
        int(value)
        return "int"
    except ValueError:
        pass
    try:
        float(value)
        return "float"
    except ValueError:
        return "string"
