#!/usr/bin/env python3
"""Rebuild store-listing-import.csv from the two listing markdown files.

The CSV is what gets uploaded to the Play Console's translation import, and the
markdown files are what a human edits. Keeping both by hand is how they drift:
the short description was corrected in three files and the CSV kept the old one,
and the full descriptions in the CSV were 384 characters behind the day this
script was written. Whoever edits a listing runs this afterwards.

    python apps/android/play/build-import-csv.py

Two files come out: the full one and a German-only one, for the case where en-US
is already the default listing and only the translation is being added.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

LIMITS = {"app_name": 30, "short_description": 80, "full_description": 4000}

# The markdown uses a localised heading per file, so match on the fenced block
# that follows any level-two heading and take them in document order.
BLOCK = re.compile(r"^## [^\n]*\n\n(?:(?!```)[^\n]*\n|\n)*?```\n(.*?)\n```", re.M | re.S)


def blocks(path: Path) -> list[str]:
    found = BLOCK.findall(path.read_text(encoding="utf-8"))
    if len(found) < 3:
        sys.exit(f"{path.name}: expected app name, short and full description, found {len(found)}")
    return [block.strip() for block in found[:3]]


def main() -> int:
    sources = [
        ("en-US", HERE / "listing-en.md"),
        ("de-DE", HERE / "listing-de.md"),
    ]

    rows = []
    problems = []
    for locale, path in sources:
        app_name, short, full = blocks(path)
        for field, text in (("app_name", app_name), ("short_description", short), ("full_description", full)):
            if len(text) > LIMITS[field]:
                problems.append(f"{locale} {field}: {len(text)} characters, limit {LIMITS[field]}")
        rows.append(
            {
                "locale": locale,
                "app_name": app_name,
                "short_description": short,
                "full_description": full,
            }
        )

    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1

    # One row per locale, and nothing in it that is not listing text. The first
    # shape had a row per *field* plus is_source, character_limit and
    # translation_context columns; the Console's importer offered only German
    # from it, and editorial notes are not listing content and have no business
    # in a file that gets uploaded.
    out = HERE / "store-listing-import.csv"
    with out.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    # The German row on its own, for the case where only the translation is being
    # added and en-US is already the default listing.
    de_only = HERE / "store-listing-import-de-DE.csv"
    with de_only.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows([row for row in rows if row["locale"] == "de-DE"])

    for row in rows:
        for field in LIMITS:
            print(f"{row['locale']:6} {field:18} {len(row[field]):>5} / {LIMITS[field]}")
    print(f"wrote {out.name} and {de_only.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
