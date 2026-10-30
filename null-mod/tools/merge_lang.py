#!/usr/bin/env python3
"""Merges every assets-src/lang/<part>.json fragment into assets/null/lang/en_us.json.

Each system keeps its own translation fragment so they can be edited independently.
Duplicate keys with different values are an error.
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "assets-src" / "lang"
OUT = ROOT / "src" / "main" / "resources" / "assets" / "null" / "lang" / "en_us.json"


def main() -> int:
    merged: dict[str, str] = {}
    owner: dict[str, str] = {}
    for part in sorted(SRC.glob("*.json")):
        data = json.loads(part.read_text(encoding="utf-8"))
        for key, value in data.items():
            if key in merged and merged[key] != value:
                print(f"conflict: {key} in {owner[key]} and {part.name}", file=sys.stderr)
                return 1
            merged[key] = value
            owner[key] = part.name
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(dict(sorted(merged.items())), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(merged)} keys -> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
