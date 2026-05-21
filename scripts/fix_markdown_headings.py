#!/usr/bin/env python3
"""
Fix Markdown ATX headings by ensuring a space after the leading hashes (e.g. "### Heading").
Skips fenced code blocks (``` or ~~~). Supports dry-run and apply modes.

Usage:
  python scripts/fix_markdown_headings.py        # dry-run, prints files that would change
  python scripts/fix_markdown_headings.py --apply  # apply changes in-place
  python scripts/fix_markdown_headings.py --root docs  # limit to a subfolder
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Tuple


def fix_file(path: Path, apply: bool = False) -> Tuple[bool, str | None]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    in_fence = False
    fence_marker = None
    changed = False
    out_lines: list[str] = []

    # match up to 3 leading spaces, 1-6 hashes, then no space before content
    heading_re = re.compile(r"^(\s{0,3})(#{1,6})(?!\s)(\S.*)$")
    fence_re = re.compile(r"^(\s*)(`{3,}|~{3,})(.*)$")

    for line in lines:
        raw = line.rstrip("\n")

        m_fence = fence_re.match(raw)
        if m_fence:
            marker = m_fence.group(2)
            if not in_fence:
                in_fence = True
                fence_marker = marker
            elif fence_marker == marker:
                in_fence = False
                fence_marker = None
            out_lines.append(line)
            continue

        if in_fence:
            out_lines.append(line)
            continue

        # Collapse separated hashes at the start (e.g. "# # 1" -> "## 1")
        leading_ws = re.match(r"^(\s*)", raw).group(1)
        rest = raw[len(leading_ws):]
        i = 0
        hash_count = 0
        # count '#' characters allowing spaces between them until encountering other chars
        while i < len(rest) and (rest[i] == '#' or rest[i].isspace()):
            if rest[i] == '#':
                hash_count += 1
            i += 1

        if hash_count > 0:
            rest_content = rest[i:].lstrip()
            if rest_content:
                collapsed = f"{leading_ws}{'#'*hash_count} {rest_content}"
            else:
                collapsed = f"{leading_ws}{'#'*hash_count}"
            if collapsed != raw:
                # if we produced a collapsed heading that contains content, write it immediately
                if rest_content:
                    out_lines.append(collapsed + "\n")
                    changed = True
                    continue
                # otherwise keep the collapsed form for further processing
                raw = collapsed

        m = heading_re.match(raw)
        if m:
            # only add a trailing space when there is heading content
            new_line = f"{m.group(1)}{m.group(2)} {m.group(3)}\n"
            if new_line != line:
                changed = True
                out_lines.append(new_line)
            else:
                out_lines.append(line)
        else:
            out_lines.append(line)

    if changed:
        new_text = "".join(out_lines)
        if apply:
            path.write_text(new_text, encoding="utf-8")
        return True, new_text
    return False, None


def find_md_files(root: Path):
    # skip common dependency/build folders
    exclude_dirs = {"node_modules", ".venv", "venv", "target", "dist", ".git"}
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        if p.suffix.lower() not in (".md", ".markdown"):
            continue
        if any(part in exclude_dirs for part in p.parts):
            continue
        yield p


def main() -> int:
    parser = argparse.ArgumentParser(description="Fix Markdown heading spacing")
    parser.add_argument("--apply", action="store_true", help="apply changes in-place")
    parser.add_argument("--root", default=".", help="root folder to search")
    args = parser.parse_args()

    root = Path(args.root)
    changed_files: list[str] = []

    for f in find_md_files(root):
        ok, _ = fix_file(f, apply=args.apply)
        if ok:
            changed_files.append(str(f))

    if changed_files:
        if args.apply:
            print("Fixed files:")
            for ff in changed_files:
                print(ff)
            return 0
        else:
            print("Would fix files:")
            for ff in changed_files:
                print(ff)
            return 2

    print("No files need fixing")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
