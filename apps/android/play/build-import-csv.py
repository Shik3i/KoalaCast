#!/usr/bin/env python3
"""Rebuild store-listing-import.csv from the two listing markdown files.

The CSV is what gets uploaded to the Play Console's translation import, and the
markdown files are what a human edits. Keeping both by hand is how they drift:
the short description was corrected in three files and the CSV kept the old one,
and the full descriptions in the CSV were 384 characters behind the day this
script was written. Whoever edits a listing runs this afterwards.

    python apps/android/play/build-import-csv.py

The translation_context column is editorial guidance for the translator and is
kept here rather than in the markdown, because it is not part of the listing.
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# field -> (character limit, en context, de context)
CONTEXT = {
    "app_name": (
        30,
        "Keep the KoalaCast brand unchanged. Podcast Player is the single primary "
        "search descriptor. Do not add promotional claims or more keywords.",
        "Die Marke KoalaCast unverändert lassen. Podcast-Player ist der einzige "
        "primäre Suchbegriff. Keine werblichen Aussagen oder weiteren Keywords.",
    ),
    "short_description": (
        80,
        "Natural US English. Play rejects monetisation claims in this field as "
        "promotional, so the absence of advertising is stated only in the full "
        "description. Describe features, never price or promotion.",
        "Natürliches Deutsch. Play beanstandet Monetarisierungsaussagen in diesem "
        "Feld als werblich; die Werbefreiheit steht deshalb nur in der "
        "Vollbeschreibung. Funktionen beschreiben, nie Preis oder Promotion.",
    ),
    "full_description": (
        4000,
        "Translate naturally. Keep the section headings in capitals and the bullet "
        "structure. Do not add rankings, awards, price claims, calls to action, "
        "competitor names or features the app does not have.",
        "Natürlich übersetzen. Abschnittsüberschriften in Großbuchstaben und die "
        "Aufzählungsstruktur beibehalten. Keine Rankings, Auszeichnungen, "
        "Preisaussagen, Handlungsaufforderungen, Wettbewerbernamen oder Funktionen "
        "hinzufügen, die die App nicht hat.",
    ),
}

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
        ("en-US", "English", "true", HERE / "listing-en.md", 1),
        ("de-DE", "German", "false", HERE / "listing-de.md", 2),
    ]
    rows = []
    problems = []
    for locale, language, is_source, path, context_index in sources:
        for field, text in zip(CONTEXT, blocks(path)):
            limit, *contexts = CONTEXT[field]
            if len(text) > limit:
                problems.append(f"{locale} {field}: {len(text)} characters, limit {limit}")
            rows.append(
                {
                    "locale": locale,
                    "language": language,
                    "is_source": is_source,
                    "field": field,
                    "character_limit": limit,
                    "text": text,
                    "translation_context": contexts[context_index - 1],
                }
            )

    if problems:
        for problem in problems:
            print(f"error: {problem}", file=sys.stderr)
        return 1

    out = HERE / "store-listing-import.csv"
    with out.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    for row in rows:
        print(f"{row['locale']:6} {row['field']:18} {len(row['text']):>5} / {row['character_limit']}")
    print(f"wrote {out.relative_to(HERE.parents[2])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
