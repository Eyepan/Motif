#!/usr/bin/env python3
"""Fills in MusicBrainz ids in schemas/genres.json and reports drift.

Fetches the full MusicBrainz genre list (/ws/2/genre/all), sets `mbid` on
every genre whose `name` matches a MusicBrainz genre name exactly, and prints
names MusicBrainz doesn't know (typos, renamed or removed genres). Never
removes or renames ids. Respects MusicBrainz's one request per second limit.

Usage: sync_genres.py [--write]
"""
import json
import sys
import time
import urllib.request
from pathlib import Path

PATH = Path(__file__).resolve().parent.parent / "schemas" / "genres.json"
API = "https://musicbrainz.org/ws/2/genre/all?fmt=json&limit=100&offset={}"
USER_AGENT = "Motif/0.1 (https://github.com/Eyepan/Motif)"


def fetch_all() -> dict:
    by_name, offset, total = {}, 0, None
    while total is None or offset < total:
        req = urllib.request.Request(API.format(offset), headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=30) as resp:
            page = json.load(resp)
        total = page["genre-count"]
        for g in page["genres"]:
            by_name[g["name"]] = g["id"]
        offset += len(page["genres"])
        if not page["genres"]:
            break
        time.sleep(1.1)
    return by_name


def main() -> int:
    doc = json.loads(PATH.read_text())
    remote = fetch_all()
    unknown, changed = [], 0
    for g in doc["genres"]:
        mbid = remote.get(g["name"])
        if mbid is None:
            unknown.append(g["name"])
        elif g["mbid"] != mbid:
            g["mbid"] = mbid
            changed += 1
    print(f"MusicBrainz has {len(remote)} genres; {changed} mbids updated")
    for name in unknown:
        print(f"not a MusicBrainz genre: {name}")
    if "--write" in sys.argv and changed:
        # Keep the one-object-per-line layout of the checked-in file.
        text = PATH.read_text()
        for g in doc["genres"]:
            if g["mbid"]:
                text = text.replace(
                    f'{{ "id": "{g["id"]}", "name": {json.dumps(g["name"], ensure_ascii=False)}, "mbid": null,',
                    f'{{ "id": "{g["id"]}", "name": {json.dumps(g["name"], ensure_ascii=False)}, "mbid": "{g["mbid"]}",',
                )
        PATH.write_text(text)
    return 1 if unknown else 0


if __name__ == "__main__":
    sys.exit(main())
