#!/usr/bin/env python3
"""Validates schemas/genres.json: unique slug ids, known parents and families,
no parent cycles, aliases that point at real genres. Exits non-zero on error."""
import json
import re
import sys
from pathlib import Path

PATH = Path(__file__).resolve().parent.parent / "schemas" / "genres.json"
SLUG = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def main() -> int:
    doc = json.loads(PATH.read_text())
    errors = []
    families = {f["id"] for f in doc["families"]}
    genres = {}
    for g in doc["genres"]:
        if not SLUG.match(g["id"]):
            errors.append(f"bad id {g['id']!r}")
        if g["id"] in genres:
            errors.append(f"duplicate id {g['id']}")
        if g["family"] not in families:
            errors.append(f"{g['id']}: unknown family {g['family']}")
        if g["mbid"] is not None and not UUID.match(g["mbid"]):
            errors.append(f"{g['id']}: bad mbid {g['mbid']}")
        genres[g["id"]] = g
    for g in genres.values():
        for p in g["parents"]:
            if p not in genres:
                errors.append(f"{g['id']}: unknown parent {p}")
    for start in genres:
        seen, stack = set(), [start]
        while stack:
            for p in genres[stack.pop()]["parents"]:
                if p == start:
                    errors.append(f"{start}: parent cycle")
                    stack = []
                    break
                if p in genres and p not in seen:
                    seen.add(p)
                    stack.append(p)
    for alias, target in doc["aliases"].items():
        if target not in genres:
            errors.append(f"alias {alias!r}: unknown genre {target}")
        if alias != alias.lower().strip():
            errors.append(f"alias {alias!r}: must be lowercase and trimmed")
    for e in errors:
        print(e)
    if not errors:
        missing = sum(1 for g in genres.values() if g["mbid"] is None)
        print(f"genres.json v{doc['version']}: {len(genres)} genres, {len(doc['aliases'])} aliases, {missing} without mbid")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
