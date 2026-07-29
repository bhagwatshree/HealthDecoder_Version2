#!/usr/bin/env python3
"""
Extracts the bundled UI translation maps from the Android app's UiTranslations.kt into a
JSON resource the iOS app bundles. Run this again whenever the Kotlin seed changes, so the
two apps stay in sync instead of drifting.

Usage: python3 extract_translations.py <UiTranslations.kt> <out.json>
"""
import json
import re
import sys

# Kotlin `private val <name> = mapOf(` -> the app's language display name.
LANGUAGE_NAMES = {
    "hindi": "Hindi",
    "marathi": "Marathi",
    "gujarati": "Gujarati",
    "tamil": "Tamil",
    "telugu": "Telugu",
    "kannada": "Kannada",
    "bengali": "Bengali",
    "punjabi": "Punjabi",
    "malayalam": "Malayalam",
    "odia": "Odia",
}

# "source" to "translation"  — both sides may contain escaped quotes.
PAIR = re.compile(r'"((?:[^"\\]|\\.)*)"\s+to\s+"((?:[^"\\]|\\.)*)"')
MAP_START = re.compile(r'private val (\w+) = mapOf\(')


def unescape(s: str) -> str:
    return s.encode().decode("unicode_escape") if "\\u" in s else s.replace('\\"', '"').replace("\\\\", "\\")


def main() -> int:
    src_path, out_path = sys.argv[1], sys.argv[2]
    with open(src_path, encoding="utf-8") as f:
        lines = f.readlines()

    result: dict[str, dict[str, str]] = {}
    current: str | None = None

    for line in lines:
        start = MAP_START.search(line)
        if start:
            var = start.group(1)
            current = LANGUAGE_NAMES.get(var)
            if current:
                result[current] = {}
            continue
        if current is None:
            continue
        # A bare `)` at map indentation closes the current map.
        if line.strip() == ")":
            current = None
            continue
        for match in PAIR.finditer(line):
            source, translation = unescape(match.group(1)), unescape(match.group(2))
            if source and translation:
                result[current][source] = translation

    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, sort_keys=True)

    total = sum(len(v) for v in result.values())
    for lang, entries in result.items():
        print(f"  {lang:12} {len(entries):4} strings")
    print(f"Total: {total} strings across {len(result)} languages -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
