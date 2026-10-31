#!/usr/bin/env python3
"""Adds the sound event fragments in assets-src/sounds/*.json to assets/null/sounds.json.

tools/gen_sounds.py writes sounds.json from scratch for the sounds it synthesises. Areas that
generate their own sounds (into their own folder under assets/null/sounds/) describe their
events in a fragment instead; run this after any generator. A fragment may not redefine an
event that gen_sounds.py or another fragment already defines.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FRAGMENTS = ROOT / "assets-src" / "sounds"
SOUNDS_JSON = ROOT / "src" / "main" / "resources" / "assets" / "null" / "sounds.json"
SOUND_DIR = SOUNDS_JSON.parent / "sounds"


def main() -> int:
    sounds = json.loads(SOUNDS_JSON.read_text(encoding="utf-8")) if SOUNDS_JSON.exists() else {}
    owner = {}
    problems = []
    for part in sorted(FRAGMENTS.glob("*.json")):
        fragment = json.loads(part.read_text(encoding="utf-8"))
        for event, entry in fragment.items():
            if event in owner:
                problems.append(f"{event} is defined in both {owner[event]} and {part.name}")
                continue
            owner[event] = part.name
            sounds[event] = entry
            for sound in entry.get("sounds", []):
                name = sound if isinstance(sound, str) else sound.get("name", "")
                if isinstance(sound, dict) and sound.get("type") == "event":
                    continue
                path = name.split(":", 1)[-1]
                if not (SOUND_DIR / f"{path}.ogg").exists():
                    problems.append(f"{part.name}: {event} names missing file sounds/{path}.ogg")
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        return 1
    SOUNDS_JSON.write_text(json.dumps(sounds, indent=2) + "\n", encoding="utf-8")
    print(f"{len(owner)} fragment events merged -> {SOUNDS_JSON.relative_to(ROOT)} ({len(sounds)} events)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
