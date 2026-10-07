#!/usr/bin/env python3
"""Checks that every migration in schemas/migrations, applied in order to the
oldest schema we still migrate from, yields exactly schemas/library.sql.

Usage: tools/check_migrations.py   (exits non-zero on drift)
"""
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

SCHEMAS = Path(__file__).resolve().parent.parent / "schemas"
# The last schema each app shipped before migrations moved into schemas/.
BASE_VERSION = 2
BASE_COMMIT = "ec4ddd5"


def describe(db: sqlite3.Connection) -> dict:
    out = {}
    for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"):
        out[f"table {name}"] = [tuple(r[1:]) for r in db.execute(f"PRAGMA table_info('{name}')")]
        out[f"fks {name}"] = sorted(tuple(r[2:]) for r in db.execute(f"PRAGMA foreign_key_list('{name}')"))
    for (name, table) in db.execute("SELECT name, tbl_name FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL"):
        out[f"index {name}"] = (table, [r[2] for r in db.execute(f"PRAGMA index_info('{name}')")])
    return out


def version(sql: str) -> int:
    return int(re.search(r"PRAGMA user_version = (\d+)", sql).group(1))


def main() -> int:
    current = (SCHEMAS / "library.sql").read_text()
    target = version(current)

    fresh = sqlite3.connect(":memory:")
    fresh.executescript(current)

    base = subprocess.run(
        ["git", "show", f"{BASE_COMMIT}:schemas/library.sql"],
        cwd=SCHEMAS, check=True, capture_output=True, text=True,
    ).stdout
    assert version(base) == BASE_VERSION
    migrated = sqlite3.connect(":memory:")
    migrated.executescript(base)
    for v in range(BASE_VERSION + 1, target + 1):
        path = SCHEMAS / "migrations" / f"{v}.sql"
        if not path.exists():
            print(f"missing {path.relative_to(SCHEMAS.parent)}")
            return 1
        migrated.executescript(path.read_text())

    a, b = describe(fresh), describe(migrated)
    if a == b:
        print(f"library.sql v{target} matches v{BASE_VERSION} + migrations")
        return 0
    for key in sorted(set(a) | set(b)):
        if a.get(key) != b.get(key):
            print(f"{key}:\n  library.sql: {a.get(key)}\n  migrated:    {b.get(key)}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
