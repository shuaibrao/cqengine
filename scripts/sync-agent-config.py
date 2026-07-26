#!/usr/bin/env python3
"""Synchronize canonical .agent rules and skills into tool-specific mirrors."""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
CANONICAL = ROOT / ".agent"
MIRRORS = (ROOT / ".cursor", ROOT / ".claude", ROOT / ".codex")


def bytes_of(path: Path) -> bytes:
    return path.read_bytes()


def expected_files() -> dict[Path, bytes]:
    expected: dict[Path, bytes] = {}
    for source in sorted((CANONICAL / "rules").glob("*.md")):
        for mirror in MIRRORS:
            suffix = ".mdc" if mirror.name == ".cursor" else ".md"
            expected[mirror / "rules" / f"{source.stem}{suffix}"] = bytes_of(source)
    skills = CANONICAL / "skills"
    if skills.is_dir():
        for source in sorted(path for path in skills.rglob("*") if path.is_file()):
            relative = source.relative_to(skills)
            for mirror in MIRRORS:
                expected[mirror / "skills" / relative] = bytes_of(source)
    return expected


def generated_files() -> set[Path]:
    files: set[Path] = set()
    for mirror in MIRRORS:
        rules = mirror / "rules"
        skills = mirror / "skills"
        if rules.is_dir():
            files.update(path for path in rules.iterdir() if path.is_file() and path.suffix in {".md", ".mdc"})
        if skills.is_dir():
            files.update(path for path in skills.rglob("*") if path.is_file())
    return files


def synchronize(*, check: bool, prune: bool) -> tuple[int, int]:
    expected = expected_files()
    changed = 0
    for destination, content in expected.items():
        if destination.exists() and bytes_of(destination) == content:
            continue
        changed += 1
        if not check:
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)

    orphans = generated_files() - set(expected)
    if orphans and prune and not check:
        for orphan in sorted(orphans):
            orphan.unlink()
        for mirror in MIRRORS:
            skills = mirror / "skills"
            if skills.is_dir():
                for directory in sorted((p for p in skills.rglob("*") if p.is_dir()), reverse=True):
                    if not any(directory.iterdir()):
                        directory.rmdir()
    return changed, len(orphans)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if mirrors differ; write nothing")
    parser.add_argument("--prune", action="store_true", help="remove generated files without canonical sources")
    args = parser.parse_args()
    if not CANONICAL.is_dir():
        print("missing canonical .agent directory", file=sys.stderr)
        return 1
    changed, orphans = synchronize(check=args.check, prune=args.prune or not args.check)
    print(f"agent mirrors: {changed} changed, {orphans} orphaned")
    if args.check and (changed or orphans):
        print("run: python3 scripts/sync-agent-config.py", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
