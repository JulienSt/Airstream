#!/usr/bin/env python3
"""Keep runtime platform probes out of Airstream's shared production sources."""

from __future__ import annotations

import argparse
import pathlib
import re
from typing import NamedTuple


REQUIRED_MERGE_AGREEMENT = (
    "Platform differences live in the build, not in the code. "
    "Shared sources must read as if only one platform existed."
)
REQUIRED_AGREEMENT_PHRASES = (
    "Platform differences live in the build, not in the code",
    "Shared sources must read as if only one platform existed",
)
FORBIDDEN_PROBES = (
    "scala.scalajs",
    "org.scalajs",
    "scala.scalanative",
    "System.getProperty",
    "isScalaJS",
    "isScalaJs",
    "isNative",
    "LinkingInfo",
    "LinktimeInfo",
)


class PlatformBoundaryError(RuntimeError):
    pass


class Violation(NamedTuple):
    path: pathlib.Path
    probe: str


def code_without_comments(source: str) -> str:
    without_blocks = re.sub(r"/\*.*?\*/", "", source, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", without_blocks)


def scan_source(source: str) -> tuple[str, ...]:
    code = code_without_comments(source)
    return tuple(probe for probe in FORBIDDEN_PROBES if probe in code)


def find_violations(repository: pathlib.Path) -> tuple[Violation, ...]:
    shared_root = repository / "src/main/scala"
    if not shared_root.exists():
        return ()
    return tuple(
        Violation(path, probe)
        for path in sorted(shared_root.rglob("*.scala"))
        for probe in scan_source(path.read_text())
    )


def assert_merge_agreement(contributing: str) -> None:
    normalized = " ".join(contributing.split())
    if any(phrase not in normalized for phrase in REQUIRED_AGREEMENT_PHRASES):
        raise PlatformBoundaryError("merge agreement does not require platform selection through source sets")


def check_repository(repository: pathlib.Path) -> tuple[Violation, ...]:
    assert_merge_agreement((repository / "CONTRIBUTING.md").read_text())
    return find_violations(repository)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    arguments = parser.parse_args()

    try:
        violations = check_repository(arguments.repository.resolve())
    except PlatformBoundaryError as error:
        raise SystemExit(f"Platform boundary check failed: {error}") from error

    if violations:
        details = "\n".join(f"  {violation.path}: forbidden probe {violation.probe}" for violation in violations)
        raise SystemExit(
            "Platform boundary check failed: shared production code must select platform behavior by source file:\n"
            + details
        )
    print("Platform boundary check passed: shared production sources contain no runtime platform probes.")


if __name__ == "__main__":
    main()
