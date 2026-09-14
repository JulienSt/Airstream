#!/usr/bin/env python3
"""Report and bound the review surface of the Airstream Native port."""

from __future__ import annotations

import argparse
import pathlib
import subprocess
from typing import NamedTuple, Sequence


BASELINE_COMMIT = "71af1572b7730ab49acb8358ee2b5abe77dc2e45"
MECHANICAL_COMMITS = frozenset({"cfcc346"})
# Reviewed against the upstream baseline below. New shared adaptations are
# CustomStreamSource, ScanLeftSignal and JsResilientIterator; Owner now matches
# upstream exactly. Merge commits are excluded from the historical metrics.
MAX_SHARED_PRODUCTION_FILES = 50
MAX_GENUINE_COMMITS = 21
MAX_GENUINE_LINE_CHANGES = 7091


class DiffGrowthError(RuntimeError):
    pass


class NameStatusRecord(NamedTuple):
    status: str
    source: str
    destination: str


class Breakdown(NamedTuple):
    pure_moves: tuple[tuple[str, str], ...]
    mechanical_commits: tuple[str, ...]
    genuine_commits: tuple[str, ...]


def parse_name_status(output: str) -> tuple[NameStatusRecord, ...]:
    records = []
    for line in output.splitlines():
        fields = line.split("\t")
        status = fields[0]
        if status.startswith(("R", "C")):
            source, destination = fields[1], fields[2]
        else:
            source = destination = fields[1]
        records.append(NameStatusRecord(status, source, destination))
    return tuple(records)


def pure_moves(records: Sequence[NameStatusRecord]) -> tuple[tuple[str, str], ...]:
    return tuple((record.source, record.destination) for record in records if record.status == "R100")


def shared_production_paths(records: Sequence[NameStatusRecord]) -> tuple[str, ...]:
    prefix = "src/main/scala/"
    return tuple(sorted({record.destination for record in records if record.destination.startswith(prefix)}))


def breakdown(
    records: Sequence[NameStatusRecord], commit_ids: Sequence[str], mechanical_ids: set[str] | frozenset[str]
) -> Breakdown:
    def is_mechanical(commit_id: str) -> bool:
        return any(commit_id.startswith(known) or known.startswith(commit_id) for known in mechanical_ids)

    mechanical = tuple(commit_id for commit_id in commit_ids if is_mechanical(commit_id))
    genuine = tuple(commit_id for commit_id in commit_ids if not is_mechanical(commit_id))
    return Breakdown(pure_moves(records), mechanical, genuine)


def assert_within_budget(name: str, actual: int, limit: int) -> None:
    if actual > limit:
        raise DiffGrowthError(f"{name} grew from its recorded budget {limit} to {actual}")


def run_git(repository: pathlib.Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=repository,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    return result.stdout


def line_changes(repository: pathlib.Path, commit_ids: Sequence[str]) -> int:
    changed = 0
    for commit_id in commit_ids:
        output = run_git(repository, "show", "--format=", "--numstat", commit_id)
        for line in output.splitlines():
            added, deleted, _path = line.split("\t", 2)
            if added != "-":
                changed += int(added) + int(deleted)
    return changed


def report(repository: pathlib.Path, check_budget: bool) -> str:
    status = run_git(repository, "diff", "--name-status", "-M100%", BASELINE_COMMIT, "HEAD")
    records = parse_name_status(status)
    commit_ids = tuple(run_git(repository, "rev-list", "--no-merges", "--reverse", f"{BASELINE_COMMIT}..HEAD").splitlines())
    categories = breakdown(records, commit_ids, MECHANICAL_COMMITS)
    shared = shared_production_paths(records)
    genuine_lines = line_changes(repository, categories.genuine_commits)

    if check_budget:
        assert_within_budget("shared production files", len(shared), MAX_SHARED_PRODUCTION_FILES)
        assert_within_budget("genuine commits", len(categories.genuine_commits), MAX_GENUINE_COMMITS)
        assert_within_budget("genuine line changes", genuine_lines, MAX_GENUINE_LINE_CHANGES)

    lines = [
        f"Upstream baseline: {BASELINE_COMMIT}",
        f"Pure moves (R100): {len(categories.pure_moves)}",
        f"Mechanical replacement commits: {len(categories.mechanical_commits)}",
        f"Genuine intervention commits: {len(categories.genuine_commits)}",
        f"Genuine intervention line changes: {genuine_lines}",
        f"Touched shared production files: {len(shared)}",
        "",
        "Pure moves:",
        *(f"  {source} -> {destination}" for source, destination in categories.pure_moves),
        "",
        "Touched shared production files:",
        *(f"  {path}" for path in shared),
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    parser.add_argument("--no-check", action="store_true", help="print metrics without enforcing the recorded budgets")
    arguments = parser.parse_args()

    try:
        print(report(arguments.repository.resolve(), check_budget=not arguments.no_check))
    except DiffGrowthError as error:
        raise SystemExit(f"Port diff budget exceeded: {error}") from error


if __name__ == "__main__":
    main()
