#!/usr/bin/env python3
"""
Finds every tr("…") string in the iOS app that has no translation yet, generates the missing
ones via the Gemini API, and writes them into:

  * HealthDecoder/Resources/UiTranslations.json  — the app's bundled offline seed
  * ../backend/ui_translations_new.sql           — rows for the backend table, which is the
                                                   runtime source of truth for BOTH apps

A string with no translation anywhere renders in English (see `tr()` in Localization.swift),
so running this is always safe and never breaks the app — it only fills gaps.

Usage:
    export GEMINI_API_KEY=...            # same key the app uses
    python3 scripts/fill_translations.py             # generate
    python3 scripts/fill_translations.py --dry-run   # just list what's missing
"""
import json
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request

MODEL = "gemini-3.6-flash"
ENDPOINT = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

LANGUAGES = [
    "Hindi", "Marathi", "Gujarati", "Tamil", "Telugu",
    "Kannada", "Bengali", "Punjabi", "Malayalam", "Odia",
]

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEED_PATH = ROOT / "HealthDecoder" / "Resources" / "UiTranslations.json"
SQL_OUT = ROOT.parent / "backend" / "ui_translations_new.sql"


def used_strings() -> set[str]:
    found = set()
    for path in (ROOT / "HealthDecoder").rglob("*.swift"):
        for match in re.finditer(r'tr\("((?:[^"\\]|\\.)*)"\)', path.read_text(encoding="utf-8")):
            found.add(match.group(1))
    return found


def translate_batch(strings: list[str], language: str, api_key: str) -> dict[str, str]:
    """Asks for the whole batch as one JSON object — far fewer calls than one-per-string."""
    prompt = (
        f"Translate each of these short mobile-app UI strings from English into {language}.\n"
        "These are button labels, screen titles, and short messages in a medical records app "
        "used in India. Keep translations SHORT (they must fit on a phone button), natural, and "
        "use everyday words a non-technical person understands. Keep medical terms that are "
        "commonly used in English as-is where a patient would recognise them better.\n"
        "Return ONLY a raw JSON object mapping each English string to its translation. "
        "No markdown fences, no commentary.\n\n"
        + json.dumps(strings, ensure_ascii=False, indent=1)
    )
    body = json.dumps({"contents": [{"parts": [{"text": prompt}]}]}).encode()
    request = urllib.request.Request(
        ENDPOINT,
        data=body,
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as e:
        print(f"    ! {language}: HTTP {e.code} — {e.read()[:200].decode(errors='replace')}")
        return {}
    except Exception as e:  # noqa: BLE001 - surface any transport failure and continue
        print(f"    ! {language}: {e}")
        return {}

    try:
        text = payload["candidates"][0]["content"]["parts"][0]["text"].strip()
    except (KeyError, IndexError):
        print(f"    ! {language}: unexpected response shape")
        return {}

    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?|```$", "", text, flags=re.MULTILINE).strip()
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        print(f"    ! {language}: model did not return valid JSON")
        return {}
    return {k: v for k, v in parsed.items() if isinstance(v, str) and v.strip()}


def sql_escape(value: str) -> str:
    return value.replace("'", "''")


def main() -> int:
    dry_run = "--dry-run" in sys.argv
    seed = json.loads(SEED_PATH.read_text(encoding="utf-8"))
    covered = set(seed.get("Hindi", {}))
    missing = sorted(used_strings() - covered)

    if not missing:
        print("Nothing missing — every tr() string already has a translation.")
        return 0

    print(f"{len(missing)} string(s) missing translations:")
    for s in missing:
        print(f"  {s}")
    if dry_run:
        return 0

    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        print("\nSet GEMINI_API_KEY to generate the translations (see Secrets.xcconfig).")
        return 1

    sql_rows = []
    for language in LANGUAGES:
        print(f"\n  {language}…", end=" ", flush=True)
        translations = translate_batch(missing, language, api_key)
        print(f"{len(translations)}/{len(missing)}")
        seed.setdefault(language, {}).update(translations)
        for source, translated in translations.items():
            sql_rows.append(
                f"    ('{language}', '{sql_escape(source)}', '{sql_escape(translated)}')"
            )

    SEED_PATH.write_text(
        json.dumps(seed, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8"
    )
    print(f"\nUpdated bundled seed: {SEED_PATH}")

    if sql_rows:
        sql = (
            "-- Generated by ios-app/scripts/fill_translations.py\n"
            "-- Apply against the same database db_init.sql seeds. ON CONFLICT DO NOTHING keeps\n"
            "-- any manual edits already made in the table.\n"
            "INSERT INTO ui_translations (language, text_key, translated_text) VALUES\n"
            + ",\n".join(sql_rows)
            + "\nON CONFLICT (language, text_key) DO NOTHING;\n"
        )
        SQL_OUT.write_text(sql, encoding="utf-8")
        print(f"Wrote backend rows:   {SQL_OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
