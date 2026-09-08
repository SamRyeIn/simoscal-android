#!/usr/bin/env python3
"""Measure the store listing copy against Play's character limits.

Play rejects an over-length field at save time, which is a slow way to find out.
The copy lives in `store/listing.md` in fenced blocks under headings that name
their own limit, so this reads the limit out of the document rather than keeping
a second list that could disagree with it.

    python3 store/check_listing.py     # exits non-zero if any field is over
"""

from __future__ import annotations

import pathlib
import re
import sys

LISTING = pathlib.Path(__file__).resolve().parent / "listing.md"

# "## Full description — limit 4000" followed by the next fenced block.
FIELD = re.compile(
    r"^##\s+(?P<name>.+?)\s+—\s+limit\s+(?P<limit>\d+)\s*$\n+```\n(?P<body>.*?)\n```",
    re.MULTILINE | re.DOTALL,
)


def main() -> None:
    text = LISTING.read_text()
    fields = list(FIELD.finditer(text))
    if not fields:
        sys.exit(f"No limit-bearing fields found in {LISTING}. Has the format changed?")

    over = []
    for match in fields:
        name = match.group("name")
        limit = int(match.group("limit"))
        body = match.group("body")
        used = len(body)
        flag = "OVER" if used > limit else "ok"
        print(f"{flag:>4}  {name:<24} {used:>5} / {limit}")
        if used > limit:
            over.append(f"{name}: {used} > {limit}")

    if over:
        sys.exit("Over the limit:\n  " + "\n  ".join(over))
    print(f"\nAll {len(fields)} fields within limits.")


if __name__ == "__main__":
    main()
