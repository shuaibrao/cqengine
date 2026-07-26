#!/usr/bin/env python3
"""Verify repository-local links in maintained Markdown documentation."""
from __future__ import annotations

import html
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parent.parent
ROOT_DOCUMENTS = (
    "README.md",
    "AGENTS.md",
    "CONTRIBUTING.md",
    "RELEASING.md",
    "SECURITY.md",
)
DOCUMENT_TREES = ("documentation", "benchmarks", "consumer-tests", "stress-tests", ".agent")
EXCLUDED_TREES = ("documentation/javadoc/apidocs",)

FENCE_START = re.compile(r"^[ ]{0,3}(`{3,}|~{3,})")
REFERENCE_DEFINITION = re.compile(r"^[ ]{0,3}\[([^]\n]+)\]:[ \t]*(.*)$")
REFERENCE_USE = re.compile(r"!?\[([^]\n]+)\]\[([^]\n]*)\]")
MARKDOWN_ESCAPE = re.compile(r"\\([!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~])")
URI_SCHEME = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")
PLACEHOLDER = re.compile(
    r"\{\{.*?}}|\{%.*?%}|\$\{.*?}|<<.*?>>|"
    r"(?:^|[/_.-])(?:PLACEHOLDER|REPLACE_ME|YOUR_[A-Z0-9_]+)(?:$|[/_.-])",
    re.IGNORECASE,
)


@dataclass(frozen=True, order=True)
class Problem:
    source: str
    line: int
    message: str

    def render(self) -> str:
        return f"{self.source}:{self.line}: {self.message}"


def markdown_files() -> list[Path]:
    files = {ROOT / name for name in ROOT_DOCUMENTS if (ROOT / name).is_file()}
    for tree in DOCUMENT_TREES:
        directory = ROOT / tree
        if directory.is_dir():
            files.update(path for path in directory.rglob("*.md") if path.is_file())

    def included(path: Path) -> bool:
        relative = path.relative_to(ROOT).as_posix()
        return not any(relative == prefix or relative.startswith(f"{prefix}/") for prefix in EXCLUDED_TREES)

    return sorted((path for path in files if included(path)), key=lambda path: path.relative_to(ROOT).as_posix())


def outside_fences(lines: list[str]) -> list[tuple[int, str]]:
    visible: list[tuple[int, str]] = []
    fence_character: str | None = None
    fence_length = 0
    for line_number, line in enumerate(lines, start=1):
        if fence_character is None:
            match = FENCE_START.match(line)
            if match:
                marker = match.group(1)
                fence_character = marker[0]
                fence_length = len(marker)
                continue
            visible.append((line_number, line))
            continue

        candidate = line.lstrip(" ")
        indentation = len(line) - len(candidate)
        if indentation <= 3:
            marker = candidate.rstrip(" \t\r\n")
            if marker and set(marker) == {fence_character} and len(marker) >= fence_length:
                fence_character = None
                fence_length = 0
    return visible


def escaped(value: str, index: int) -> bool:
    backslashes = 0
    index -= 1
    while index >= 0 and value[index] == "\\":
        backslashes += 1
        index -= 1
    return backslashes % 2 == 1


def definition_destination(value: str) -> str | None:
    value = value.lstrip()
    if not value:
        return None
    if value.startswith("<"):
        for index in range(1, len(value)):
            if value[index] == ">" and not escaped(value, index):
                return value[1:index]
        return None

    index = 0
    while index < len(value):
        if value[index].isspace() and not escaped(value, index):
            break
        index += 1
    return value[:index]


def inline_destinations(value: str) -> list[str]:
    destinations: list[str] = []
    cursor = 0
    while True:
        marker = value.find("](", cursor)
        if marker < 0:
            return destinations
        cursor = marker + 2
        if escaped(value, marker):
            continue

        while cursor < len(value) and value[cursor] in " \t":
            cursor += 1
        if cursor >= len(value):
            continue

        if value[cursor] == "<":
            end = cursor + 1
            while end < len(value) and (value[end] != ">" or escaped(value, end)):
                end += 1
            if end < len(value):
                destinations.append(value[cursor + 1:end])
                cursor = end + 1
            continue

        start = cursor
        nested_parentheses = 0
        while cursor < len(value):
            character = value[cursor]
            if character == "\\":
                cursor += 2
                continue
            if character == "(":
                nested_parentheses += 1
            elif character == ")":
                if nested_parentheses == 0:
                    break
                nested_parentheses -= 1
            elif character.isspace() and nested_parentheses == 0:
                break
            cursor += 1
        destinations.append(value[start:cursor])


def normalized_reference(label: str) -> str:
    return " ".join(label.split()).casefold()


def clean_destination(destination: str) -> str:
    return MARKDOWN_ESCAPE.sub(r"\1", html.unescape(destination.strip()))


def placeholder(destination: str) -> bool:
    return bool(PLACEHOLDER.search(destination))


def target_problem(source: Path, line: int, destination: str) -> Problem | None:
    relative_source = source.relative_to(ROOT).as_posix()
    destination = clean_destination(destination)
    if not destination or destination.startswith("#") or placeholder(destination):
        return None
    if destination.startswith("//") or URI_SCHEME.match(destination):
        return None

    try:
        parsed = urlsplit(destination)
        path_text = unquote(parsed.path)
    except ValueError as error:
        return Problem(relative_source, line, f"invalid local target {destination!r}: {error}")
    if not path_text:
        return None

    candidate = Path(path_text)
    if not candidate.is_absolute():
        candidate = source.parent / candidate
    try:
        resolved = candidate.resolve(strict=False)
        relative_target = resolved.relative_to(ROOT)
    except (OSError, RuntimeError, ValueError):
        return Problem(relative_source, line, f"local target escapes repository: {destination}")

    target_name = relative_target.as_posix().casefold()
    if target_name == "changelog.md":
        return Problem(relative_source, line, f"reference to removed root CHANGELOG is forbidden: {destination}")
    if target_name == "documentation/maintenance" or target_name.startswith("documentation/maintenance/"):
        return Problem(
            relative_source,
            line,
            f"reference to removed documentation/maintenance is forbidden: {destination}",
        )
    try:
        exists = resolved.exists()
    except (OSError, ValueError):
        exists = False
    if not exists:
        return Problem(relative_source, line, f"missing local target: {destination}")
    return None


def check_file(path: Path) -> list[Problem]:
    relative = path.relative_to(ROOT).as_posix()
    try:
        lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    except (OSError, UnicodeError) as error:
        return [Problem(relative, 1, f"cannot read Markdown: {error}")]

    visible = outside_fences(lines)
    definitions: dict[str, tuple[int, str]] = {}
    references: list[tuple[int, str]] = []
    inline_targets: list[tuple[int, str]] = []

    for line_number, line in visible:
        definition = REFERENCE_DEFINITION.match(line)
        if definition and not definition.group(1).startswith("^"):
            label = normalized_reference(definition.group(1))
            destination = definition_destination(definition.group(2))
            if destination is not None:
                definitions.setdefault(label, (line_number, destination))
            continue

        inline_targets.extend((line_number, destination) for destination in inline_destinations(line))
        for reference in REFERENCE_USE.finditer(line):
            label = reference.group(2) or reference.group(1)
            if not label.startswith("^"):
                references.append((line_number, normalized_reference(label)))

    problems: list[Problem] = []
    for line_number, destination in inline_targets:
        problem = target_problem(path, line_number, destination)
        if problem is not None:
            problems.append(problem)
    for line_number, destination in definitions.values():
        problem = target_problem(path, line_number, destination)
        if problem is not None:
            problems.append(problem)
    for line_number, label in references:
        if label not in definitions:
            problems.append(Problem(relative, line_number, f"missing Markdown reference definition: {label}"))
    return problems


def main() -> int:
    files = markdown_files()
    problems = sorted({problem for path in files for problem in check_file(path)})
    if problems:
        for problem in problems:
            print(problem.render(), file=sys.stderr)
        print(f"markdown-links=failed files={len(files)} problems={len(problems)}", file=sys.stderr)
        return 1
    print(f"markdown-links=verified files={len(files)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
